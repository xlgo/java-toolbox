package com.aqishi.toolbox.feature.network.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class NetDiagnosticsPanelTest {

    @Test
    void scannerPrefillKeepsTheActualIpAndHostnameForSniWithoutRunningRequests() throws Exception {
        NetDiagnosticsPanel panel = new NetDiagnosticsPanel();
        try {
            javax.swing.SwingUtilities.invokeAndWait(
                    () -> panel.useEndpoint("127.0.0.1", 8443, "service.example", 100));
            assertEquals(
                    "127.0.0.1",
                    field(panel, "tlsHostField", javax.swing.JTextField.class).getText());
            assertEquals(
                    "service.example",
                    field(panel, "tlsSniField", javax.swing.JTextField.class).getText());
            assertEquals(
                    8443, field(panel, "tlsPortSpinner", javax.swing.JSpinner.class).getValue());
            assertEquals(
                    "https://127.0.0.1:8443/",
                    field(panel, "httpUrlField", javax.swing.JTextField.class).getText());
            assertEquals(
                    null,
                    field(panel, "tlsWorker", java.util.concurrent.atomic.AtomicReference.class)
                            .get());
        } finally {
            panel.closeResources();
        }
    }

    private static <T> T field(Object panel, String name, Class<T> type) throws Exception {
        var field = NetDiagnosticsPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(panel));
    }

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
