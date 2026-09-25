package com.aqishi.toolbox.feature.codec.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TextEscapePanelTest {

    @Test
    void buildsViewWithCatalogIdentity() {
        TextEscapePanel panel = new TextEscapePanel();

        assertEquals("codec", panel.getGroup());
        assertEquals("text.escape", panel.getName());
        assertNotNull(panel.getView());
        panel.closeResources();
        // 重复调用必须安全
        panel.closeResources();
    }

    @Test
    void lineAndColumnAreOneBased() {
        assertArrayEquals(new int[]{1, 1}, TextEscapePanel.lineAndColumn("abc", 0));
        assertArrayEquals(new int[]{1, 3}, TextEscapePanel.lineAndColumn("abc", 2));
        assertArrayEquals(new int[]{2, 1}, TextEscapePanel.lineAndColumn("ab\ncd", 3));
        assertArrayEquals(new int[]{3, 2}, TextEscapePanel.lineAndColumn("a\n\nxy", 4));
    }
}
