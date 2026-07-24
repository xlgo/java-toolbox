package com.aqishi.toolbox.ui;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
