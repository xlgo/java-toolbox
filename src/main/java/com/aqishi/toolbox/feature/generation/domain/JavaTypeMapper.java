package com.aqishi.toolbox.feature.generation.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SQL 列类型到 Java 类型的映射。
 *
 * <p>一律映射为包装类型：实体字段为 null 表示「数据库里是 NULL / 未赋值」，
 * 用基本类型会把 NULL 静默变成 0 或 false。</p>
 *
 * <table>
 *   <caption>映射表（按规范化后的基础类型名，不区分大小写）</caption>
 *   <tr><th>SQL 类型</th><th>Java 类型</th><th>说明</th></tr>
 *   <tr><td>boolean, bool, tinyint(1), bit, bit(1)</td><td>Boolean</td>
 *       <td>MySQL 习惯用 tinyint(1) 表示布尔；bit(n&gt;1) 是位串，映射为 byte[]</td></tr>
 *   <tr><td>tinyint, smallint, mediumint, int, integer, int2, int4, serial, smallserial, year</td>
 *       <td>Integer</td><td>int/integer UNSIGNED 超出 Integer 范围，映射为 Long</td></tr>
 *   <tr><td>bigint, int8, bigserial, serial8</td><td>Long</td>
 *       <td>bigint UNSIGNED 仍为 Long 并告警，或按选项映射为 BigInteger</td></tr>
 *   <tr><td>decimal, numeric, dec, fixed, money, smallmoney</td><td>BigDecimal</td><td></td></tr>
 *   <tr><td>number (Oracle)</td><td>Integer / Long / BigDecimal</td>
 *       <td>NUMBER(p) 或 NUMBER(p,0)：p&le;9 为 Integer，p&le;18 为 Long，否则 BigDecimal；
 *           无精度或带小数位为 BigDecimal</td></tr>
 *   <tr><td>float, real, float4, binary_float</td><td>Float</td>
 *       <td>float(p) 且 p&gt;24 按 SQL 标准是双精度，映射为 Double</td></tr>
 *   <tr><td>double, double precision, float8, binary_double</td><td>Double</td><td></td></tr>
 *   <tr><td>char, varchar, nchar, nvarchar, varchar2, nvarchar2, character varying, text 系列,
 *           clob, nclob, enum, set, json, jsonb, xml, citext, inet, cidr ...</td><td>String</td><td></td></tr>
 *   <tr><td>uuid, uniqueidentifier</td><td>UUID</td><td></td></tr>
 *   <tr><td>date</td><td>LocalDate</td><td>Oracle DATE 带时分秒，见类末说明</td></tr>
 *   <tr><td>time, time without time zone</td><td>LocalTime</td><td></td></tr>
 *   <tr><td>time with time zone, timetz</td><td>OffsetTime</td><td></td></tr>
 *   <tr><td>datetime, datetime2, smalldatetime, timestamp, timestamp without time zone,
 *           timestamp with local time zone</td><td>LocalDateTime</td><td></td></tr>
 *   <tr><td>timestamptz, timestamp with time zone, datetimeoffset</td><td>OffsetDateTime</td><td></td></tr>
 *   <tr><td>blob 系列, bytea, binary, varbinary, raw, long raw, image</td><td>byte[]</td><td></td></tr>
 *   <tr><td>其他</td><td>Object</td><td>并产生 UNKNOWN 告警</td></tr>
 * </table>
 *
 * <p>数组列（{@code text[]}、{@code integer ARRAY}）映射为元素类型的 Java 数组，
 * 如 {@code String[]}。选项「使用 java.util.Date」会把所有日期时间类型换成 {@code Date}。</p>
 *
 * <p>已知局限：Oracle 的 DATE 实际含时分秒，但单看类型名无法区分方言，这里按 SQL 标准映射为
 * {@code LocalDate}；Oracle 用户需要自行改成 {@code LocalDateTime}。</p>
 */
public final class JavaTypeMapper {

    /** Java 侧的类型类别；日期时间类别在 JPA 下决定 @Temporal 的取值 */
    public enum Kind {
        BOOLEAN, INTEGER, LONG, BIG_INTEGER, BIG_DECIMAL, FLOAT, DOUBLE, STRING, UUID,
        LOCAL_DATE, LOCAL_TIME, OFFSET_TIME, LOCAL_DATE_TIME, OFFSET_DATE_TIME, BYTES, OBJECT;

        /** 是否属于日期时间类 */
        public boolean isTemporal() {
            return this == LOCAL_DATE || this == LOCAL_TIME || this == OFFSET_TIME
                    || this == LOCAL_DATE_TIME || this == OFFSET_DATE_TIME;
        }
    }

    /** 映射附带的提示，由生成器转成告警 */
    public enum Note {
        NONE,
        /** bigint unsigned 映射为 Long，可能溢出 */
        UNSIGNED_BIGINT,
        /** 不认识的类型，映射为 Object */
        UNKNOWN
    }

