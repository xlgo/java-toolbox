package com.aqishi.toolbox.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MainFrameStructureTest {

    @Test
    void usesUnifiedSidebarAndLazyContentInsteadOfTabbedNavigation() throws Exception {
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
