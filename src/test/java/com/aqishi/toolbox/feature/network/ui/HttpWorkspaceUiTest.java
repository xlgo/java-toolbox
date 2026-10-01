package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.HttpWorkspaceStore;
import com.aqishi.toolbox.feature.network.domain.HttpWorkspace.*;
import com.aqishi.toolbox.infra.secrets.VaultSecretStore;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HttpWorkspaceUiTest {
    @TempDir Path temp;
    @Test void sendsResolvedPatchButPersistsOnlyTemplateAndClearsSelectionsOnLock() throws Exception {
        var received=new AtomicReference<String>();var latch=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/echo",exchange->{
            received.set(exchange.getRequestMethod()+"|"+exchange.getRequestHeaders().getFirst("Authorization")+"|"+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204,-1);exchange.close();latch.countDown();
        });server.start();
        try(var vault=new VaultUiTestSupport(temp)){
            vault.service().create("master".toCharArray()).get();var secrets=new VaultSecretStore(vault.service());var store=new HttpWorkspaceStore(secrets);
            store.edit(d->d.environment(new Environment("test",Map.of("baseUrl",new Variable("http://127.0.0.1:"+server.getAddress().getPort(),false),"token",new Variable("ui-secret-token",true))))).get();
            var panel=new HttpTestPanel(secrets);
            try {
                SwingUtilities.invokeAndWait(()->{
                    panel.getView();field(panel,"methodBox",JComboBox.class).setSelectedItem("PATCH");
                    field(panel,"urlField",JTextField.class).setText("{{baseUrl}}/echo");
                    field(panel,"reqHeadersArea",JTextArea.class).setText("Authorization: Bearer {{token}}\nContent-Type: text/plain");
                    field(panel,"reqBodyArea",JTextArea.class).setText("{{token}}");
                    var bar=field(panel,"workspace",HttpWorkspaceBar.class);field(bar,"environment",JComboBox.class).setSelectedItem("test");
                    assertTrue(bar.resolve(new Request("","GET","{{baseUrl}}","{{token}}","",false),true).headers().contains("******"));
                    field(panel,"sendBtn",JButton.class).doClick();
                });
                assertTrue(latch.await(8,TimeUnit.SECONDS));assertEquals("PATCH|Bearer ui-secret-token|ui-secret-token",received.get());
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(store.load().history().isEmpty()&&System.nanoTime()<end)Thread.sleep(20);
                assertEquals("{{baseUrl}}/echo",store.load().history().get(0).url());assertEquals("{{token}}",store.load().history().get(0).body());
                vault.service().lock();
                SwingUtilities.invokeAndWait(()->{
                    var bar=field(panel,"workspace",HttpWorkspaceBar.class);
                    assertEquals(1,field(bar,"environment",JComboBox.class).getItemCount());assertEquals(0,field(bar,"saved",JComboBox.class).getItemCount());
                    assertEquals("",field(panel,"reqHeadersArea",JTextArea.class).getText());
                    assertEquals("",field(panel,"reqBodyArea",JTextArea.class).getText());
                });
            } finally { SwingUtilities.invokeAndWait(panel::closeResources); }
        }finally{server.stop(0);}
    }
    private static <T>T field(Object object,String name,Class<T> type){try{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(object));}catch(Exception error){throw new AssertionError(error);}}
}
