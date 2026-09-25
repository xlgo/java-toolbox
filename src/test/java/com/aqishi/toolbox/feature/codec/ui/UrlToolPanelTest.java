package com.aqishi.toolbox.feature.codec.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UrlToolPanelTest {

    @Test
    public void testUrlToolPanelInstantiation() {
        UrlToolPanel panel = new UrlToolPanel();
        assertEquals("codec", panel.getGroup());
        assertEquals("url.tool", panel.getName());
        assertNotNull(panel.getView());
    }
}
