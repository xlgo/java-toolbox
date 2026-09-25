package com.aqishi.toolbox.feature.codec.domain;

/**
 * {@link TextEscaper} 支持的转义格式。
 *
 * <p>每种格式都是「字面量内容」级别的转义：只处理引号之间的内容，
 * 不负责拼接完整语句或文档。URL 与 Base64 由各自的专用工具处理，这里不重复。</p>
 */
public enum EscapeFormat {
    /** Java 字符串字面量内容。 */
    JAVA,
    /** JSON 字符串（RFC 8259）。 */
    JSON,
    /** JavaScript 字符串字面量内容。 */
    JAVASCRIPT,
    /** 仅做 \\uXXXX 转换，类似 native2ascii。 */
    UNICODE,
    /** HTML 文本/属性值。 */
    HTML,
    /** XML 1.0 文本/属性值。 */
    XML,
    /** CSV 单个字段（RFC 4180）。 */
    CSV,
    /** SQL 字符串字面量。 */
    SQL,
    /** 让文本在 java.util.regex 中按字面匹配。 */
    REGEX,
    /** java.util.Properties 的键或值。 */
    PROPERTIES
}
