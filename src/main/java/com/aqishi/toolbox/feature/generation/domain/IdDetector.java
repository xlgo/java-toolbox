package com.aqishi.toolbox.feature.generation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 猜测一段粘贴文本是哪种 ID，并给出所有说得通的解读及可信度。
 *
 * <p>同一串文本常有多种合法解读：19 位数字在每种雪花预设下都能解码，13 位数字既可能是毫秒时间戳
 * 也可能是很早期的雪花 ID。这里不做单选，而是全部列出并按可信度排序，主要依据是解出的时间
 * 是否落在「该格式出现之后、当前时间之前」且越近越可信。</p>
 *
 * <p>细节项的键是固定英文标识（{@link #D_VERSION} 等，雪花字段直接用字段名），
 * 值是与语言无关的数字 / 十六进制 / 布尔值，由界面负责本地化。</p>
 */
public final class IdDetector {

    /** 解读类型 */
    public enum Kind {
        UUID, ULID, OBJECT_ID, KSUID, SNOWFLAKE, UNIX_SECONDS, UNIX_MILLIS
    }

    public static final String D_CANONICAL = "canonical";
    public static final String D_VERSION = "version";
    public static final String D_VARIANT = "variant";
    public static final String D_GREGORIAN = "gregorian100ns";
    public static final String D_CLOCK_SEQUENCE = "clockSequence";
    public static final String D_NODE = "node";
    public static final String D_NODE_RANDOM = "nodeRandom";
    public static final String D_RAND_A = "randA";
    public static final String D_ULID = "ulid";
    public static final String D_UUID = "uuid";
    public static final String D_RANDOMNESS = "randomness";
    public static final String D_SECONDS = "seconds";
    public static final String D_MILLIS = "millis";
    public static final String D_RANDOM = "random";
    public static final String D_COUNTER = "counter";
    public static final String D_KSUID_SECONDS = "ksuidSeconds";
    public static final String D_PAYLOAD = "payload";
    public static final String D_TICKS = "ticks";
    public static final String D_HEX = "hex";
    public static final String D_EPOCH = "epoch";

    public static final String W_SIGN_BIT = "signBit";
    public static final String W_UNUSED_BITS = "unusedBits";
    public static final String W_FUTURE = "futureTimestamp";
    public static final String W_NON_RFC_VARIANT = "nonRfcVariant";
    public static final String W_UNKNOWN_VERSION = "unknownVersion";
    public static final String W_LENIENT_CHARS = "lenientChars";
    public static final String W_IMPLAUSIBLE_TIME = "implausibleTime";

    private static final Instant EARLIEST_UNIX_GUESS = Instant.parse("1995-01-01T00:00:00Z");
    private static final Instant EARLIEST_UUID_V1 = Instant.parse("1990-01-01T00:00:00Z");
    private static final Instant EARLIEST_UUID_V7 = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant EARLIEST_OBJECT_ID = Instant.parse("2009-01-01T00:00:00Z");
    private static final Instant EARLIEST_ULID = Instant.parse("2016-01-01T00:00:00Z");
    private static final Instant EARLIEST_KSUID = Instant.ofEpochSecond(KsuidCodec.EPOCH_SECONDS);

    /**
     * 一个细节项。
     *
     * @param key   固定英文标识
     * @param value 数字、字符串或布尔值
     */
    public record Detail(String key, Object value) {
    }

    /**
     * 一种解读。
     *
     * @param kind      类型
     * @param subtype   UUID 为 {@code v7} / {@code nil} / {@code max}；雪花为逗号分隔的预设 ID；其余为空串
     * @param timestamp 内嵌时间，没有时为 null
     * @param details   细节项
     * @param warnings  警告标识
     * @param score     0..1 可信度
     */
    public record Interpretation(Kind kind, String subtype, Instant timestamp, List<Detail> details,
                                 List<String> warnings, double score) {
        public Interpretation {
            details = List.copyOf(details);
            warnings = List.copyOf(warnings);
        }
    }

    private IdDetector() {
    }

    public static List<Interpretation> detect(String input) {
        return detect(input, Instant.now());
    }

    public static List<Interpretation> detect(String input, Instant now) {
        Objects.requireNonNull(now, "now");
        String value = normalize(input);
        List<Interpretation> result = new ArrayList<>();
        if (value.isEmpty()) {
            return result;
        }
        tryUuid(value, now, result);
        tryUlid(value, now, result);
        tryObjectId(value, now, result);
        tryKsuid(value, now, result);
        tryDecimal(value, now, result);
        tryHexNumber(value, now, result);
        result.sort(Comparator.comparingDouble(Interpretation::score).reversed());
        return result;
    }

    /** 去掉首尾空白、引号、行尾逗号，以及 {@code ObjectId("...")} 这种包装 */
    static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String value = input.trim();
        while (value.endsWith(",") || value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.startsWith("ObjectId(") && value.endsWith(")")) {
            value = value.substring("ObjectId(".length(), value.length() - 1).trim();
        }
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'' || first == '`') && last == first) {
                value = value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    // ==========================================
    // 各格式
    // ==========================================

    private static void tryUuid(String value, Instant now, List<Interpretation> out) {
        java.util.UUID uuid;
        try {
            uuid = UuidCodec.parse(value);
        } catch (IdCodecException e) {
            return;
        }
        UuidCodec.Info info = UuidCodec.inspect(uuid);
        List<Detail> details = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        details.add(new Detail(D_CANONICAL, uuid.toString()));
        details.add(new Detail(D_VERSION, info.version()));
        details.add(new Detail(D_VARIANT, info.variant().name()));
        boolean compact = value.replace("-", "").length() == value.length();
        double score = compact ? 0.75 : 1.0;
        String subtype;
        if (info.isNil()) {
            subtype = "nil";
            score *= 0.9;
        } else if (info.isMax()) {
            subtype = "max";
            score *= 0.9;
        } else {
            subtype = "v" + info.version();
            if (info.variant() != UuidCodec.Variant.RFC_9562) {
                warnings.add(W_NON_RFC_VARIANT);
                score *= 0.5;
            } else if (info.version() < 1 || info.version() > 8) {
                warnings.add(W_UNKNOWN_VERSION);
                score *= 0.5;
            }
        }
        if (info.gregorian100ns() != null) {
            details.add(new Detail(D_GREGORIAN, info.gregorian100ns()));
            details.add(new Detail(D_CLOCK_SEQUENCE, info.clockSequence()));
            details.add(new Detail(D_NODE, UuidCodec.formatNode(info.node())));
            details.add(new Detail(D_NODE_RANDOM, info.nodeMulticast()));
        }
        if (info.randA() != null) {
            details.add(new Detail(D_RAND_A, info.randA()));
            details.add(new Detail(D_ULID, UlidCodec.fromUuid(uuid)));
        }
        if (info.timestamp() != null) {
            Instant earliest = info.version() == 7 ? EARLIEST_UUID_V7 : EARLIEST_UUID_V1;
            double plausibility = SnowflakeCodec.timePlausibility(info.timestamp(), earliest, now);
            if (plausibility < 0.1) {
                warnings.add(timeWarning(info.timestamp(), now));
            }
            score *= 0.6 + 0.4 * plausibility;
        }
        out.add(new Interpretation(Kind.UUID, subtype, info.timestamp(), details, warnings, score));
    }

    private static void tryUlid(String value, Instant now, List<Interpretation> out) {
        if (value.length() != UlidCodec.LENGTH) {
            return;
        }
        UlidCodec.Decoded decoded;
        try {
            decoded = UlidCodec.decode(value);
        } catch (IdCodecException e) {
            return;
        }
        List<Detail> details = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        details.add(new Detail(D_CANONICAL, decoded.canonical()));
        details.add(new Detail(D_MILLIS, decoded.timestampMillis()));
        details.add(new Detail(D_RANDOMNESS, decoded.randomnessHex()));
        details.add(new Detail(D_UUID, decoded.uuid().toString()));
        double plausibility = SnowflakeCodec.timePlausibility(decoded.timestamp(), EARLIEST_ULID, now);
        double score = 0.5 + 0.45 * plausibility;
        if (decoded.lenient()) {
            warnings.add(W_LENIENT_CHARS);
            score *= 0.8;
        }
        if (plausibility < 0.1) {
            warnings.add(timeWarning(decoded.timestamp(), now));
        }
        out.add(new Interpretation(Kind.ULID, "", decoded.timestamp(), details, warnings, score));
    }

    private static void tryObjectId(String value, Instant now, List<Interpretation> out) {
        if (value.length() != ObjectIdCodec.HEX_LENGTH) {
            return;
        }
        ObjectIdCodec.Decoded decoded;
        try {
            decoded = ObjectIdCodec.decode(value);
        } catch (IdCodecException e) {
            return;
        }
        List<Detail> details = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        details.add(new Detail(D_CANONICAL, decoded.hex()));
        details.add(new Detail(D_SECONDS, decoded.seconds()));
        details.add(new Detail(D_RANDOM, decoded.randomHex()));
        details.add(new Detail(D_COUNTER, decoded.counter()));
        double plausibility = SnowflakeCodec.timePlausibility(decoded.timestamp(), EARLIEST_OBJECT_ID, now);
        if (plausibility < 0.1) {
            warnings.add(timeWarning(decoded.timestamp(), now));
        }
        out.add(new Interpretation(Kind.OBJECT_ID, "", decoded.timestamp(), details, warnings,
                0.5 + 0.45 * plausibility));
    }

    private static void tryKsuid(String value, Instant now, List<Interpretation> out) {
        if (value.length() != KsuidCodec.LENGTH) {
            return;
        }
        KsuidCodec.Decoded decoded;
        try {
            decoded = KsuidCodec.decode(value);
        } catch (IdCodecException e) {
            return;
        }
        List<Detail> details = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        details.add(new Detail(D_KSUID_SECONDS, decoded.rawSeconds()));
        details.add(new Detail(D_PAYLOAD, decoded.payloadHex()));
        double plausibility = SnowflakeCodec.timePlausibility(decoded.timestamp(), EARLIEST_KSUID, now);
        if (plausibility < 0.1) {
            warnings.add(timeWarning(decoded.timestamp(), now));
        }
        out.add(new Interpretation(Kind.KSUID, "", decoded.timestamp(), details, warnings,
                0.5 + 0.45 * plausibility));
    }

    private static void tryDecimal(String value, Instant now, List<Interpretation> out) {
        if (!value.matches("\\d{1,19}")) {
            return;
        }
        long number;
        try {
            number = Long.parseLong(value);
        } catch (NumberFormatException e) {
            return;
        }
        String digits = Long.toString(number);
        int length = digits.length();
        // 真实雪花 ID 近年都在 17～19 位；位数很少的多半是普通整数或时间戳
        double digitFactor = length < 15 ? 0.3 : length < 17 ? 0.6 : 1.0;
        addSnowflakeGuesses(number, now, digitFactor * 0.95, out);

        if (length <= 11) {
            addUnixTime(Kind.UNIX_SECONDS, number, number * 1000L, now, out);
        }
        if (length >= 11 && length <= 14) {
            addUnixTime(Kind.UNIX_MILLIS, number, number, now, out);
        }
    }

    private static void tryHexNumber(String value, Instant now, List<Interpretation> out) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.matches("0x[0-9a-f]{1,16}")) {
            return;
        }
        long number = Long.parseUnsignedLong(lower.substring(2), 16);
        addSnowflakeGuesses(number, now, 0.8, out);
    }

    private static void addSnowflakeGuesses(long number, Instant now, double factor, List<Interpretation> out) {
        List<SnowflakeCodec.PresetGuess> guesses = SnowflakeCodec.guessPreset(number, now);
        // 结构完全相同的预设（Twitter / MyBatis-Plus / Hutool）解码结果一样，合并成一条
        List<List<SnowflakeCodec.PresetGuess>> groups = new ArrayList<>();
        for (SnowflakeCodec.PresetGuess guess : guesses) {
            List<SnowflakeCodec.PresetGuess> target = null;
            for (List<SnowflakeCodec.PresetGuess> group : groups) {
                if (group.get(0).decoded().layout().sameStructure(guess.decoded().layout())) {
                    target = group;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                groups.add(target);
            }
            target.add(guess);
        }
        for (List<SnowflakeCodec.PresetGuess> group : groups) {
            SnowflakeCodec.PresetGuess first = group.get(0);
            SnowflakeCodec.Decoded decoded = first.decoded();
            StringBuilder subtype = new StringBuilder();
            for (SnowflakeCodec.PresetGuess guess : group) {
                if (subtype.length() > 0) {
                    subtype.append(',');
                }
                subtype.append(guess.preset().id());
            }
            List<Detail> details = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            details.add(new Detail(D_TICKS, decoded.ticks()));
            decoded.fields().forEach((name, fieldValue) -> details.add(new Detail(name, fieldValue)));
            details.add(new Detail(D_HEX, "0x" + Long.toHexString(number)));
            details.add(new Detail(D_EPOCH, decoded.layout().epoch().toString()));
            for (SnowflakeCodec.Warning warning : decoded.warnings()) {
                switch (warning) {
                    case SIGN_BIT_SET:
                        warnings.add(W_SIGN_BIT);
                        break;
                    case UNUSED_BITS_SET:
                        warnings.add(W_UNUSED_BITS);
                        break;
                    default:
                        warnings.add(W_FUTURE);
                        break;
                }
            }
            out.add(new Interpretation(Kind.SNOWFLAKE, subtype.toString(), decoded.timestamp(), details,
                    warnings, first.score() * factor));
        }
    }

    private static void addUnixTime(Kind kind, long raw, long millis, Instant now, List<Interpretation> out) {
        Instant time = Instant.ofEpochMilli(millis);
        double plausibility = SnowflakeCodec.timePlausibility(time, EARLIEST_UNIX_GUESS, now);
        List<String> warnings = new ArrayList<>();
        if (plausibility < 0.1) {
            warnings.add(timeWarning(time, now));
        }
        List<Detail> details = List.of(new Detail(kind == Kind.UNIX_SECONDS ? D_SECONDS : D_MILLIS, raw));
        out.add(new Interpretation(kind, "", time, details, warnings, 0.92 * plausibility));
    }

    private static String timeWarning(Instant time, Instant now) {
        return time.isAfter(now.plus(SnowflakeCodec.FUTURE_TOLERANCE)) ? W_FUTURE : W_IMPLAUSIBLE_TIME;
    }
}
