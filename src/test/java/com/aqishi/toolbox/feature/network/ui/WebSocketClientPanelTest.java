package com.aqishi.toolbox.feature.network.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class WebSocketClientPanelTest {

    @Test
    public void testWebSocketClientPanelInstantiation() {
        WebSocketClientPanel panel = new WebSocketClientPanel();
        assertEquals("misc", panel.getGroup());
        assertEquals("websocket.client", panel.getName());
        assertNotNull(panel.getView());
    }
}