    /**
     * 一次映射的结果。
     *
     * @param kind          类型类别
     * @param qualifiedName 全限定名；数组时为元素类型的全限定名，{@code byte[]} 为 {@code byte}
     * @param arrayDims     Java 数组维数（{@code byte[]} 本身算 1 维）
     * @param jdbcType      MyBatis {@code JdbcType} 枚举名
     * @param lengthApplies JPA {@code @Column(length)} 是否适用（定长/变长字符与二进制）
     * @param note          附带提示
     */
    public record JavaType(Kind kind, String qualifiedName, int arrayDims, String jdbcType,
                           boolean lengthApplies, Note note) {

        /** 不含包名的类型写法，例如 {@code String[]} */
        public String simpleName() {
            String base = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
            return base + "[]".repeat(arrayDims);
        }

        /** 是否为 Java 数组（含 byte[]） */
        public boolean isArray() {
            return arrayDims > 0;
        }

        /** 是否需要 import（java.lang 与基本类型不需要） */
        public boolean needsImport() {
            return qualifiedName.contains(".") && !qualifiedName.startsWith("java.lang.");
        }

        /** MyBatis XML 中 javaType 属性的写法 */
        public String mybatisJavaType() {
            if ("byte".equals(qualifiedName) && arrayDims == 1) {
                return "_byte[]";
            }
            if (arrayDims == 0) {
                return qualifiedName;
            }
            // Class.forName 能识别的 JVM 二进制名，MyBatis 解析 javaType 时走的正是它
            String element = "byte".equals(qualifiedName) ? "B" : "L" + qualifiedName + ";";
            return "[".repeat(arrayDims) + element;
        }
    }

    private record Spec(Kind kind, String jdbcType, boolean lengthApplies) {
    }

    private static final Map<String, Spec> TYPES = new HashMap<>();

    static {
        put(Kind.BOOLEAN, "BOOLEAN", false, "boolean", "bool");
        put(Kind.INTEGER, "TINYINT", false, "tinyint");
        put(Kind.INTEGER, "SMALLINT", false, "smallint", "int2", "smallserial", "serial2");
        put(Kind.INTEGER, "INTEGER", false, "mediumint", "int", "integer", "int4", "serial",
                "serial4", "year", "middleint", "int3");
        put(Kind.LONG, "BIGINT", false, "bigint", "int8", "bigserial", "serial8");
        put(Kind.BIG_DECIMAL, "DECIMAL", false, "decimal", "numeric", "dec", "fixed", "money",
                "smallmoney");
        put(Kind.BIG_DECIMAL, "NUMERIC", false, "number");
        put(Kind.FLOAT, "REAL", false, "float", "real", "float4", "binary_float");
        put(Kind.DOUBLE, "DOUBLE", false, "double", "double precision", "float8", "binary_double");
        put(Kind.STRING, "CHAR", true, "char", "character", "nchar", "national char",
                "national character");
        put(Kind.STRING, "VARCHAR", true, "varchar", "character varying", "char varying",
                "varchar2", "nvarchar", "nvarchar2", "nchar varying", "national character varying",
                "national char varying", "string", "varchar_ignorecase");
        put(Kind.STRING, "LONGVARCHAR", false, "text", "tinytext", "mediumtext", "longtext",
                "ntext", "long", "long varchar", "citext");
        put(Kind.STRING, "CLOB", false, "clob", "nclob", "dbclob");
        put(Kind.STRING, "VARCHAR", false, "enum", "set", "inet", "cidr", "macaddr", "macaddr8",
                "rowid", "urowid", "tsvector", "tsquery", "sysname");
        put(Kind.STRING, "OTHER", false, "json", "jsonb");
        put(Kind.STRING, "SQLXML", false, "xml", "xmltype");
        put(Kind.UUID, "OTHER", false, "uuid", "uniqueidentifier");
        put(Kind.LOCAL_DATE, "DATE", false, "date");
        put(Kind.LOCAL_TIME, "TIME", false, "time", "time without time zone");
        put(Kind.OFFSET_TIME, "TIME_WITH_TIMEZONE", false, "timetz", "time with time zone");
        put(Kind.LOCAL_DATE_TIME, "TIMESTAMP", false, "datetime", "datetime2", "smalldatetime",
                "timestamp", "timestamp without time zone", "timestamp with local time zone");
        put(Kind.OFFSET_DATE_TIME, "TIMESTAMP_WITH_TIMEZONE", false, "timestamptz",
                "timestamp with time zone", "datetimeoffset");
        put(Kind.BYTES, "BINARY", true, "binary");
        put(Kind.BYTES, "VARBINARY", true, "varbinary", "raw", "bit varying", "varbit");
        put(Kind.BYTES, "LONGVARBINARY", false, "bytea", "long raw", "long varbinary", "image");
        put(Kind.BYTES, "BLOB", false, "blob", "tinyblob", "mediumblob", "longblob");
        put(Kind.BOOLEAN, "BIT", false, "bit");
    }

