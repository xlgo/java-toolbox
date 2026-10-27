package com.aqishi.toolbox.infra.etcd;

import com.aqishi.toolbox.feature.data.domain.EtcdModels.*;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class EtcdTlsTest {
    @TempDir Path temp;
    @Test void routedTlsVerifiesOriginalHostnameAndRejectsMismatch() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
        long now=System.currentTimeMillis();X500Name name=new X500Name("CN=etcd.test.invalid");
        var builder=new JcaX509v3CertificateBuilder(name,BigInteger.ONE,new Date(now-60000),new Date(now+86400000),name,pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(GeneralName.dNSName,"etcd.test.invalid")));
        X509Certificate certificate=new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        Path ca=temp.resolve("ca.pem");try(JcaPEMWriter writer=new JcaPEMWriter(Files.newBufferedWriter(ca))){writer.writeObject(certificate);}
        KeyStore keys=KeyStore.getInstance("PKCS12");keys.load(null,null);keys.setKeyEntry("server",pair.getPrivate(),new char[0],new java.security.cert.Certificate[]{certificate});
        KeyManagerFactory keyManager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keyManager.init(keys,new char[0]);
        TrustManagerFactory trustManager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trustManager.init(keys);
        SSLContext context=SSLContext.getInstance("TLS");context.init(keyManager.getKeyManagers(),trustManager.getTrustManagers(),null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/version",exchange->{byte[] body="{\"etcdserver\":\"3.5.34\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        int port=server.getAddress().getPort();
        try {
            Options valid=new Options(URI.create("https://etcd.test.invalid:2379"),Api.V3,"","",3000,ca.toString(),"","","");
            try(EtcdHttpTransport transport=new EtcdHttpTransport(valid,"127.0.0.1",port,null)) {
                assertEquals("3.5.34",transport.request("GET","/version","",new byte[0],"").path("etcdserver").asText());
            }
            Options invalid=new Options(URI.create("https://wrong.test.invalid:2379"),Api.V3,"","",3000,ca.toString(),"","","");
            try(EtcdHttpTransport transport=new EtcdHttpTransport(invalid,"127.0.0.1",port,null)) {
                assertThrows(IOException.class,()->transport.request("GET","/version","",new byte[0],""));
            }
        }finally{server.stop(0);}

        HttpsServer mutual=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        mutual.setHttpsConfigurator(new HttpsConfigurator(context){
            @Override public void configure(com.sun.net.httpserver.HttpsParameters params){
                SSLParameters ssl=context.getDefaultSSLParameters();ssl.setNeedClientAuth(true);params.setSSLParameters(ssl);
            }
        });
        mutual.createContext("/version",exchange->{byte[] body="{\"etcdserver\":\"3.5.34\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});mutual.start();
        Path privateKey=temp.resolve("client.key");try(JcaPEMWriter writer=new JcaPEMWriter(Files.newBufferedWriter(privateKey))){writer.writeObject(pair.getPrivate());}
        try {
            Options without=new Options(URI.create("https://etcd.test.invalid:2379"),Api.V3,"","",2000,ca.toString(),"","","");
            try(EtcdHttpTransport transport=new EtcdHttpTransport(without,"127.0.0.1",mutual.getAddress().getPort(),null)){
                assertThrows(IOException.class,()->transport.request("GET","/version","",new byte[0],""));
            }
            Options with=new Options(URI.create("https://etcd.test.invalid:2379"),Api.V3,"","",2000,ca.toString(),ca.toString(),privateKey.toString(),"");
            try(EtcdHttpTransport transport=new EtcdHttpTransport(with,"127.0.0.1",mutual.getAddress().getPort(),null)){
                assertEquals("3.5.34",transport.request("GET","/version","",new byte[0],"").path("etcdserver").asText());
            }
        }finally{mutual.stop(0);}
    }
}
