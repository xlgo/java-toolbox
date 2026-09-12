package com.aqishi.toolbox.feature.cloud.ui;

import org.java_websocket.client.WebSocketClient;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pod 文件传输资源登记表：跟踪在途 WebSocket 与流式工作线程。
 *
 * <p>从 {@code K8sManagerPanel} 拆出后由面板持有，并注入各传输对话框，
 * 使 {@code ManagedResourceOwner.closeResources()} 能在应用退出时统一取消传输。</p>
 */
final class TransferRegistry {

    private final Set<WebSocketClient> clients = Collections.newSetFromMap(
            new ConcurrentHashMap<WebSocketClient, Boolean>());
    private final Set<Thread> threads = Collections.newSetFromMap(
            new ConcurrentHashMap<Thread, Boolean>());

    /** 启动一个具名守护工作线程，结束后自动注销。 */
    void startWorker(String name, Runnable action) {
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } finally {
                threads.remove(Thread.currentThread());
            }
        }, name);
        worker.setDaemon(true);
        threads.add(worker);
        worker.start();
    }

    void register(WebSocketClient client) {
        if (client != null) clients.add(client);
    }

    void unregister(WebSocketClient client) {
        if (client != null) clients.remove(client);
    }

    /** 先关闭传输套接字，再中断等待中的流式工作线程。 */
    void cancelAll() {
        for (WebSocketClient client : clients.toArray(new WebSocketClient[0])) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 连接已废弃，关闭失败无需处理
            }
        }
        clients.clear();
        for (Thread worker : threads.toArray(new Thread[0])) {
            worker.interrupt();
        }
    }
}
