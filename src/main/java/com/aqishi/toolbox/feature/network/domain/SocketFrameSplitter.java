package com.aqishi.toolbox.feature.network.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * TCP 是字节流，一次 read 拿到的数据与对端的"一条消息"没有对应关系。本类按配置把字节流切成帧，
 * 仅用于接收显示；统计与回显仍按原始字节进行。
 *
 * <p>每条连接一个实例，非线程安全（由该连接的读线程独占）。时间由调用方传入，便于测试注入。</p>
 */
public final class SocketFrameSplitter {

    /** 分帧方式。 */
    public enum Mode {
        /** 每次读到的块即一帧。 */
        RAW,
        /** 按分隔符切分（如 \n、\r\n 或任意字节序列）。 */
        DELIMITER,
        /** 每 N 字节一帧。 */
        FIXED_LENGTH,
        /** 连续到达的块合并，直到空闲超过 N 毫秒。 */
        IDLE_TIMEOUT
    }

    /** 默认单帧上限：防止对端不发分隔符时缓冲无限增长。 */
    public static final int DEFAULT_MAX_FRAME = 64 * 1024;

    /** 不可变的分帧配置。 */
    public static final class Config {
        private final Mode mode;
        private final byte[] delimiter;
        private final boolean keepDelimiter;
        private final int fixedLength;
        private final long idleMillis;
        private final int maxFrameSize;

        private Config(Mode mode, byte[] delimiter, boolean keepDelimiter, int fixedLength,
                       long idleMillis, int maxFrameSize) {
            this.mode = mode;
            this.delimiter = delimiter;
            this.keepDelimiter = keepDelimiter;
            this.fixedLength = fixedLength;
            this.idleMillis = idleMillis;
            this.maxFrameSize = maxFrameSize;
        }

        public static Config raw() {
            return new Config(Mode.RAW, new byte[0], false, 0, 0, DEFAULT_MAX_FRAME);
        }

        public static Config delimiter(byte[] delimiter, boolean keepDelimiter) {
            Objects.requireNonNull(delimiter, "delimiter");
            if (delimiter.length == 0) {
                throw new IllegalArgumentException("delimiter must not be empty");
            }
            return new Config(Mode.DELIMITER, delimiter.clone(), keepDelimiter, 0, 0, DEFAULT_MAX_FRAME);
        }

        public static Config fixedLength(int length) {
            if (length < 1) {
                throw new IllegalArgumentException("fixed length must be >= 1");
            }
            return new Config(Mode.FIXED_LENGTH, new byte[0], false, length, 0,
                    Math.max(DEFAULT_MAX_FRAME, length));
        }

        public static Config idleTimeout(long idleMillis) {
            if (idleMillis < 1) {
                throw new IllegalArgumentException("idle timeout must be >= 1 ms");
            }
            return new Config(Mode.IDLE_TIMEOUT, new byte[0], false, 0, idleMillis, DEFAULT_MAX_FRAME);
        }

        /** 返回替换了单帧上限的副本；定长模式下上限不会小于帧长。 */
        public Config withMaxFrameSize(int maxFrameSize) {
            if (maxFrameSize < 1) {
                throw new IllegalArgumentException("max frame size must be >= 1");
            }
            int effective = mode == Mode.FIXED_LENGTH ? Math.max(maxFrameSize, fixedLength) : maxFrameSize;
            return new Config(mode, delimiter, keepDelimiter, fixedLength, idleMillis, effective);
        }

        public Mode getMode() {
            return mode;
        }

        public byte[] getDelimiter() {
            return delimiter.clone();
        }

        public boolean isKeepDelimiter() {
            return keepDelimiter;
        }

        public int getFixedLength() {
            return fixedLength;
        }

        public long getIdleMillis() {
            return idleMillis;
        }

        public int getMaxFrameSize() {
            return maxFrameSize;
        }
    }

    private final Config config;
    private byte[] buffer = new byte[256];
    private int size;
    /** 分隔符搜索的起点，避免每来一块都从头扫描整个缓冲。 */
    private int scanFrom;
    private long lastDataAt;

