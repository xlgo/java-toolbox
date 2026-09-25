package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.util.Objects;

/**
 * 工具面板基类。每个具体工具继承此类并实现 {@link #build()}。
 *
 * <p>身份元数据（稳定 ID、分类、标签键、搜索关键词）只在 {@code ToolCatalog} 里维护一份，
 * 面板通过构造器传入对应的 {@link ToolDescriptor}，自己不再声明任何一项——
 * 早期每个面板各写一份分类和关键词，目录重排后几乎全部过期而无人察觉。</p>
 *
 * <p>{@code build()} 返回的组件会放进右侧内容区，且只构建一次。</p>
 */
public abstract class ToolPanel {

    private final ToolDescriptor descriptor;
    private JComponent view;

    protected ToolPanel(ToolDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    public String getGroup() {
        return descriptor.getCategoryId();
    }

    public String getName() {
        return descriptor.getId();
    }

    public String[] getSearchKeywords() {
        return descriptor.getKeywords().toArray(new String[0]);
    }

    public String getGroupLabel() {
        return I18n.get("group." + descriptor.getCategoryId());
    }

    public String getLabel() {
        return I18n.get(descriptor.getLabelKey());
    }

    /**
     * 判断当前工具是否与搜索查询匹配（大小写不敏感）。
     * 匹配来源：名称、分组名、搜索关键词。
     */
    public boolean matchesSearch(String query) {
        if (query == null) return false;
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return true;
        if (descriptor.getId().toLowerCase(java.util.Locale.ROOT).contains(q)
                || getLabel().toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
        if (descriptor.getCategoryId().toLowerCase(java.util.Locale.ROOT).contains(q)
                || getGroupLabel().toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
        for (String kw : descriptor.getKeywords()) {
            if (kw != null && kw.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    /** 返回面板视图，惰性构建且只构建一次 */
    public final JComponent getView() {
        if (view == null) {
            view = build();
        }
        return view;
    }

    /** 子类实现：构建该工具的 UI */
    protected abstract JComponent build();
}
