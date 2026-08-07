package com.aqishi.toolbox.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class QrCodePanelTest {

    @Test
    public void testQrCodePanelInstantiation() {
        QrCodePanel panel = new QrCodePanel();
        assertEquals("misc", panel.getGroup());
        assertEquals("qrcode", panel.getName());
        assertNotNull(panel.getView());
    }
}
