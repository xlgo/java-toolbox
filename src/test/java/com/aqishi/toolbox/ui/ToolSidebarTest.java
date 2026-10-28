package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具目录侧栏的行为测试。
 *
 * <p>侧栏不再是分类入口，而是完整工具列表：这里验证平铺条目、稳定工具 ID、
 * 搜索、收藏持久化与选中高亮。分类本身的顺序由目录模型保证。</p>
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
        ConfigManager.set("locale", "zh_CN");
        I18n.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (configScope != null) configScope.close();
        if (support != null) support.close();
    }

    @Test
    void listsEveryToolAsANavigationRow() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            assertNotNull(named(sidebar, "nav.tool.hash.codec"), "每个工具都应有独立入口");
            assertNotNull(named(sidebar, "nav.tool.radix.encoding"));
            assertNotNull(named(sidebar, "nav.tool.regex.tester"));
            assertNotNull(named(sidebar, "nav.tool.http.client"));
        });
    }

    @Test
    void clickingARowReportsTheStableToolId() throws Exception {
        AtomicReference<String> opened = new AtomicReference<>();
        onEdt(() -> {
            ToolSidebar sidebar = new ToolSidebar(model(), opened::set, () -> { });
            named(sidebar, "nav.tool.regex.tester").doClick(0);
        });
        assertEquals("regex.tester", opened.get());
    }

    @Test
    void selectionHighlightFollowsTheOpenTool() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSelectedTool("radix.encoding");
            assertEquals("radix.encoding", sidebar.getSelectedToolId());
            assertEquals("convert", sidebar.getSelectedCategory());

            JButton item = named(sidebar, "nav.tool.radix.encoding");
            item.setSize(220, 34);
            BufferedImage snapshot = new BufferedImage(220, 34, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = snapshot.createGraphics();
            item.paint(graphics);
            graphics.dispose();

            assertEquals(Tokens.activeSurface().getRGB(), snapshot.getRGB(214, 16));
        });
    }

    @Test
    void searchFiltersChineseNamesAndShowsAnEmptyState() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setQuery("正则");
            assertNotNull(named(sidebar, "nav.tool.regex.tester"));
            assertEquals(null, find(sidebar, JButton.class,
                    button -> "nav.tool.http.client".equals(button.getName())));

            sidebar.setQuery("no-such-tool-xyz");
            assertNotNull(find(sidebar, javax.swing.JLabel.class,
                    label -> I18n.get("nav.empty").equals(label.getText())));
        });
    }

    @Test
    void searchAlsoMatchesEnglishKeywords() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setQuery("regexp");
            assertNotNull(named(sidebar, "nav.tool.regex.tester"));
            assertEquals(null, find(sidebar, JButton.class,
                    button -> "nav.tool.http.client".equals(button.getName())));
        });
    }

    @Test
    void favoritesArePersistedAndToggleableFromTheRowStar() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            JButton star = named(sidebar, "nav.favorite.regex.tester");
            star.doClick(0);

            assertTrue(sidebar.isFavorite("regex.tester"));
            assertEquals("regex.tester", ConfigManager.get("nav.favorites", ""));
            assertNotNull(named(sidebar, "nav.favorite.regex.tester"));
        });
    }

    @Test
    void favoriteStateSurvivesAFilterRebuild() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setFavorite("http.client", true);
            sidebar.setQuery("http");
            sidebar.setQuery("");
            assertTrue(sidebar.isFavorite("http.client"));
        });
    }

    @Test
    void refreshLabelsKeepsTheCurrentSelection() throws Exception {
        onEdt(() -> {
            ToolSidebar sidebar = sidebar();
            sidebar.setSelectedTool("radix.encoding");
            sidebar.refreshLabels();
            assertEquals("radix.encoding", sidebar.getSelectedToolId());
            assertNotNull(named(sidebar, "nav.tool.radix.encoding"));
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
            assertEquals(Tokens.navigationBackground().getRGB(), snapshot.getRGB(200, 200));
        });
    }

    // ---------------------------------------------------------------- 辅助

    private static ToolSidebar sidebar() {
        return new ToolSidebar(model(), id -> { }, () -> { });
    }

    private static ToolNavigationModel model() {
        return new ToolNavigationModel(Arrays.asList(
                tool("crypto", "hash.codec", "哈希计算", "安全", "digest"),
                tool("convert", "radix.encoding", "进制转换", "编码", "binary"),
                tool("codec", "regex.tester", "正则测试", "编码", "regexp", "正则"),
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
