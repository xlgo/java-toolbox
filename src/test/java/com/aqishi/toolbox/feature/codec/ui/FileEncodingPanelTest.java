package com.aqishi.toolbox.feature.codec.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileEncodingPanelTest {

    @Test
    void buildsViewWithCatalogIdentity() throws Exception {
        FileEncodingPanel panel = new FileEncodingPanel();
        assertEquals("codec", panel.getGroup());
        assertEquals("file.encoding", panel.getName());
        SwingUtilities.invokeAndWait(() -> {
            assertNotNull(panel.getView());
            JTabbedPane tabs = find(panel.getView(), JTabbedPane.class);
            assertNotNull(tabs);
            assertEquals(3, tabs.getTabCount());
        });
        panel.closeResources();
        // 可重复调用
        panel.closeResources();
    }

    @Test
    void parsesHexInVariousNotations() {
        byte[] expected = {(byte) 0xE4, (byte) 0xB8, (byte) 0xAD};
        assertArrayEquals(expected, FileEncodingPanel.parseHex("E4 B8 AD"));
        assertArrayEquals(expected, FileEncodingPanel.parseHex("e4b8ad"));
        assertArrayEquals(expected, FileEncodingPanel.parseHex("0xE4, 0xB8, 0xAD"));
        assertArrayEquals(expected, FileEncodingPanel.parseHex("\\xE4\\xB8\\xAD"));
        assertNull(FileEncodingPanel.parseHex("E4 B"));
        assertNull(FileEncodingPanel.parseHex("zz"));
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container) {
                T nested = find((Container) child, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
