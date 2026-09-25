package com.aqishi.toolbox.util;

import java.util.ArrayList;
import java.util.List;

/**
 * POSIX shell（bash / zsh / sh）参数的引用与切分。
 *
 * <p>用单引号而不是双引号：双引号里 {@code $}、反引号仍会展开，交互式 bash 里的 {@code !}
 * 还会触发历史扩展且无法可靠转义。单引号内部没有任何特殊字符，唯一要处理的是单引号本身，
 * 写成 {@code '\''}（结束引号、转义一个单引号、重新开始引号）。换行也会被原样保留。</p>
 */
public final class ShellQuote {

    private ShellQuote() {
    }

    /** 把任意文本包成一个单引号参数；{@code null} 视为空串。 */
    public static String single(String value) {
        String text = value == null ? "" : value;
        return "'" + text.replace("'", "'\\''") + "'";
    }

    /**
     * 按 POSIX shell 规则切词：单引号内原样保留；双引号内只有 {@code \" \\ \$ \`} 需要反转义；
     * 引号外的反斜杠转义下一个字符，反斜杠加换行是续行。也接受 bash 的 {@code $'...'} 写法
     * （Chrome 在请求体含特殊字符时会生成），其中处理常见的 {@code \n \t \' \\} 转义。
     *
     * @throws IllegalArgumentException 引号未闭合
     */
    public static List<String> split(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                int end = text.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated single quote");
                }
                current.append(text, i + 1, end);
                inWord = true;
                i = end + 1;
            } else if (ch == '$' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                i = readAnsiC(text, i + 2, current);
                inWord = true;
            } else if (ch == '"') {
                i = readDoubleQuoted(text, i + 1, current);
                inWord = true;
            } else if (ch == '\\') {
                if (i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (next == '\n') {
                        i += 2;
                        continue;
                    }
                    if (next == '\r' && i + 2 < text.length() && text.charAt(i + 2) == '\n') {
                        i += 3;
                        continue;
                    }
                    current.append(next);
                    inWord = true;
                }
                i += 2;
            } else if (Character.isWhitespace(ch)) {
                if (inWord) {
                    words.add(current.toString());
                    current.setLength(0);
                    inWord = false;
                }
                i++;
            } else {
                current.append(ch);
                inWord = true;
                i++;
            }
        }
        if (inWord) {
            words.add(current.toString());
        }
        return words;
    }

    private static int readDoubleQuoted(String text, int start, StringBuilder out) {
        int i = start;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '"') {
                return i + 1;
            }
            if (ch == '\\' && i + 1 < text.length() && "\"\\$`\n".indexOf(text.charAt(i + 1)) >= 0) {
                if (text.charAt(i + 1) != '\n') {
                    out.append(text.charAt(i + 1));
                }
                i += 2;
                continue;
            }
            out.append(ch);
            i++;
        }
        throw new IllegalArgumentException("Unterminated double quote");
    }

    private static int readAnsiC(String text, int start, StringBuilder out) {
        int i = start;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                return i + 1;
            }
            if (ch == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    default: out.append(next); break;
                }
                i += 2;
                continue;
            }
            out.append(ch);
            i++;
        }
        throw new IllegalArgumentException("Unterminated $'...' quote");
    }
}
