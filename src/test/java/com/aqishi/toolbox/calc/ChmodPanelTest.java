package com.aqishi.toolbox.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ChmodPanelTest {

    @Test
    public void testChmodPanelInstantiation() {
        ChmodPanel panel = new ChmodPanel();
        assertEquals("calc", panel.getGroup());
        assertEquals("chmod.calc", panel.getName());
        assertNotNull(panel.getView());
    }
}
