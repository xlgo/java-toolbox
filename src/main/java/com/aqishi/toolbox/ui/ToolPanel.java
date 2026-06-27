package com.aqishi.toolbox.ui;

import javax.swing.*;
import java.util.Arrays;

/**
 * 工具面板基类。每个具体工具继承此类并实现 {@link #build()}。
 * <p>分组 + 名称用于左侧导航展示，{@code build()} 返回的组件会放进右侧内容区。</p>
 * <p>搜索关键词用于模糊匹配，搜索时名称、分组名、关键词均参与匹配。</p>
 */
public abstract class ToolPanel {

    private final String group;
    private final String name;
    private final String[] searchKeywords;
    private JComponent view;

    protected ToolPanel(String group, String name, String... searchKeywords) {
        this.group = group;
        this.name = name;
        this.searchKeywords = searchKeywords;
    }

    public String getGroup() {
        return group;
    }

    public String getName() {
        return name;
    }

    public String[] getSearchKeywords() {
        return searchKeywords;
    }

    /**
     * 判断当前工具是否与搜索查询匹配（大小写不敏感）。
     * 匹配来源：名称、分组名、搜索关键词。
     */
    public boolean matchesSearch(String query) {
        String q = query.toLowerCase();
        if (name.toLowerCase().contains(q)) return true;
        if (group.toLowerCase().contains(q)) return true;
        for (String kw : searchKeywords) {
            if (kw.toLowerCase().contains(q)) return true;
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
