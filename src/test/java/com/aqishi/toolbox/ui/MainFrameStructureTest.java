package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.vault.ApplicationPaths;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MainFrameStructureTest {
    @TempDir
    Path temp;

    @Test
    void usesUnifiedSidebarAndLazyContentInsteadOfTabbedNavigation() throws Exception {
        ApplicationPaths paths = ApplicationPaths.resolve(
                System.getProperty("os.name"), temp.toString(),
                Collections.<String, String>emptyMap(), temp);
        try (AutoCloseable ignored = ConfigManagerTestSupport.install(paths)) {
            AtomicReference<MainFrame> frameRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> frameRef.set(new MainFrame()));
            MainFrame frame = frameRef.get();
            try {
                assertNotNull(find(frame.getContentPane(), ToolSidebar.class));
                assertNotNull(find(frame.getContentPane(), ToolContentHost.class));
                assertNull(find(frame.getContentPane(), JTabbedPane.class));
                assertNotNull(findButton(frame.getContentPane(), "☰"));
            } finally {
                SwingUtilities.invokeAndWait(frame::dispose);
                SwingUtilities.invokeAndWait(() -> { });
            }
        }
    }

    @Test
    void usesOneInjectedVaultSessionForBothSensitiveTools() throws Exception {
        try (VaultUiTestSupport support = new VaultUiTestSupport(temp.resolve("vault"))) {
            AtomicReference<MainFrame> frameRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> frameRef.set(
                    new MainFrame(support.service(), support.clipboard())));
            MainFrame frame = frameRef.get();
            try {
                assertSame(support.service(), frame.getVaultServiceForTest());
            } finally {
                SwingUtilities.invokeAndWait(frame::dispose);
            }
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) {
                T nested = find((Container) child, type);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static JButton findButton(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton nested = findButton((Container) child, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
