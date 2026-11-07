package com.aqishi.toolbox.feature.codec.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PDF 页面范围解析工具。
 *
 * <p>支持解析类似 "1-3, 5, 8-10" 的页码范围，页码为 1-based。
 * 若表达式为空或 null，则返回全部页面。</p>
 */
public final class PageRangeParser {

    private PageRangeParser() {
    }

    /**
     * 解析页面范围。
     *
     * @param rangeExpression 页面范围表达式，例如 "1-5", "1,3,5-7"，为空则代表全部
     * @param totalPages      PDF 总页数
     * @return 按用户指定顺序排列的页码列表（1-based）
     * @throws IllegalArgumentException 当页码超出有效范围 [1, totalPages] 或格式错误时抛出
     */
    public static List<Integer> parse(String rangeExpression, int totalPages) {
        if (totalPages <= 0) {
            return Collections.emptyList();
        }
        if (rangeExpression == null || rangeExpression.trim().isEmpty() || "全部".equalsIgnoreCase(rangeExpression.trim()) || "all".equalsIgnoreCase(rangeExpression.trim())) {
            List<Integer> all = new ArrayList<>(totalPages);
            for (int i = 1; i <= totalPages; i++) {
                all.add(i);
            }
            return all;
        }

        String cleaned = rangeExpression.trim().replace('，', ',');
        String[] tokens = cleaned.split(",");
        Set<Integer> result = new LinkedHashSet<>();

        for (String token : tokens) {
            String part = token.trim();
            if (part.isEmpty()) {
                continue;
            }

            int dashIdx = part.indexOf('-');
            if (dashIdx < 0) {
                // 单页
                int page = parsePageNumber(part);
                validatePage(page, totalPages);
                result.add(page);
            } else {
                // 范围，如 "1-5"
                String startStr = part.substring(0, dashIdx).trim();
                String endStr = part.substring(dashIdx + 1).trim();

                int start = startStr.isEmpty() ? 1 : parsePageNumber(startStr);
                int end = endStr.isEmpty() ? totalPages : parsePageNumber(endStr);

                validatePage(start, totalPages);
                validatePage(end, totalPages);

                if (start <= end) {
                    for (int p = start; p <= end; p++) {
                        result.add(p);
                    }
                } else {
                    for (int p = start; p >= end; p--) {
                        result.add(p);
                    }
                }
            }
        }

        return new ArrayList<>(result);
    }

    private static int parsePageNumber(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的页码数字: \"" + text + "\"");
        }
    }

    private static void validatePage(int page, int totalPages) {
        if (page < 1 || page > totalPages) {
            throw new IllegalArgumentException("页码 " + page + " 超出范围 (有效页码: 1 ~ " + totalPages + ")");
        }
    }
}
