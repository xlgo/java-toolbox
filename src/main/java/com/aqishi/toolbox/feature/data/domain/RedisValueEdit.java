package com.aqishi.toolbox.feature.data.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 从 Redis 值编辑器里截下来的一次保存请求：目标 key、类型和完整的新内容。
 *
 * <p>必须在 EDT 上从表格构建，再交给后台线程写入。之前后台线程直接读"当前选中的 key"
 * 和表格模型：保存请求排队期间用户点了另一个 key，list/set/zset 的"先删后写"
 * 就会删掉新选中的那个 key，再把旧 key 的内容写进去。快照把 key 和内容一起冻结，
 * 这种错位就不可能发生。</p>
 *
 * <p>所有解析与校验都在构建时完成（例如 zset 的分值），保证写入阶段不会半途失败、
 * 留下一个已被清空的 key。</p>
 */
public final class RedisValueEdit {

    /** 可编辑的 Redis 数据类型，对应 {@code TYPE} 命令的返回值。 */
    public enum Type {
        STRING, HASH, LIST, SET, ZSET;

        /** 解析 {@code TYPE} 命令的返回值；不支持的类型抛出 {@link IllegalArgumentException}。 */
        public static Type of(String redisType) {
            if (redisType == null) {
                throw new IllegalArgumentException("Redis type is required");
            }
            try {
                return valueOf(redisType.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unsupported Redis type: " + redisType, unknown);
            }
        }
    }

    private final String key;
    private final Type type;
    private final String text;
    private final Map<String, String> fields;
    private final List<String> items;
    private final Map<String, Double> scores;

    private RedisValueEdit(String key, Type type, String text, Map<String, String> fields,
                           List<String> items, Map<String, Double> scores) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Redis key is required");
        }
        this.key = key;
        this.type = Objects.requireNonNull(type, "type");
        this.text = text;
        this.fields = fields;
        this.items = items;
        this.scores = scores;
    }

    public static RedisValueEdit string(String key, String text) {
        return new RedisValueEdit(key, Type.STRING, text == null ? "" : text, null, null, null);
    }

    /**
     * 哈希：每行 {@code [field, value]}。字段名为空的行视为未填写而跳过；
     * 同名字段后出现的覆盖先出现的，与逐条 {@code HSET} 的效果一致。
     */
    public static RedisValueEdit hash(String key, List<String[]> rows) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String[] row : rows) {
            String field = cell(row, 0);
            if (field != null && !field.trim().isEmpty()) {
                String value = cell(row, 1);
                fields.put(field, value == null ? "" : value);
            }
        }
        return new RedisValueEdit(key, Type.HASH, null, Collections.unmodifiableMap(fields), null, null);
    }

    /** 列表：保持顺序；{@code null} 表示该行尚未填写而跳过，空串则是合法元素。 */
    public static RedisValueEdit list(String key, List<String> values) {
        List<String> items = new ArrayList<>();
        for (String value : values) {
            if (value != null) {
                items.add(value);
            }
        }
        return new RedisValueEdit(key, Type.LIST, null, null, Collections.unmodifiableList(items), null);
    }

    /** 集合：空白成员视为未填写而跳过。 */
    public static RedisValueEdit set(String key, List<String> members) {
        List<String> items = new ArrayList<>();
        for (String member : members) {
            if (member != null && !member.trim().isEmpty()) {
                items.add(member);
            }
        }
        return new RedisValueEdit(key, Type.SET, null, null, Collections.unmodifiableList(items), null);
    }

    /**
     * 有序集合：每行 {@code [score, member]}。成员为空的行跳过；分值无法解析时立即抛出，
     * 异常信息带上行号（从 1 开始），此时尚未对 Redis 做任何修改。
     */
    public static RedisValueEdit zset(String key, List<String[]> rows) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String member = cell(rows.get(i), 1);
            if (member == null || member.trim().isEmpty()) {
                continue;
            }
            String rawScore = cell(rows.get(i), 0);
            try {
                scores.put(member, Double.parseDouble(rawScore == null ? "" : rawScore.trim()));
            } catch (NumberFormatException badScore) {
                throw new IllegalArgumentException(
                        "Row " + (i + 1) + ": score is not a number: " + rawScore, badScore);
            }
        }
        return new RedisValueEdit(key, Type.ZSET, null, null, null, Collections.unmodifiableMap(scores));
    }

    public String key() {
        return key;
    }

    public Type type() {
        return type;
    }

    /** {@link Type#STRING} 的新值；其他类型为 {@code null}。 */
    public String text() {
        return text;
    }

    /** {@link Type#HASH} 的完整字段表；其他类型为 {@code null}。 */
    public Map<String, String> fields() {
        return fields;
    }

    /** {@link Type#LIST} 或 {@link Type#SET} 的元素；其他类型为 {@code null}。 */
    public List<String> items() {
        return items;
    }

    /** {@link Type#ZSET} 的成员到分值；其他类型为 {@code null}。 */
    public Map<String, Double> scores() {
        return scores;
    }

    private static String cell(String[] row, int index) {
        return row != null && index < row.length ? row[index] : null;
    }
}
