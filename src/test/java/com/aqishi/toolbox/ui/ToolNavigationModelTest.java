package com.aqishi.toolbox.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolNavigationModelTest {

    @Test
    void preservesGroupAndToolInsertionOrder() {
        ToolPanel hash = tool("crypto", "hash.codec", "Hash", "Security", "digest");
        ToolPanel radix = tool("convert", "radix.encoding", "Radix", "Convert", "binary");
        ToolPanel rsa = tool("crypto", "asymmetric.crypto", "RSA", "Security", "signature");

        ToolNavigationModel model = new ToolNavigationModel(Arrays.asList(hash, radix, rsa));

        assertEquals(Arrays.asList("crypto", "convert"), model.getGroupIds());
        assertEquals(Arrays.asList(hash, rsa), model.getGroups().get(0).getTools());
        assertEquals(Arrays.asList(radix), model.getGroups().get(1).getTools());
    }

    @Test
    void filtersByStableIdLocalizedLabelGroupAndKeyword() {
        ToolPanel hash = tool("crypto", "hash.codec", "摘要与编解码", "加密安全", "digest");
        ToolPanel radix = tool("convert", "radix.encoding", "进制与编码", "转换编码", "binary");
        ToolNavigationModel model = new ToolNavigationModel(Arrays.asList(hash, radix));

        assertEquals("hash.codec", onlyToolId(model.filter("HASH")));
        assertEquals("hash.codec", onlyToolId(model.filter("摘要")));
        assertEquals("hash.codec", onlyToolId(model.filter("加密安全")));
        assertEquals("radix.encoding", onlyToolId(model.filter("BINARY")));
        assertEquals(2, model.filter(" ").size());
        assertTrue(model.filter("missing").isEmpty());
    }

    @Test
    void rejectsDuplicateStableToolIds() {
        ToolPanel first = tool("crypto", "same.id", "One", "Security");
        ToolPanel second = tool("convert", "same.id", "Two", "Convert");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolNavigationModel(Arrays.asList(first, second)));

        assertTrue(error.getMessage().contains("same.id"));
    }

    @Test
    void toolPanelSearchHandlesNullAndUsesTrimmedQueries() {
        ToolPanel panel = tool("crypto", "hash.codec", "Hash", "Security", "Digest");

        assertFalse(panel.matchesSearch(null));
        assertTrue(panel.matchesSearch("  DIGEST  "));
        assertTrue(panel.matchesSearch(" "));
    }

    private static String onlyToolId(List<ToolNavigationModel.Group> groups) {
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).getTools().size());
        return groups.get(0).getTools().get(0).getName();
    }

    private static ToolPanel tool(
            String group, String id, String label, String groupLabel, String... keywords) {
        return new ToolPanel(group, id, keywords) {
            @Override
            public String getLabel() {
                return label;
            }

            @Override
            public String getGroupLabel() {
                return groupLabel;
            }

            @Override
            protected JComponent build() {
                return new JPanel();
            }
        };
    }
}
