package com.aqishi.toolbox.vault;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecureClipboardTest {
    @Test
    void clearsOnlyWhenClipboardStillContainsSensitiveValue() throws Exception {
        MemoryClipboard gateway = new MemoryClipboard();
        CapturingScheduler scheduler = new CapturingScheduler();
        SecureClipboard clipboard = new SecureClipboard(gateway, scheduler);

        clipboard.copySensitive("secret");
        scheduler.runScheduled();

        assertEquals("", gateway.readText());
    }

    @Test
    void preservesContentCopiedByTheUserLater() throws Exception {
        MemoryClipboard gateway = new MemoryClipboard();
        CapturingScheduler scheduler = new CapturingScheduler();
        SecureClipboard clipboard = new SecureClipboard(gateway, scheduler);

        clipboard.copySensitive("secret");
        gateway.writeText("user replacement");
        scheduler.runScheduled();

        assertEquals("user replacement", gateway.readText());
    }

    private static final class MemoryClipboard implements SecureClipboard.ClipboardGateway {
        private String value = "";

        @Override
        public String readText() {
            return value;
        }

        @Override
        public void writeText(String value) {
            this.value = value;
        }

        @Override
        public void clear() {
            value = "";
        }
    }

    private static final class CapturingScheduler implements VaultScheduler {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
            assertEquals(30, delay);
            assertEquals(TimeUnit.SECONDS, unit);
            tasks.add(task);
            return () -> tasks.remove(task);
        }

        @Override
        public Cancellable scheduleAtFixedRate(
                Runnable task, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        void runScheduled() {
            List<Runnable> copy = new ArrayList<>(tasks);
            tasks.clear();
            copy.forEach(Runnable::run);
        }

        @Override
        public void close() {
            tasks.clear();
        }
    }
}
