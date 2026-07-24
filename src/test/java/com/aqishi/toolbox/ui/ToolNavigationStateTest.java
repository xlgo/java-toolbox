package com.aqishi.toolbox.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolNavigationStateTest {

    @Test
    void clampsSidebarWidthToSupportedRange() {
        assertEquals(190, ToolNavigationState.clampSidebarWidth(100));
        assertEquals(228, ToolNavigationState.clampSidebarWidth(228));
        assertEquals(320, ToolNavigationState.clampSidebarWidth(500));
    }

    @Test
    void resolvesSavedToolOrFallsBackToFirstTool() {
        ToolNavigationModel model = new ToolNavigationModel(Arrays.asList(
                tool("crypto", "hash.codec"),
                tool("convert", "radix.encoding")));

        assertEquals("radix.encoding", ToolNavigationState.resolveToolId(model, "radix.encoding"));
        assertEquals("hash.codec", ToolNavigationState.resolveToolId(model, "removed.tool"));
        assertEquals("hash.codec", ToolNavigationState.resolveToolId(model, null));
    }

    @Test
    void distinguishesMissingExpansionStateFromIntentionallyEmptyState() {
        LinkedHashSet<String> valid = new LinkedHashSet<>(Arrays.asList("crypto", "convert"));

        assertEquals(valid, ToolNavigationState.parseExpandedGroups(null, valid));
        assertEquals(Collections.emptySet(), ToolNavigationState.parseExpandedGroups("", valid));
        assertEquals(
                new LinkedHashSet<>(Collections.singletonList("convert")),
                ToolNavigationState.parseExpandedGroups("removed,convert", valid));
        assertEquals("crypto,convert", ToolNavigationState.serializeExpandedGroups(valid));
    }

    private static ToolPanel tool(String group, String id) {
        return new ToolPanel(group, id) {
            @Override
            protected JComponent build() {
                return new JPanel();
            }
        };
    }
}
