package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.VaultCrypto;
import com.aqishi.toolbox.vault.VaultException;
import com.aqishi.toolbox.vault.VaultListener;
import com.aqishi.toolbox.vault.VaultService;
import com.aqishi.toolbox.vault.VaultState;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.Arrays;
import java.util.Objects;

/** Shared setup, migration, unlock, busy, and content state surface. */
public final class VaultAccessPanel extends JPanel {
    static final String SETUP = "SETUP";
    static final String MIGRATE = "MIGRATE";
    static final String UNLOCK = "UNLOCK";
    static final String BUSY = "BUSY";
    static final String CONTENT = "CONTENT";
    static final String ERROR = "ERROR";

    private final VaultService service;
    private final CardLayout cards = new CardLayout();
    private final VaultListener listener = this::handleState;
    private final JPasswordField setupPassword = new JPasswordField(22);
    private final JPasswordField setupConfirm = new JPasswordField(22);
    private final JPasswordField migrationPassword = new JPasswordField(22);
    private final JPasswordField migrationConfirmation = new JPasswordField(22);
    private final JPasswordField unlockPassword = new JPasswordField(22);
    private String visibleCardName;
    private boolean disposed;

    public VaultAccessPanel(VaultService service, JComponent content) {
        super();
        this.service = Objects.requireNonNull(service, "service");
        Objects.requireNonNull(content, "content");
        setLayout(cards);
        add(buildSetup(), SETUP);
        add(buildMigration(), MIGRATE);
        add(buildUnlock(), UNLOCK);
        add(buildBusy(), BUSY);
        add(buildContent(content), CONTENT);
        add(buildError(), ERROR);
        service.addListener(listener);
        showState(service.getState());
    }

    String getVisibleCardName() {
        return visibleCardName;
    }

    public void dispose() {
        if (!disposed) {
            disposed = true;
            service.removeListener(listener);
        }
    }

    @Override
    public void removeNotify() {
        dispose();
        super.removeNotify();
    }

    private JComponent buildSetup() {
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = constraints();
        addField(fields, c, 0, I18n.get("vault.masterPassword"), setupPassword);
        addField(fields, c, 1, I18n.get("vault.confirmPassword"), setupConfirm);
        JButton create = UIUtils.button(I18n.get("vault.create"), 132);
        create.addActionListener(event -> create());
        setupConfirm.addActionListener(event -> create());
        return centered(I18n.get("vault.setup.title"),
                I18n.get("vault.setup.help"), fields, create);
    }

    private JComponent buildMigration() {
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = constraints();
        addField(fields, c, 0, I18n.get("vault.masterPassword"), migrationPassword);
        addField(fields, c, 1, I18n.get("vault.confirmPassword"), migrationConfirmation);
        JButton migrate = UIUtils.button(I18n.get("vault.migrate"), 132);
        migrate.addActionListener(event -> migrate());
        migrationPassword.addActionListener(event -> migrate());
        return centered(I18n.get("vault.migrate.title"),
                I18n.get("vault.migrate.help"), fields, migrate);
    }

    private JComponent buildUnlock() {
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = constraints();
        addField(fields, c, 0, I18n.get("vault.masterPassword"), unlockPassword);
        JButton unlock = UIUtils.button(I18n.get("vault.unlock"), 132);
        unlock.addActionListener(event -> unlock());
        unlockPassword.addActionListener(event -> unlock());
        return centered(I18n.get("vault.unlock.title"), null, fields, unlock);
    }

    private JComponent buildBusy() {
        JPanel panel = new JPanel(new GridBagLayout());
        JLabel label = new JLabel(I18n.get("vault.busy"));
        label.setFont(UIUtils.titleFont().deriveFont(15f));
        panel.add(label);
        return panel;
    }

