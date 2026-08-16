package com.aqishi.toolbox.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 工具入口的稳定元数据。工具实例由 ToolRegistry 创建，描述对象本身不持有 UI 或外部连接。
 */
public final class ToolDescriptor {

    private final String id;
    private final String categoryId;
    private final String labelKey;
    private final List<String> keywords;

    public ToolDescriptor(String id, String categoryId, String labelKey, List<String> keywords) {
        this.id = requireText(id, "id");
        this.categoryId = requireText(categoryId, "categoryId");
        this.labelKey = requireText(labelKey, "labelKey");
        Objects.requireNonNull(keywords, "keywords");
        List<String> copy = new ArrayList<>();
        for (String keyword : keywords) {
            if (keyword != null && !keyword.trim().isEmpty() && !copy.contains(keyword)) {
                copy.add(keyword);
            }
        }
        this.keywords = Collections.unmodifiableList(copy);
    }

    public String getId() {
        return id;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public String getLabelKey() {
        return labelKey;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
