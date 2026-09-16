package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class MainFrameStructureTest {
    @TempDir
    Path temp;

    @Test
    void usesUnifiedSidebarAndLazyContentInsteadOfTabbedNavigation() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                assertNotNull(find(frame.getContentPane(), ToolSidebar.class));
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                assertNotNull(host);
                assertNull(find(frame.getContentPane(), JTabbedPane.class));
                assertEquals(1, host.getComponentCount());
                assertTrue(host.isMounted("json.format"));
                assertFalse(host.isMounted("jsonpath.tester"));

                JButton expand = named(frame, "workbench.expandNavigation", JButton.class);
                assertNotNull(expand.getIcon());
                assertTrue(expand.getText().isEmpty(), "navigation uses a vector icon, not a font glyph");
                assertEquals(I18n.get("nav.expand"), expand.getAccessibleContext().getAccessibleName());
                assertFalse(expand.isVisible());
            });
        }
    }

    @Test
    void usesOneInjectedVaultSessionForBothSensitiveTools() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() ->
                    assertSame(fixture.support.service(), fixture.frame.getVaultServiceForTest()));
        }
    }

    @ParameterizedTest(name = "header fits {0} at {1} x {2}")
    @CsvSource({
            "zh_CN, 820, 520",
            "en_US, 820, 520",
            "zh_CN, 1024, 660",
            "en_US, 1024, 660"
    })
    void keepsHeaderControlsReachableAndLongTitlesOutOfSettings(
            String locale, int width, int height) throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture(locale)) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                frame.setSize(width, height);
                frame.validate();
                assertHeaderLayout(frame);

                JLabel title = named(frame, "workbench.title", JLabel.class);
                title.setText("en_US".equals(locale)
                        ? "JSON formatting and deeply nested structured data transformation workbench"
                        : "JSON 格式化与多层嵌套结构化数据转换工作台：完整工具名称显示测试");
                frame.validate();
                assertTrue(title.getPreferredSize().width > title.getWidth(),
                        "the regression probe must exercise a title that needs clipping");
                assertHeaderLayout(frame);

                collapseButton(frame).doClick(0);
                frame.validate();
                assertHeaderLayout(frame);
                JButton expand = named(frame, "workbench.expandNavigation", JButton.class);
                assertTrue(expand.isVisible());
                assertWithinContent(frame, expand);
            });
        }
    }

    @Test
    void preservesLazyViewsEditorStateAndSelectionAcrossChromeChanges() throws Exception {
        try (WorkbenchFixture fixture = new WorkbenchFixture("zh_CN")) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = fixture.frame;
                ToolSidebar sidebar = find(frame.getContentPane(), ToolSidebar.class);
                ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
                JComponent jsonView = frame.findTool("json.format").getView();
                JTextArea editor = find(jsonView, JTextArea.class);
                assertNotNull(editor);
                editor.setText("{\"keepMyDraft\":true}");
                sidebar.setExpandedGroupIds(Collections.singleton("codec"));

                frame.selectTool("jsonpath.tester");
                JComponent pathView = frame.findTool("jsonpath.tester").getView();
                JTextField expression = find(pathView, JTextField.class);
                assertNotNull(expression);
                expression.setText("$.keepMyDraft");
                assertEquals(2, host.getComponentCount());
                assertFalse(host.isMounted("http.client"));
                assertFalse(host.isMounted("ssh"));

                collapseButton(frame).doClick(0);
                assertFalse(sidebar.isVisible());
                assertPreserved(frame, host, sidebar, jsonView, pathView, editor, expression);
                named(frame, "workbench.expandNavigation", JButton.class).doClick(0);
                assertTrue(sidebar.isVisible());
                assertPreserved(frame, host, sidebar, jsonView, pathView, editor, expression);

                named(frame, "workbench.theme", JComboBox.class).setSelectedItem("Flat Dark（深色）");
                assertTrue(ThemeManager.current().dark);
                assertPreserved(frame, host, sidebar, jsonView, pathView, editor, expression);

                named(frame, "workbench.language", JComboBox.class).setSelectedItem("English");
                assertEquals("en_US", ConfigManager.get("locale", null));
                assertEquals(frame.findTool("jsonpath.tester").getLabel(),
                        named(frame, "workbench.title", JLabel.class).getText());
                assertLinkedSetting(frame, "workbench.theme", "top.theme");
                assertLinkedSetting(frame, "workbench.language", "top.lang");
                assertPreserved(frame, host, sidebar, jsonView, pathView, editor, expression);

                named(frame, "workbench.theme", JComboBox.class).setSelectedItem("Flat Light（浅色）");
                named(frame, "workbench.language", JComboBox.class).setSelectedItem("简体中文");
                assertFalse(ThemeManager.current().dark);
                assertEquals("zh_CN", ConfigManager.get("locale", null));
                assertPreserved(frame, host, sidebar, jsonView, pathView, editor, expression);

                frame.selectTool("json.format");
                assertTrue(jsonView.isVisible());
                assertFalse(pathView.isVisible());
                assertEquals("json.format", sidebar.getSelectedToolId());
                assertSame(jsonView, frame.findTool("json.format").getView());
                assertEquals("{\"keepMyDraft\":true}", editor.getText());
                assertEquals(2, host.getComponentCount());
            });
        }
    }

    private static void assertPreserved(
            MainFrame frame, ToolContentHost host, ToolSidebar sidebar,
            JComponent jsonView, JComponent pathView, JTextArea editor, JTextField expression) {
        assertEquals("jsonpath.tester", sidebar.getSelectedToolId());
        assertEquals("jsonpath.tester", ConfigManager.get("nav.selectedTool", null));
        assertEquals(Collections.singleton("codec"), sidebar.getExpandedGroupIds());
        assertSame(jsonView, frame.findTool("json.format").getView());
        assertSame(pathView, frame.findTool("jsonpath.tester").getView());
        assertSame(jsonView, host.getComponent(0));
        assertSame(pathView, host.getComponent(1));
        assertEquals(2, host.getComponentCount());
        assertFalse(jsonView.isVisible());
        assertTrue(pathView.isVisible());
        assertEquals("{\"keepMyDraft\":true}", editor.getText());
        assertEquals("$.keepMyDraft", expression.getText());
    }

    private static void assertHeaderLayout(MainFrame frame) {
        JLabel title = named(frame, "workbench.title", JLabel.class);
        JComboBox<?> theme = named(frame, "workbench.theme", JComboBox.class);
        JComboBox<?> language = named(frame, "workbench.language", JComboBox.class);
        assertWithinContent(frame, title);
        assertWithinContent(frame, theme);
        assertWithinContent(frame, language);
        assertTrue(theme.getWidth() >= theme.getPreferredSize().width,
                "theme picker must retain its usable width");
        assertTrue(language.getWidth() >= language.getPreferredSize().width,
                "language picker must retain its usable width");
        Rectangle titleBounds = boundsInContent(frame, title);
        Rectangle themeBounds = boundsInContent(frame, theme);
        Rectangle languageBounds = boundsInContent(frame, language);
        assertTrue(titleBounds.getMaxX() <= themeBounds.x,
                "long tool title must not overlap settings");
        assertTrue(themeBounds.getMaxX() < languageBounds.x,
                "settings must retain a visible horizontal gap");
        assertEquals(themeBounds.y, languageBounds.y, "settings should stay on one row");
        assertEquals(themeBounds.height, languageBounds.height);
        assertLinkedSetting(frame, "workbench.theme", "top.theme");
        assertLinkedSetting(frame, "workbench.language", "top.lang");
        ToolContentHost host = find(frame.getContentPane(), ToolContentHost.class);
        assertNotNull(host);
        Rectangle bodyBounds = boundsInContent(frame, host);
        assertTrue(bodyBounds.y >= themeBounds.getMaxY(),
                "the header must not wrap controls into the tool page");
        assertTrue(bodyBounds.height > 0);
    }

    private static void assertLinkedSetting(MainFrame frame, String name, String labelKey) {
        JComboBox<?> field = named(frame, name, JComboBox.class);
        JLabel label = find(frame.getContentPane(), JLabel.class, candidate -> candidate.getLabelFor() == field);
        assertNotNull(label, "setting needs a linked visible label: " + name);
        assertEquals(I18n.get(labelKey), label.getText());
        assertEquals(label.getText(), field.getAccessibleContext().getAccessibleName());
        assertTrue(field.isEnabled());
        assertTrue(field.isFocusable());
        assertWithinContent(frame, label);
        assertTrue(boundsInContent(frame, label).getMaxY() <= boundsInContent(frame, field).y,
                "setting label should sit above its control");
    }

    private static void assertWithinContent(MainFrame frame, Component component) {
        Rectangle bounds = boundsInContent(frame, component);
        assertTrue(bounds.width > 0 && bounds.height > 0,
                () -> component.getName() + " has no usable bounds: " + bounds);
        assertTrue(new Rectangle(frame.getContentPane().getSize()).contains(bounds),
                () -> component.getName() + " escapes the window content: " + bounds);
    }

    private static Rectangle boundsInContent(MainFrame frame, Component component) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), frame.getContentPane());
    }

    private static JButton collapseButton(MainFrame frame) {
        JButton button = find(frame.getContentPane(), JButton.class,
                candidate -> I18n.get("nav.collapse").equals(candidate.getAccessibleContext().getAccessibleName()));
        assertNotNull(button);
        return button;
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
            ConfigManager.set("theme", "Flat Light（浅色）");
            ConfigManager.set("nav.selectedTool", "json.format");
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
