package com.aqishi.toolbox.feature.cloud.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class KubectlPortForwardTest {
    private PortForwardConfig.Credentials credentials(){return new PortForwardConfig.Credentials("https://cluster.example.test","private-token",false,"ca","cert","key");}
    private KubectlPortForward.Target target(){return new KubectlPortForward.Target("default","service","api",0,"http");}
    @Test void writesPrivateKubeconfigAndDeletesOnClose() throws Exception{
        Path file;
        try(var config=PortForwardConfig.create(credentials())){
            file=config.path();var json=new ObjectMapper().readTree(Files.readString(file));
            assertEquals("private-token",json.path("users").get(0).path("user").path("token").asText());
            assertEquals(Base64.getEncoder().encodeToString("ca".getBytes(StandardCharsets.UTF_8)),json.path("clusters").get(0).path("cluster").path("certificate-authority-data").asText());
            if(Files.getFileStore(file).supportsFileAttributeView("posix"))assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
            else assertNotNull(Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class));
        }
        assertFalse(Files.exists(file));assertFalse(Files.exists(file.getParent()));
    }
    @Test void commandHasNoSecretsAndBindsOnlyToLoopback(){
        var args=KubectlPortForward.command("C:/Program Files/kubectl.exe",Path.of("private-config"),target());
        assertEquals("C:/Program Files/kubectl.exe",args.get(0));assertTrue(args.contains(":http"));assertTrue(args.contains("--address=127.0.0.1"));
        assertFalse(args.toString().contains("private-token"));
    }
    @Test void skipTlsDoesNotAlsoPassCa() throws Exception {
        var c=new PortForwardConfig.Credentials("https://localhost","t",true,"ca","","");
        var cluster=new ObjectMapper().readTree(PortForwardConfig.json(c)).path("clusters").get(0).path("cluster");
        assertTrue(cluster.path("insecure-skip-tls-verify").asBoolean());assertFalse(cluster.has("certificate-authority-data"));
    }
    @Test void rejectsInvalidResourceAndPortArguments(){
        assertThrows(IllegalArgumentException.class,()->new KubectlPortForward.Target("default","pod","--bad",0,"80"));
        assertThrows(IllegalArgumentException.class,()->new KubectlPortForward.Target("default","pod","api",0,"65536"));
        assertThrows(IllegalArgumentException.class,()->new KubectlPortForward.Target("default","pod","api",0,"80;whoami"));
        assertThrows(IllegalArgumentException.class,()->new PortForwardConfig.Credentials("file:///tmp/x","",false,"","",""));
    }
    @Test void childListenerIsDetectedAndCloseReleasesPortAndConfig() throws Exception {
        AtomicReference<Path> configFile=new AtomicReference<>();AtomicReference<Process> process=new AtomicReference<>();
        var session=new KubectlPortForward("kubectl",target(),command -> {
            configFile.set(Path.of(command.get(2)));
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),EchoChild.class.getName()).redirectErrorStream(true).start();process.set(child);return child;
        });
        try {
            session.start(credentials());await(()->session.snapshot().state()!=KubectlPortForward.State.STARTING);
            assertEquals(KubectlPortForward.State.RUNNING,session.snapshot().state(),session.snapshot().log());
            assertFalse(session.snapshot().log().contains("private-token"));assertTrue(Files.exists(configFile.get()));
            int port=session.snapshot().localPort();
            try(Socket socket=new Socket("127.0.0.1",port)){socket.setSoTimeout(3000);socket.getOutputStream().write(42);assertEquals(42,socket.getInputStream().read());}
            session.close();await(()->!process.get().isAlive()&&!Files.exists(configFile.get()));
            assertEquals(KubectlPortForward.State.STOPPED,session.snapshot().state());
            try(ServerSocket rebound=new ServerSocket()){rebound.setReuseAddress(true);rebound.bind(new InetSocketAddress("127.0.0.1",port));}
        }finally{session.close();}
    }
    @Test void missingExecutableCleansUpAndCloseBeforeStartIsRejected() throws Exception {
        AtomicReference<Path> file=new AtomicReference<>();
        var session=new KubectlPortForward("missing",target(),command->{file.set(Path.of(command.get(2)));throw new IOException("private-token");});
        session.start(credentials());await(()->session.snapshot().state()==KubectlPortForward.State.FAILED);
        await(()->file.get()!=null&&!Files.exists(file.get()));assertFalse(session.snapshot().log().contains("private-token"));session.close();
        var never=new KubectlPortForward("unused",target());never.close();assertThrows(IllegalStateException.class,()->never.start(credentials()));
    }
    private static void await(BooleanSupplier done) throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);assertTrue(done.getAsBoolean(),"Timed out waiting for port-forward lifecycle");}
    public static class EchoChild {
        public static void main(String[] args)throws Exception{
            try(ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))){
                System.out.println("token=private-token");System.out.println("Forwarding from 127.0.0.1:"+server.getLocalPort()+" -> 8080");System.out.flush();
                while(true)try(Socket socket=server.accept()){socket.getOutputStream().write(socket.getInputStream().read());}
            }
        }
    }
}
