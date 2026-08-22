package com.aqishi.toolbox.ui;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 以稳定工具 ID 管理惰性挂载的内容视图。
 *
 * <p>构造时只建立 ID 到 {@link ToolPanel} 的索引；首次显示工具时才创建并挂载
 * Swing 组件。所有公开方法都应在 Swing EDT 上调用，以保证 {@link CardLayout}
 * 和组件树的访问安全。</p>
 */
public final class ToolContentHost extends JPanel {

    private final CardLayout cards = new CardLayout();
    private final Map<String, ToolPanel> toolsById = new LinkedHashMap<>();
    private final Set<String> mountedToolIds = new LinkedHashSet<>();

    public ToolContentHost(List<ToolPanel> tools) {
        super();
        setLayout(cards);
        for (ToolPanel tool : tools) {
            toolsById.put(tool.getName(), tool);
        }
    }

    public boolean showTool(String toolId) {
        ToolPanel tool = toolsById.get(toolId);
        if (tool == null) {
            return false;
        }
        if (mountedToolIds.add(toolId)) {
            add(tool.getView(), toolId);
        }
        cards.show(this, toolId);
        return true;
    }

    public boolean isMounted(String toolId) {
        return mountedToolIds.contains(toolId);
    }
}
