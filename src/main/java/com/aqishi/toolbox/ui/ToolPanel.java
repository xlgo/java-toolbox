package com.aqishi.toolbox.ui;

import javax.swing.*;

/**
 * 工具面板基类。每个具体工具继承此类并实现 {@link #build()}。
 * <p>分组 + 名称用于左侧导航展示，{@code build()} 返回的组件会放进右侧内容区。</p>
 */
public abstract class ToolPanel {

    private final String group;
    private final String name;
    private JComponent view;

    protected ToolPanel(String group, String name) {
        this.group = group;
        this.name = name;
    }

    public String getGroup() {
        return group;
    }

    public String getName() {
        return name;
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
