package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外壳结构测试。
 *
 * <p>重构后的主窗口是「顶栏 + 侧栏 + 首页卡片 / 工具页 + 状态栏」：启动后停在首页的
 * 工具卡片网格，点卡片进入工具页，工具页左上角的「返回」回到首页。这里验证的是
 * 这层外壳契约，而不是某个工具表单。</p>
 */
class MainFrameStructureTest {

    @TempDir
    Path temp;

    @Test
    void startsOnTheCardHomeAndKeepsEveryToolReachable() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                assertNotNull(find(frame.getContentPane(), LauncherPanel.class), "首页必须有卡片网格");
                assertNotNull(find(frame.getContentPane(), ToolSidebar.class));
                assertNotNull(find(frame.getContentPane(), TopBar.class));

                LauncherPanel home = find(frame.getContentPane(), LauncherPanel.class);
                assertEquals(frame.toolCount(), cards(home).size(),
                        "每个注册工具都必须有可点击卡片");
                assertNotNull(card(home, "json.format"));

                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertNotNull(host);
                assertEquals(0, host.getComponentCount(), "首页不应预先挂载工具视图");

                assertNotNull(named(frame, "workbench.search", JTextField.class));
                assertNotNull(named(frame, "workbench.clock", JLabel.class));
                assertEquals(I18n.get("nav.all"), named(frame, "home.title", JLabel.class).getText());
            });
        }
    }

    @Test
    void clickingACardOpensTheToolPage() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                LauncherPanel home = find(frame.getContentPane(), LauncherPanel.class);
                card(home, "regex.tester").doClick(0);

                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertTrue(host.isMounted("regex.tester"));
                assertEquals(frame.findTool("regex.tester").getLabel(),
                        named(frame, "workbench.toolTitle", JLabel.class).getText());
                assertNotNull(named(frame, "workbench.toolFavorite", JButton.class));

                ToolSidebar sidebar = find(frame.getContentPane(), ToolSidebar.class);
                assertEquals("regex.tester", sidebar.getCurrentToolId());
                assertEquals("codec", sidebar.getActiveCategory(), "侧栏应高亮工具所属分类");
            });
        }
    }

    @Test
    void backButtonReturnsToTheCardHome() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                LauncherPanel home = find(frame.getContentPane(), LauncherPanel.class);
                card(home, "jsonpath.tester").doClick(0);

                named(frame, "workbench.toolBack", JButton.class).doClick(0);
                assertTrue(home.isVisible(), "返回后应回到卡片首页");
                ToolSidebar sidebar = find(frame.getContentPane(), ToolSidebar.class);
                assertEquals(null, sidebar.getCurrentToolId());
            });
        }
    }

    @Test
    void mountsOnlyTheToolsThatWereActuallyOpened() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertFalse(host.isMounted("http.client"));

                frame.selectTool("jsonpath.tester");
                frame.selectTool("regex.tester");
                assertEquals(2, host.getComponentCount());
                assertFalse(host.isMounted("http.client"));
            });
        }
    }

    @Test
    void sidebarCategorySwitchesTheHomeView() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                named(frame, "nav.category.codec", JButton.class).doClick(0);

                LauncherPanel home = find(frame.getContentPane(), LauncherPanel.class);
                assertEquals("codec", home.getCategory());
                List<LauncherCard> cards = cards(home);
                assertFalse(cards.isEmpty());
                for (LauncherCard card : cards) {
                    assertEquals("codec", frame.findTool(card.getToolId()).getGroup());
                }
            });
        }
    }

    @Test
    void searchShowsResultsOnTheHomeView() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() ->
                    named(fixture.frame, "workbench.search", JTextField.class).setText("正则"));
            // 搜索带 140ms 去抖：在 EDT 之外等待，否则定时器无法触发
            Thread.sleep(320);
            SwingUtilities.invokeAndWait(() -> {
                LauncherPanel home = find(fixture.frame.getContentPane(), LauncherPanel.class);
                assertEquals("正则", home.getQuery());
                assertNotNull(card(home, "regex.tester"));
            });
        }
    }

    @Test
    void favoriteButtonInHeaderPersistsAndUpdatesTheSidebar() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                frame.selectTool("json.format");
                JButton favorite = named(frame, "workbench.toolFavorite", JButton.class);
                favorite.doClick(0);

                assertEquals("json.format", ConfigManager.get("nav.favorites", ""));
                assertTrue(favorite.getText().contains(I18n.get("tool.favorite.on.short")));

                favorite.doClick(0);
                assertEquals("", ConfigManager.get("nav.favorites", ""));
            });
        }
    }

    @Test
    void collapsesAndExpandsTheSidebar() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolSidebar sidebar = find(frame.getContentPane(), ToolSidebar.class);
                JButton toggle = named(frame, "workbench.sidebarToggle", JButton.class);

                toggle.doClick(0);
                assertFalse(sidebar.isVisible());
                toggle.doClick(0);
                assertTrue(sidebar.isVisible());
            });
        }
    }

    @Test
    void keepsDraftsAcrossThemeAndAccentSwitches() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                frame.selectTool("json.format");
                JComponent jsonView = frame.findTool("json.format").getView();
                javax.swing.JTextArea editor = find(jsonView, javax.swing.JTextArea.class);
                if (editor != null) {
                    editor.setText("keep-my-draft");
                }

                named(frame, "top.theme.dark", AbstractButton.class).doClick(0);
                assertTrue(ThemeManager.skin().isDark());
                named(frame, "top.theme.light", AbstractButton.class).doClick(0);
                assertFalse(ThemeManager.skin().isDark());

                assertSame(jsonView, frame.findTool("json.format").getView());
                if (editor != null) {
                    assertEquals("keep-my-draft", editor.getText());
                }
            });
        }
    }

    @Test
    void supportsKeyboardShortcutsForSearchAndSidebar() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                assertNotNull(frame.getRootPane().getActionMap().get("nav.focusSearch"));
                assertNotNull(frame.getRootPane().getActionMap().get("nav.toggleSidebar"));
                assertNotNull(frame.getRootPane().getActionMap().get("nav.clearSearch"));
                assertNotNull(frame.getRootPane().getActionMap().get("nav.quick.1"));
            });
        }
    }

    @Test
    void statusBarNamesTheCurrentToolAndTheme() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                frame.selectTool("json.format");
                JLabel current = named(frame, "workbench.status.current", JLabel.class);
                assertTrue(current.getText().contains(frame.findTool("json.format").getLabel()));
                JLabel theme = named(frame, "workbench.status.theme", JLabel.class);
                assertTrue(theme.getText().contains(ThemeManager.skin().label()));
            });
        }
    }

    @ParameterizedTest(name = "top bar fits {0} at {1} x {2}")
    @CsvSource({
            "zh_CN, 900, 600",
            "en_US, 900, 600",
            "zh_CN, 1180, 760",
            "en_US, 1180, 760"
    })
    void keepsTopBarControlsReachableOnNarrowWindows(String locale, int width, int height)
            throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture(locale)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                frame.setSize(width, height);
                frame.validate();

                JTextField search = named(frame, "workbench.search", JTextField.class);
                AbstractButton theme = named(frame, "top.theme.light", AbstractButton.class);
                JButton appearance = named(frame, "workbench.appearance", JButton.class);
                JButton sidebar = named(frame, "workbench.sidebarToggle", JButton.class);

                for (Component component : new Component[]{search, theme, appearance, sidebar}) {
                    assertWithinContent(frame, component);
                }
                assertTrue(search.getWidth() >= 190, "搜索框必须保持可用宽度, 实际=" + search.getWidth());
                assertTrue(Math.abs(boundsInContent(frame, theme).getCenterY()
                                - boundsInContent(frame, search).getCenterY()) <= 2,
                        "搜索框与主题色卡应保持在同一行");
            });
        }
    }

    // ---------------------------------------------------------------- 断言辅助

    private static List<LauncherCard> cards(Container root) {
        List<LauncherCard> found = new ArrayList<>();
        collect(root, LauncherCard.class, found);
        return found;
    }

    private static LauncherCard card(Container root, String toolId) {
        for (LauncherCard candidate : cards(root)) {
            if (candidate.getToolId().equals(toolId)) return candidate;
        }
        throw new AssertionError("缺少工具卡片: " + toolId);
    }

    private static <T extends Component> void collect(Container root, Class<T> type, List<T> target) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) target.add(type.cast(child));
            if (child instanceof Container) collect((Container) child, type, target);
        }
    }

    private static void assertWithinContent(MainFrame frame, Component component) {
        Rectangle bounds = boundsInContent(frame, component);
        assertTrue(bounds.width > 0 && bounds.height > 0,
                () -> component.getName() + " has no usable bounds: " + bounds);
        assertTrue(new Rectangle(frame.getContentPane().getSize()).contains(bounds),
                () -> component.getName() + " escapes the window content: " + bounds);
    }

    private static Rectangle boundsInContent(MainFrame frame, Component component) {
        return SwingUtilities.convertRectangle(
                component.getParent(), component.getBounds(), frame.getContentPane());
    }

    private static <T extends Component> T named(MainFrame frame, String name, Class<T> type) {
        T component = find(frame.getContentPane(), type, candidate -> name.equals(candidate.getName()));
        assertNotNull(component, "missing named workbench component: " + name);
        return component;
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

    /** Uses the real window/layout managers without showing a desktop window or reading user preferences. */
    private final class WorkbenchFixture implements AutoCloseable {
        private final VaultUiTestSupport support;
        private final AutoCloseable configScope;
        private final String previousTheme = ThemeManager.current().name;
        private final MainFrame frame;

        private WorkbenchFixture(String locale) throws Exception {
            support = new VaultUiTestSupport(temp.resolve("profile"));
            configScope = ConfigManagerTestSupport.install(support.paths());
            ConfigManager.set("locale", locale);
            ConfigManager.set("theme", ThemeManager.LIGHT_THEME);
            ConfigManager.set("accent.color", "");
            ConfigManager.set("nav.favorites", "");
            ConfigManager.set("nav.recent", "");
            ConfigManager.set("nav.category", ToolSidebar.ALL);
            AtomicReference<MainFrame> frameRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                I18n.init();
                ThemeManager.setupDefault();
                MainFrame created = new MainFrame(support.service(), support.clipboard());
                created.addNotify();
                created.validate();
                frameRef.set(created);
            });
            frame = frameRef.get();
            SwingUtilities.invokeAndWait(frame::validate);
        }

        @Override
        public void close() throws Exception {
            try {
                SwingUtilities.invokeAndWait(frame::dispose);
                SwingUtilities.invokeAndWait(() -> {
                    ConfigManager.set("theme", previousTheme);
                    ThemeManager.setupDefault();
                });
            } finally {
                support.close();
                configScope.close();
                SwingUtilities.invokeAndWait(I18n::init);
            }
        }
    }
}
