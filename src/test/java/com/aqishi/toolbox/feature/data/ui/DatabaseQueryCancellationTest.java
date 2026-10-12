package com.aqishi.toolbox.feature.data.ui;

import com.aqishi.toolbox.util.I18n;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseQueryCancellationTest {
    @Test void cancelDoesNotEnableAnotherQueryUntilOriginalExecutionEnds() throws Exception {
        DatabasePanel panel = new DatabasePanel();
        CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "execute": entered.countDown(); assertTrue(released.await(5, TimeUnit.SECONDS)); return false;
                case "cancel": cancelled.countDown(); return null;
                case "getUpdateCount": return 0;
                default: return null;
            }
        });
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("isClosed")) return false;
            if (method.getName().equals("createStatement")) return statement;
            return null;
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.getView();
                set(panel, "connection", connection);
                set(panel, "isConnected", true);
                field(panel, "sqlEditor", JTextArea.class).setText("SELECT slow");
                field(panel, "runBtn", JButton.class).doClick();
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> field(panel, "cancelSqlBtn", JButton.class).doClick());
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertFalse(field(panel, "runBtn", JButton.class).isEnabled()));
            released.countDown();
            AtomicBoolean finished = new AtomicBoolean();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!finished.get() && System.nanoTime() < end) {
                SwingUtilities.invokeAndWait(() -> finished.set(field(panel, "runBtn", JButton.class).isEnabled()));
                if (!finished.get()) Thread.sleep(20);
            }
            assertTrue(finished.get());
            SwingUtilities.invokeAndWait(() -> assertEquals(I18n.get("data.query.cancelled"), field(panel, "resultStatusLabel", JLabel.class).getText()));
        } finally {
            released.countDown();
            SwingUtilities.invokeAndWait(panel::closeResources);
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
