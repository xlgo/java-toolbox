package com.aqishi.toolbox.feature.network.domain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话收发计数，线程安全。速率由相邻两次 {@link #snapshot()} 之差算出，不在热路径上做任何计时。
 */
public final class SocketStats {

    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong packetsReceived = new AtomicLong();

    public void recordSent(int bytes) {
        bytesSent.addAndGet(bytes);
        packetsSent.incrementAndGet();
    }

    public void recordReceived(int bytes) {
        bytesReceived.addAndGet(bytes);
        packetsReceived.incrementAndGet();
    }

    public void reset() {
        bytesSent.set(0);
        bytesReceived.set(0);
        packetsSent.set(0);
        packetsReceived.set(0);
    }

    public Snapshot snapshot() {
        return new Snapshot(System.nanoTime(), bytesSent.get(), bytesReceived.get(),
                packetsSent.get(), packetsReceived.get());
    }

    /** 某一时刻的计数快照。 */
    public static final class Snapshot {
        private final long nanoTime;
        private final long bytesSent;
        private final long bytesReceived;
        private final long packetsSent;
        private final long packetsReceived;

        public Snapshot(long nanoTime, long bytesSent, long bytesReceived,
                        long packetsSent, long packetsReceived) {
            this.nanoTime = nanoTime;
            this.bytesSent = bytesSent;
            this.bytesReceived = bytesReceived;
            this.packetsSent = packetsSent;
            this.packetsReceived = packetsReceived;
        }

        public long getBytesSent() {
            return bytesSent;
        }

        public long getBytesReceived() {
            return bytesReceived;
        }

        public long getPacketsSent() {
            return packetsSent;
        }

        public long getPacketsReceived() {
            return packetsReceived;
        }

        /** 相对 previous 的发送速率（字节/秒）；previous 为空或间隔为零时返回 0。 */
        public long sendRate(Snapshot previous) {
            return rate(previous, bytesSent, previous == null ? 0 : previous.bytesSent);
        }

        /** 相对 previous 的接收速率（字节/秒）。 */
        public long receiveRate(Snapshot previous) {
            return rate(previous, bytesReceived, previous == null ? 0 : previous.bytesReceived);
        }

        private long rate(Snapshot previous, long current, long before) {
            if (previous == null) {
                return 0;
            }
            long elapsed = nanoTime - previous.nanoTime;
            long delta = current - before;
            if (elapsed <= 0 || delta <= 0) {
                return 0;
            }
            return (long) (delta * 1_000_000_000.0 / elapsed);
        }
    }
}
