package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一级分类导航的行为测试。
 *
 * <p>重构后侧栏不再承载工具树：它只负责「去哪一组」，工具由主区磁贴承担。
 * 因此这里断言的是分类列表、选中同步、折叠入口与底部设置区，而不再是树的展开状态。</p>
 */
class ToolSidebarTest {

    @Test
    void listsEveryCategoryIncludingAllTools() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            assertNotNull(named(sidebar, "nav.category.all"), "全部工具入口必须存在");
            assertNotNull(named(sidebar, "nav.category.crypto"));
            assertNotNull(named(sidebar, "nav.category.convert"));

            JButton all = named(sidebar, "nav.category.all");
            assertEquals(I18n.get("nav.all"), all.getAccessibleContext().getAccessibleName());
            assertEquals(ToolSidebar.ALL, sidebar.getSelectedCategory(), "默认停在全部工具");
        });
    }

    @Test
    void selectionCallbackUsesStableCategoryId() throws Exception {
        AtomicReference<String> selected = new AtomicReference<>();
        onEdt(() -> {
            ToolSidebar sidebar = new ToolSidebar(model(), selected::set, () -> { });
            named(sidebar, "nav.category.crypto").doClick(0);
        });
        assertEquals("crypto", selected.get());
    }

    @Test
    void selectingAToolMovesTheHighlightToItsCategory() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSelectedTool("radix.encoding");
            assertEquals("convert", sidebar.getSelectedCategory());
            assertEquals("radix.encoding", sidebar.getSelectedToolId());
        });
    }

    @Test
    void repeatedClickOnTheSameCategoryStillNotifies() throws Exception {
        // 用户可能正停在某个工具页，点当前分类就是要回到列表，不能因为「已经是这个分类」而吞掉回调
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        onEdt(() -> {
            ToolSidebar sidebar = new ToolSidebar(model(), id -> calls.updateAndGet(v -> v + 1), () -> { });
            named(sidebar, "nav.category.crypto").doClick(0);
            named(sidebar, "nav.category.crypto").doClick(0);
        });
        assertEquals(2, calls.get());
    }

    @Test
    void usesCompactCollapseControlWithLocalizedDescription() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            JButton collapse = find(sidebar, JButton.class,
                    button -> I18n.get("nav.collapse")
                            .equals(button.getAccessibleContext().getAccessibleName()));
            assertNotNull(collapse);
            assertTrue(collapse.getText() == null || collapse.getText().isEmpty());
            assertNotNull(collapse.getIcon());
            assertTrue(collapse.isFocusPainted());
            assertEquals(I18n.get("nav.collapse"), collapse.getToolTipText());
        });
    }

    @Test
    void selectedCategoryPaintsItsHighlightAcrossTheRow() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSize(240, 520);
            layoutAll(sidebar);
            sidebar.setSelectedCategory("crypto");
            JButton item = named(sidebar, "nav.category.crypto");
            item.setSize(216, Tokens.RAIL_ROW_HEIGHT);

            BufferedImage snapshot = new BufferedImage(
                    item.getWidth(), item.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = snapshot.createGraphics();
            item.paint(graphics);
            graphics.dispose();

            // 行尾远离文字与图标，只可能是高亮底色
            assertEquals(Tokens.accentSoft().getRGB(),
                    snapshot.getRGB(item.getWidth() - 6, item.getHeight() / 2));
        });
    }

    @Test
    void unselectedCategoryKeepsNavigationBackground() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSelectedCategory("crypto");
            JButton other = named(sidebar, "nav.category.convert");
            other.setSize(216, Tokens.RAIL_ROW_HEIGHT);
            BufferedImage snapshot = new BufferedImage(
                    other.getWidth(), other.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = snapshot.createGraphics();
            other.paint(graphics);
            graphics.dispose();
            Color painted = new Color(snapshot.getRGB(other.getWidth() - 6, other.getHeight() / 2), true);
            assertFalse(equalsRgb(Tokens.accentSoft(), painted), "未选中项不应带选中底色");
        });
    }

    @Test
    void refreshLabelsKeepsTheCurrentCategory() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSelectedCategory("convert");
            sidebar.refreshLabels();
            assertEquals("convert", sidebar.getSelectedCategory());
            assertNotNull(named(sidebar, "nav.category.convert"));
        });
    }

    @Test
    void footerHostsThemeControls() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            JPanel footer = new JPanel();
            JLabel probe = new JLabel("外观");
            footer.add(probe);
            sidebar.setFooter(footer);
            assertNotNull(find(sidebar, JLabel.class, label -> "外观".equals(label.getText())));
        });
    }

    @Test
    void focusSearchDelegatesToTheInjectedAction() throws Exception {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSearchAction(() -> calls.updateAndGet(v -> v + 1));
            sidebar.focusSearch();
        });
        assertEquals(1, calls.get());
    }

    @Test
    void sidebarDrawsItsNavigationSurface() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSize(240, 400);
            layoutAll(sidebar);
            BufferedImage snapshot = new BufferedImage(240, 400, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = snapshot.createGraphics();
            sidebar.paint(graphics);
            graphics.dispose();
            assertEquals(Tokens.navigationBackground().getRGB(),
                    snapshot.getRGB(200, 380));
        });
    }

    // ---------------------------------------------------------------- 辅助

    private static boolean equalsRgb(Color expected, Color actual) {
        return expected.getRGB() == actual.getRGB();
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

    private static JButton named(Container root, String name) {
        JButton button = find(root, JButton.class, candidate -> name.equals(candidate.getName()));
        assertNotNull(button, "缺少导航项: " + name);
        return button;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        return find(root, type, ignored -> true);
    }

    private static <T extends Component> T find(Container root, Class<T> type, Predicate<T> matches) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && matches.test(type.cast(child))) return type.cast(child);
            if (child instanceof Container) {
                T nested = find((Container) child, type, matches);
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

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
}
