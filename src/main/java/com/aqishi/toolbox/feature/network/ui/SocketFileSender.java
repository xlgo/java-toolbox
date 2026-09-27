package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import javax.swing.SwingUtilities;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在后台线程里把文件按块送进会话的发送队列。队列满时阻塞等待（背压），不会丢块也不会撑爆内存；
 * 进度按块回调到 EDT，最多每 100 ms 一次。
 */
final class SocketFileSender {

    /** 块的去向：一般是会话的 sendAwait。 */
    interface ChunkSink {
        void accept(byte[] chunk) throws Exception;
    }

    /** EDT 上的回调。 */
    interface Listener {
        void onProgress(long sent, long total);

        /** 结束：error 为 null 表示成功或被取消。 */
        void onFinished(long sent, long total, boolean cancelled, Exception error);
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Thread worker;

    /** 启动发送；每个实例只用一次。 */
    void start(Path file, int chunkSize, ChunkSink sink, Listener listener) {
        Thread thread = DaemonThreads.factory("socket-debug-file").newThread(() -> run(file, chunkSize, sink, listener));
        worker = thread;
        thread.start();
    }

    void cancel() {
        cancelled.set(true);
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    boolean isRunning() {
        Thread thread = worker;
        return thread != null && thread.isAlive();
    }

    private void run(Path file, int chunkSize, ChunkSink sink, Listener listener) {
        long total = 0;
        long sent = 0;
        Exception failure = null;
        try {
            total = Files.size(file);
            long lastReport = 0;
            byte[] buffer = new byte[Math.max(1, chunkSize)];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while (!cancelled.get() && (read = in.read(buffer)) > 0) {
                    sink.accept(Arrays.copyOf(buffer, read));
                    sent += read;
                    long now = System.nanoTime();
                    if (now - lastReport > 100_000_000L) {
                        lastReport = now;
                        long s = sent;
                        long t = total;
                        SwingUtilities.invokeLater(() -> listener.onProgress(s, t));
                    }
                }
            }
        } catch (InterruptedException stopped) {
            cancelled.set(true);
        } catch (Exception error) {
            if (!cancelled.get()) {
                failure = error;
            }
        }
        long s = sent;
        long t = total;
        boolean wasCancelled = cancelled.get();
        Exception f = failure;
        SwingUtilities.invokeLater(() -> listener.onFinished(s, t, wasCancelled, f));
    }
}
