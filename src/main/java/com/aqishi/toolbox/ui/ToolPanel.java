package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.util.Arrays;
import java.util.Objects;

/**
 * 工具面板基类。每个具体工具继承此类并实现 {@link #build()}。
 * <p>分组 + 名称用于左侧导航展示，{@code build()} 返回的组件会放进右侧内容区。</p>
 * <p>搜索关键词用于模糊匹配，搜索时名称、分组名、关键词均参与匹配。</p>
 */
public abstract class ToolPanel {

    private ToolDescriptor descriptor;
    private JComponent view;

    /**
     * 兼容旧面板构造器。新面板应传入 ToolDescriptor；注册表会在迁移期间绑定集中目录元数据。
     */
    protected ToolPanel(String group, String name, String... searchKeywords) {
        this.descriptor = new ToolDescriptor(
                name, group, "tool." + name, Arrays.asList(searchKeywords));
    }

    protected ToolPanel(ToolDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    /**
     * 仅供 ToolRegistry 在旧面板迁移期间绑定集中目录元数据。
     */
    public final void bindDescriptor(ToolDescriptor descriptor) {
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
