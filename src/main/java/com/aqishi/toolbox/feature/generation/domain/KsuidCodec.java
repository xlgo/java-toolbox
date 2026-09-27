package com.aqishi.toolbox.feature.generation.domain;

import com.aqishi.toolbox.util.Hex;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * KSUID（Segment）：4 字节时间戳（自 1400000000 起的秒数，无符号）+ 16 字节随机载荷，
 * 共 20 字节，Base62 编码为定长 27 个字符。
 */
public final class KsuidCodec {

    public static final int LENGTH = 27;
    /** KSUID 纪元：2014-05-13T16:53:20Z */
    public static final long EPOCH_SECONDS = 1_400_000_000L;

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(62);
    private static final BigInteger MAX_VALUE = BigInteger.ONE.shiftLeft(160).subtract(BigInteger.ONE);

    /**
     * 解析结果。
     *
     * @param text       规范写法
     * @param rawSeconds 时间字段原值（相对 KSUID 纪元）
     * @param payloadHex 16 字节载荷
     */
    public record Decoded(String text, long rawSeconds, String payloadHex) {
        public Instant timestamp() {
            return Instant.ofEpochSecond(EPOCH_SECONDS + rawSeconds);
        }
    }

    private final Random random;
    private final LongSupplier clockMillis;

    public KsuidCodec() {
        this(new SecureRandom(), System::currentTimeMillis);
    }

    public KsuidCodec(Random random, LongSupplier clockMillis) {
        this.random = Objects.requireNonNull(random, "random");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    public String generate() {
        byte[] payload = new byte[16];
        random.nextBytes(payload);
        return encode(Math.floorDiv(clockMillis.getAsLong(), 1000L), payload);
    }

    public static String encode(long unixSeconds, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length != 16) {
            throw new IdCodecException(IdCodecException.Code.INVALID_LENGTH, "payload must be 16 bytes",
                    16, payload.length);
        }
        long raw = unixSeconds - EPOCH_SECONDS;
        if (raw < 0 || raw > 0xFFFFFFFFL) {
            throw new IdCodecException(IdCodecException.Code.OVERFLOW, "timestamp outside ksuid range");
        }
        byte[] bytes = new byte[20];
        bytes[0] = (byte) (raw >>> 24);
        bytes[1] = (byte) (raw >>> 16);
        bytes[2] = (byte) (raw >>> 8);
        bytes[3] = (byte) raw;
        System.arraycopy(payload, 0, bytes, 4, 16);
        char[] out = new char[LENGTH];
        BigInteger value = new BigInteger(1, bytes);
        for (int i = LENGTH - 1; i >= 0; i--) {
            BigInteger[] parts = value.divideAndRemainder(BASE);
            out[i] = ALPHABET.charAt(parts[1].intValue());
            value = parts[0];
        }
        return new String(out);
    }

    public static Decoded decode(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw new IdCodecException(IdCodecException.Code.EMPTY, "empty ksuid");
        }
        if (value.length() != LENGTH) {
            throw new IdCodecException(IdCodecException.Code.INVALID_LENGTH,
                    "ksuid must have 27 characters", LENGTH, value.length());
        }
        BigInteger number = BigInteger.ZERO;
        for (int i = 0; i < LENGTH; i++) {
            int digit = ALPHABET.indexOf(value.charAt(i));
            if (digit < 0) {
                throw new IdCodecException(IdCodecException.Code.INVALID_CHARACTER,
                        "not a base62 character", String.valueOf(value.charAt(i)), i);
            }
            number = number.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        if (number.compareTo(MAX_VALUE) > 0) {
            throw new IdCodecException(IdCodecException.Code.OVERFLOW, "ksuid exceeds 160 bits");
        }
        byte[] raw = number.toByteArray();
        byte[] bytes = new byte[20];
        int copy = Math.min(raw.length, 20);
        System.arraycopy(raw, raw.length - copy, bytes, 20 - copy, copy);
        long seconds = ((bytes[0] & 0xFFL) << 24) | ((bytes[1] & 0xFFL) << 16)
                | ((bytes[2] & 0xFFL) << 8) | (bytes[3] & 0xFFL);
        return new Decoded(value, seconds, Hex.toHex(bytes, 4, 16));
    }
}
