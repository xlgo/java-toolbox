package com.aqishi.toolbox.feature.codec.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JsonPathPanelTest {

    @Test
    public void testJsonPathPanelInstantiation() {
        JsonPathPanel panel = new JsonPathPanel();
        assertEquals("codec", panel.getGroup());
        assertEquals("jsonpath.tester", panel.getName());
        assertNotNull(panel.getView());
    }
}
