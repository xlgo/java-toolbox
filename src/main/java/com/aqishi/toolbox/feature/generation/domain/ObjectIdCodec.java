package com.aqishi.toolbox.feature.generation.domain;

import com.aqishi.toolbox.util.Hex;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * MongoDB ObjectId：4 字节 Unix 秒 + 5 字节进程级随机值 + 3 字节计数器，共 12 字节 / 24 位十六进制。
 *
 * <p>随机值在每个生成器实例（相当于一个进程）内固定，计数器从随机值起步、每次加 1 并在 24 位内回绕，
 * 与官方驱动的行为一致。</p>
 */
public final class ObjectIdCodec {

    public static final int HEX_LENGTH = 24;

    /**
     * 解析结果。
     *
     * @param hex       规范写法（小写）
     * @param seconds   时间部分（Unix 秒，无符号 32 位）
     * @param randomHex 5 字节随机值
     * @param counter   3 字节计数器
     */
    public record Decoded(String hex, long seconds, String randomHex, int counter) {
        public Instant timestamp() {
            return Instant.ofEpochSecond(seconds);
        }
    }

    private final LongSupplier clockMillis;
    private final byte[] processRandom = new byte[5];
    private final AtomicInteger counter;

    public ObjectIdCodec() {
        this(new SecureRandom(), System::currentTimeMillis);
    }

    public ObjectIdCodec(Random random, LongSupplier clockMillis) {
        Objects.requireNonNull(random, "random");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        random.nextBytes(processRandom);
        this.counter = new AtomicInteger(random.nextInt(0x1000000));
    }

    public String generate() {
        long seconds = Math.floorDiv(clockMillis.getAsLong(), 1000L);
        int count = counter.getAndIncrement() & 0xFFFFFF;
        byte[] bytes = new byte[12];
        bytes[0] = (byte) (seconds >>> 24);
        bytes[1] = (byte) (seconds >>> 16);
        bytes[2] = (byte) (seconds >>> 8);
        bytes[3] = (byte) seconds;
        System.arraycopy(processRandom, 0, bytes, 4, 5);
        bytes[9] = (byte) (count >>> 16);
        bytes[10] = (byte) (count >>> 8);
        bytes[11] = (byte) count;
        return Hex.toHex(bytes);
    }

    public static Decoded decode(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw new IdCodecException(IdCodecException.Code.EMPTY, "empty object id");
        }
        if (value.length() != HEX_LENGTH) {
            throw new IdCodecException(IdCodecException.Code.INVALID_LENGTH,
                    "object id must have 24 hex digits", HEX_LENGTH, value.length());
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                throw new IdCodecException(IdCodecException.Code.INVALID_CHARACTER,
                        "not a hex digit", String.valueOf(value.charAt(i)), i);
            }
        }
        String hex = value.toLowerCase(java.util.Locale.ROOT);
        long seconds = Long.parseLong(hex.substring(0, 8), 16);
        int count = Integer.parseInt(hex.substring(18), 16);
        return new Decoded(hex, seconds, hex.substring(8, 18), count);
    }
}
