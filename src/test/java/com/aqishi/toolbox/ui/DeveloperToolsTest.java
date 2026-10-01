package com.aqishi.toolbox.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.kit.TextWorkbench;
import com.aqishi.toolbox.util.I18n;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

class DeveloperToolsTest {
    @Test
    void allNewViewsBuildWithCatalogIdentityAndClose() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    for (ToolPanel panel :
                            List.of(
                                    new com.aqishi.toolbox.feature.codec.ui.StructuredDiffPanel(),
                                    new com.aqishi.toolbox.feature.data.ui.TableSqlPanel(),
                                    new com.aqishi.toolbox.feature.generation.ui.JsonDtoPanel(),
                                    new com.aqishi.toolbox.feature.data.ui.MyBatisLogPanel(),
                                    new com.aqishi.toolbox.feature.system.ui.MavenDependencyPanel(),
                                    new com.aqishi.toolbox.feature.network.ui.SsePanel())) {
                        try {
                            JComponent view = panel.getView();
                            assertSame(view, panel.getView());
                            assertFalse(panel.getLabel().startsWith("tool."));
                            assertNotNull(view);
                        } finally {
                            ((ManagedResourceOwner) panel).closeResources();
                        }
                    }
                });
    }

    @Test
    void offlineWorkbenchRunsSampleOnBackgroundWorker() throws Exception {
        for (ToolPanel panel :
                List.of(
                        new com.aqishi.toolbox.feature.generation.ui.JsonDtoPanel(),
                        new com.aqishi.toolbox.feature.data.ui.MyBatisLogPanel(),
                        new com.aqishi.toolbox.feature.system.ui.MavenDependencyPanel())) {
            AtomicReference<TextWorkbench> workbench = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(
                        () -> {
                            JComponent view = panel.getView();
                            workbench.set(find(view, TextWorkbench.class));
                            JButton button = findButton(view, I18n.get("devtools.run"));
                            assertNotNull(button);
                            button.doClick();
                        });
                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                AtomicReference<String> result = new AtomicReference<>("");
                while (result.get().isEmpty() && System.nanoTime() < end) {
                    SwingUtilities.invokeAndWait(
                            () -> result.set(workbench.get().output.getText()));
                    if (result.get().isEmpty()) Thread.sleep(15);
                }
                assertFalse(result.get().isBlank(), panel.getName());
            } finally {
                SwingUtilities.invokeAndWait(() -> ((ManagedResourceOwner) panel).closeResources());
            }
        }
    }

    private static <T> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container)
            for (Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) return found;
            }
        return null;
    }

    private static JButton findButton(Component component, String text) {
        if (component instanceof JButton b && b.getText().equals(text)) return b;
        if (component instanceof Container c)
            for (Component child : c.getComponents()) {
                var found = findButton(child, text);
                if (found != null) return found;
            }
        return null;
    }
}
