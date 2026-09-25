package com.aqishi.toolbox.feature.codec.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 标识符切词与命名风格转换。
 *
 * <p>切词规则（按优先级）：</p>
 * <ol>
 *   <li>字母、数字之外的字符（{@code _ - . / 空格}等）一律视为分隔符。</li>
 *   <li>小写后接大写断开：{@code fooBar → foo | Bar}。</li>
 *   <li>连续大写后接小写，在最后一个大写前断开：{@code HTTPServer → HTTP | Server}。</li>
 *   <li>数字粘在前一个词上：{@code utf8Decoder → utf8 | Decoder}；数字后再出现大写才断开：
 *       {@code version2Name → version2 | Name}。</li>
 *   <li>无大小写的文字（中文等）与有大小写的字母之间断开：{@code 用户Name → 用户 | Name}。</li>
 *   <li>少数大小写混排的固定写法整体识别，否则按上面的规则会切得面目全非：
 *       {@code IPv6Address → IPv6 | Address}（而不是 {@code I | Pv6 | Address}）。</li>
 * </ol>
 */
public final class NamingConverter {

    /** 按上述第 6 条整体识别的混排写法，区分大小写。 */
    private static final List<String> MIXED_CASE_TOKENS = List.of("IPv4", "IPv6", "OAuth", "iOS");

    /** 保留缩写时使用的规范写法，键为小写。 */
    private static final Map<String, String> ACRONYMS;

    static {
        Map<String, String> map = new HashMap<>();
        String list = "ID UI IO OS DB IP URL URI UUID GUID HTTP HTTPS JSON XML HTML CSS SQL API JWT TCP UDP"
                + " DNS SSH FTP SMTP CPU GPU RAM PDF CSV UTF ASCII AWS JVM JDK SDK TLS SSL SHA MD RPC"
                + " YAML TOML CRC HMAC OTP QR SMS CDN VM K8S IPv4 IPv6 OAuth iOS";
        for (String acronym : list.split(" ")) {
            map.put(acronym.toLowerCase(Locale.ROOT), acronym);
        }
        ACRONYMS = Collections.unmodifiableMap(map);
    }

    private enum Kind { UPPER, LOWER, DIGIT, CASELESS, SEPARATOR }

    private NamingConverter() {
    }

    /** 把标识符转换为指定风格。 */
    public static String convert(String identifier, NamingStyle style, boolean keepAcronyms) {
        return style.join(split(identifier), keepAcronyms);
    }

    /** 切分为单词，保留原始大小写；空输入返回空列表。 */
    public static List<String> split(String identifier) {
        List<String> words = new ArrayList<>();
        if (identifier == null) {
            return words;
        }
        int n = identifier.length();
        int i = 0;
        while (i < n) {
            while (i < n && kindAt(identifier, i) == Kind.SEPARATOR) {
                i += Character.charCount(identifier.codePointAt(i));
            }
            int start = i;
            while (i < n && kindAt(identifier, i) != Kind.SEPARATOR) {
                i += Character.charCount(identifier.codePointAt(i));
            }
            if (i > start) {
                splitToken(identifier.substring(start, i), words);
            }
        }
        return words;
    }

    /**
     * 返回单词对应的缩写规范写法（如 {@code http → HTTP}、{@code utf8 → UTF8}、{@code ipv6 → IPv6}），
     * 不是已知缩写返回 null。末尾数字先剥掉再查表，这样 {@code sha256}、{@code md5} 也能识别。
     */
    public static String canonicalAcronym(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        String direct = ACRONYMS.get(lower);
        if (direct != null) {
            return direct;
        }
        int end = lower.length();
        while (end > 0 && isAsciiDigit(lower.charAt(end - 1))) {
            end--;
        }
        if (end == 0 || end == lower.length()) {
            return null;
        }
        String base = ACRONYMS.get(lower.substring(0, end));
        return base == null ? null : base + lower.substring(end);
    }

    // ==========================================
    // 切词实现
    // ==========================================

    private static void splitToken(String token, List<String> words) {
        int n = token.length();
        int wordStart = 0;
        int i = 0;
        while (i < n) {
            if (i == wordStart) {
                int special = matchMixedCase(token, i);
                if (special > i) {
                    words.add(token.substring(i, special));
                    i = special;
                    wordStart = special;
                    continue;
                }
            }
            if (i > wordStart && isBoundary(token, wordStart, i)) {
                words.add(token.substring(wordStart, i));
                wordStart = i;
                continue;
            }
            i += Character.charCount(token.codePointAt(i));
        }
        if (wordStart < n) {
            words.add(token.substring(wordStart));
        }
    }

    /** 在 i 处匹配混排固定写法（连同其后紧跟的数字），返回结束下标；不匹配返回 -1。 */
    private static int matchMixedCase(String token, int i) {
        for (String candidate : MIXED_CASE_TOKENS) {
            if (!token.startsWith(candidate, i)) {
                continue;
            }
            int end = i + candidate.length();
            while (end < token.length() && isAsciiDigit(token.charAt(end))) {
                end++;
            }
            // 后面紧跟小写说明只是碰巧同形（如 OAuthorize），不能当作固定写法
            if (end < token.length() && kindAt(token, end) == Kind.LOWER) {
                continue;
            }
            return end;
        }
        return -1;
    }

    /** i 处是否开始一个新词；wordStart 为当前词起点（保证 i > wordStart）。 */
    private static boolean isBoundary(String token, int wordStart, int i) {
        int prevIndex = token.offsetByCodePoints(i, -1);
        Kind prev = kindAt(token, prevIndex);
        Kind current = kindAt(token, i);
        switch (current) {
            case UPPER:
                if (prev == Kind.LOWER || prev == Kind.CASELESS) {
                    return true;
                }
                if (prev == Kind.DIGIT) {
                    // 纯数字开头的词（如 2FA）不在数字后断开，否则会切出孤零零的「2」
                    return hasNonDigit(token, wordStart, i);
                }
                if (prev == Kind.UPPER) {
                    int next = i + Character.charCount(token.codePointAt(i));
                    return next < token.length() && kindAt(token, next) == Kind.LOWER;
                }
                return false;
            case LOWER:
                return prev == Kind.CASELESS;
            case CASELESS:
                if (prev == Kind.DIGIT) {
                    return hasNonDigit(token, wordStart, i);
                }
                return prev == Kind.UPPER || prev == Kind.LOWER;
            default:
                // 数字总是粘在前一个词上
                return false;
        }
    }

    private static boolean hasNonDigit(String token, int from, int to) {
        for (int k = from; k < to; k++) {
            if (!Character.isDigit(token.charAt(k))) {
                return true;
            }
        }
        return false;
    }

    private static Kind kindAt(String s, int index) {
        int cp = s.codePointAt(index);
        if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) {
            return Kind.UPPER;
        }
        if (Character.isLowerCase(cp)) {
            return Kind.LOWER;
        }
        if (Character.isDigit(cp)) {
            return Kind.DIGIT;
        }
        if (Character.isLetter(cp)) {
            return Kind.CASELESS;
        }
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) {
            // 组合附加符号（如 e + U+0301）跟随前一个字符，归为小写以免在中间断开
            return Kind.LOWER;
        }
        return Kind.SEPARATOR;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
