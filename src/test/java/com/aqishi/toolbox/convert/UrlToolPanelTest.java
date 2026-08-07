package com.aqishi.toolbox.convert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UrlToolPanelTest {

    @Test
    public void testUrlToolPanelInstantiation() {
        UrlToolPanel panel = new UrlToolPanel();
        assertEquals("convert", panel.getGroup());
        assertEquals("url.tool", panel.getName());
        assertNotNull(panel.getView());
    }
}
