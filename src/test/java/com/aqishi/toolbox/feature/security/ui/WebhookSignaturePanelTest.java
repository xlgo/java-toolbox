package com.aqishi.toolbox.feature.security.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WebhookSignaturePanelTest {

    @Test
    void buildsViewWithCatalogIdentity() {
        WebhookSignaturePanel panel = new WebhookSignaturePanel();

        assertEquals("security", panel.getGroup());
        assertEquals("webhook.signature", panel.getName());
        assertNotNull(panel.getView());
    }
}
