package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首页卡片视图测试：全部 / 分类 / 收藏 / 最近 / 搜索五种视图，以及卡片的打开回调。
 */
class LauncherPanelTest {

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
    void showsEveryToolAsACardByDefault() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            assertEquals(4, tiles(launcher).size(), "四个工具都应有卡片");
            assertNotNull(tile(launcher, "json.format"));
            assertNotNull(tile(launcher, "jsonpath.tester"));
            assertEquals(ToolSidebar.ALL, launcher.getCategory());
        });
    }

    @Test
    void filterShowsOnlyTheSelectedCategory() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            launcher.setCategory("codec");
            assertEquals("codec", launcher.getCategory());
            assertEquals(
                    new LinkedHashSet<>(Arrays.asList(
                            "json.format", "jsonpath.tester", "regex.tester")),
                    tileIds(launcher));
        });
    }

    @Test
    void searchMatchesChineseNamesAndShowsEmptyState() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            launcher.setQuery("正则");
            assertEquals(Set.of("regex.tester"), tileIds(launcher));

            launcher.setQuery("no-such-tool-xyz");
            assertTrue(tiles(launcher).isEmpty());
            assertNotNull(labelContaining(launcher, I18n.get("home.empty.title")), "空态必须有提示");
        });
    }

    @Test
    void searchMatchesEnglishKeywords() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            launcher.setQuery("regexp");
            assertEquals(Set.of("regex.tester"), tileIds(launcher), "英文关键词也应命中");
        });
    }

    @Test
    void favoritesArePersistedAndFilterable() throws Exception {
        onEdt(() -> {
            ToolShortcuts shortcuts = ToolShortcuts.load();
            LauncherPanel launcher = new LauncherPanel(model(), shortcuts, id -> { });
            LauncherCard card = tile(launcher, "json.format");
            assertNotNull(card);
            card.setFavorite(true, true);

            assertEquals("json.format", ConfigManager.get("nav.favorites", ""));
            assertTrue(shortcuts.isFavorite("json.format"));

            launcher.setCategory(ToolSidebar.FAVORITES);
            assertEquals(Set.of("json.format"), tileIds(launcher));
        });
    }

    @Test
    void favoriteStateSurvivesARebuild() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            tile(launcher, "jsonpath.tester").setFavorite(true, true);

            // 切换分类会重建卡片，收藏状态必须从持久化里恢复
            launcher.setCategory("codec");
            launcher.setCategory(ToolSidebar.ALL);
            LauncherCard restored = tile(launcher, "jsonpath.tester");
            assertNotNull(restored);
            assertTrue(restored.isFavorite());
        });
    }

    @Test
    void recentlyOpenedToolsAreListed() throws Exception {
        onEdt(() -> {
            ToolShortcuts shortcuts = ToolShortcuts.load();
            shortcuts.markOpened("regex.tester");
            shortcuts.markOpened("http.client");
            LauncherPanel launcher = new LauncherPanel(model(), shortcuts, id -> { });
            launcher.setCategory(ToolSidebar.RECENT);

            List<String> order = new ArrayList<>();
            for (LauncherCard card : tiles(launcher)) {
                order.add(card.getToolId());
            }
            assertEquals(Arrays.asList("http.client", "regex.tester"), order, "最近打开应置顶排序");
        });
    }

    @Test
    void clickingACardReportsTheStableToolId() throws Exception {
        AtomicReference<String> opened = new AtomicReference<>();
        onEdt(() -> {
            LauncherPanel launcher = launcher(opened::set);
            tile(launcher, "regex.tester").doClick(0);
        });
        assertEquals("regex.tester", opened.get());
    }

    @Test
    void cardsCarryTheToolSummaryAndCategoryTag() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            LauncherCard card = tile(launcher, "json.format");
            assertNotNull(card);
            assertTrue(card.getAccessibleContext().getAccessibleName().startsWith("JSON 格式化"));
            String expected = I18n.get("tool.json.format.summary");
            if ("tool.json.format.summary".equals(expected)) {
                expected = I18n.get("tool.summary.generic");
            }
            assertTrue(card.getToolTipText().contains(expected),
                    "卡片说明应来自工具摘要: " + card.getToolTipText());
        });
    }

    // ---------------------------------------------------------------- 辅助

    private static LauncherPanel launcher(java.util.function.Consumer<String> open) {
        return new LauncherPanel(model(), ToolShortcuts.load(), open);
    }

    private static ToolNavigationModel model() {
        List<ToolPanel> tools = Arrays.asList(
                tool("codec", "json.format", "JSON 格式化", "json", "pretty"),
                tool("codec", "jsonpath.tester", "JSONPath 提取", "jsonpath"),
                tool("codec", "regex.tester", "正则测试", "regexp", "正则"),
                tool("network", "http.client", "HTTP 接口测试", "http", "request"));
        return new ToolNavigationModel(tools);
    }

    private static ToolPanel tool(String group, String id, String label, String... keywords) {
        return new ToolPanel(TestDescriptors.of(group, id, keywords)) {
            @Override public String getLabel() { return label; }
            @Override public String getGroupLabel() { return group; }
            @Override protected JComponent build() { return new JPanel(); }
        };
    }

    private static Set<String> tileIds(Container root) {
        Set<String> ids = new LinkedHashSet<>();
        for (LauncherCard card : tiles(root)) {
            ids.add(card.getToolId());
        }
        return ids;
    }

    private static List<LauncherCard> tiles(Container root) {
        List<LauncherCard> found = new ArrayList<>();
        collect(root, LauncherCard.class, found);
        return found;
    }

    private static <T extends Component> void collect(Container root, Class<T> type, List<T> target) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) target.add(type.cast(child));
            if (child instanceof Container) collect((Container) child, type, target);
        }
    }

    private static LauncherCard tile(Container root, String toolId) {
        for (LauncherCard card : tiles(root)) {
            if (card.getToolId().equals(toolId)) return card;
        }
        return null;
    }

    private static JLabel labelContaining(Container root, String text) {
        List<JLabel> labels = new ArrayList<>();
        collect(root, JLabel.class, labels);
        for (JLabel label : labels) {
            if (label.getText() != null && label.getText().contains(text)) return label;
        }
        return null;
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
}
