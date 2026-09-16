package com.aqishi.toolbox.feature.network.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NetDiagnosticsPanelTest {

    @Test
    void buildsViewWithCatalogIdentity() {
        NetDiagnosticsPanel panel = new NetDiagnosticsPanel();

        assertEquals("network", panel.getGroup());
        assertEquals("net.diagnostics", panel.getName());
        assertNotNull(panel.getView());
    }

    /** 关闭时必须能重复调用而不抛异常：外壳退出时会无条件调一次。 */
    @Test
    void closesResourcesIdempotently() {
        NetDiagnosticsPanel panel = new NetDiagnosticsPanel();
        panel.getView();

        panel.closeResources();
        panel.closeResources();
    }
}
