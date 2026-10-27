package com.aqishi.toolbox.infra.etcd;

import com.aqishi.toolbox.feature.data.domain.EtcdModels.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EtcdHttpTransportTest {
    static Options options(int port,int timeout) {return new Options(URI.create("http://127.0.0.1:"+port),Api.V3,"","",timeout,"","","","");}
    @Test void httpStatusIsPrimaryAndRedirectsNeverLeakCredentials() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger followed=new AtomicInteger(),closed=new AtomicInteger();
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().set("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/target",exchange->{followed.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.createContext("/denied",exchange->{byte[] data="{\"code\":7,\"message\":\"sensitive server data\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(403,data.length);exchange.getResponseBody().write(data);exchange.close();});
        server.start();int port=server.getAddress().getPort();
        try(EtcdHttpTransport transport=new EtcdHttpTransport(options(port,2000),"127.0.0.1",port,closed::incrementAndGet)) {
            EtcdException error=assertThrows(EtcdException.class,()->transport.request("POST","/denied","application/json",new byte[]{'{','}'},"token"));
            assertEquals(403,error.status());assertEquals(7,error.code());assertFalse(error.getMessage().contains("sensitive"));
            assertThrows(IOException.class,()->transport.request("GET","/redirect","",new byte[0],"token"));
            assertEquals(0,followed.get());
            transport.close();transport.close();assertEquals(1,closed.get());
        } finally {server.stop(0);}
    }
    @Test void deadlineBoundsSlowResponsesAndWritesAreNotRetried() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger requests=new AtomicInteger();
        server.createContext("/slow",exchange->{requests.incrementAndGet();try {Thread.sleep(1500);}catch(InterruptedException ignored){}exchange.close();});
        server.start();int port=server.getAddress().getPort();long started=System.nanoTime();
        try(EtcdHttpTransport transport=new EtcdHttpTransport(options(port,1000),"127.0.0.1",port,null)) {
            assertThrows(IOException.class,()->transport.request("POST","/slow","application/json",new byte[]{'{','}'},""));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2500);assertEquals(1,requests.get());
        }finally {server.stop(0);}
    }
}
