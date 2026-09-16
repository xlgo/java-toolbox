package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Logging;
import com.aqishi.toolbox.vault.ApplicationPaths;
import com.aqishi.toolbox.vault.VaultBootstrap;

import javax.swing.*;
import java.nio.file.Path;
import java.util.Set;

/**
 * Manual visual-QA launcher with an isolated profile and non-network sample tools.
 * Args: [jsonpath.tester|json.format] [light|dark|theme name] [zh_CN|en_US] [width] [height].
 * This only opens the application; it never captures or automates the desktop.
 */
public final class WorkbenchPreview {
    private WorkbenchPreview() {
    }

    public static void main(String[] args) throws Exception {
        String tool = args.length > 0 ? args[0] : "jsonpath.tester";
        if ("jsonpath".equals(tool)) tool = "jsonpath.tester";
        if (!Set.of("jsonpath.tester", "json.format").contains(tool)) {
            throw new IllegalArgumentException("Preview supports jsonpath.tester or json.format only");
        }
        String theme = args.length > 1 ? args[1] : "light";
        if ("light".equals(theme)) theme = "Flat Light（浅色）";
        if ("dark".equals(theme)) theme = "Flat Dark（深色）";
        if (ThemeManager.get(theme) == null) throw new IllegalArgumentException("Unknown theme: " + theme);
        String locale = args.length > 2 ? args[2] : "zh_CN";
        if (!Set.of("zh_CN", "en_US").contains(locale)) {
            throw new IllegalArgumentException("Preview locale must be zh_CN or en_US");
        }
        int width = args.length > 3 ? Integer.parseInt(args[3]) : 1180;
        int height = args.length > 4 ? Integer.parseInt(args[4]) : 760;
        if (width < 820 || height < 520) {
            throw new IllegalArgumentException("Preview size must be at least 820 x 520");
        }

        Path profile = Path.of("target", "ui-preview-profile").toAbsolutePath().normalize();
        System.setProperty(ApplicationPaths.CONFIG_ROOT_PROPERTY, profile.toString());
        System.clearProperty("screenshot");
        Logging.bootstrap();
        ConfigManagerTestSupport.install(ApplicationPaths.systemDefault());
        ConfigManager.set("locale", locale);
        ConfigManager.set("theme", theme);
        ConfigManager.set("nav.selectedTool", tool);
        ConfigManager.set("nav.expandedGroups", "codec");
        ConfigManager.set("nav.sidebarCollapsed", "false");
        ConfigManager.setInt("nav.sidebarWidth", 248);
        ConfigManager.setInt("width", width);
        ConfigManager.setInt("height", height);
        ConfigManager.setInt("x", -1);
        ConfigManager.setInt("y", -1);
        I18n.init();
        ThemeManager.setupDefault();
        VaultBootstrap.Components vault = VaultBootstrap.createDefault();
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(vault.getService(), vault.getClipboard());
            frame.setVisible(true);
        });
    }
}
