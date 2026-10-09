package com.aqishi.toolbox.util;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 通用字符串搜索匹配器，支持普通模糊匹配、通配符（* 和 ?）匹配以及高级正则表达式匹配。
 *
 * <p>匹配策略与优先级：</p>
 * <ol>
 *     <li><b>空串 / 空白</b>：匹配所有目标；</li>
 *     <li><b>正则模式</b>（以 {@code regex:} 或 {@code r:} 开头）：
 *         使用 Java 正则表达式进行查找匹配（大小写不敏感）；输入中的半成品正则语法错误时优雅降级；</li>
 *     <li><b>通配符模式</b>（包含 {@code *} 或 {@code ?}）：
 *         {@code *} 代表 0 个或多个任意字符，{@code ?} 代表单个任意字符，
 *         对模式内普通字符及元字符做严格转义，进行全字忽略大小写匹配（如 {@code order*}、{@code *consumer}）；</li>
 *     <li><b>普通多词 / 模糊匹配</b>：
 *         若包含空格，按空格切分为多个关键词，要求同时包含（AND 模式，忽略大小写）；
 *         若为单一词汇，则进行标准子串包含（contains，忽略大小写）。</li>
 * </ol>
 */
public final class SearchMatcher implements Predicate<String> {

    private final String patternText;
    private final Predicate<String> delegate;

    private SearchMatcher(String patternText, Predicate<String> delegate) {
        this.patternText = patternText;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 根据搜索文本构造匹配器。
     *
     * @param query 用户输入的搜索过滤文本
     * @return 对应的匹配器实例
     */
    public static SearchMatcher of(String query) {
        if (query == null) {
            return new SearchMatcher("", target -> true);
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return new SearchMatcher(query, target -> true);
        }

        // 1. 正则模式：regex:... 或 r:...
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("regex:") || lower.startsWith("r:")) {
            int prefixLen = lower.startsWith("regex:") ? 6 : 2;
            String regexBody = trimmed.substring(prefixLen).trim();
            if (regexBody.isEmpty()) {
                return new SearchMatcher(query, target -> true);
            }
            try {
                Pattern pattern = Pattern.compile(regexBody, Pattern.CASE_INSENSITIVE);
                return new SearchMatcher(query, target -> target != null && pattern.matcher(target).find());
            } catch (PatternSyntaxException ex) {
                // 正则语法尚未输入完整或非法时，容错退化为子串包含匹配
                String fallback = regexBody.toLowerCase();
                return new SearchMatcher(query, target -> target != null && target.toLowerCase().contains(fallback));
            }
        }

        // 2. 通配符模式：包含 '*' 或 '?'
        if (trimmed.indexOf('*') >= 0 || trimmed.indexOf('?') >= 0) {
            Pattern pattern = compileWildcard(trimmed);
            return new SearchMatcher(query, target -> target != null && pattern.matcher(target).matches());
        }

        // 3. 普通模式：如果包含空格，按多关键词 AND 匹配；否则按包含子串匹配
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length > 1) {
            String[] lowerTokens = new String[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                lowerTokens[i] = tokens[i].toLowerCase();
            }
            return new SearchMatcher(query, target -> {
                if (target == null) {
                    return false;
                }
                String lowerTarget = target.toLowerCase();
                for (String t : lowerTokens) {
                    if (!lowerTarget.contains(t)) {
                        return false;
                    }
                }
                return true;
            });
        }

        String single = trimmed.toLowerCase();
        return new SearchMatcher(query, target -> target != null && target.toLowerCase().contains(single));
    }

    /**
     * 将通配符表达式（* 与 ?）转换为严格全字匹配的正则。
     */
    private static Pattern compileWildcard(String expr) {
        StringBuilder sb = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '*') {
                if (literal.length() > 0) {
                    sb.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                // 折叠连续的 *，避免 ReDoS
                while (i + 1 < expr.length() && expr.charAt(i + 1) == '*') {
                    i++;
                }
                sb.append(".*");
            } else if (c == '?') {
                if (literal.length() > 0) {
                    sb.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                sb.append(".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            sb.append(Pattern.quote(literal.toString()));
        }
        sb.append("$");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    @Override
    public boolean test(String target) {
        return delegate.test(target);
    }

    /**
     * 等同于 {@link #test(String)}。
     */
    public boolean matches(String target) {
        return test(target);
    }

    /**
     * 获取原始模式输入文本。
     */
    public String patternText() {
        return patternText;
    }
}
