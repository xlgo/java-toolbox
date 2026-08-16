package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.UIUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolNavigationState {

    private static final Map<String, String> LEGACY_GROUP_IDS = legacyGroupIds();

    private ToolNavigationState() {
    }

    public static int clampSidebarWidth(int width) {
        return Math.max(
                UIUtils.SIDEBAR_MIN_WIDTH,
                Math.min(UIUtils.SIDEBAR_MAX_WIDTH, width));
    }

    public static String resolveToolId(ToolNavigationModel model, String savedToolId) {
        return model.findTool(savedToolId) != null ? savedToolId : model.getFirstToolId();
    }

    public static LinkedHashSet<String> parseExpandedGroups(
            String encoded, Collection<String> validGroupIds) {
        LinkedHashSet<String> valid = new LinkedHashSet<>(validGroupIds);
        if (encoded == null) {
            return valid;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (encoded.trim().isEmpty()) {
            return result;
        }
        for (String part : encoded.split(",")) {
            String id = migrateGroupId(part.trim());
            if (valid.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    public static String serializeExpandedGroups(Collection<String> groupIds) {
        StringBuilder encoded = new StringBuilder();
        for (String id : groupIds) {
            if (encoded.length() > 0) {
                encoded.append(',');
            }
            encoded.append(id);
        }
        return encoded.toString();
    }

    /**
     * 将旧版本保存的导航分组迁移到当前领域分类；顺序和去重由调用方保持。
     */
    public static String migrateGroupId(String groupId) {
        if (groupId == null) {
            return null;
        }
        String migrated = LEGACY_GROUP_IDS.get(groupId.trim());
        return migrated == null ? groupId.trim() : migrated;
    }

    private static Map<String, String> legacyGroupIds() {
        LinkedHashMap<String, String> ids = new LinkedHashMap<>();
        ids.put("crypto", "security");
        ids.put("convert", "codec");
        ids.put("format", "codec");
        ids.put("dev", "network");
        ids.put("generate", "generation");
        ids.put("calc", "compute");
        ids.put("algo", "compute");
        ids.put("chart", "diagram");
        ids.put("misc", "system");
        ids.put("monitor", "monitor");
        return java.util.Collections.unmodifiableMap(ids);
    }
}
