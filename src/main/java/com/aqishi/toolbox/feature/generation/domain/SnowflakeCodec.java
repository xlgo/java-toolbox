package com.aqishi.toolbox.feature.generation.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 雪花类 ID 的解码、编码、预设猜测与线程安全生成器。
 */
public final class SnowflakeCodec {

    /** 超过当前时间多久才算「来自未来」：容忍各节点之间的少量时钟偏差 */
    public static final Duration FUTURE_TOLERANCE = Duration.ofDays(1);

    private static final double MILLIS_PER_YEAR = 365.2425 * 86_400_000d;

    private SnowflakeCodec() {
    }

    /** 解码时发现的可疑之处 */
    public enum Warning {
        /** 最高位为 1：在 long 中是负数，不是任何规范实现能生成的值 */
        SIGN_BIT_SET,
        /** 布局不足 63 位时，时间戳之上的空闲位不为 0 */
        UNUSED_BITS_SET,
        /** 解码出的时间晚于当前时间一天以上 */
        FUTURE_TIMESTAMP
    }

    /**
     * 解码结果。
     *
     * @param id              原始值
     * @param layout          使用的布局
     * @param ticks           时间戳字段的原始刻度数
     * @param timestampMillis 换算后的 Unix 毫秒
     * @param fields          各字段取值，按布局顺序
     * @param warnings        可疑之处
     */
    public record Decoded(long id, SnowflakeLayout layout, long ticks, long timestampMillis,
                          Map<String, Long> fields, Set<Warning> warnings) {
        public Decoded {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            warnings = warnings.isEmpty()
                    ? Collections.unmodifiableSet(EnumSet.noneOf(Warning.class))
                    : Collections.unmodifiableSet(EnumSet.copyOf(warnings));
        }

        public Instant timestamp() {
            return Instant.ofEpochMilli(timestampMillis);
        }
    }

    /**
     * 某个预设下的解码结果与可信度。
     *
     * @param preset  预设
     * @param decoded 按该预设解码的结果
     * @param score   0..1，越大越可能
     */
    public record PresetGuess(SnowflakeLayout.Preset preset, Decoded decoded, double score) {
    }

    // ==========================================
    // 解码 / 编码
    // ==========================================

    public static Decoded decode(long id, SnowflakeLayout layout) {
        return decode(id, layout, Instant.now());
    }

    public static Decoded decode(long id, SnowflakeLayout layout, Instant now) {
        Objects.requireNonNull(layout, "layout");
        Set<Warning> warnings = EnumSet.noneOf(Warning.class);
        if (id < 0) {
            warnings.add(Warning.SIGN_BIT_SET);
        }
        int total = layout.totalBits();
        long unused = total >= SnowflakeLayout.USABLE_BITS ? 0 : (id & Long.MAX_VALUE) >>> total;
        if (unused != 0) {
            warnings.add(Warning.UNUSED_BITS_SET);
        }

        Map<String, Long> values = new LinkedHashMap<>();
        List<SnowflakeLayout.Field> fields = layout.fields();
        int shift = 0;
        long[] extracted = new long[fields.size()];
        for (int i = fields.size() - 1; i >= 0; i--) {
            SnowflakeLayout.Field field = fields.get(i);
            extracted[i] = (id >>> shift) & field.maxValue();
            shift += field.bits();
        }
        for (int i = 0; i < fields.size(); i++) {
            values.put(fields.get(i).name(), extracted[i]);
        }
        long ticks = (id >>> shift) & layout.maxTicks();
        long millis = layout.ticksToMillis(ticks);
        if (now != null && millis > now.plus(FUTURE_TOLERANCE).toEpochMilli()) {
            warnings.add(Warning.FUTURE_TIMESTAMP);
        }
        return new Decoded(id, layout, ticks, millis, values, warnings);
    }

