package com.aqishi.toolbox.feature.generation.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * UUID 的生成与解析（RFC 9562）。
 *
 * <p>v1 / v6 / v7 生成需要跨调用保持单调状态，所以这些方法是实例方法且同步；
 * v3 / v5 / 解析是纯函数，为静态方法。</p>
 */
public final class UuidCodec {

    public static final UUID NIL = new UUID(0L, 0L);
    public static final UUID MAX = new UUID(-1L, -1L);

    public static final UUID NAMESPACE_DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    public static final UUID NAMESPACE_URL = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    public static final UUID NAMESPACE_OID = UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8");
    public static final UUID NAMESPACE_X500 = UUID.fromString("6ba7b814-9dad-11d1-80b4-00c04fd430c8");

    /** 1582-10-15（格里高利历起点）到 1970-01-01 之间的 100 纳秒数 */
    public static final long GREGORIAN_OFFSET = 0x01B21DD213814000L;

    /** UUID 变体 */
    public enum Variant {
        /** 0xx：NCS 向后兼容保留 */
        NCS,
        /** 10x：RFC 4122 / RFC 9562 */
        RFC_9562,
        /** 110：微软 GUID 向后兼容保留 */
        MICROSOFT,
        /** 111：保留给未来 */
        FUTURE
    }

    /**
     * 解析结果。
     *
     * @param uuid            规范化后的值
     * @param version         版本号（高 4 位），nil / max 分别为 0 / 15
     * @param variant         变体
     * @param timestamp       v1 / v6 / v7 内嵌的时间，其余为 null
     * @param gregorian100ns  v1 / v6 的原始 60 位时间戳（100ns，自 1582-10-15 起），其余为 null
     * @param clockSequence   v1 / v6 的 14 位时钟序列，其余为 null
     * @param node            v1 / v6 的 48 位节点，其余为 null
     * @param nodeMulticast   节点首字节最低位为 1：按 RFC 约定表示随机生成而非真实 MAC
     * @param randA           v7 的 12 位 rand_a（本实现用作同毫秒计数器），其余为 null
     */
    public record Info(UUID uuid, int version, Variant variant, Instant timestamp, Long gregorian100ns,
                       Integer clockSequence, Long node, boolean nodeMulticast, Integer randA) {

        public boolean isNil() {
            return NIL.equals(uuid);
        }

        public boolean isMax() {
            return MAX.equals(uuid);
        }
    }

    private final Random random;
    private final LongSupplier clockMillis;
    private final long node;
    private final int clockSequence;

    private long lastV7Millis = -1L;
    private int v7Counter;
    private long lastGregorian = -1L;

    public UuidCodec() {
        this(new SecureRandom(), System::currentTimeMillis);
    }

    public UuidCodec(Random random, LongSupplier clockMillis) {
        this.random = Objects.requireNonNull(random, "random");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        // 不读取真实网卡 MAC：随机节点并置多播位，这是 RFC 为「非 MAC 节点」规定的标记
        this.node = (random.nextLong() & 0xFFFFFFFFFFFFL) | 0x010000000000L;
        this.clockSequence = random.nextInt(0x4000);
    }

    // ==========================================
    // 生成
    // ==========================================

    public UUID v4() {
        long msb = random.nextLong();
        long lsb = random.nextLong();
        msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x4000L;
        return new UUID(msb, withRfcVariant(lsb));
    }

    /**
     * v7：48 位 Unix 毫秒 + 版本 + 12 位 rand_a + 变体 + 62 位 rand_b。
     *
     * <p>采用 RFC 9562 §6.2 方法 1：rand_a 作为同一毫秒内的计数器，每毫秒首次以最高位为 0 的
     * 随机值起步，保证同毫秒内至少还能递增 2048 次；计数器用尽或时钟回拨时把时间戳前推 1ms，
     * 以此保持严格递增。</p>
     */
    public synchronized UUID v7() {
        long now = clockMillis.getAsLong();
        if (now > lastV7Millis) {
            lastV7Millis = now;
            v7Counter = random.nextInt(0x800);
        } else {
            v7Counter++;
            if (v7Counter > 0xFFF) {
                lastV7Millis++;
                v7Counter = random.nextInt(0x800);
            }
        }
        long msb = ((lastV7Millis & 0xFFFFFFFFFFFFL) << 16) | 0x7000L | v7Counter;
        return new UUID(msb, withRfcVariant(random.nextLong()));
    }

    public synchronized UUID v1() {
        long ts = nextGregorian();
        long msb = ((ts & 0xFFFFFFFFL) << 32)
                | (((ts >>> 32) & 0xFFFFL) << 16)
                | 0x1000L
                | ((ts >>> 48) & 0x0FFFL);
        return new UUID(msb, clockSequenceAndNode());
    }

    /** v6：与 v1 同样的时间戳，但按高位到低位重排，字典序即时间序 */
    public synchronized UUID v6() {
        long ts = nextGregorian();
        long msb = ((ts >>> 28) << 32)
                | (((ts >>> 12) & 0xFFFFL) << 16)
                | 0x6000L
                | (ts & 0x0FFFL);
        return new UUID(msb, clockSequenceAndNode());
    }

    /** 时钟只有毫秒精度，用 100ns 单位的余量做同毫秒递增，保证严格单调 */
    private long nextGregorian() {
        long ts = clockMillis.getAsLong() * 10_000L + GREGORIAN_OFFSET;
        if (ts <= lastGregorian) {
            ts = lastGregorian + 1;
        }
        lastGregorian = ts;
        return ts;
    }

    private long clockSequenceAndNode() {
        return ((0x8000L | clockSequence) << 48) | node;
    }

