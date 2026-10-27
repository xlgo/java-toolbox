package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.EtcdModels;
import com.aqishi.toolbox.feature.data.domain.EtcdModels.*;
import com.aqishi.toolbox.infra.etcd.*;
import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtcdClientTest {
    private static final byte[] KEY = "prefix/key".getBytes(StandardCharsets.UTF_8);
    private static Options options(Api api, String username) {
        return new Options(URI.create("http://127.0.0.1:2379"), api, username, "secret", 2000, "", "", "", "");
    }
    static class Wire implements EtcdTransport {
        final Queue<Object> replies = new ArrayDeque<>();
        final List<String> paths = new ArrayList<>(), auth = new ArrayList<>(), methods = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        Wire reply(Object... values) { replies.addAll(List.of(values)); return this; }
        @Override public JsonNode request(String method,String path,String type,byte[] body,String authorization) throws IOException {
            paths.add(path); methods.add(method); auth.add(authorization); bodies.add(body);
            Object result = replies.remove(); if (result instanceof IOException error) throw error;
            return Json.mapper().readTree(result.toString());
        }
        JsonNode body(int index) throws IOException { return Json.mapper().readTree(bodies.get(index)); }
        @Override public void cancel() { }
        @Override public void close() { }
    }
    @Test void prefixEndsAndBinaryRepresentationAreLossless() {
        assertArrayEquals(new byte[]{'b'},EtcdModels.prefixEnd(new byte[]{'a',(byte)255}));
        assertArrayEquals(new byte[]{0},EtcdModels.prefixEnd(new byte[]{(byte)255}));
        byte[] binary={0,(byte)255,13,10};
        assertArrayEquals(binary,EtcdModels.parse(EtcdModels.encode(binary,Encoding.BASE64),Encoding.BASE64));
        assertThrows(IllegalArgumentException.class,()->EtcdModels.encode(binary,Encoding.UTF8));
        assertArrayEquals(new byte[]{'a',0},EtcdModels.after(new byte[]{'a'}));
    }
    @Test void endpointValidationRejectsCredentialsQueriesAndIncompleteClientCertificates() {
        for(String value:List.of("ftp://localhost:2379","http://name:pass@localhost:2379","http://localhost/v3","http://localhost?token=x","http://localhost:0"))
            assertThrows(IllegalArgumentException.class,()->new Options(URI.create(value),Api.V3,"","",2000,"","","",""));
        assertThrows(IllegalArgumentException.class,()->new Options(URI.create("https://localhost"),Api.V3,"","",2000,"","client.pem","",""));
        assertFalse(options(Api.V3,"root").toString().contains("secret"));
    }
    @Test void v3PaginationPinsSnapshotAndAdvancesPastLastByteKey() throws Exception {
        Wire wire=new Wire().reply("{\"etcdserver\":\"3.5.34\"}",
                "{\"header\":{\"revision\":\"42\"},\"more\":true,\"kvs\":[{\"key\":\"YWJj\",\"value\":\"AP8=\",\"mod_revision\":\"7\"}]}",
                "{\"header\":{\"revision\":\"50\"}}");
        EtcdClient client=new EtcdClient(options(Api.V3,""),wire);client.connect();
        Page page=client.list(new byte[0],1,null,0);
        assertArrayEquals(new byte[]{0,(byte)255},page.entries().get(0).value());
        assertEquals(42,page.revision());assertArrayEquals(new byte[]{'a','b','c',0},page.nextKey());
        client.list(new byte[0],1,page.nextKey(),page.revision());
        assertEquals("42",wire.body(2).path("revision").asText());
        assertEquals("YWJjAA==",wire.body(2).path("key").asText());
        assertEquals("AA==",wire.body(1).path("range_end").asText());
    }
    @Test void v3CreationAndUpdatesUseTransactionsAndPreserveLeaseByDefault() throws Exception {
        Wire wire=new Wire().reply("{\"succeeded\":true}","{\"succeeded\":true}");
        EtcdClient client=new EtcdClient(options(Api.V3,""),wire);
        client.put(KEY,new byte[]{0,(byte)255},0,-1,false);
        JsonNode create=wire.body(0);assertEquals("VERSION",create.at("/compare/0/target").asText());
        assertEquals("0",create.at("/compare/0/version").asText());
        assertFalse(create.at("/success/0/request_put").has("ignore_lease"));
        client.put(KEY,new byte[0],9,-1,false);
        JsonNode update=wire.body(1);assertEquals("MOD",update.at("/compare/0/target").asText());
        assertEquals("9",update.at("/compare/0/mod_revision").asText());
        assertTrue(update.at("/success/0/request_put/ignore_lease").asBoolean());
    }
    @Test void leasesAndDeletesTargetOnlyTheExactKey() throws Exception {
        Wire wire=new Wire().reply("{\"ID\":\"123\"}","{\"succeeded\":true}","{\"succeeded\":true}");
        EtcdClient client=new EtcdClient(options(Api.V3,""),wire);client.put(KEY,new byte[0],0,30,false);
        assertEquals("30",wire.body(0).path("TTL").asText());
        assertEquals("123",wire.body(1).at("/success/0/request_put/lease").asText());
        client.delete(new Entry(KEY,new byte[0],1,7,1,0,0,false));
        JsonNode deletion=wire.body(2).at("/success/0/request_delete_range");
        assertEquals(Base64.getEncoder().encodeToString(KEY),deletion.path("key").asText());
        assertFalse(deletion.has("range_end"));
    }
    @Test void conflictsAndTransportFailuresAreNeverBlindlyRetried() {
        Wire conflict=new Wire().reply("{\"succeeded\":false}");
        assertThrows(IOException.class,()->new EtcdClient(options(Api.V3,""),conflict).put(KEY,new byte[0],0,0,false));
        Wire uncertain=new Wire().reply(new IOException("connection closed"));
        assertThrows(IOException.class,()->new EtcdClient(options(Api.V3,""),uncertain).put(KEY,new byte[0],4,0,false));
        assertEquals(1,uncertain.paths.size());assertEquals(1,conflict.paths.size());
    }
    @Test void newLeaseIsRevokedOnExplicitConflictButNotOnAmbiguousTransportFailure() throws Exception {
        Wire rejected=new Wire().reply("{\"ID\":\"123\"}","{\"succeeded\":false}","{}");
        assertThrows(IOException.class,()->new EtcdClient(options(Api.V3,""),rejected).put(KEY,new byte[0],3,60,false));
        assertEquals("/v3/lease/revoke",rejected.paths.get(2));
        Wire uncertain=new Wire().reply("{\"ID\":\"123\"}",new IOException("connection closed"));
        assertThrows(IOException.class,()->new EtcdClient(options(Api.V3,""),uncertain).put(KEY,new byte[0],3,60,false));
        assertEquals(2,uncertain.paths.size());
    }
    @Test void tokenRefreshUsesOnlyExplicitAuthenticationRejectionAndVersionedGateway() throws Exception {
        Wire wire=new Wire().reply("{\"etcdserver\":\"3.3.27\"}","{\"token\":\"first\"}",
                new EtcdException(401,16,"expired"),"{\"token\":\"second\"}","{\"header\":{\"revision\":1}}");
        EtcdClient client=new EtcdClient(options(Api.V3,"root"),wire);client.connect();client.list(KEY,1,null,0);
        assertEquals("/v3beta/auth/authenticate",wire.paths.get(1));assertEquals("first",wire.auth.get(2));
        assertEquals("",wire.auth.get(3));assertEquals("second",wire.auth.get(4));
    }
    @Test void v2DirectoryBrowsingAndSpecialCharacterKeysUseRestNotV3() throws Exception {
        Wire wire=new Wire().reply("{\"etcdserver\":\"3.5.34\"}",
                "{\"node\":{\"key\":\"/\",\"dir\":true,\"nodes\":[{\"key\":\"/folder\",\"dir\":true,\"modifiedIndex\":1},{\"key\":\"/key\",\"value\":\"v\",\"modifiedIndex\":2}]}}",
                "{\"node\":{\"key\":\"/a +?%#\",\"modifiedIndex\":3}}");
        EtcdClient client=new EtcdClient(options(Api.V2,"root"),wire);client.connect();
        Page page=client.list(new byte[]{'/'},1,null,0);assertTrue(page.more());assertTrue(page.entries().get(0).directory());
        client.put("/a +?%#".getBytes(StandardCharsets.UTF_8),"+&=中文".getBytes(StandardCharsets.UTF_8),2,0,false);
        assertEquals("/v2/keys/a%20%2B%3F%25%23",wire.paths.get(2));
        assertTrue(new String(wire.bodies.get(2),StandardCharsets.UTF_8).contains("prevIndex=2"));
        assertTrue(wire.auth.get(2).startsWith("Basic "));
    }
    @Test void v2MissingKeyIsDistinctFromApiDisabled() throws Exception {
        Wire wire=new Wire().reply(new EtcdException(404,100,"missing"),new EtcdException(404,0,"no api"));
        EtcdClient client=new EtcdClient(options(Api.V2,""),wire);
        assertNull(client.get(new byte[]{'/','x'}));
        assertThrows(IOException.class,()->client.get(new byte[]{'/','x'}));
        for(String path:List.of("bad","/a/../b","/a//b"))assertThrows(IllegalArgumentException.class,()->EtcdModels.v2Path(path.getBytes(StandardCharsets.UTF_8)));
    }
}
