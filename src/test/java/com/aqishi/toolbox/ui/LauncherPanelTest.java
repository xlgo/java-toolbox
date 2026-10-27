package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
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
 * 启动器的数据视图测试：分类、搜索、收藏三种过滤方式，以及磁贴的打开回调。
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
        ConfigManager.set("locale", "zh_CN");
        com.aqishi.toolbox.util.I18n.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (configScope != null) configScope.close();
        if (support != null) support.close();
    }

    @Test
    void showsEveryToolAsATileByDefault() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            assertEquals(4, tiles(launcher).size(), "四个工具都应有磁贴");
            assertNotNull(tile(launcher, "json.format"));
            assertNotNull(tile(launcher, "jsonpath.tester"));
            assertNull(launcher.getCategoryFilter());
        });
    }

    @Test
    void filterShowsOnlyTheSelectedCategory() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            launcher.setCategoryFilter("codec");
            assertEquals("codec", launcher.getCategoryFilter());
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
            assertNotNull(labelContaining(launcher,
                    com.aqishi.toolbox.util.I18n.get("launcher.empty")), "空态必须有提示");
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
            LauncherPanel launcher = launcher(id -> { });
            LauncherCard card = tile(launcher, "json.format");
            assertNotNull(card);
            card.setFavorite(true, true);

            assertEquals("json.format", ConfigManager.get("nav.favorites", ""));
            assertTrue(launcher.isFavorite("json.format"));

            launcher.setFavoriteOnly(true);
            assertEquals(Set.of("json.format"), tileIds(launcher));
        });
    }

    @Test
    void favoriteStateSurvivesARebuild() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            tile(launcher, "jsonpath.tester").setFavorite(true, true);

            // 切换分类会重建磁贴，收藏状态必须从持久化里恢复
            launcher.setCategoryFilter("codec");
            launcher.setCategoryFilter(null);
            LauncherCard restored = tile(launcher, "jsonpath.tester");
            assertNotNull(restored);
            assertTrue(restored.isFavorite());
        });
    }

    @Test
    void clickingATileReportsTheStableToolId() throws Exception {
        AtomicReference<String> opened = new AtomicReference<>();
        onEdt(() -> {
            LauncherPanel launcher = launcher(opened::set);
            tile(launcher, "regex.tester").doClick(0);
        });
        assertEquals("regex.tester", opened.get());
    }

    @Test
    void tilesCarryChineseTitleAndEnglishSubtitle() throws Exception {
        onEdt(() -> {
            LauncherPanel launcher = launcher(id -> { });
            LauncherCard card = tile(launcher, "json.format");
            assertNotNull(card);
            assertTrue(card.getAccessibleContext().getAccessibleName().startsWith("JSON 格式化"));
            assertTrue(card.getToolTipText().contains("JSON Formatter"),
                    "中文界面下副标题应是英文名: " + card.getToolTipText());
        });
    }

    // ---------------------------------------------------------------- 辅助

    private static LauncherPanel launcher(java.util.function.Consumer<String> open) {
        return new LauncherPanel(model(), open);
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
