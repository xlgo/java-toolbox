package com.aqishi.toolbox.catalog;

import java.util.Objects;

/**
 * 导航分类的稳定描述。分类 ID 用于持久化和注册表索引，显示文本由 labelKey 提供。
 */
public final class ToolCategory {

    private final String id;
    private final String labelKey;
    private final int order;

    public ToolCategory(String id, String labelKey, int order) {
        this.id = requireText(id, "id");
        this.labelKey = requireText(labelKey, "labelKey");
        if (order < 0) {
            throw new IllegalArgumentException("order must be non-negative");
        }
        this.order = order;
    }

    public String getId() {
        return id;
    }

    public String getLabelKey() {
        return labelKey;
    }

    public int getOrder() {
        return order;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
