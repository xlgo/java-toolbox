package com.aqishi.toolbox.feature.codec.domain;

/**
 * 反转义失败：携带错误码与出错位置，界面据此本地化提示并定位到输入中的具体字符。
 *
 * <p>消息只用英文——领域层不产出面向用户的中文，文案由界面层按 {@link Code} 映射。</p>
 */
public class UnescapeException extends Exception {

    private static final long serialVersionUID = 1L;

    /** 错误（或宽松模式下的警告）类别。 */
    public enum Code {
        /** 反斜杠后跟着该格式不认识的字符。 */
        BAD_ESCAPE,
        /** 输入以一个孤立的反斜杠结束。 */
        TRUNCATED_ESCAPE,
        /** \\u 后不足 4 位十六进制就到了结尾。 */
        TRUNCATED_UNICODE,
        /** \\u、\\x、\\u{...} 中出现了非十六进制字符。 */
        INVALID_HEX,
        /** 码点超出 Unicode 范围，或是该格式不允许的字符。 */
        INVALID_CODE_POINT,
        /** 实体缺少结尾的分号。 */
        UNTERMINATED_ENTITY,
        /** 不认识的命名实体。 */
        UNKNOWN_ENTITY,
        /** &amp; 后面不是合法的实体写法（如 &amp;#;、XML 中的裸 &amp;）。 */
        INVALID_ENTITY,
        /** 字符串中出现了未转义的控制字符（JSON 严格模式）。 */
        UNESCAPED_CONTROL,
        /** 出现了未转义/未成对的引号。 */
        UNEXPECTED_QUOTE,
        /** 引号字段没有结束引号。 */
        UNTERMINATED_QUOTE,
        /** 要求整体加引号时缺少包围引号。 */
        MISSING_QUOTE,
        /** 结束引号之后还有多余字符。 */
        TRAILING_CHARACTERS,
        /** 转义时遇到 XML 1.0 不允许的字符，已替换为 U+FFFD（只作为警告出现，不会抛出）。 */
        XML_ILLEGAL_CHAR
    }

    private final Code code;
    private final int offset;
    private final int length;

    public UnescapeException(Code code, int offset, int length) {
        super(code + " at offset " + offset);
        this.code = code;
        this.offset = offset;
        this.length = Math.max(1, length);
    }

    public Code getCode() {
        return code;
    }

    /** 出错序列在原始输入中的起始下标（UTF-16 单元）。 */
    public int getOffset() {
        return offset;
    }

    /** 出错序列的长度，至少为 1，便于界面选中整段。 */
    public int getLength() {
        return length;
    }
}