    /**
     * 按布局拼出 ID。
     *
     * @param fieldValues 各字段取值，缺省的字段按 0 处理（含序列号）
     * @throws IdCodecException 时间早于纪元、超出时间戳位宽、字段越界或字段名未知
     */
    public static long encode(SnowflakeLayout layout, Instant instant, Map<String, Long> fieldValues) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(instant, "instant");
        long ticks = ticksOf(layout, instant.toEpochMilli());
        Map<String, Long> values = fieldValues == null ? Map.of() : fieldValues;
        for (String name : values.keySet()) {
            if (layout.field(name) == null) {
                throw new IdCodecException(IdCodecException.Code.UNKNOWN_FIELD, "unknown field " + name, name);
            }
        }
        long id = ticks;
        for (SnowflakeLayout.Field field : layout.fields()) {
            Long value = values.get(field.name());
            long v = value == null ? 0L : value;
            checkField(field, v);
            id = (id << field.bits()) | v;
        }
        return id;
    }

    /** 把 Unix 毫秒换算成布局刻度，并校验范围 */
    public static long ticksOf(SnowflakeLayout layout, long unixMillis) {
        if (unixMillis < layout.epochMillis()) {
            throw new IdCodecException(IdCodecException.Code.BEFORE_EPOCH,
                    "instant is before layout epoch", layout.epoch().toString());
        }
        long ticks = Math.floorDiv(unixMillis - layout.epochMillis(), layout.unit().millis());
        if (ticks > layout.maxTicks()) {
            throw new IdCodecException(IdCodecException.Code.TIMESTAMP_OVERFLOW,
                    "timestamp exceeds layout capacity", layout.exhaustedAt().toString());
        }
        return ticks;
    }

    private static void checkField(SnowflakeLayout.Field field, long value) {
        if (value < 0 || value > field.maxValue()) {
            throw new IdCodecException(IdCodecException.Code.OUT_OF_RANGE,
                    "field out of range: " + field.name(), field.name(), field.maxValue());
        }
    }

    // ==========================================
    // 预设猜测
    // ==========================================

    /**
     * 按「解码出的时间是否可信」给所有已知预设排序。
     *
     * <p>同一个数值在不同纪元、不同时间位宽下会解出完全不同的时间：Twitter 纪元的 ID
     * 当作 Discord 解码会落到几年后的未来，当作 Sonyflake 则远在几十年后。
     * 所以排除「未来」「早于纪元」「符号位」的结果后，越接近当下越可能是真实来源。</p>
     */
    public static List<PresetGuess> guessPreset(long id, Instant now) {
        Objects.requireNonNull(now, "now");
        List<PresetGuess> guesses = new ArrayList<>();
        for (SnowflakeLayout.Preset preset : SnowflakeLayout.Preset.known()) {
            SnowflakeLayout layout = preset.layout();
            Decoded decoded = decode(id, layout, now);
            double score = timePlausibility(decoded.timestamp(), layout.epoch(), now);
            if (decoded.warnings().contains(Warning.SIGN_BIT_SET)
                    || decoded.warnings().contains(Warning.UNUSED_BITS_SET)) {
                score *= 0.1;
            }
            // 刚好落在纪元附近（一天内）的值多半只是个小整数，而不是真实 ID
            if (decoded.timestampMillis() - layout.epochMillis() < 86_400_000L) {
                score *= 0.3;
            }
            guesses.add(new PresetGuess(preset, decoded, score));
        }
        // 稳定排序：分数相同（如 Twitter / MyBatis-Plus / Hutool）时保留预设声明顺序
        guesses.sort(Comparator.comparingDouble(PresetGuess::score).reversed());
        return guesses;
    }

    /**
     * 时间可信度：0..1。
     *
     * <p>晚于 {@code now} 一天以上几乎不可能；早于 {@code earliest} 也不可能；
     * 其余按距今年数线性衰减，25 年前降到下限 0.1——近几年生成的 ID 最常见。</p>
     */
    public static double timePlausibility(Instant time, Instant earliest, Instant now) {
        if (time == null) {
            return 0;
        }
        if (time.isAfter(now.plus(FUTURE_TOLERANCE))) {
            return 0.02;
        }
        if (earliest != null && time.isBefore(earliest)) {
            return 0.05;
        }
        double years = Math.max(0, (now.toEpochMilli() - time.toEpochMilli()) / MILLIS_PER_YEAR);
        return Math.max(0.1, 1.0 - years / 25.0);
    }

    // ==========================================
    // 时间文本解析
    // ==========================================

    private static final DateTimeFormatter SPACE_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    /**
     * 解析纪元 / 时刻输入：纯数字按 Unix 毫秒；带时区或 {@code Z} 的 ISO 时间按其自带偏移；
     * 不带时区的日期时间（{@code 2016-05-20T00:00:00}、{@code 2016-05-20 00:00:00}）
     * 与纯日期按 {@code zone} 解释。
     */
    public static Instant parseInstant(String text, ZoneId zone) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw new IdCodecException(IdCodecException.Code.EMPTY, "empty time");
        }
        ZoneId effective = zone == null ? ZoneId.of("UTC") : zone;
        if (value.matches("-?\\d{1,19}")) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(value));
            } catch (NumberFormatException e) {
                throw new IdCodecException(IdCodecException.Code.INVALID_TIME, "bad epoch millis", value);
            }
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他格式
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他格式
        }
        try {
            return ZonedDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他格式
        }
        try {
            return LocalDateTime.parse(value).atZone(effective).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他格式
        }
        try {
            return LocalDateTime.parse(value, SPACE_DATE_TIME).atZone(effective).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他格式
        }
        try {
            return LocalDate.parse(value).atStartOfDay(effective).toInstant();
        } catch (DateTimeParseException e) {
            throw new IdCodecException(IdCodecException.Code.INVALID_TIME, "unparseable time", value);
        }
    }

    // ==========================================
    // 生成器
    // ==========================================

    /** 生成器使用的时钟与等待策略，测试中可替换成可控时钟 */
    public interface TimeSource {
        long currentMillis();

        /**
         * 等待一段时间。{@code hintMillis} 为预计还需等待的毫秒数，可能为 0；
         * 实现必须保证调用后时间有机会前进，否则生成器会一直等待。
         */
        void waitMillis(long hintMillis) throws InterruptedException;

        static TimeSource system() {
            return SystemTimeSource.INSTANCE;
        }
    }

    private enum SystemTimeSource implements TimeSource {
        INSTANCE;

        @Override
        public long currentMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void waitMillis(long hintMillis) throws InterruptedException {
            // 毫秒级刻度下剩余时间通常不足 1ms，sleep 在 Windows 上最小粒度可达 15ms，自旋更合适
            if (hintMillis <= 1) {
                Thread.onSpinWait();
            } else {
                Thread.sleep(hintMillis - 1);
            }
        }
    }

    /**
     * 线程安全的雪花 ID 生成器。
     *
     * <ul>
     *   <li>同一刻度内序列号自增，用满后等到下一刻度；</li>
     *   <li>时钟回拨不超过 {@code maxBackwardsMillis} 时等待时钟追上，超过则抛出
     *       {@link IdCodecException.Code#CLOCK_BACKWARDS}，绝不生成重复或倒序的 ID；</li>
     *   <li>布局没有序列字段时，每个刻度只出一个 ID。</li>
     * </ul>
     */
    public static final class Generator {

        /** 默认容忍的时钟回拨：NTP 微调通常在几毫秒内 */
        public static final long DEFAULT_MAX_BACKWARDS_MILLIS = 10L;

        private final SnowflakeLayout layout;
        private final TimeSource timeSource;
        private final long maxBackwardsMillis;
        private final long nodeBitsValue;
        private final long sequenceMask;
        private final int sequenceShift;

        private long lastTicks = -1L;
        private long sequence;

        public Generator(SnowflakeLayout layout, Map<String, Long> nodeFields) {
            this(layout, nodeFields, TimeSource.system(), DEFAULT_MAX_BACKWARDS_MILLIS);
        }

        /**
         * @param nodeFields 除序列号外的字段取值（数据中心、机器号等），缺省按 0
         */
        public Generator(SnowflakeLayout layout, Map<String, Long> nodeFields, TimeSource timeSource,
                         long maxBackwardsMillis) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
            this.maxBackwardsMillis = Math.max(0, maxBackwardsMillis);
            Map<String, Long> values = nodeFields == null ? Map.of() : nodeFields;
            SnowflakeLayout.Field sequenceField = layout.sequenceField();
            for (String name : values.keySet()) {
                SnowflakeLayout.Field field = layout.field(name);
                if (field == null) {
                    throw new IdCodecException(IdCodecException.Code.UNKNOWN_FIELD, "unknown field " + name, name);
                }
            }
            long bits = 0;
            for (SnowflakeLayout.Field field : layout.fields()) {
                if (field.sequence()) {
                    continue;
                }
                Long value = values.get(field.name());
                long v = value == null ? 0L : value;
                checkField(field, v);
                bits |= v << layout.shiftOf(field.name());
            }
            this.nodeBitsValue = bits;
            this.sequenceMask = sequenceField == null ? 0L : sequenceField.maxValue();
            this.sequenceShift = sequenceField == null ? 0 : layout.shiftOf(sequenceField.name());
        }

        public SnowflakeLayout layout() {
            return layout;
        }

        public synchronized long next() {
            long ticks = currentTicks();
            if (ticks < lastTicks) {
                ticks = awaitClockCatchUp(ticks);
            }
            if (ticks == lastTicks) {
                sequence = (sequence + 1) & sequenceMask;
                if (sequence == 0) {
                    ticks = awaitNextTick();
                }
            } else {
                sequence = 0;
            }
            lastTicks = ticks;
            return (ticks << layout.timestampShift()) | nodeBitsValue | (sequence << sequenceShift);
        }

        private long currentTicks() {
            return ticksOf(layout, timeSource.currentMillis());
        }

        private long awaitClockCatchUp(long ticks) {
            long unitMillis = layout.unit().millis();
            long backMillis = (lastTicks - ticks) * unitMillis;
            if (backMillis > maxBackwardsMillis) {
                throw new IdCodecException(IdCodecException.Code.CLOCK_BACKWARDS,
                        "clock moved backwards by " + backMillis + " ms", backMillis);
            }
            // 等待总量设上限：外部时钟继续回拨时不会无限等下去
            long budget = maxBackwardsMillis + unitMillis * 2 + 1;
            long waited = 0;
            long current = ticks;
            while (current < lastTicks) {
                long hint = Math.max(0, layout.ticksToMillis(lastTicks) - timeSource.currentMillis());
                pause(hint);
                waited += Math.max(1, hint);
                current = currentTicks();
                if (current < lastTicks && waited > budget) {
                    throw new IdCodecException(IdCodecException.Code.CLOCK_BACKWARDS,
                            "clock did not catch up", (lastTicks - current) * unitMillis);
                }
            }
            return current;
        }

        private long awaitNextTick() {
            long current = currentTicks();
            while (current <= lastTicks) {
                long hint = Math.max(0, layout.ticksToMillis(lastTicks + 1) - timeSource.currentMillis());
                pause(hint);
                current = currentTicks();
                if (current < lastTicks) {
                    current = awaitClockCatchUp(current);
                }
            }
            return current;
        }

        private void pause(long hint) {
            try {
                timeSource.waitMillis(hint);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the clock", e);
            }
        }
    }
}
