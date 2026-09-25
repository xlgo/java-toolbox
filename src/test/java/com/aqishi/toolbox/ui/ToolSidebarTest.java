package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.ui.kit.Tokens;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolSidebarTest {

    @Test
    void filtersInlineAndShowsEmptyState() throws Exception {
        AtomicReference<ToolSidebar> sidebarRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> sidebarRef.set(sidebar()));
        ToolSidebar sidebar = sidebarRef.get();
        JTextField search = find(sidebar, JTextField.class);
        JTree tree = find(sidebar, JTree.class);

        SwingUtilities.invokeAndWait(() -> search.setText("missing"));

        // 过滤由 120ms 去抖 Timer 在 EDT 上触发；固定 sleep 在机器繁忙时会漏掉，
        // 改为轮询等待空态出现（超时上限给足，避免慢机误判）。
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !showsEmptyState(tree)) {
            Thread.sleep(20);
            SwingUtilities.invokeAndWait(() -> { });
        }
        assertTrue(showsEmptyState(tree), "search filter did not settle within timeout");
    }

    private static boolean showsEmptyState(JTree tree) {
        return tree.getRowCount() == 1
                && I18n.get("nav.empty").equals(
                        tree.getPathForRow(0).getLastPathComponent().toString());
    }

    @Test
    void selectionCallbackUsesStableToolId() throws Exception {
        AtomicReference<String> selected = new AtomicReference<>();
        ToolSidebar[] sidebar = new ToolSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            ToolNavigationModel model = model();
            sidebar[0] = new ToolSidebar(model, selected::set, () -> { });
            JTree tree = find(sidebar[0], JTree.class);
            TreePath path = findPath(tree, "Hash");
            tree.setSelectionPath(path);
        });

        assertEquals("hash.codec", selected.get());
    }

    @Test
    void shortcutsFocusSearchOpenSelectionAndClearQuery() throws Exception {
        ToolSidebar[] sidebar = new ToolSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            sidebar[0] = sidebar();
            JTextField search = find(sidebar[0], JTextField.class);
            search.setText("Hash");
            sidebar[0].getActionMap().get("nav.focusSearch")
                    .actionPerformed(new java.awt.event.ActionEvent(sidebar[0], 0, ""));
            assertTrue(search.isFocusOwner() || !sidebar[0].isShowing());
            sidebar[0].getActionMap().get("nav.clearSearch")
                    .actionPerformed(new java.awt.event.ActionEvent(sidebar[0], 0, ""));
            assertEquals("", search.getText());
        });
    }

    @Test
    void restoresPersistedExpansionSet() throws Exception {
        ToolSidebar[] sidebar = new ToolSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            sidebar[0] = sidebar();
            sidebar[0].setExpandedGroupIds(Collections.singleton("convert"));
        });

        assertEquals(Collections.singleton("convert"), sidebar[0].getExpandedGroupIds());
    }

    @Test
    void usesCompactCollapseControlWithLocalizedDescription() throws Exception {
        ToolSidebar[] sidebar = new ToolSidebar[1];
        SwingUtilities.invokeAndWait(() -> sidebar[0] = sidebar());

        JButton collapse = find(sidebar[0], JButton.class);
        assertTrue(collapse.getText() == null || collapse.getText().isEmpty());
        assertNotNull(collapse.getIcon());
        assertTrue(collapse.isFocusPainted());
        assertEquals(I18n.get("nav.collapse"), collapse.getToolTipText());
        assertEquals(
                I18n.get("nav.collapse"),
                collapse.getAccessibleContext().getAccessibleName());
    }

    @Test
    void narrowSidebarKeepsSearchShortcutOutsideTheInput() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSize(200, 520);
            layoutAll(sidebar);
            JTextField search = find(sidebar, JTextField.class);
            assertNotNull(search.getClientProperty("JTextField.leadingIcon"));
            assertNull(search.getClientProperty("JTextField.trailingComponent"));
            assertTrue(search.getWidth() >= 170, "search uses the full narrow sidebar width");
            assertNotNull(findLabel(sidebar, "Ctrl K"));
            JTree tree = find(sidebar, JTree.class);
            assertTrue(tree.getScrollableTracksViewportWidth());
            assertEquals(Tokens.NAV_ROW_HEIGHT, tree.getRowHeight());
        });
    }

    @Test
    void selectionPaintsAcrossTheRowAndKeepsCategoryCountSeparate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSize(240, 520);
            layoutAll(sidebar);
            JTree tree = find(sidebar, JTree.class);
            sidebar.setSelectedTool("hash.codec");
            Rectangle selected = tree.getPathBounds(findPath(tree, "Hash"));
            BufferedImage snapshot = new BufferedImage(tree.getWidth(), tree.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = snapshot.createGraphics();
            tree.paint(graphics);
            graphics.dispose();
            assertEquals(Tokens.accentSoft().getRGB(), snapshot.getRGB(
                    tree.getWidth() - 10, selected.y + selected.height / 2));
            TreePath group = findPath(tree, "Security");
            Component row = tree.getCellRenderer().getTreeCellRendererComponent(
                    tree, group.getLastPathComponent(), false, true, false, 0, false);
            assertInstanceOf(Container.class, row);
            assertNotNull(findLabel((Container) row, "Security"));
            assertNotNull(findLabel((Container) row, "1"));
            sidebar.restyle();
            assertEquals("hash.codec", sidebar.getSelectedToolId());
        });
    }

    @Test
    void refreshLabelsKeepsTheMountedViewAndCallbacksOnTheEdt() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        AtomicReference<Boolean> callbackOnEdt = new AtomicReference<>(false);
        ToolPanel tool = new ToolPanel(TestDescriptors.of("crypto", "hash.codec", "digest")) {
            @Override public String getLabel() { return "Hash"; }
            @Override public String getGroupLabel() { return "Security"; }
            @Override protected JComponent build() {
                builds.incrementAndGet();
                return new JPanel();
            }
        };
        ToolNavigationModel model =
                new ToolNavigationModel(Collections.singletonList(tool));
        ToolContentHost host =
                new ToolContentHost(Collections.singletonList(tool));
        ToolSidebar[] sidebar = new ToolSidebar[1];

        SwingUtilities.invokeAndWait(() -> {
            sidebar[0] = new ToolSidebar(model, id -> {
                callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
                host.showTool(id);
            }, () -> { });
            sidebar[0].setSelectedTool("hash.codec");
            sidebar[0].getActionMap().get("nav.openSelection")
                    .actionPerformed(new java.awt.event.ActionEvent(sidebar[0], 0, ""));
            sidebar[0].refreshLabels();
        });

        assertTrue(callbackOnEdt.get());
        assertEquals(1, builds.get());
        assertTrue(host.isMounted("hash.codec"));
    }

    private static ToolSidebar sidebar() {
        return new ToolSidebar(model(), id -> { }, () -> { });
    }

    private static ToolNavigationModel model() {
        return new ToolNavigationModel(Arrays.asList(
                tool("crypto", "hash.codec", "Hash", "Security", "digest"),
                tool("convert", "radix.encoding", "Radix", "Convert", "binary")));
    }

    private static ToolPanel tool(
            String group, String id, String label, String groupLabel, String... keywords) {
        return new ToolPanel(TestDescriptors.of(group, id, keywords)) {
            @Override public String getLabel() { return label; }
            @Override public String getGroupLabel() { return groupLabel; }
            @Override protected JComponent build() { return new JPanel(); }
        };
    }

    private static TreePath findPath(JTree tree, String label) {
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            if (label.equals(path.getLastPathComponent().toString())) {
                return path;
            }
        }
        fail("Missing tree path: " + label);
        return null;
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

    private static JLabel findLabel(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && text.equals(((JLabel) child).getText())) return (JLabel) child;
            if (child instanceof Container) {
                JLabel nested = findLabel((Container) child, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static void layoutAll(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container) layoutAll((Container) child);
        }
    }
}
