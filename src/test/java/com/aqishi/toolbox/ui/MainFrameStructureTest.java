package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Tokens;
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
 * <p>重构后的主窗口是「顶栏 + 完整工具侧栏 + 带标题的工作区」：启动后直接进入
 * 上次使用的工具（首次为 JSON 格式化），侧栏负责全部工具入口，工作区标题与
 * 收藏状态跟随当前工具。这里验证的是这层外壳契约，而不是某个工具表单。</p>
 */
class MainFrameStructureTest {

    @TempDir
    Path temp;

    @Test
    void startsOnARealToolWithoutASeparateLauncher() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                assertNull(find(frame.getContentPane(), LauncherPanel.class),
                        "效果页没有独立启动器，应用应直接进入工具页");
                assertNotNull(find(frame.getContentPane(), ToolSidebar.class));
                assertNotNull(find(frame.getContentPane(), TopBar.class));
                assertNotNull(find(frame.getContentPane(), ToolHeader.class));

                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertNotNull(host);
                assertEquals(1, host.getComponentCount(), "启动时应挂载当前工具");
                assertTrue(host.isMounted("json.format"));
                assertTrue(host.isVisible());

                JLabel title = named(frame, "workbench.toolTitle", JLabel.class);
                assertEquals(frame.findTool("json.format").getLabel(), title.getText());
                JLabel description = named(frame, "workbench.toolDescription", JLabel.class);
                assertNotNull(description.getText());
                assertFalse(description.getText().isEmpty());

                ToolSidebar sidebar = find(frame.getContentPane(), ToolSidebar.class);
                assertEquals("json.format", sidebar.getSelectedToolId());
                assertEquals(77, sidebar.getToolCount(), "侧栏必须保留全部现有工具");
                assertEquals(77, countNamedButtons(sidebar, "nav.tool."),
                        "每个注册工具都必须有可点击入口");
                assertNotNull(named(frame, "workbench.search", JTextField.class));
                assertNotNull(named(frame, "workbench.clock", JLabel.class));
            });
        }
    }

    @Test
    void restoresTheLastToolAndShowsItsHeader() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", "jsonpath.tester")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertTrue(host.isMounted("jsonpath.tester"));
                assertEquals(frame.findTool("jsonpath.tester").getLabel(),
                        named(frame, "workbench.toolTitle", JLabel.class).getText());
                assertNotNull(named(frame, "workbench.toolFavorite", JButton.class));
            });
        }
    }

    @Test
    void clickingASidebarRowSwitchesTheToolAndHeader() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);

                named(frame, "nav.tool.regex.tester", JButton.class).doClick(0);

                assertTrue(host.isMounted("regex.tester"));
                assertEquals(frame.findTool("regex.tester").getLabel(),
                        named(frame, "workbench.toolTitle", JLabel.class).getText());
                ToolSidebar sidebar = find(frame.getContentPane(), ToolSidebar.class);
                assertEquals("regex.tester", sidebar.getSelectedToolId());
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

                frame.selectTool("jsonpath.tester");
                frame.selectTool("regex.tester");
                assertEquals(3, host.getComponentCount());
                assertFalse(host.isMounted("http.client"));
            });
        }
    }

    @Test
    void sidebarSearchFiltersToolsWithoutChangingTheOpenTool() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", "json.format")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolSidebar sidebar = find(frame.getContentPane(), ToolSidebar.class);
                sidebar.setQuery("正则");

                assertNotNull(named(frame, "nav.tool.regex.tester", JButton.class));
                assertNull(findNamedButton(sidebar, "nav.tool.http.client"));
                assertEquals("json.format", sidebar.getSelectedToolId(),
                        "筛选目录不应关掉正在编辑的工具");

                sidebar.setQuery("");
                assertNotNull(named(frame, "nav.tool.http.client", JButton.class));
            });
        }
    }

    @Test
    void favoriteButtonInHeaderPersistsAndUpdatesSidebar() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", "json.format")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                JButton favorite = named(frame, "workbench.toolFavorite", JButton.class);
                favorite.doClick(0);

                ToolSidebar sidebar = find(frame.getContentPane(), ToolSidebar.class);
                assertTrue(sidebar.isFavorite("json.format"));
                assertEquals("json.format", ConfigManager.get("nav.favorites", ""));
                assertTrue(favorite.getText().contains(I18n.get("tool.favorite.on")));

                favorite.doClick(0);
                assertFalse(sidebar.isFavorite("json.format"));
            });
        }
    }

    @Test
    void collapsesAndExpandsTheToolSidebar() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
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
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", "json.format")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                JComponent jsonView = frame.findTool("json.format").getView();
                javax.swing.JTextArea editor = find(jsonView, javax.swing.JTextArea.class);
                if (editor != null) {
                    editor.setText("keep-my-draft");
                }

                named(frame, "top.theme.dark", AbstractButton.class).doClick(0);
                assertTrue(ThemeManager.skin().isDark());
                named(frame, "top.theme.light", AbstractButton.class).doClick(0);
                assertFalse(ThemeManager.skin().isDark());
                named(frame, "top.accent.blue", AbstractButton.class).doClick(0);
                assertEquals(0x2563EB, ThemeManager.accentRgb());

                assertSame(jsonView, frame.findTool("json.format").getView());
                if (editor != null) {
                    assertEquals("keep-my-draft", editor.getText());
                }
            });
        }
    }

    @Test
    void supportsKeyboardShortcutsForSearchAndSidebar() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", null)) {
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
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN", "json.format")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                JLabel current = named(frame, "workbench.status.current", JLabel.class);
                assertTrue(current.getText().contains(frame.findTool("json.format").getLabel()));
                JLabel theme = named(frame, "workbench.status.theme", JLabel.class);
                assertTrue(theme.getText().contains(ThemeManager.skin().label()));
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
    void keepsTopBarControlsReachableOnNarrowWindows(String locale, int width, int height)
            throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture(locale, "json.format")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                frame.setSize(width, height);
                frame.validate();

                JTextField search = named(frame, "workbench.search", JTextField.class);
                JLabel clock = named(frame, "workbench.clock", JLabel.class);
                AbstractButton theme = named(frame, "top.theme.light", AbstractButton.class);
                JButton appearance = named(frame, "workbench.appearance", JButton.class);
                JButton sidebar = named(frame, "workbench.sidebarToggle", JButton.class);

                for (Component component : new Component[]{
                        search, clock, theme, appearance, sidebar}) {
                    assertWithinContent(frame, component);
                }
                assertTrue(search.getWidth() >= 190, "搜索框必须保持可用宽度, 实际=" + search.getWidth());
                assertTrue(Math.abs(boundsInContent(frame, theme).getCenterY()
                                - boundsInContent(frame, clock).getCenterY()) <= 1,
                        "外观控件应保持在同一行");

                ToolHeader header = find(frame.getContentPane(), ToolHeader.class);
                Rectangle headerBounds = boundsInContent(frame, header);
                assertTrue(headerBounds.height > 40);
                assertTrue(headerBounds.width > 300);
            });
        }
    }

    // ---------------------------------------------------------------- 断言辅助

    private static JButton findNamedButton(Container root, String name) {
        return find(root, JButton.class, button -> name.equals(button.getName()));
    }

    private static int countNamedButtons(Container root, String prefix) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button
                    && button.getName() != null
                    && button.getName().startsWith(prefix)) {
                count++;
            }
            if (child instanceof Container container) {
                count += countNamedButtons(container, prefix);
            }
        }
        return count;
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
            ConfigManager.set("accent.color", "#0F766E");
            ConfigManager.set("nav.favorites", "");
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
