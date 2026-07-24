package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.UIUtils;

import java.util.Collection;
import java.util.LinkedHashSet;

public final class ToolNavigationState {

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
            String id = part.trim();
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
}
