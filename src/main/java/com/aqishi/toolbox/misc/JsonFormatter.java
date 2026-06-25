package com.aqishi.toolbox.misc;

/**
 * 轻量 JSON 美化/压缩器（无第三方依赖）。
 * <p>基于字符状态机：正确处理字符串内的引号转义，避免误判。</p>
 */
final class JsonFormatter {

    private JsonFormatter() {
    }

    /** 美化（缩进 2 空格） */
    static String pretty(String json) {
        return format(json, true);
    }

    /** 压缩成一行 */
    static String compact(String json) {
        return format(json, false);
    }

    private static String format(String json, boolean pretty) {
        if (json == null) return "";
        StringBuilder sb = new StringBuilder(json.length() * 2);
        int indent = 0;
        boolean inStr = false;       // 是否在字符串内
        boolean escape = false;      // 上一个字符是否为转义反斜杠

        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);

            if (inStr) {
                sb.append(ch);
                if (escape) { escape = false; continue; }
                if (ch == '\\') { escape = true; continue; }
                if (ch == '"') inStr = false;
                continue;
            }

            // 不在字符串内
            switch (ch) {
                case '"':
                    inStr = true;
                    sb.append(ch);
                    break;
                case ' ': case '\n': case '\r': case '\t':
                    // 跳过空白
                    break;
                case '{': case '[':
                    sb.append(ch);
                    if (pretty) newline(sb, ++indent);
                    break;
                case '}': case ']':
                    if (pretty) newline(sb, --indent);
                    sb.append(ch);
                    break;
                case ',':
                    sb.append(ch);
                    if (pretty) newline(sb, indent);
                    break;
                case ':':
                    sb.append(ch);
                    if (pretty) sb.append(' ');
                    break;
                default:
                    sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void newline(StringBuilder sb, int indent) {
        sb.append('\n');
        for (int i = 0; i < indent; i++) sb.append("  ");
    }
}