    private JComponent buildContent(JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, UIUtils.SPACE_XS));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIUtils.SPACE_XS, 0));
        JButton settings = UIUtils.button(I18n.get("vault.settings"), 88);
        JButton lock = UIUtils.button(I18n.get("vault.lock"), 88);
        settings.addActionListener(event -> {
            Window owner = SwingUtilities.getWindowAncestor(this);
            new VaultSettingsDialog(owner, service).setVisible(true);
        });
        lock.addActionListener(event -> service.lock());
        actions.add(settings);
        actions.add(lock);
        panel.add(actions, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildError() {
        JPanel panel = new JPanel(new GridBagLayout());
        JLabel label = new JLabel(I18n.get("vault.readOnly"));
        label.setFont(UIUtils.plainFont());
        panel.add(label);
        return panel;
    }

    private JComponent centered(String title, String help,
                                JComponent fields, JButton action) {
        JPanel outer = new JPanel(new GridBagLayout());
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                new EmptyBorder(8, 10, 8, 10)));
        GridBagConstraints c = constraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        if (help != null) {
            JLabel helpLabel = new JLabel("<html>" + help + "</html>");
            helpLabel.setFont(UIUtils.plainFont());
            form.add(helpLabel, c);
            c.gridy++;
        }
        form.add(fields, c);
        c.gridy++;
        c.insets = new Insets(12, 8, 4, 8);
        form.add(action, c);
        outer.add(form);
        return outer;
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        return c;
    }

    private static void addField(JPanel panel, GridBagConstraints c, int row,
                                 String label, JPasswordField field) {
        GridBagConstraints left = (GridBagConstraints) c.clone();
        left.gridx = 0;
        left.gridy = row;
        left.weightx = 0;
        panel.add(new JLabel(label), left);
        GridBagConstraints right = (GridBagConstraints) c.clone();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        field.getAccessibleContext().setAccessibleName(label);
        panel.add(field, right);
    }

    private void create() {
        char[] password = setupPassword.getPassword();
        char[] confirmation = setupConfirm.getPassword();
        if (password.length == 0 || !Arrays.equals(password, confirmation)) {
            UIUtils.error(this, password.length == 0
                    ? I18n.get("vault.password.empty")
                    : I18n.get("vault.password.mismatch"));
            VaultCrypto.wipe(password);
            VaultCrypto.wipe(confirmation);
            return;
        }
        showCard(BUSY);
        service.create(password).whenComplete((ignored, error) -> completePasswordAction(
                setupPassword, setupConfirm, confirmation, error));
    }

    private void migrate() {
        char[] password = migrationPassword.getPassword();
        char[] confirmation = migrationConfirmation.getPassword();
        if (password.length == 0) {
            VaultCrypto.wipe(password);
            VaultCrypto.wipe(confirmation);
            UIUtils.error(this, I18n.get("vault.password.empty"));
            return;
        }
        if (service.getMigrationMode() == com.aqishi.toolbox.vault.LegacyVaultMigrator.MigrationMode.TOTP_ONLY
                && !Arrays.equals(password, confirmation)) {
            VaultCrypto.wipe(password);
            VaultCrypto.wipe(confirmation);
            UIUtils.error(this, I18n.get("vault.password.mismatch"));
            return;
        }
        showCard(BUSY);
        service.migrate(password).whenComplete((ignored, error) ->
                completePasswordAction(migrationPassword, migrationConfirmation, confirmation, error));
    }

    private void unlock() {
        char[] password = unlockPassword.getPassword();
        if (password.length == 0) {
            VaultCrypto.wipe(password);
            UIUtils.error(this, I18n.get("vault.password.empty"));
            return;
        }
        showCard(BUSY);
        service.unlock(password).whenComplete((ignored, error) ->
                completePasswordAction(unlockPassword, null, null, error));
    }

    private void completePasswordAction(JPasswordField first,
                                        JPasswordField second,
                                        char[] extra,
                                        Throwable error) {
        VaultCrypto.wipe(extra);
        SwingUtilities.invokeLater(() -> {
            first.setText("");
            if (second != null) second.setText("");
            if (error != null) {
                UIUtils.error(this, safeMessage(error));
                showState(service.getState());
            } else if (!service.getCleanupWarnings().isEmpty()) {
                UIUtils.info(this, I18n.get("vault.cleanup.warning") + "\n"
                        + String.join("\n", service.getCleanupWarnings()));
            }
        });
    }

    private void handleState(VaultState newState) {
        if (SwingUtilities.isEventDispatchThread()) showState(newState);
        else SwingUtilities.invokeLater(() -> showState(newState));
    }

    private void showState(VaultState newState) {
        if (newState == VaultState.UNLOCKED) showCard(CONTENT);
        else if (newState == VaultState.MIGRATION_REQUIRED) showCard(MIGRATE);
        else if (newState == VaultState.UNLOCKING || newState == VaultState.SAVING) showCard(BUSY);
        else if (newState == VaultState.ERROR_READ_ONLY) showCard(ERROR);
        else showCard(service.isInitialized() ? UNLOCK : SETUP);
    }

    private void showCard(String name) {
        visibleCardName = name;
        cards.show(this, name);
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof VaultException)) {
            cause = cause.getCause();
        }
        if (cause instanceof VaultException) {
            return I18n.get("vault.error.generic");
        }
        return I18n.get("vault.error.generic");
    }
}