    private static void put(Kind kind, String jdbcType, boolean lengthApplies, String... names) {
        for (String name : names) {
            TYPES.put(name, new Spec(kind, jdbcType, lengthApplies));
        }
    }

    private JavaTypeMapper() {
    }

    /** 是否为映射表里认识的基础类型名（解析器用它区分 {@code key varchar(20)} 与 {@code KEY idx (a)}） */
    public static boolean isKnownType(String typeName) {
        return typeName != null && TYPES.containsKey(typeName.toLowerCase(Locale.ROOT));
    }

    /**
     * 映射一列的 Java 类型。
     *
     * @param column                     列定义
     * @param legacyDate                 日期时间类型是否改用 {@code java.util.Date}
     * @param unsignedBigintAsBigInteger bigint unsigned 是否映射为 {@code BigInteger}
     */
    public static JavaType map(ColumnDef column, boolean legacyDate, boolean unsignedBigintAsBigInteger) {
        String type = column.typeName().toLowerCase(Locale.ROOT);
        Spec spec = TYPES.get(type);
        Note note = Note.NONE;
        Kind kind;
        String jdbcType;
        boolean lengthApplies;
        if (spec == null) {
            kind = Kind.OBJECT;
            jdbcType = "OTHER";
            lengthApplies = false;
            note = Note.UNKNOWN;
        } else {
            kind = spec.kind();
            jdbcType = spec.jdbcType();
            lengthApplies = spec.lengthApplies();
        }
        Integer length = column.length();

        switch (type) {
            case "tinyint":
                if (length != null && length == 1) {
                    kind = Kind.BOOLEAN;
                    jdbcType = "BIT";
                }
                break;
            case "bit":
                if (length != null && length > 1) {
                    kind = Kind.BYTES;
                    jdbcType = "BINARY";
                }
                break;
            case "int":
            case "integer":
            case "int4":
                // mediumint unsigned 最大约 1677 万，Integer 装得下；int unsigned 装不下
                if (column.unsigned()) {
                    kind = Kind.LONG;
                    jdbcType = "BIGINT";
                }
                break;
            case "bigint":
            case "int8":
                if (column.unsigned()) {
                    if (unsignedBigintAsBigInteger) {
                        kind = Kind.BIG_INTEGER;
                        jdbcType = "BIGINT";
                    } else {
                        note = Note.UNSIGNED_BIGINT;
                    }
                }
                break;
            case "number":
                kind = oracleNumber(length, column.scale());
                jdbcType = kind == Kind.INTEGER ? "INTEGER" : kind == Kind.LONG ? "BIGINT" : "NUMERIC";
                break;
            case "float":
                if (length != null && length > 24) {
                    kind = Kind.DOUBLE;
                    jdbcType = "DOUBLE";
                }
                break;
            default:
                break;
        }

        String qualifiedName = qualifiedName(kind, legacyDate);
        int dims = column.arrayDimensions();
        if (kind == Kind.BYTES) {
            dims += 1;
        }
        if (column.isArray()) {
            jdbcType = "ARRAY";
        }
        return new JavaType(kind, qualifiedName, dims, jdbcType, lengthApplies, note);
    }

    /** Oracle NUMBER：按精度挑最小的够用的整数类型，带小数或不限精度一律 BigDecimal */
    private static Kind oracleNumber(Integer precision, Integer scale) {
        boolean integral = scale == null || scale == 0;
        if (precision == null || !integral) {
            return Kind.BIG_DECIMAL;
        }
        if (precision <= 9) {
            return Kind.INTEGER;
        }
        if (precision <= 18) {
            return Kind.LONG;
        }
        return Kind.BIG_DECIMAL;
    }

    private static String qualifiedName(Kind kind, boolean legacyDate) {
        if (legacyDate && kind.isTemporal()) {
            return "java.util.Date";
        }
        switch (kind) {
            case BOOLEAN:
                return "java.lang.Boolean";
            case INTEGER:
                return "java.lang.Integer";
            case LONG:
                return "java.lang.Long";
            case BIG_INTEGER:
                return "java.math.BigInteger";
            case BIG_DECIMAL:
                return "java.math.BigDecimal";
            case FLOAT:
                return "java.lang.Float";
            case DOUBLE:
                return "java.lang.Double";
            case STRING:
                return "java.lang.String";
            case UUID:
                return "java.util.UUID";
            case LOCAL_DATE:
                return "java.time.LocalDate";
            case LOCAL_TIME:
                return "java.time.LocalTime";
            case OFFSET_TIME:
                return "java.time.OffsetTime";
            case LOCAL_DATE_TIME:
                return "java.time.LocalDateTime";
            case OFFSET_DATE_TIME:
                return "java.time.OffsetDateTime";
            case BYTES:
                return "byte";
            default:
                return "java.lang.Object";
        }
    }
}
