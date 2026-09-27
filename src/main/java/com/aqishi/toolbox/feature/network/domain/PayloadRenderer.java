package com.aqishi.toolbox.feature.network.domain;

import java.nio.charset.Charset;

/**
 * 把收发的字节渲染成日志里可读的文本。纯函数、线程安全。
 */
public final class PayloadRenderer {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** 文本模式下控制字符的呈现方式。 */
    public enum ControlStyle {
        /** 显示为 {@code \r}、{@code \n}、{@code \xNN} 等转义，一条记录始终保持一行。 */
        ESCAPE,
        /** 显示为 {@code .}，与十六进制转储的 ASCII 列一致。 */
        DOT
    }

    private PayloadRenderer() {
    }

    /**
     * 按显示模式渲染。
     *
     * @param hexDump HEX 部分是否用"偏移 + 16 字节 + ASCII 列"的转储格式（多行）
     */
    public static String render(byte[] data, PayloadDisplayMode mode, Charset charset,
                                ControlStyle style, boolean hexDump) {
        switch (mode) {
            case HEX:
                return hexDump ? hexDump(data) : toHex(data);
            case BOTH:
                String hex = hexDump ? hexDump(data) : toHex(data);
                String separator = hexDump ? "\n" : "  |  ";
                return text(data, charset, style) + separator + hex;
            default:
                return text(data, charset, style);
        }
    }

    /** 空格分隔的大写十六进制，例如 {@code 48 65 6C}。 */
    public static String toHex(byte[] data) {
        if (data.length == 0) {
            return "";
        }
        char[] out = new char[data.length * 3 - 1];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            int p = i * 3;
            out[p] = HEX[v >>> 4];
            out[p + 1] = HEX[v & 0x0F];
            if (i < data.length - 1) {
                out[p + 2] = ' ';
            }
        }
        return new String(out);
    }

    /**
     * 经典十六进制转储：每行 {@code 0000: 48 65 ...  |Hel...|}，16 字节一行，
     * 第 8 个字节后多一个空格便于数数，最后一行以空格补齐让 ASCII 列对齐。
     */
    public static String hexDump(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 4 + 16);
        for (int offset = 0; offset < data.length; offset += 16) {
            if (offset > 0) {
                out.append('\n');
            }
            appendHex4(out, offset);
            out.append(": ");
            int end = Math.min(data.length, offset + 16);
            for (int i = offset; i < offset + 16; i++) {
                if (i < end) {
                    int v = data[i] & 0xFF;
                    out.append(HEX[v >>> 4]).append(HEX[v & 0x0F]).append(' ');
                } else {
                    out.append("   ");
                }
                if (i - offset == 7) {
                    out.append(' ');
                }
            }
            out.append(" |");
            for (int i = offset; i < end; i++) {
                int v = data[i] & 0xFF;
                out.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
            }
            out.append('|');
        }
        return out.toString();
    }

    private static void appendHex4(StringBuilder out, int value) {
        if (value > 0xFFFF) {
            appendHex4(out, value >>> 16);
        }
        out.append(HEX[(value >>> 12) & 0xF]).append(HEX[(value >>> 8) & 0xF])
                .append(HEX[(value >>> 4) & 0xF]).append(HEX[value & 0xF]);
    }

    /** 按字符集解码，控制字符按 style 替换，保证结果不含换行等破坏日志排版的字符。 */
    public static String text(byte[] data, Charset charset, ControlStyle style) {
        String decoded = new String(data, charset);
        StringBuilder out = null;
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (!isControl(c)) {
                if (out != null) {
                    out.append(c);
                }
                continue;
            }
            if (out == null) {
                out = new StringBuilder(decoded.length() + 16);
                out.append(decoded, 0, i);
            }
            if (style == ControlStyle.DOT) {
                out.append('.');
                continue;
            }
            switch (c) {
                case '\r': out.append("\\r"); break;
                case '\n': out.append("\\n"); break;
                case '\t': out.append("\\t"); break;
                case '\0': out.append("\\0"); break;
                default:
                    out.append("\\x").append(HEX[(c >>> 4) & 0xF]).append(HEX[c & 0xF]);
                    break;
            }
        }
        return out == null ? decoded : out.toString();
    }

    private static boolean isControl(char c) {
        return c < 0x20 || c == 0x7F;
    }
}
