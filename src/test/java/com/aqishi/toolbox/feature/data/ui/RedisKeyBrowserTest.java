package com.aqishi.toolbox.feature.data.ui;

import com.aqishi.toolbox.infra.redis.RedisResource;
import com.aqishi.toolbox.util.ConfigManager;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import javax.swing.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RedisKeyBrowserTest {
    @Test void browserUsesCursorAndLoadMoreWithoutIssuingKeysCommand() throws Exception {
        String savedBatchSize = ConfigManager.get("redis.keys.batchSize", null);
        ConfigManager.remove("redis.keys.batchSize");
        RedisPanel panel = new RedisPanel();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        Jedis client = new Jedis() {
            @Override public String ping() { return "PONG"; }
            @Override public java.util.Set<String> keys(String pattern) { throw new AssertionError("browser must use SCAN"); }
            @Override public ScanResult<String> scan(String cursor, ScanParams params) {
                int call = calls.incrementAndGet();
                assertEquals(call == 1 ? "0" : "4", cursor);
                List<String> keys = IntStream.range(call == 1 ? 0 : 1000, call == 1 ? 1000 : 1002).mapToObj(i -> "ns:" + i).toList();
                return new ScanResult<>(call == 1 ? "4" : "0", keys);
            }
            @Override public void close() { closed.set(true); }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.getView();
                assertEquals(1000, field(panel, "keyBatchSizeSpinner", JSpinner.class).getValue());
                set(panel, "jedis", client);
                set(panel, "redisResource", new RedisResource(client));
                field(panel, "refreshBtn", JButton.class).setEnabled(true);
                field(panel, "refreshBtn", JButton.class).doClick();
            });
            await(() -> field(panel, "moreKeysBtn", JButton.class).isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1000, field(panel, "keyListModel", DefaultListModel.class).size());
                field(panel, "moreKeysBtn", JButton.class).doClick();
            });
            await(() -> field(panel, "keyListModel", DefaultListModel.class).size() == 1002);
            SwingUtilities.invokeAndWait(() -> assertFalse(field(panel, "moreKeysBtn", JButton.class).isEnabled()));
            assertEquals(2, calls.get());
        } finally {
            SwingUtilities.invokeAndWait(panel::closeResources);
            restoreBatchSize(savedBatchSize);
        }
        assertTrue(closed.get());
    }

    @Test void typedBatchSizeIsCommittedRememberedAndUsedByLoadMore() throws Exception {
        String savedBatchSize = ConfigManager.get("redis.keys.batchSize", null);
        ConfigManager.remove("redis.keys.batchSize");
        RedisPanel panel = new RedisPanel();
        AtomicInteger calls = new AtomicInteger();
        Jedis client = new Jedis() {
            @Override public String ping() { return "PONG"; }
            @Override public ScanResult<String> scan(String cursor, ScanParams params) {
                int call = calls.incrementAndGet();
                assertEquals(call == 1 ? "0" : "4", cursor);
                return new ScanResult<>(call == 1 ? "4" : "0",
                        IntStream.range(call == 1 ? 0 : 1000, call == 1 ? 1000 : 1600).mapToObj(i -> "key:" + i).toList());
            }
            @Override public void close() { }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.getView();
                set(panel, "jedis", client);
                set(panel, "redisResource", new RedisResource(client));
                field(panel, "refreshBtn", JButton.class).setEnabled(true);
                field(panel, "refreshBtn", JButton.class).doClick();
            });
            await(() -> field(panel, "moreKeysBtn", JButton.class).isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                var spinner = field(panel, "keyBatchSizeSpinner", JSpinner.class);
                ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText("250");
                field(panel, "moreKeysBtn", JButton.class).doClick();
                assertEquals(250, ConfigManager.getInt("redis.keys.batchSize", 0));
            });
            await(() -> field(panel, "keyListModel", DefaultListModel.class).size() == 1250 && field(panel, "moreKeysBtn", JButton.class).isEnabled());
            SwingUtilities.invokeAndWait(() -> field(panel, "moreKeysBtn", JButton.class).doClick());
            await(() -> field(panel, "keyListModel", DefaultListModel.class).size() == 1500 && field(panel, "moreKeysBtn", JButton.class).isEnabled());
            SwingUtilities.invokeAndWait(() -> field(panel, "moreKeysBtn", JButton.class).doClick());
            await(() -> field(panel, "keyListModel", DefaultListModel.class).size() == 1600 && !field(panel, "moreKeysBtn", JButton.class).isEnabled());
            assertEquals(2, calls.get());
            SwingUtilities.invokeAndWait(() -> {
                RedisPanel next = new RedisPanel();
                try {
                    next.getView();
                    assertEquals(250, field(next, "keyBatchSizeSpinner", JSpinner.class).getValue());
                } finally { next.closeResources(); }
            });
        } finally {
            SwingUtilities.invokeAndWait(panel::closeResources);
            restoreBatchSize(savedBatchSize);
        }
    }

    private static void restoreBatchSize(String value) {
        if (value == null) ConfigManager.remove("redis.keys.batchSize");
        else ConfigManager.set("redis.keys.batchSize", value);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AtomicBoolean done = new AtomicBoolean();
        while (System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(20);
        }
        fail("browser did not finish loading");
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
