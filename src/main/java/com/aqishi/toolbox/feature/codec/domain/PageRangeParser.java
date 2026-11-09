package com.aqishi.toolbox.feature.codec.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 页面范围解析工具。
 *
 * <p>支持解析类似 "1-3, 5, 8-10" 的页码范围，页码为 1-based。
 * 若表达式为空或 null，则返回全部页面。</p>
 *
 * <p>页码按用户指定顺序保留，<b>不做去重</b>：写 "1, 1, 2" 就会输出两次第 1 页。
 * 这是刻意为之，页面保留集允许同一页出现多次。</p>
 */
public final class PageRangeParser {

    private PageRangeParser() {
    }

    /**
     * 解析页面范围。
     *
     * @param rangeExpression 页面范围表达式，例如 "1-5", "1,3,5-7"，为空则代表全部
     * @param totalPages      PDF 总页数
     * @return 按用户指定顺序排列的页码列表（1-based，可含重复项）
     * @throws IllegalArgumentException 当页码超出有效范围 [1, totalPages] 或格式错误时抛出
     */
    public static List<Integer> parse(String rangeExpression, int totalPages) {
        if (totalPages <= 0) {
            return new ArrayList<>();
        }
        if (isAllExpression(rangeExpression)) {
            List<Integer> all = new ArrayList<>(totalPages);
            for (int i = 1; i <= totalPages; i++) {
                all.add(i);
            }
            return all;
        }

        String cleaned = rangeExpression.trim().replace('，', ',');
        String[] tokens = cleaned.split(",");
        List<Integer> result = new ArrayList<>();

        for (String token : tokens) {
            String part = token.trim();
            if (part.isEmpty()) {
                continue;
            }

            int dashIdx = part.indexOf('-');
            if (dashIdx < 0) {
                int page = parsePageNumber(part);
                validatePage(page, totalPages);
                result.add(page);
            } else {
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

        return result;
    }

    /**
     * 判断表达式是否代表"全部页面"。集中在此处，避免各面板硬编码中文/英文标记。
     */
    public static boolean isAllExpression(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }
        String text = expression.trim();
        return "\u5168\u90e8".equalsIgnoreCase(text) || "all".equalsIgnoreCase(text);
    }

    /**
     * 将页码列表反向压缩为可编辑文本，例如 [1,2,3,5] → "1-3, 5"。
     * 连续 3 页及以上才折叠成范围；倒序段写成 "5-3"，重复页原样列出，保证往返不丢信息。
     */
    public static String format(List<Integer> pages) {
        if (pages == null || pages.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < pages.size()) {
            int start = pages.get(i);
            int end = start;
            int step = 0;
            int j = i + 1;
            if (j < pages.size()) {
                int next = pages.get(j);
                if (next == start + 1) {
                    step = 1;
                } else if (next == start - 1) {
                    step = -1;
                }
            }
            if (step != 0) {
                while (j < pages.size() && pages.get(j) == end + step) {
                    end = pages.get(j);
                    j++;
                }
            } else {
                j = i + 1;
            }
            if (j - i >= 3) {
                parts.add(start + "-" + end);
            } else {
                for (int k = i; k < j; k++) {
                    parts.add(String.valueOf(pages.get(k)));
                }
            }
            i = j;
        }
        return String.join(", ", parts);
    }

    private static int parsePageNumber(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid page number: \"" + text + "\"");
        }
    }

    private static void validatePage(int page, int totalPages) {
        if (page < 1 || page > totalPages) {
            throw new IllegalArgumentException("Page " + page + " out of range (valid: 1 ~ " + totalPages + ")");
        }
    }
}
