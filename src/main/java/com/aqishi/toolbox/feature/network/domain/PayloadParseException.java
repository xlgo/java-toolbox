package com.aqishi.toolbox.feature.network.domain;

/**
 * 载荷解析失败。携带错误码与出错字符在输入中的位置（从 0 开始），界面据此定位并本地化提示。
 */
public class PayloadParseException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** 解析错误类别。 */
    public enum Code {
        /** 十六进制输入里出现了非十六进制字符。 */
        INVALID_HEX_CHAR,
        /** 某个十六进制片段的位数为奇数，无法凑成整字节。 */
        ODD_HEX_DIGITS,
        /** 未知的转义序列或转义序列不完整。 */
        INVALID_ESCAPE
    }

    private final Code code;
    private final int position;

    public PayloadParseException(Code code, int position, String message) {
        super(message + " at position " + position);
        this.code = code;
        this.position = position;
    }

    public Code getCode() {
        return code;
    }

    /** 出错字符的下标（从 0 开始）。 */
    public int getPosition() {
        return position;
    }
}
