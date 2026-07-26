package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.vault.PasswordAccount;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AccountManagerPanelVaultTest {
    @TempDir
    Path temp;

    @Test
    void readsAndWritesOnlyThroughTheSharedVault() throws Exception {
        try (VaultUiTestSupport support = new VaultUiTestSupport(temp)) {
            support.service().create("master".toCharArray()).get();
            support.service().replacePasswordAccounts(Collections.singletonList(
                    new PasswordAccount("GitHub", "dev", "secret", "https://github.com"))).get();
            AccountManagerPanel panel = new AccountManagerPanel(
                    support.service(), support.clipboard());
            SwingUtilities.invokeAndWait(panel::getView);

            JTable table = find(panel.getView(), JTable.class);
            assertNotNull(table);
            assertEquals(1, table.getRowCount());
            assertFalse(Files.exists(support.paths().getLegacyPasswordFile()));

            support.service().lock();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(0, table.getRowCount());
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) {
                T result = find((Container) child, type);
                if (result != null) return result;
            }
        }
        return null;
    }
}
