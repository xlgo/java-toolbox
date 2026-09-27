package com.aqishi.toolbox.feature.generation.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * ULID：48 位 Unix 毫秒 + 80 位随机数，Crockford Base32 编码为 26 个字符。
 *
 * <p>128 位与 UUID 完全等长，所以用 {@link UUID} 作为二进制容器，ULID ↔ UUID 互转只是换一种写法。</p>
 */
public final class UlidCodec {

    public static final int LENGTH = 26;
    public static final long MAX_TIMESTAMP = 0xFFFFFFFFFFFFL;

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int[] DECODE = new int[128];

    static {
        java.util.Arrays.fill(DECODE, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE[ALPHABET[i]] = i;
            DECODE[Character.toLowerCase(ALPHABET[i])] = i;
        }
        // Crockford 规定的易混字符宽容映射；U 不在字母表中，按规范拒绝
        DECODE['I'] = 1;
        DECODE['i'] = 1;
        DECODE['L'] = 1;
        DECODE['l'] = 1;
        DECODE['O'] = 0;
        DECODE['o'] = 0;
    }

    /**
     * 解析结果。
     *
     * @param canonical       规范写法（大写、易混字符已归一）
     * @param timestampMillis 时间部分
     * @param randomnessHex   80 位随机部分的十六进制
     * @param uuid            同样 128 位的 UUID 写法
     * @param lenient         输入里用到了 I / L / O，已按 Crockford 规则归一（大小写不算）
     */
    public record Decoded(String canonical, long timestampMillis, String randomnessHex, UUID uuid,
                          boolean lenient) {
        public Instant timestamp() {
            return Instant.ofEpochMilli(timestampMillis);
        }
    }

    private final Random random;
    private final LongSupplier clockMillis;

    private long lastMillis = -1L;
    private long lastRandomHigh;
    private long lastRandomLow;

    public UlidCodec() {
        this(new SecureRandom(), System::currentTimeMillis);
    }

    public UlidCodec(Random random, LongSupplier clockMillis) {
        this.random = Objects.requireNonNull(random, "random");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** 每次都取全新随机数；同一毫秒内的多个 ULID 之间没有顺序保证 */
    public String generate() {
        long millis = clockMillis.getAsLong();
        checkTimestamp(millis);
        return encode(millis, random.nextInt(0x10000), random.nextLong());
    }

    /**
     * 单调模式：同一毫秒（或时钟回拨）时沿用上一个时间戳并把随机部分加 1，保证严格递增；
     * 80 位加满时按规范报错而不是回绕。
     */
    public synchronized String generateMonotonic() {
        long millis = clockMillis.getAsLong();
        checkTimestamp(millis);
        if (millis > lastMillis) {
            lastMillis = millis;
            lastRandomHigh = random.nextInt(0x10000);
            lastRandomLow = random.nextLong();
        } else {
            long low = lastRandomLow + 1;
            long high = lastRandomHigh;
            if (low == 0) {
                high++;
                if (high > 0xFFFFL) {
                    throw new IdCodecException(IdCodecException.Code.MONOTONIC_OVERFLOW,
                            "ulid randomness overflow within the same millisecond");
                }
            }
            lastRandomLow = low;
            lastRandomHigh = high;
        }
        return encode(lastMillis, lastRandomHigh, lastRandomLow);
    }

    private static void checkTimestamp(long millis) {
        if (millis < 0 || millis > MAX_TIMESTAMP) {
            throw new IdCodecException(IdCodecException.Code.OVERFLOW, "timestamp outside 48 bits");
        }
    }

    /**
     * @param randomHigh 随机部分高 16 位
     * @param randomLow  随机部分低 64 位
     */
    public static String encode(long millis, long randomHigh, long randomLow) {
        checkTimestamp(millis);
        long msb = (millis << 16) | (randomHigh & 0xFFFFL);
        return fromUuid(new UUID(msb, randomLow));
    }

    /** 同 128 位的 UUID 写成 ULID */
    public static String fromUuid(UUID uuid) {
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        char[] out = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (lo & 31)];
            lo = (lo >>> 5) | (hi << 59);
            hi >>>= 5;
        }
        return new String(out);
    }

    public static UUID toUuid(String ulid) {
        return decode(ulid).uuid();
    }

    public static Decoded decode(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw new IdCodecException(IdCodecException.Code.EMPTY, "empty ulid");
        }
        if (value.length() != LENGTH) {
            throw new IdCodecException(IdCodecException.Code.INVALID_LENGTH,
                    "ulid must have 26 characters", LENGTH, value.length());
        }
        long hi = 0;
        long lo = 0;
        boolean lenient = false;
        for (int i = 0; i < LENGTH; i++) {
            char c = value.charAt(i);
            int digit = c < 128 ? DECODE[c] : -1;
            if (digit < 0) {
                throw new IdCodecException(IdCodecException.Code.INVALID_CHARACTER,
                        "not a Crockford base32 character", String.valueOf(c), i);
            }
            if (ALPHABET[digit] != Character.toUpperCase(c)) {
                lenient = true;
            }
            if (i == 0 && digit > 7) {
                // 26 × 5 = 130 位，首字符只能占 3 位，超过 7 就超出 128 位
                throw new IdCodecException(IdCodecException.Code.OVERFLOW, "ulid exceeds 128 bits");
            }
            hi = (hi << 5) | (lo >>> 59);
            lo = (lo << 5) | digit;
        }
        UUID uuid = new UUID(hi, lo);
        String randomness = String.format(Locale.ROOT, "%04x%016x", hi & 0xFFFFL, lo);
        return new Decoded(fromUuid(uuid), hi >>> 16, randomness, uuid, lenient);
    }
}
