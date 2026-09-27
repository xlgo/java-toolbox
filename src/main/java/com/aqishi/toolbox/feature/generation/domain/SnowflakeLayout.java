package com.aqishi.toolbox.feature.generation.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 雪花类 ID 的位布局：纪元、时间单位、时间戳位宽，以及时间戳之后按高位到低位排列的字段。
 *
 * <p>最高位（第 63 位）固定留作符号位，保证 ID 在 Java {@code long} / 数据库 BIGINT 中为正数，
 * 所以时间戳与各字段合计不得超过 63 位；不足 63 位时，时间戳之上的空闲位应当为 0。</p>
 *
 * <p>字段名使用固定的英文标识（{@link #DATACENTER} 等），界面按名称映射到国际化文案。</p>
 *
 * @param id            布局标识：预设 ID 或 {@code custom}
 * @param epochMillis   纪元（Unix 毫秒）
 * @param unit          时间戳计数单位
 * @param timestampBits 时间戳位宽
 * @param fields        时间戳之后的字段，按高位到低位排列
 */
public record SnowflakeLayout(String id, long epochMillis, TickUnit unit, int timestampBits,
                              List<Field> fields) {

    public static final String DATACENTER = "datacenter";
    public static final String WORKER = "worker";
    public static final String SEQUENCE = "sequence";
    public static final String MACHINE = "machine";
    public static final String PROCESS = "process";
    public static final String INCREMENT = "increment";

    /** 符号位之外可用的位数 */
    public static final int USABLE_BITS = 63;

    /** 纪元允许的上限：9999-12-31T23:59:59.999Z，防止后续换算溢出 */
    private static final long MAX_EPOCH_MILLIS = 253402300799999L;

    /** 时间戳计数单位 */
    public enum TickUnit {
        MILLIS(1L), TEN_MILLIS(10L), SECONDS(1000L);

        private final long millis;

        TickUnit(long millis) {
            this.millis = millis;
        }

        public long millis() {
            return millis;
        }
    }

    /**
     * 时间戳之后的一个字段。
     *
     * @param name     英文标识
     * @param bits     位宽
     * @param sequence 是否为同一时间单位内自增的序列号字段（生成器据此自增）
     */
    public record Field(String name, int bits, boolean sequence) {
        public Field {
            if (name == null || name.isBlank()) {
                throw invalid("field name must not be blank");
            }
            if (bits < 1 || bits > 62) {
                throw invalid("field bits must be within 1..62: " + name);
            }
        }

        public long maxValue() {
            return (1L << bits) - 1;
        }
    }

    public SnowflakeLayout {
        if (id == null || id.isBlank()) {
            throw invalid("layout id must not be blank");
        }
        Objects.requireNonNull(unit, "unit");
        if (epochMillis < 0 || epochMillis > MAX_EPOCH_MILLIS) {
            throw invalid("epoch must be within 1970..9999");
        }
        if (timestampBits < 1 || timestampBits > USABLE_BITS) {
            throw invalid("timestamp bits must be within 1..63");
        }
        Objects.requireNonNull(fields, "fields");
        fields = List.copyOf(fields);
        int total = timestampBits;
        int sequenceCount = 0;
        Set<String> names = new HashSet<>();
        for (Field field : fields) {
            Objects.requireNonNull(field, "field");
            if (!names.add(field.name())) {
                throw invalid("duplicate field name: " + field.name());
            }
            if (field.sequence()) {
                sequenceCount++;
            }
            total += field.bits();
        }
        if (sequenceCount > 1) {
            throw invalid("at most one sequence field is allowed");
        }
        if (total > USABLE_BITS) {
            throw invalid("total bits " + total + " exceed 63");
        }
    }

    // ==========================================
    // 预设
    // ==========================================

    /**
     * 常见实现的默认布局。
     *
     * <p>各预设的参数来源（按各项目公开源码的默认值整理）：</p>
     * <ul>
     *   <li>Twitter：snowflake 原始实现，纪元 1288834974657（2010-11-04T01:42:54.657Z），
     *       41 位毫秒 + 5 位数据中心 + 5 位机器 + 12 位序列。</li>
     *   <li>MyBatis-Plus：{@code com.baomidou.mybatisplus.core.toolkit.Sequence}（{@code IdWorker} 的底层），
     *       {@code twepoch = 1288834974657L}，datacenterIdBits = workerIdBits = 5、sequenceBits = 12，
     *       移位顺序与 Twitter 相同（数据中心在高位）。</li>
     *   <li>Hutool：{@code cn.hutool.core.lang.Snowflake}，{@code DEFAULT_TWEPOCH = 1288834974657L}，
     *       同样 5 + 5 + 12，数据中心在高位。三者位布局完全一致，解码结果无法区分。</li>
     *   <li>百度 UidGenerator：{@code DefaultUidGenerator} 默认 timeBits = 28（秒级增量）、
     *       workerBits = 22、seqBits = 13，epochStr = "2016-05-20"。该字符串按 JVM 默认时区解析，
     *       这里假定部署在 Asia/Shanghai，即 2016-05-19T16:00:00Z；28 位秒约 8.5 年，已于 2024 年底用尽，
     *       实际项目通常会改大 timeBits 或纪元。</li>
     *   <li>Sonyflake：39 位时间（10 毫秒为单位）+ 8 位序列 + 16 位机器号，
     *       纪元 2014-09-01T00:00:00Z；注意序列在机器号之上。</li>
     *   <li>Discord：纪元 1420070400000（2015-01-01T00:00:00Z），文档写作 42 位时间戳，
     *       但最高位在 2084 年前恒为 0，这里按 41 位处理以保留符号位；其后 5 位 worker、5 位 process、12 位 increment。</li>
     * </ul>
     */
    public enum Preset {
        TWITTER("twitter"),
        MYBATIS_PLUS("mybatisPlus"),
        HUTOOL("hutool"),
        BAIDU_UID("baiduUid"),
        SONYFLAKE("sonyflake"),
        DISCORD("discord"),
        CUSTOM("custom");

        private final String id;

        Preset(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public SnowflakeLayout layout() {
            switch (this) {
                case TWITTER:
                    return twitterLike(id, TWITTER_EPOCH);
                case MYBATIS_PLUS:
                    return twitterLike(id, TWITTER_EPOCH);
                case HUTOOL:
                    return twitterLike(id, TWITTER_EPOCH);
                case BAIDU_UID:
                    return new SnowflakeLayout(id, BAIDU_EPOCH, TickUnit.SECONDS, 28, List.of(
                            new Field(WORKER, 22, false),
                            new Field(SEQUENCE, 13, true)));
                case SONYFLAKE:
                    return new SnowflakeLayout(id, SONYFLAKE_EPOCH, TickUnit.TEN_MILLIS, 39, List.of(
                            new Field(SEQUENCE, 8, true),
                            new Field(MACHINE, 16, false)));
                case DISCORD:
                    return new SnowflakeLayout(id, DISCORD_EPOCH, TickUnit.MILLIS, 41, List.of(
                            new Field(WORKER, 5, false),
                            new Field(PROCESS, 5, false),
                            new Field(INCREMENT, 12, true)));
                case CUSTOM:
                default:
                    return twitterLike(id, CUSTOM_DEFAULT_EPOCH);
            }
        }

        /** 除 CUSTOM 之外的全部预设，用于解码时逐一尝试 */
        public static List<Preset> known() {
            List<Preset> result = new ArrayList<>();
            for (Preset preset : values()) {
                if (preset != CUSTOM) {
                    result.add(preset);
                }
            }
            return result;
        }

        public static Preset byId(String id) {
            for (Preset preset : values()) {
                if (preset.id.equals(id)) {
                    return preset;
                }
            }
            return CUSTOM;
        }
    }

    public static final long TWITTER_EPOCH = 1288834974657L;
    public static final long DISCORD_EPOCH = 1420070400000L;
    public static final long SONYFLAKE_EPOCH = 1409529600000L;
    /** 2016-05-20T00:00:00 Asia/Shanghai，见 {@link Preset} 的说明 */
    public static final long BAIDU_EPOCH = LocalDate.of(2016, 5, 20)
            .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    /** 自定义布局的初始纪元：2020-01-01T00:00:00Z */
    public static final long CUSTOM_DEFAULT_EPOCH = 1577836800000L;

    private static SnowflakeLayout twitterLike(String id, long epoch) {
        return new SnowflakeLayout(id, epoch, TickUnit.MILLIS, 41, List.of(
                new Field(DATACENTER, 5, false),
                new Field(WORKER, 5, false),
                new Field(SEQUENCE, 12, true)));
    }

    // ==========================================
    // 派生量
    // ==========================================

    /** 时间戳与字段合计位数 */
    public int totalBits() {
        int total = timestampBits;
        for (Field field : fields) {
            total += field.bits();
        }
        return total;
    }

    /** 时间戳左移的位数，即全部字段位宽之和 */
    public int timestampShift() {
        return totalBits() - timestampBits;
    }

    public long maxTicks() {
        return timestampBits >= 63 ? Long.MAX_VALUE : (1L << timestampBits) - 1;
    }

    /** 字段相对最低位的偏移 */
    public int shiftOf(String name) {
        int shift = 0;
        for (int i = fields.size() - 1; i >= 0; i--) {
            Field field = fields.get(i);
            if (field.name().equals(name)) {
                return shift;
            }
            shift += field.bits();
        }
        throw new IdCodecException(IdCodecException.Code.UNKNOWN_FIELD, "unknown field " + name, name);
    }

    public Field field(String name) {
        for (Field field : fields) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    public Field sequenceField() {
        for (Field field : fields) {
            if (field.sequence()) {
                return field;
            }
        }
        return null;
    }

    /** 序列号位宽，没有序列字段时为 0（每个时间单位只能出一个 ID） */
    public int sequenceBits() {
        Field field = sequenceField();
        return field == null ? 0 : field.bits();
    }

    /** 非序列字段合计位宽，决定可区分的节点数 */
    public int nodeBits() {
        return timestampShift() - sequenceBits();
    }

    /** 单节点每秒最多可生成的 ID 数 */
    public double idsPerSecondPerNode() {
        return Math.pow(2, sequenceBits()) * (1000.0 / unit.millis());
    }

    /** 可同时工作的节点数（非序列字段所有组合） */
    public double nodeCount() {
        return Math.pow(2, nodeBits());
    }

    /** 刻度数换算成 Unix 毫秒；超出 long 范围时饱和到 {@link Long#MAX_VALUE} */
    public long ticksToMillis(long ticks) {
        long unitMillis = unit.millis();
        if (ticks > (Long.MAX_VALUE - epochMillis) / unitMillis) {
            return Long.MAX_VALUE;
        }
        return epochMillis + ticks * unitMillis;
    }

    /** 时间戳位用尽后的第一个时刻（即最大可表示时间 + 1 个单位） */
    public Instant exhaustedAt() {
        long maxTicks = maxTicks();
        long last = ticksToMillis(maxTicks);
        if (last == Long.MAX_VALUE) {
            return Instant.ofEpochMilli(Long.MAX_VALUE);
        }
        long next = last + unit.millis();
        return Instant.ofEpochMilli(next < last ? Long.MAX_VALUE : next);
    }

    public Instant epoch() {
        return Instant.ofEpochMilli(epochMillis);
    }

    /** 除 {@link #id()} 之外完全相同，即解码结果不可区分 */
    public boolean sameStructure(SnowflakeLayout other) {
        return other != null && epochMillis == other.epochMillis && unit == other.unit
                && timestampBits == other.timestampBits && fields.equals(other.fields);
    }

    public SnowflakeLayout withId(String newId) {
        return new SnowflakeLayout(newId, epochMillis, unit, timestampBits, fields);
    }

    private static IdCodecException invalid(String message) {
        return new IdCodecException(IdCodecException.Code.INVALID_LAYOUT, message, message);
    }
}
