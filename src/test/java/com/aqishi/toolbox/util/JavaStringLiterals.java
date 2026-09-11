package com.aqishi.toolbox.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Java 词法扫描器，只负责从源码中摘出字符串字面量。
 *
 * <p>供 {@link I18nHardcodedTextTest} 统计硬编码中文使用。它必须能区分字面量与
 * 注释——否则 Javadoc 里的中文说明会被误判成待国际化的文案，门禁就失去意义。
 * 因此这里处理了行注释、块注释、字符字面量、转义以及 Java 15+ 的文本块。</p>
 *
 * <p>它不是完整的 Java 解析器：不理解语法结构，只按字符状态机推进。对于
 * "找出所有字符串字面量" 这一用途，这个精度已经足够，且不引入解析器依赖。</p>
 */
final class JavaStringLiterals {

    private JavaStringLiterals() {
    }

    /**
     * 扫描源码，按出现顺序返回所有字符串字面量的<em>内容</em>（不含两端引号，
     * 保留原始转义序列）。文本块以其原始内容返回，不做缩进剥离。
     */
    static List<String> scan(String source) {
        List<String> literals = new ArrayList<>();
        int length = source.length();
        int index = 0;
        while (index < length) {
            char current = source.charAt(index);

            // 行注释：吃到行尾
            if (current == '/' && index + 1 < length && source.charAt(index + 1) == '/') {
                index = source.indexOf('\n', index);
                if (index < 0) {
                    break;
                }
                continue;
            }

            // 块注释（含 Javadoc）：吃到 */
            if (current == '/' && index + 1 < length && source.charAt(index + 1) == '*') {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? length : end + 2;
                continue;
            }

            // 文本块：以 """ 开头，必须先于普通字符串判断
            if (current == '"' && index + 2 < length
                    && source.charAt(index + 1) == '"' && source.charAt(index + 2) == '"') {
                int contentStart = index + 3;
                int end = findTextBlockEnd(source, contentStart);
                literals.add(source.substring(contentStart, Math.min(end, length)));
                index = Math.min(end + 3, length);
                continue;
            }

            // 普通字符串字面量
            if (current == '"') {
                int contentStart = index + 1;
                int end = findQuotedEnd(source, contentStart, '"');
                literals.add(source.substring(contentStart, Math.min(end, length)));
                index = Math.min(end + 1, length);
                continue;
            }

            // 字符字面量：内容不算字符串，但必须正确跳过，
            // 否则 '"' 这样的写法会让后续扫描的引号配对整体错位
            if (current == '\'') {
                int end = findQuotedEnd(source, index + 1, '\'');
                index = Math.min(end + 1, length);
                continue;
            }

            index++;
        }
        return literals;
    }

    /** 返回终止引号的下标；未闭合时返回源码长度。 */
    private static int findQuotedEnd(String source, int from, char quote) {
        int index = from;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == '\\') {
                index += 2; // 跳过转义序列整体，使 \" 不被当作结束引号
                continue;
            }
            if (c == quote) {
                return index;
            }
            if (c == '\n' && quote == '"') {
                return index; // 普通字符串不跨行；遇到换行按未闭合处理，避免吞掉整个文件
            }
            index++;
        }
        return source.length();
    }

    /** 返回文本块结束定界符 {@code """} 的起始下标；未闭合时返回源码长度。 */
    private static int findTextBlockEnd(String source, int from) {
        int index = from;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (c == '"' && index + 2 < source.length()
                    && source.charAt(index + 1) == '"' && source.charAt(index + 2) == '"') {
                return index;
            }
            index++;
        }
        return source.length();
    }

    /** 字面量中是否含有汉字（用于判定是否为待国际化的中文文案）。 */
    static boolean containsHan(String text) {
        return text.codePoints().anyMatch(
                cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }
}
