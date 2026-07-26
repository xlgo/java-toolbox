package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.vault.TotpAccount;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import com.aqishi.toolbox.util.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpPanelVaultTest {
    @TempDir
    Path temp;

    @Test
    void keepsTotpSecretsInsideTheEncryptedVault() throws Exception {
        try (VaultUiTestSupport support = new VaultUiTestSupport(temp)) {
            support.service().create("master".toCharArray()).get();
            support.service().replaceTotpAccounts(Collections.singletonList(
                    new TotpAccount("1", "Mail", "JBSWY3DPEHPK3PXP", "Example",
                            "SHA1", 6, 30, true))).get();
            TotpPanel panel = new TotpPanel(support.service(), support.clipboard());
            SwingUtilities.invokeAndWait(panel::getView);

            assertTrue(hasLabel(panel.getView(), "Mail"));
            assertFalse(Files.exists(support.paths().getLegacyConfigFile()));
            String encrypted = new String(Files.readAllBytes(support.paths().getVaultFile()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(encrypted.contains("JBSWY3DPEHPK3PXP"));

            support.service().lock();
            SwingUtilities.invokeAndWait(() -> { });
            assertFalse(hasLabel(panel.getView(), "Mail"));
        }
    }

    @Test
    void manualRevealWorksWhenDirectDisplayIsDisabled() throws Exception {
        ConfigManager.set("totp.show_directly", "false");
        try (VaultUiTestSupport support = new VaultUiTestSupport(temp)) {
            support.service().create("master".toCharArray()).get();
            support.service().replaceTotpAccounts(Collections.singletonList(
                    new TotpAccount("1", "Mail", "JBSWY3DPEHPK3PXP", "Example",
                            "SHA1", 6, 30, true))).get();
            TotpPanel panel = new TotpPanel(support.service(), support.clipboard());
            SwingUtilities.invokeAndWait(panel::getView);

            JButton reveal = findButton(panel.getView(), "显示/隐藏");
            assertTrue(reveal != null);
            SwingUtilities.invokeAndWait(reveal::doClick);

            assertTrue(hasCode(panel.getView()));
        } finally {
            ConfigManager.set("totp.show_directly", "true");
        }
    }

    private static boolean hasLabel(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && text.equals(((JLabel) child).getText())) return true;
            if (child instanceof Container && hasLabel((Container) child, text)) return true;
        }
        return false;
    }

    private static boolean hasCode(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel
                    && ((JLabel) child).getText().matches("\\d{3} \\d{3}")) return true;
            if (child instanceof Container && hasCode((Container) child)) return true;
        }
        return false;
    }

    private static JButton findButton(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton nested = findButton((Container) child, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
