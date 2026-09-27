package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketEvent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/** 收集会话事件并提供带超时的等待，测试里不用 sleep。 */
final class EventRecorder implements SocketSessionListener {

    static final long TIMEOUT_MS = 5000;

    private final List<SocketEvent> events = new ArrayList<>();

    @Override
    public synchronized void onEvent(SocketEvent event) {
        events.add(event);
        notifyAll();
    }

    synchronized List<SocketEvent> snapshot() {
        return new ArrayList<>(events);
    }

    /** 等到出现满足条件的事件并返回它。 */
    synchronized SocketEvent await(Predicate<SocketEvent> condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (true) {
            for (SocketEvent event : events) {
                if (condition.test(event)) {
                    return event;
                }
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            if (remaining <= 0) {
                fail("Timed out waiting for event; got " + events);
            }
            wait(remaining);
        }
    }

    SocketEvent await(SocketEvent.Type type) throws InterruptedException {
        return await(e -> e.getType() == type);
    }

    /** 等到指定客户端（0 表示不限）收到的字节拼起来包含 expected。 */
    synchronized String awaitReceived(int clientId, String expected) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (true) {
            String text = received(clientId);
            if (text.contains(expected)) {
                return text;
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            if (remaining <= 0) {
                fail("Timed out waiting for '" + expected + "'; received '" + text + "'");
            }
            wait(remaining);
        }
    }

    synchronized String received(int clientId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (SocketEvent event : events) {
            if (event.getType() == SocketEvent.Type.RECEIVED && (clientId == 0 || event.getClientId() == clientId)) {
                out.write(event.getData(), 0, event.getData().length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    synchronized long count(Predicate<SocketEvent> condition) {
        return events.stream().filter(condition).count();
    }

    synchronized void clear() {
        events.clear();
    }

    /** 等待所有以 socket-debug 开头的线程退出。 */
    static boolean awaitNoSessionThreads(long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            if (sessionThreads().isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return sessionThreads().isEmpty();
    }

    static List<String> sessionThreads() {
        List<String> names = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(SocketSession.THREAD_PREFIX)) {
                names.add(thread.getName());
            }
        }
        return names;
    }
}
