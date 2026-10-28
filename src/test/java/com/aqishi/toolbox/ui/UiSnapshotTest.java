package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 视觉验收：把首页、工具页与深色主题渲染成 PNG，便于人工比对效果页。
 *
 * <p>只在显式指定时运行（{@code -Dtest=UiSnapshotTest}），产物写到
 * {@code target/screenshots}。这里不打开真实窗口，也不触碰用户配置。</p>
 */
class UiSnapshotTest {

    @TempDir
    Path temp;

    @Test
    void renderShellScreenshots() throws Exception {
        Path output = Path.of("target", "screenshots");
        Files.createDirectories(output);
        shot("home-light", ThemeManager.LIGHT_THEME, ToolSidebar.ALL, null, output);
        shot("home-wide", ThemeManager.LIGHT_THEME, ToolSidebar.ALL, null, output, 1600, 900);
        shot("tool-dark", ThemeManager.DARK_THEME, ToolSidebar.ALL, "json.format", output);
        shot("home-dark", ThemeManager.DARK_THEME, ToolSidebar.ALL, null, output);
        shot("home-cream", ThemeManager.CREAM_THEME, ToolSidebar.ALL, null, output);
        shot("tool-json-light", ThemeManager.LIGHT_THEME, ToolSidebar.ALL, "json.format", output);
        String[] samples = {
                "xml.format", "sql.format", "string.tool", "text.escape", "text.diff",
                "jsonpath.tester", "xpath.tool", "file.encoding", "format.convert",
                "base64.image", "radix.encoding", "structured.diff", "table.sql",
                "json.dto", "mybatis.sql", "maven.tree"
        };
        for (String tool : samples) {
            shot("tool-" + tool, ThemeManager.LIGHT_THEME, ToolSidebar.ALL, tool, output);
        }
    }

    private static void dumpButtons(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JButton button && button.getText() != null
                    && !button.getText().isEmpty()) {
                java.awt.Rectangle bounds = javax.swing.SwingUtilities.convertRectangle(
                        button.getParent(), button.getBounds(), root);
                System.out.println("[button] " + button.getText() + " " + bounds
                        + " pref=" + button.getPreferredSize() + " min=" + button.getMinimumSize());
            }
            if (child instanceof java.awt.Container container) {
                dumpButtons(container);
            }
        }
    }

    private static void layoutTree(java.awt.Container root) {
        root.doLayout();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof java.awt.Container container) {
                layoutTree(container);
            }
        }
    }

    private void shot(String name, String theme, String category, String toolId, Path output)
            throws Exception {
        shot(name, theme, category, toolId, output, 1180, 760);
    }

    private void shot(String name, String theme, String category, String toolId, Path output,
                      int width, int height) throws Exception {
        try (VaultUiTestSupport support = new VaultUiTestSupport(temp.resolve("profile-" + name))) {
            AutoCloseable configScope = ConfigManagerTestSupport.install(support.paths());
            try {
                ConfigManager.set("locale", "zh_CN");
                ConfigManager.set("theme", theme);
                ConfigManager.set("accent.color", "");
                ConfigManager.set("nav.favorites", "");
                ConfigManager.set("nav.recent", "");
                ConfigManager.set("nav.category", category);
                ConfigManager.set("width", Integer.toString(width));
                ConfigManager.set("height", Integer.toString(height));

                AtomicReference<MainFrame> ref = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    I18n.init();
                    ThemeManager.setupDefault();
                    MainFrame frame = new MainFrame(support.service(), support.clipboard());
                    frame.setSize(width, height);
                    frame.addNotify();
                    frame.validate();
                    if (toolId != null) {
                        frame.selectTool(toolId);
                        frame.validate();
                    }
                    ref.set(frame);
                });
                MainFrame frame = ref.get();
                SwingUtilities.invokeAndWait(() -> {
                    frame.doLayout();
                    layoutTree(frame.getContentPane());
                    frame.validate();
                    frame.repaint();
                });
                Thread.sleep(400);
                SwingUtilities.invokeAndWait(() -> {
                    BufferedImage image = new BufferedImage(
                            frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    if (Boolean.getBoolean("snapshot.debug")) {
                        dumpButtons(frame.getContentPane());
                    }
                    try {
                        frame.getContentPane().printAll(graphics);
                    } finally {
                        graphics.dispose();
                    }
                    try {
                        javax.imageio.ImageIO.write(image, "png",
                                output.resolve(name + ".png").toFile());
                    } catch (java.io.IOException error) {
                        throw new IllegalStateException(error);
                    }
                });
                SwingUtilities.invokeAndWait(frame::dispose);
                System.out.println("[snapshot] " + name + " -> " + output.resolve(name + ".png"));
            } finally {
                configScope.close();
            }
        }
    }
}
