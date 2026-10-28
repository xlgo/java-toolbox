package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 侧栏导航测试。
 *
 * <p>效果页的侧栏是「视图入口 + 分类」，不再逐个列出工具：这里验证三档视图入口、
 * 分类计数、选中高亮与折叠开关，以及收藏数量随 {@link ToolShortcuts} 实时刷新。</p>
 */
class ToolSidebarTest {

    @TempDir
    Path temp;

    private VaultUiTestSupport support;
    private AutoCloseable configScope;

    @BeforeEach
    void setUp() throws Exception {
        support = new VaultUiTestSupport(temp.resolve("profile"));
        configScope = ConfigManagerTestSupport.install(support.paths());
        ConfigManager.set("nav.favorites", "");
        ConfigManager.set("nav.recent", "");
        ConfigManager.set("locale", "zh_CN");
        I18n.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (configScope != null) configScope.close();
        if (support != null) support.close();
    }

    @Test
    void listsTheThreeViewsAndEveryCategory() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar(ToolShortcuts.load(), ignored -> { });
            assertNotNull(named(sidebar, "nav.category.all"));
            assertNotNull(named(sidebar, "nav.category.favorites"));
            assertNotNull(named(sidebar, "nav.category.recent"));
            assertNotNull(named(sidebar, "nav.category.crypto"));
            assertNotNull(named(sidebar, "nav.category.convert"));
            assertNotNull(named(sidebar, "nav.category.codec"));
            assertNotNull(named(sidebar, "nav.category.network"));
            assertNull(find(sidebar, JButton.class,
                    button -> button.getName() != null && button.getName().startsWith("nav.tool.")),
                    "工具入口改由首页卡片承担，侧栏不再平铺工具");
        });
    }

    @Test
    void clickingACategoryReportsItsId() throws Exception {
        AtomicReference<String> selected = new AtomicReference<>();
        onEdt(() -> {
            ToolSidebar sidebar = sidebar(ToolShortcuts.load(), selected::set);
            named(sidebar, "nav.category.codec").doClick(0);
        });
        assertEquals("codec", selected.get());
    }

    @Test
    void activeEntryFollowsTheRequestedCategory() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar(ToolShortcuts.load(), ignored -> { });
            sidebar.setActiveCategory("convert");
            assertEquals("convert", sidebar.getActiveCategory());
            assertActiveBackground(sidebar, "nav.category.convert");
            assertTrue(!isActive(sidebar, "nav.category.all"), "其它入口不应保持高亮");
        });
    }

    @Test
    void favoriteAndRecentCountsFollowTheShortcutStore() throws Exception {
        onEdt(() -> {
            ToolShortcuts shortcuts = ToolShortcuts.load();
            ToolSidebar sidebar = sidebar(shortcuts, ignored -> { });
            assertEquals("", badgeText(sidebar, "nav.category.favorites"));

            shortcuts.toggleFavorite("regex.tester");
            assertEquals("1", badgeText(sidebar, "nav.category.favorites"));

            shortcuts.markOpened("http.client");
            assertEquals("1", badgeText(sidebar, "nav.category.recent"));
        });
    }

    @Test
    void categoryBadgeCountsItsTools() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar(ToolShortcuts.load(), ignored -> { });
            assertEquals("2", badgeText(sidebar, "nav.category.codec"));
            assertEquals("5", badgeText(sidebar, "nav.category.all"));
        });
    }

    @Test
    void selectionSurvivesALabelRefresh() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar(ToolShortcuts.load(), ignored -> { });
            sidebar.setActiveCategory("network");
            sidebar.refreshLabels();
            assertEquals("network", sidebar.getActiveCategory());
            assertActiveBackground(sidebar, "nav.category.network");
        });
    }

    @Test
    void collapseButtonReportsToItsListener() throws Exception {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        onEdt(() -> {
            ToolSidebar sidebar = new ToolSidebar(model(), ToolShortcuts.load(), ignored -> { },
                    () -> calls.updateAndGet(value -> value + 1));
            named(sidebar, "workbench.sidebarCollapse").doClick(0);
        });
        assertEquals(1, calls.get());
    }

    @Test
    void sidebarDrawsItsNavigationSurface() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar(ToolShortcuts.load(), ignored -> { });
            sidebar.setSize(240, 400);
            layoutAll(sidebar);
            BufferedImage snapshot = new BufferedImage(240, 400, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = snapshot.createGraphics();
            sidebar.paint(graphics);
            graphics.dispose();
            assertEquals(Tokens.navigationBackground().getRGB(), snapshot.getRGB(200, 380));
        });
    }

    // ---------------------------------------------------------------- 辅助

    private static ToolSidebar sidebar(ToolShortcuts shortcuts,
                                       java.util.function.Consumer<String> listener) {
        return new ToolSidebar(model(), shortcuts, listener, () -> { });
    }

    private static ToolNavigationModel model() {
        return new ToolNavigationModel(Arrays.asList(
                tool("crypto", "hash.codec", "哈希计算", "安全", "digest"),
                tool("convert", "radix.encoding", "进制转换", "编码", "binary"),
                tool("codec", "regex.tester", "正则测试", "编码", "regexp", "正则"),
                tool("codec", "json.format", "JSON 格式化", "编码", "json"),
                tool("network", "http.client", "HTTP 接口测试", "网络", "http", "request")));
    }

    private static ToolPanel tool(
            String group, String id, String label, String groupLabel, String... keywords) {
        return new ToolPanel(TestDescriptors.of(group, id, keywords)) {
            @Override public String getLabel() { return label; }
            @Override public String getGroupLabel() { return groupLabel; }
            @Override protected JComponent build() { return new JPanel(); }
        };
    }

    private static boolean isActive(Container root, String name) {
        JButton button = named(root, name);
        return Boolean.TRUE.equals(button.getClientProperty("active"));
    }

    private static void assertActiveBackground(Container root, String name) {
        JButton item = named(root, name);
        item.setSize(220, 34);
        BufferedImage snapshot = new BufferedImage(220, 34, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = snapshot.createGraphics();
        item.paint(graphics);
        graphics.dispose();
        assertEquals(Tokens.activeSurface().getRGB(), snapshot.getRGB(214, 16),
                "选中行应铺强调色浅底");
    }

    /** 计数徽标是导航行的第三个子组件（WEST 图标、CENTER 文本、EAST 计数） */
    private static String badgeText(Container root, String name) {
        JButton item = named(root, name);
        assertEquals(3, item.getComponentCount(), "导航行结构应为 图标 + 文本 + 计数");
        return ((JLabel) item.getComponent(2)).getText();
    }

    private static JButton named(Container root, String name) {
        JButton button = find(root, JButton.class, candidate -> name.equals(candidate.getName()));
        assertNotNull(button, "缺少导航项: " + name);
        return button;
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