    public static UUID v3(UUID namespace, String name) {
        return nameBased(3, "MD5", namespace, name);
    }

    public static UUID v5(UUID namespace, String name) {
        return nameBased(5, "SHA-1", namespace, name);
    }

    private static UUID nameBased(int version, String algorithm, UUID namespace, String name) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
        ByteBuffer ns = ByteBuffer.allocate(16);
        ns.putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits());
        digest.update(ns.array());
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        ByteBuffer hash = ByteBuffer.wrap(digest.digest());
        long msb = hash.getLong();
        long lsb = hash.getLong();
        msb = (msb & 0xFFFFFFFFFFFF0FFFL) | ((long) version << 12);
        return new UUID(msb, withRfcVariant(lsb));
    }

    private static long withRfcVariant(long lsb) {
        return (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    }

    // ==========================================
    // 格式化 / 解析
    // ==========================================

    public static String format(UUID uuid, boolean upperCase, boolean hyphens) {
        String text = uuid.toString();
        if (!hyphens) {
            text = text.replace("-", "");
        }
        return upperCase ? text.toUpperCase(Locale.ROOT) : text;
    }

    /**
     * 解析各种常见写法：带或不带连字符、花括号、{@code urn:uuid:} 前缀、大小写均可。
     */
    public static UUID parse(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw new IdCodecException(IdCodecException.Code.EMPTY, "empty uuid");
        }
        if (value.regionMatches(true, 0, "urn:uuid:", 0, 9)) {
            value = value.substring(9);
        }
        if (value.length() >= 2 && value.charAt(0) == '{' && value.charAt(value.length() - 1) == '}') {
            value = value.substring(1, value.length() - 1);
        }
        String hex;
        if (value.length() == 36) {
            for (int index : new int[]{8, 13, 18, 23}) {
                if (value.charAt(index) != '-') {
                    throw new IdCodecException(IdCodecException.Code.INVALID_CHARACTER,
                            "hyphen expected", String.valueOf(value.charAt(index)), index);
                }
            }
            hex = value.replace("-", "");
            if (hex.length() != 32) {
                throw new IdCodecException(IdCodecException.Code.INVALID_CHARACTER, "misplaced hyphen", "-", -1);
            }
        } else if (value.length() == 32) {
            hex = value;
        } else {
            throw new IdCodecException(IdCodecException.Code.INVALID_LENGTH,
                    "uuid must have 32 hex digits", 32, value.replace("-", "").length());
        }
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                throw new IdCodecException(IdCodecException.Code.INVALID_CHARACTER,
                        "not a hex digit", String.valueOf(hex.charAt(i)), i);
            }
        }
        long msb = Long.parseUnsignedLong(hex.substring(0, 16), 16);
        long lsb = Long.parseUnsignedLong(hex.substring(16), 16);
        return new UUID(msb, lsb);
    }

    public static Info inspect(String text) {
        return inspect(parse(text));
    }

    public static Info inspect(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        int version = (int) ((msb >>> 12) & 0xF);
        Variant variant = variantOf(lsb);
        if (variant != Variant.RFC_9562 || NIL.equals(uuid) || MAX.equals(uuid)) {
            return new Info(uuid, version, variant, null, null, null, null, false, null);
        }
        switch (version) {
            case 1: {
                long ts = ((msb & 0x0FFFL) << 48)
                        | (((msb >>> 16) & 0xFFFFL) << 32)
                        | (msb >>> 32);
                return gregorianInfo(uuid, version, variant, ts, lsb);
            }
            case 6: {
                long ts = ((msb >>> 32) << 28)
                        | (((msb >>> 16) & 0xFFFFL) << 12)
                        | (msb & 0x0FFFL);
                return gregorianInfo(uuid, version, variant, ts, lsb);
            }
            case 7: {
                long millis = msb >>> 16;
                return new Info(uuid, version, variant, Instant.ofEpochMilli(millis), null, null, null,
                        false, (int) (msb & 0x0FFFL));
            }
            default:
                return new Info(uuid, version, variant, null, null, null, null, false, null);
        }
    }

    private static Info gregorianInfo(UUID uuid, int version, Variant variant, long ts, long lsb) {
        long sinceUnix = ts - GREGORIAN_OFFSET;
        Instant instant = Instant.ofEpochSecond(Math.floorDiv(sinceUnix, 10_000_000L),
                Math.floorMod(sinceUnix, 10_000_000L) * 100L);
        int clockSeq = (int) ((lsb >>> 48) & 0x3FFFL);
        long nodeValue = lsb & 0xFFFFFFFFFFFFL;
        boolean multicast = (nodeValue & 0x010000000000L) != 0;
        return new Info(uuid, version, variant, instant, ts, clockSeq, nodeValue, multicast, null);
    }

    static Variant variantOf(long lsb) {
        int top = (int) (lsb >>> 61);
        if ((top & 0b100) == 0) {
            return Variant.NCS;
        }
        if ((top & 0b010) == 0) {
            return Variant.RFC_9562;
        }
        if ((top & 0b001) == 0) {
            return Variant.MICROSOFT;
        }
        return Variant.FUTURE;
    }

    /** 48 位节点格式化为 {@code 9f:6b:de:ce:d8:46} */
    public static String formatNode(long node) {
        StringBuilder text = new StringBuilder(17);
        for (int shift = 40; shift >= 0; shift -= 8) {
            if (text.length() > 0) {
                text.append(':');
            }
            text.append(String.format(Locale.ROOT, "%02x", (node >>> shift) & 0xFF));
        }
        return text.toString();
    }
}
