package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClasspathDiagnosticPanelTest {

    @Test
    @DisplayName("ClasspathDiagnosticPanel should build UI components cleanly")
    void testPanelLifecycle() {
        ClasspathDiagnosticPanel panel = new ClasspathDiagnosticPanel();
        assertEquals(ToolCatalog.CLASSPATH_DIAGNOSTIC.getId(), panel.getName());

        JComponent comp = panel.getView();
        assertNotNull(comp);

        // 验证生命周期资源释放不会抛异常
        panel.closeResources();
    }
}
