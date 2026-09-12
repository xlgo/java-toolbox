package com.aqishi.toolbox.feature.data.domain;

import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.Hex;
import com.aqishi.toolbox.util.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Kafka 消息内容展示相关的纯逻辑：格式嗅探、JSON 美化、
 * 二进制判定与 HEX 转储、生产者头文本解析。
 */
public final class KafkaMessageFormat {

    /** 消息内容的嗅探结果。 */
    public enum Kind {
        EMPTY, JSON, XML, PLAIN
    }

    /** 一条解析出来的消息头。 */
    public record Header(String key, String value) {
    }

    private KafkaMessageFormat() {
    }

    /** 按首尾字符嗅探结构类型，不解析内容。 */
    public static Kind detect(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return Kind.EMPTY;
        }
        String trimmed = rawText.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return Kind.JSON;
        }
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            return Kind.XML;
        }
        return Kind.PLAIN;
    }

    /** 是 JSON 结构则美化输出，否则原样返回；空输入归一为空串。 */
    public static String tryFormatJson(String raw) {
        if (detect(raw) != Kind.JSON) {
            return raw == null || raw.trim().isEmpty() ? "" : raw;
        }
        try {
            Object json = Json.mapper().readValue(raw.trim(), Object.class);
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception error) {
            Errors.ignored("内容不是合法 JSON，按原文展示", error);
            return raw;
        }
    }

    /** 不可打印字节占比超过 5% 判定为二进制；制表/换行/回车视为可打印。 */
    public static boolean isBinaryData(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        int nonPrintableCount = 0;
        for (byte b : bytes) {
            int u = b & 0xFF;
            if ((u < 32 && u != 9 && u != 10 && u != 13) || u == 127) {
                nonPrintableCount++;
            }
        }
        return (double) nonPrintableCount / bytes.length > 0.05;
    }

    /** 经典 16 字节一行、偏移 + HEX + ASCII 三栏的转储格式。 */
    public static String formatHexDump(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "[空数据]";
        }
        StringBuilder sb = new StringBuilder();
        int len = bytes.length;
        String allHex = Hex.toHexUpper(bytes);
        for (int i = 0; i < len; i += 16) {
            sb.append(String.format("%08X  ", i));
            for (int j = 0; j < 16; j++) {
                if (i + j < len) {
                    int h = (i + j) * 2;
                    sb.append(allHex, h, h + 2).append(' ');
                } else {
                    sb.append("   ");
                }
                if (j == 7) {
                    sb.append(" ");
                }
            }
            sb.append(" |");
            for (int j = 0; j < 16; j++) {
                if (i + j < len) {
                    int b = bytes[i + j] & 0xFF;
                    if (b >= 32 && b <= 126) {
                        sb.append((char) b);
                    } else {
                        sb.append('.');
                    }
                } else {
                    sb.append(' ');
                }
            }
            sb.append("|\n");
        }
        return sb.toString();
    }

    /**
     * 解析生产者消息头文本：每行一条，{@code key=value} 或 {@code key: value}，
     * 空行与 {@code #} 开头的行忽略。
     */
    public static List<Header> parseHeaders(String text) {
        List<Header> headers = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return headers;
        }
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator == -1) {
                separator = line.indexOf(':');
            }
            if (separator > 0) {
                headers.add(new Header(line.substring(0, separator).trim(),
                        line.substring(separator + 1).trim()));
            }
        }
        return headers;
    }

    /** 便捷方法：把头解析结果的字节形式直接交给 Kafka 记录使用。 */
    public static byte[] headerValueBytes(Header header) {
        return header.value().getBytes(StandardCharsets.UTF_8);
    }
}
