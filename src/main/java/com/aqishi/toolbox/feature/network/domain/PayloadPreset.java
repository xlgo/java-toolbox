package com.aqishi.toolbox.feature.network.domain;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 快捷发送的预设条目。以 JSON 持久化到本地偏好，只保存要发送的报文，不应存放任何凭据。
 *
 * <p>枚举字段以名称字符串保存：将来增删枚举常量时旧数据仍可读，未知值回退到默认。</p>
 */
public class PayloadPreset {

    private String name = "";
    private String format = PayloadFormat.TEXT.name();
    private String content = "";
    private String charset = "UTF-8";
    private boolean escapes;
    private String lineEnding = PayloadLineEnding.NONE.name();
    private String checksum = PayloadChecksum.NONE.name();

    public PayloadPreset() {
    }

    public PayloadPreset(String name, PayloadFormat format, String content,
                         PayloadLineEnding lineEnding, PayloadChecksum checksum) {
        this.name = name == null ? "" : name;
        this.format = format.name();
        this.content = content == null ? "" : content;
        this.lineEnding = lineEnding.name();
        this.checksum = checksum.name();
    }

    /** 按预设内容构造编码参数；字符集或枚举值无法识别时回退到默认值。 */
    public PayloadCodec.Spec toSpec() {
        return new PayloadCodec.Spec(formatValue(), charsetValue(), escapes,
                lineEndingValue(), checksumValue());
    }

    public PayloadFormat formatValue() {
        return parse(PayloadFormat.class, format, PayloadFormat.TEXT);
    }

    public PayloadLineEnding lineEndingValue() {
        return parse(PayloadLineEnding.class, lineEnding, PayloadLineEnding.NONE);
    }

    public PayloadChecksum checksumValue() {
        return parse(PayloadChecksum.class, checksum, PayloadChecksum.NONE);
    }

    public Charset charsetValue() {
        try {
            return Charset.forName(charset);
        } catch (RuntimeException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public boolean isEscapes() {
        return escapes;
    }

    public void setEscapes(boolean escapes) {
        this.escapes = escapes;
    }

    public String getLineEnding() {
        return lineEnding;
    }

    public void setLineEnding(String lineEnding) {
        this.lineEnding = lineEnding;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    @Override
    public String toString() {
        return name;
    }
}
