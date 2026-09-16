package com.aqishi.toolbox.feature.codec.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class XPathPanelTest {

    @Test
    void buildsViewWithCatalogIdentity() {
        XPathPanel panel = new XPathPanel();

        assertEquals("codec", panel.getGroup());
        assertEquals("xpath.tool", panel.getName());
        assertNotNull(panel.getView());
    }
}