    public SocketFrameSplitter(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Config getConfig() {
        return config;
    }

    /** 喂入一块数据，返回因此完整的帧（可能为空）。 */
    public List<byte[]> feed(byte[] data, int offset, int length, long nowMillis) {
        if (length == 0) {
            return Collections.emptyList();
        }
        if (config.mode == Mode.RAW) {
            List<byte[]> frames = new ArrayList<>(1);
            for (int start = offset; start < offset + length; start += config.maxFrameSize) {
                int end = Math.min(offset + length, start + config.maxFrameSize);
                frames.add(Arrays.copyOfRange(data, start, end));
            }
            return frames;
        }
        append(data, offset, length);
        lastDataAt = nowMillis;
        List<byte[]> frames = new ArrayList<>();
        switch (config.mode) {
            case DELIMITER:
                splitDelimited(frames);
                break;
            case FIXED_LENGTH:
                while (size >= config.fixedLength) {
                    frames.add(take(config.fixedLength, 0));
                }
                break;
            default:
                break;
        }
        enforceMax(frames);
        return frames;
    }

    public List<byte[]> feed(byte[] data, long nowMillis) {
        return feed(data, 0, data.length, nowMillis);
    }

    /** 空闲超时模式下，若距最后一块数据已超过阈值则返回合并后的帧。其他模式恒为空。 */
    public List<byte[]> poll(long nowMillis) {
        if (config.mode == Mode.IDLE_TIMEOUT && size > 0 && nowMillis - lastDataAt >= config.idleMillis) {
            return Collections.singletonList(take(size, 0));
        }
        return Collections.emptyList();
    }

    /** 下一次应调用 {@link #poll} 的时刻；没有待定数据时返回 -1。 */
    public long nextDeadline() {
        if (config.mode == Mode.IDLE_TIMEOUT && size > 0) {
            return lastDataAt + config.idleMillis;
        }
        return -1L;
    }

    /** 已缓冲、尚未成帧的字节数。 */
    public int pending() {
        return size;
    }

    /** 取出剩余字节（连接关闭时调用，避免丢掉最后半帧）；无剩余时返回 null。 */
    public byte[] drain() {
        return size == 0 ? null : take(size, 0);
    }

    private void splitDelimited(List<byte[]> frames) {
        byte[] delimiter = config.delimiter;
        int i = Math.max(0, scanFrom);
        while (i + delimiter.length <= size) {
            if (matchesAt(i, delimiter)) {
                int frameLength = i + delimiter.length;
                frames.add(take(frameLength, config.keepDelimiter ? 0 : delimiter.length));
                i = 0;
            } else {
                i++;
            }
        }
        // 下次从可能构成分隔符前缀的位置继续，分隔符跨块到达也能识别
        scanFrom = Math.max(0, size - delimiter.length + 1);
    }

    private boolean matchesAt(int position, byte[] delimiter) {
        for (int k = 0; k < delimiter.length; k++) {
            if (buffer[position + k] != delimiter[k]) {
                return false;
            }
        }
        return true;
    }

    /** 缓冲超过上限时强制切出，宁可切断一条超长消息也不让内存失控。 */
    private void enforceMax(List<byte[]> frames) {
        while (size >= config.maxFrameSize && config.mode != Mode.FIXED_LENGTH) {
            frames.add(take(config.maxFrameSize, 0));
        }
    }

    /** 从缓冲头部取走 length 字节，返回其中前 length - trimTail 字节。 */
    private byte[] take(int length, int trimTail) {
        byte[] frame = Arrays.copyOf(buffer, length - trimTail);
        System.arraycopy(buffer, length, buffer, 0, size - length);
        size -= length;
        scanFrom = 0;
        return frame;
    }

    private void append(byte[] data, int offset, int length) {
        if (size + length > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + length));
        }
        System.arraycopy(data, offset, buffer, size, length);
        size += length;
    }
}
