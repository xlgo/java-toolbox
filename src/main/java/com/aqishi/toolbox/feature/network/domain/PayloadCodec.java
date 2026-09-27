package com.aqishi.toolbox.feature.network.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 把用户在发送框里写的内容变成要发出去的字节：十六进制解析、转义序列、行尾、校验值。
 *
 * <p>纯函数、无状态，线程安全。</p>
 */
public final class PayloadCodec {

    /** 界面可选的字符集。GBK/GB18030 是国内设备协议的常客，UTF-16 用于少数 Windows 系设备。 */
    public static final List<String> CHARSETS = Collections.unmodifiableList(Arrays.asList(
            "UTF-8", "GBK", "GB18030", "US-ASCII", "ISO-8859-1", "UTF-16LE", "UTF-16BE"));

    private PayloadCodec() {
    }

    /** 一次发送的完整参数。 */
    public static final class Spec {
        private final PayloadFormat format;
        private final Charset charset;
        private final boolean escapes;
        private final PayloadLineEnding lineEnding;
        private final PayloadChecksum checksum;

        public Spec(PayloadFormat format, Charset charset, boolean escapes,
                    PayloadLineEnding lineEnding, PayloadChecksum checksum) {
            this.format = Objects.requireNonNull(format, "format");
            this.charset = Objects.requireNonNull(charset, "charset");
            this.escapes = escapes;
            this.lineEnding = lineEnding == null ? PayloadLineEnding.NONE : lineEnding;
            this.checksum = checksum == null ? PayloadChecksum.NONE : checksum;
        }

        public PayloadFormat getFormat() {
            return format;
        }

        public Charset getCharset() {
            return charset;
        }

        public boolean isEscapes() {
            return escapes;
        }

        public PayloadLineEnding getLineEnding() {
            return lineEnding;
        }

        public PayloadChecksum getChecksum() {
            return checksum;
        }
    }

    /**
     * 按 spec 把输入编码为最终字节。行尾只对文本追加（HEX 模式下用户要什么字节自己写），
     * 校验值最后追加且覆盖行尾在内的全部内容——Modbus ASCII/RTU 之外的大多数私有协议也是这样算的。
     */
    public static byte[] encode(String input, Spec spec) {
        String source = input == null ? "" : input;
        byte[] body;
        if (spec.getFormat() == PayloadFormat.HEX) {
            body = parseHex(source);
        } else {
            byte[] text = spec.isEscapes()
                    ? encodeEscaped(source, spec.getCharset())
                    : source.getBytes(spec.getCharset());
            body = concat(text, spec.getLineEnding().bytes());
        }
        return spec.getChecksum().append(body);
    }

    /**
     * 解析十六进制文本。接受 {@code 48 65 6C}、{@code 48656C}、{@code 0x48 0x65}、{@code 48-65}，
     * 分隔符可以是空白、逗号、分号、冒号、连字符。每个片段（两个分隔符之间）位数必须为偶数。
     *
     * @throws PayloadParseException 含非法字符或奇数位片段时，附带出错位置
     */
    public static byte[] parseHex(String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length() / 2 + 1);
        int length = input.length();
        int i = 0;
        while (i < length) {
            char c = input.charAt(i);
            if (isHexSeparator(c)) {
                i++;
                continue;
            }
            int tokenStart = i;
            if (c == '0' && i + 1 < length && (input.charAt(i + 1) == 'x' || input.charAt(i + 1) == 'X')) {
                i += 2;
            }
            int digitsStart = i;
            while (i < length && !isHexSeparator(input.charAt(i))) {
                if (Character.digit(input.charAt(i), 16) < 0) {
                    throw new PayloadParseException(PayloadParseException.Code.INVALID_HEX_CHAR, i,
                            "Invalid hex character '" + input.charAt(i) + "'");
                }
                i++;
            }
            int digits = i - digitsStart;
            if (digits == 0) {
                // 只有孤零零的 "0x" 前缀
                throw new PayloadParseException(PayloadParseException.Code.INVALID_HEX_CHAR,
                        tokenStart, "Hex prefix without digits");
            }
            if (digits % 2 != 0) {
                throw new PayloadParseException(PayloadParseException.Code.ODD_HEX_DIGITS,
                        tokenStart, "Odd number of hex digits");
            }
            for (int d = digitsStart; d < i; d += 2) {
                int high = Character.digit(input.charAt(d), 16);
                int low = Character.digit(input.charAt(d + 1), 16);
                out.write((high << 4) | low);
            }
        }
        return out.toByteArray();
    }

    private static boolean isHexSeparator(char c) {
        return Character.isWhitespace(c) || c == ',' || c == ';' || c == ':' || c == '-';
    }

    /**
     * 解释转义序列后编码：{@code \r \n \t \0 \\ \xNN \}{@code uNNNN}。
     *
     * <p>{@code \xNN} 是<em>原始字节</em>而非字符——若当作 U+00NN 再按 UTF-8 编码，
     * {@code \xFF} 会变成 C3 BF 两个字节，这正是很多调试工具的坑。因此文本分段编码，
     * 原始字节直接写入输出。</p>
     */
    public static byte[] encodeEscaped(String input, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length() + 8);
        StringBuilder segment = new StringBuilder();
        int length = input.length();
        int i = 0;
        while (i < length) {
            char c = input.charAt(i);
            if (c != '\\') {
                segment.append(c);
                i++;
                continue;
            }
            if (i + 1 >= length) {
                throw new PayloadParseException(PayloadParseException.Code.INVALID_ESCAPE, i,
                        "Dangling backslash");
            }
            char kind = input.charAt(i + 1);
            switch (kind) {
                case 'r': segment.append('\r'); i += 2; break;
                case 'n': segment.append('\n'); i += 2; break;
                case 't': segment.append('\t'); i += 2; break;
                case '0': segment.append('\0'); i += 2; break;
                case '\\': segment.append('\\'); i += 2; break;
                case 'x':
                    flush(segment, charset, out);
                    out.write(hexValue(input, i, i + 2, 2));
                    i += 4;
                    break;
                case 'u':
                    segment.append((char) hexValue(input, i, i + 2, 4));
                    i += 6;
                    break;
                default:
                    throw new PayloadParseException(PayloadParseException.Code.INVALID_ESCAPE, i,
                            "Unknown escape \\" + kind);
            }
        }
        flush(segment, charset, out);
        return out.toByteArray();
    }

    private static int hexValue(String input, int escapeStart, int from, int count) {
        if (from + count > input.length()) {
            throw new PayloadParseException(PayloadParseException.Code.INVALID_ESCAPE, escapeStart,
                    "Incomplete escape sequence");
        }
        int value = 0;
        for (int i = from; i < from + count; i++) {
            int digit = Character.digit(input.charAt(i), 16);
            if (digit < 0) {
                throw new PayloadParseException(PayloadParseException.Code.INVALID_ESCAPE, escapeStart,
                        "Invalid hex digit in escape sequence");
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private static void flush(StringBuilder segment, Charset charset, ByteArrayOutputStream out) {
        if (segment.length() > 0) {
            byte[] bytes = segment.toString().getBytes(charset);
            out.write(bytes, 0, bytes.length);
            segment.setLength(0);
        }
    }

    static byte[] concat(byte[] first, byte[] second) {
        if (second.length == 0) {
            return first;
        }
        byte[] out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
