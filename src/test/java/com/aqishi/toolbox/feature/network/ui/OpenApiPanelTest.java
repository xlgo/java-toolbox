package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.OpenApiService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiPanelTest {
    @Test
    void commitsActiveParameterEditAndCanCancelRequest() throws Exception {
        var received = new AtomicReference<String>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test", exchange -> {
            received.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawQuery());
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var panel = new OpenApiPanel();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var spec = new OpenApiService().parse("""
                openapi: 3.0.3
                paths:
                  /test:
                    patch:
                      parameters:
                        - {name: q, in: query, schema: {type: string}}
                """);
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.getView();
                JList<?> endpoints = field(panel, "endpointList", JList.class);
                @SuppressWarnings("unchecked")
                var model = (DefaultListModel<Object>) endpoints.getModel();
                model.clear();
                model.addElement(spec.getEndpoints().get(0));
                endpoints.setSelectedIndex(0);
                JComboBox<?> servers = field(panel, "serverCombo", JComboBox.class);
                servers.setSelectedItem(base);
                JTable params = field(panel, "paramsTable", JTable.class);
                assertTrue(params.editCellAt(0, 3));
                ((JTextField) params.getEditorComponent()).setText("new value");
                field(panel, "sendBtn", JButton.class).doClick();
                assertFalse(params.isEditing());
                assertTrue(field(panel, "cancelBtn", JButton.class).isEnabled());
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("PATCH q=new%20value", received.get());
            SwingUtilities.invokeAndWait(() -> field(panel, "cancelBtn", JButton.class).doClick());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            var finished = new java.util.concurrent.atomic.AtomicBoolean();
            while (!finished.get() && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> finished.set(field(panel, "sendBtn", JButton.class).isEnabled()));
                if (!finished.get()) Thread.sleep(10);
            }
            assertTrue(finished.get(), "Cancel must restore the send button");
            SwingUtilities.invokeAndWait(() -> assertFalse(field(panel, "cancelBtn", JButton.class).isEnabled()));
        } finally {
            SwingUtilities.invokeAndWait(panel::closeResources);
            release.countDown();
            server.stop(0);
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
