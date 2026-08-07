package com.aqishi.toolbox.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class HostsManagerPanelTest {

    @Test
    public void testHostsManagerPanelInstantiation() {
        HostsManagerPanel panel = new HostsManagerPanel();
        assertEquals("misc", panel.getGroup());
        assertEquals("hosts.manager", panel.getName());
        assertNotNull(panel.getView());
    }
}
