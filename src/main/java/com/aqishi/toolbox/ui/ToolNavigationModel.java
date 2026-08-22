package com.aqishi.toolbox.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具导航的只读索引。
 *
 * <p>输入顺序决定分类和工具的显示顺序；每个稳定工具 ID 必须唯一，重复 ID
 * 会在构造时立即拒绝。{@link #filter(String)} 只返回匹配工具，不会修改原始
 * 分类或已挂载的工具视图。</p>
 */
public final class ToolNavigationModel {

    public static final class Group {
        private final String id;
        private final List<ToolPanel> tools;

        private Group(String id, List<ToolPanel> tools) {
            this.id = id;
            this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return tools.isEmpty() ? id : tools.get(0).getGroupLabel();
        }

        public List<ToolPanel> getTools() {
            return tools;
        }
    }

    private final List<Group> groups;
    private final Map<String, ToolPanel> toolsById;

    public ToolNavigationModel(List<ToolPanel> tools) {
        LinkedHashMap<String, List<ToolPanel>> grouped = new LinkedHashMap<>();
        LinkedHashMap<String, ToolPanel> indexed = new LinkedHashMap<>();
        for (ToolPanel tool : tools) {
            ToolPanel previous = indexed.put(tool.getName(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate tool id: " + tool.getName());
            }
            grouped.computeIfAbsent(tool.getGroup(), key -> new ArrayList<>()).add(tool);
        }

        List<Group> ordered = new ArrayList<>();
        for (Map.Entry<String, List<ToolPanel>> entry : grouped.entrySet()) {
            ordered.add(new Group(entry.getKey(), entry.getValue()));
        }
        groups = Collections.unmodifiableList(ordered);
        toolsById = Collections.unmodifiableMap(indexed);
    }

    public List<Group> getGroups() {
        return groups;
    }

    public List<String> getGroupIds() {
        List<String> ids = new ArrayList<>();
        for (Group group : groups) {
            ids.add(group.getId());
        }
        return Collections.unmodifiableList(ids);
    }

    public Set<String> getToolIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(toolsById.keySet()));
    }

    public ToolPanel findTool(String toolId) {
        return toolId == null ? null : toolsById.get(toolId);
    }

    public String getFirstToolId() {
        return toolsById.isEmpty() ? null : toolsById.keySet().iterator().next();
    }

    public List<Group> filter(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return groups;
        }

        List<Group> matches = new ArrayList<>();
        for (Group group : groups) {
            List<ToolPanel> tools = new ArrayList<>();
            for (ToolPanel tool : group.getTools()) {
                if (tool.matchesSearch(normalized)) {
                    tools.add(tool);
                }
            }
            if (!tools.isEmpty()) {
                matches.add(new Group(group.getId(), tools));
            }
        }
        return Collections.unmodifiableList(matches);
    }
}
