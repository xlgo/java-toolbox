package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外壳结构测试。
 *
 * <p>重构后主窗口是「顶栏 + 一级分类 + 卡片启动器/工具页」：工具不再挂在侧栏树上，
 * 而是以磁贴进入。这里验证的是外壳契约——首次进启动器、点磁贴才挂载工具、
 * 返回能回到列表、顶栏控件在窄窗口下不互相挤压。</p>
 */
class MainFrameStructureTest {

    @TempDir
    Path temp;

    @Test
    void startsOnTheLauncherWithoutMountingAnyTool() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                assertNotNull(find(frame.getContentPane(), ToolSidebar.class));
                assertNotNull(find(frame.getContentPane(), TopBar.class));
                assertNotNull(find(frame.getContentPane(), LauncherPanel.class));
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertNotNull(host);
                assertNull(find(frame.getContentPane(), JTabbedPane.class));

                assertEquals(0, host.getComponentCount(), "启动器视图不应预挂载任何工具");
                assertFalse(host.isVisible());
                assertFalse(named(frame, "workbench.back", JButton.class).isVisible(),
                        "启动器不需要返回按钮");
                assertTrue(named(frame, "workbench.search", JTextField.class).isVisible());
                assertTrue(named(frame, "workbench.favorites", javax.swing.AbstractButton.class).isVisible());
                assertTrue(named(frame, "workbench.themeToggle", JButton.class).isVisible());
            });
        }
    }

    @Test
    void restoresTheLastToolAndShowsItsTitle() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", "json.format")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertEquals(1, host.getComponentCount());
                assertTrue(host.isMounted("json.format"));
                assertTrue(host.isVisible());

                JButton back = named(frame, "workbench.back", JButton.class);
                assertTrue(back.isVisible(), "工具页必须能一键回到列表");
                assertNotNull(back.getIcon());
                JLabel title = named(frame, "workbench.title", JLabel.class);
                assertTrue(title.isVisible());
                assertEquals(frame.findTool("json.format").getLabel(), title.getText());
            });
        }
    }

    @Test
    void clickingATileMountsItsToolAndBackReturnsToTheLauncher() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);

                JButton tile = named(frame, "launcher.tile.jsonpath.tester", JButton.class);
                tile.doClick(0);

                assertTrue(host.isMounted("jsonpath.tester"));
                assertTrue(host.isVisible());
                assertEquals(frame.findTool("jsonpath.tester").getLabel(),
                        named(frame, "workbench.title", JLabel.class).getText());

                named(frame, "workbench.back", JButton.class).doClick(0);
                assertFalse(host.isVisible(), "返回后应回到启动器");
                assertFalse(named(frame, "workbench.back", JButton.class).isVisible());
            });
        }
    }

    @Test
    void mountsOnlyTheToolsThatWereActuallyOpened() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertFalse(host.isMounted("http.client"));
                assertFalse(host.isMounted("ssh"));

                frame.selectTool("json.format");
                frame.selectTool("jsonpath.tester");
                assertEquals(2, host.getComponentCount());
                assertFalse(host.isMounted("http.client"));
            });
        }
    }

    @Test
    void categoryNavigationFiltersTheLauncher() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                LauncherPanel launcher = find(frame.getContentPane(), LauncherPanel.class);
                assertNull(launcher.getCategoryFilter(), "全部工具视图不过滤");

                named(frame, "nav.category.codec", JButton.class).doClick(0);
                assertEquals("codec", launcher.getCategoryFilter());

                named(frame, "nav.category.all", JButton.class).doClick(0);
                assertNull(launcher.getCategoryFilter());
            });
        }
    }

    @Test
    void searchFindsToolsAcrossCategories() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                LauncherPanel launcher = find(frame.getContentPane(), LauncherPanel.class);
                launcher.setQuery("JSONPath");
                assertEquals("JSONPath", launcher.getQuery());
                assertTrue(launcher.isVisible());
                launcher.setQuery("");
                assertEquals("", launcher.getQuery());
            });
        }
    }

    @ParameterizedTest(name = "header fits {0} at {1} x {2}")
    @CsvSource({
            "zh_CN, 900, 600",
            "en_US, 900, 600",
            "zh_CN, 1180, 760",
            "en_US, 1180, 760"
    })
    void keepsHeaderControlsReachableWithLongToolTitles(String locale, int width, int height)
            throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture(locale, "json.format")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                frame.setSize(width, height);
                frame.validate();
                assertHeaderLayout(frame);

                JLabel title = named(frame, "workbench.title", JLabel.class);
                String probe = "en_US".equals(locale)
                        ? "JSON formatting and deeply nested structured data transformation workbench"
                        : "JSON 格式化与多层嵌套结构化数据转换工作台：完整工具名称显示测试";
                title.setText(probe);
                frame.validate();
                assertHeaderLayout(frame);
            });
        }
    }

    @Test
    void collapsesAndExpandsTheCategoryRail() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolSidebar sidebar = find(frame.getContentPane(), ToolSidebar.class);
                JButton collapse = find(frame.getContentPane(), JButton.class,
                        button -> I18n.get("nav.collapse")
                                .equals(button.getAccessibleContext().getAccessibleName()));
                assertNotNull(collapse);

                collapse.doClick(0);
                assertFalse(sidebar.isVisible());
                JButton expand = named(frame, "workbench.expandNavigation", JButton.class);
                assertTrue(expand.isVisible(), "折叠后必须留下展开入口");

                expand.doClick(0);
                assertTrue(sidebar.isVisible());
                assertFalse(expand.isVisible());
            });
        }
    }

    @Test
    void keepsDraftsAndSelectionAcrossThemeAndLanguageSwitches() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", "json.format")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                JComponent jsonView = frame.findTool("json.format").getView();
                javax.swing.JTextArea editor = find(jsonView, javax.swing.JTextArea.class);
                if (editor != null) {
                    editor.setText("keep-my-draft");
                }

                // 一键深浅切换
                named(frame, "workbench.themeToggle", JButton.class).doClick(0);
                assertTrue(ThemeManager.skin().isDark());
                named(frame, "workbench.themeToggle", JButton.class).doClick(0);
                assertFalse(ThemeManager.skin().isDark());

                // 皮肤菜单三档
                ThemeManager.applySkin(com.aqishi.toolbox.ui.kit.Skin.EYE_CARE);
                assertEquals(com.aqishi.toolbox.ui.kit.Skin.EYE_CARE, ThemeManager.skin());

                assertSame(jsonView, frame.findTool("json.format").getView());
                if (editor != null) {
                    assertEquals("keep-my-draft", editor.getText());
                }

                // 语言切换后标题跟随本地化，但选中的工具不变
                JComboBox<?> language = named(frame, "workbench.language", JComboBox.class);
                assertEquals(0, language.getSelectedIndex());
                language.setSelectedItem("English");
                assertEquals("en_US", ConfigManager.get("locale", null));
                assertEquals(frame.findTool("json.format").getLabel(),
                        named(frame, "workbench.title", JLabel.class).getText());

                language.setSelectedItem("简体中文");
                assertEquals("zh_CN", ConfigManager.get("locale", null));
            });
        }
    }

    @Test
    void supportsKeyboardShortcutsForSearchAndBack() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", "json.format")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertTrue(host.isVisible());

                frame.getRootPane().getActionMap().get("nav.back")
                        .actionPerformed(new java.awt.event.ActionEvent(frame, 0, ""));
                assertFalse(host.isVisible(), "Esc 应回到启动器");

                assertNotNull(frame.getRootPane().getActionMap().get("nav.focusSearch"));
            });
        }
    }

    // ---------------------------------------------------------------- 断言辅助

    private static void assertHeaderLayout(MainFrame frame) {
        JLabel title = named(frame, "workbench.title", JLabel.class);
        JTextField search = named(frame, "workbench.search", JTextField.class);
        JComboBox<?> language = named(frame, "workbench.language", JComboBox.class);
        JButton theme = named(frame, "workbench.themeToggle", JButton.class);
        javax.swing.AbstractButton favorites =
                named(frame, "workbench.favorites", javax.swing.AbstractButton.class);

        for (Component component : new Component[]{title, search, language, theme, favorites}) {
            assertWithinContent(frame, component);
        }
        assertTrue(search.getWidth() >= 200, "搜索框必须保持可用宽度");
        assertTrue(language.getWidth() >= language.getPreferredSize().width,
                "语言下拉不能被压缩");

        Rectangle titleBounds = boundsInContent(frame, title);
        Rectangle searchBounds = boundsInContent(frame, search);
        assertTrue(titleBounds.getMaxX() <= searchBounds.x + 1,
                "长工具名不能压到搜索框上");
        assertEquals(boundsInContent(frame, theme).y, boundsInContent(frame, language).y,
                "外观控件应保持在同一行");

        LauncherPanel launcher = find(frame.getContentPane(), LauncherPanel.class);
        Rectangle body = boundsInContent(frame, launcher);
        assertTrue(body.y >= boundsInContent(frame, search).getMaxY(),
                "顶栏不能换行压进内容区");
        assertTrue(body.height > 0);
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

        private WorkbenchFixture(String locale, String startTool) throws Exception {
            support = new VaultUiTestSupport(temp.resolve("profile"));
            configScope = ConfigManagerTestSupport.install(support.paths());
            ConfigManager.set("locale", locale);
            ConfigManager.set("theme", "Flat Light（浅色）");
            ConfigManager.set("nav.selectedTool", startTool == null ? "" : startTool);
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
            // The shell restores its divider on the next EDT turn.
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
