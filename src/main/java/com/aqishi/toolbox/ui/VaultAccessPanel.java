package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.VaultCrypto;
import com.aqishi.toolbox.vault.VaultException;
import com.aqishi.toolbox.vault.VaultListener;
import com.aqishi.toolbox.vault.VaultService;
import com.aqishi.toolbox.vault.VaultState;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
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

    /** 说明文字的换行宽度：HTML 标签不会自己换行，给一个上限免得卡片被拉成一条 */
    private static final int HELP_WIDTH = 340;

    private final VaultService service;
    private final CardLayout cards = new CardLayout();
    private final VaultListener listener = this::handleState;
    private final JPasswordField setupPassword = Fields.password();
    private final JPasswordField setupConfirm = Fields.password();
    private final JPasswordField migrationPassword = Fields.password();
    private final JPasswordField migrationConfirmation = Fields.password();
    private final JPasswordField unlockPassword = Fields.password();
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
        FormGrid fields = new FormGrid();
        addField(fields, I18n.get("vault.masterPassword"), setupPassword);
        addField(fields, I18n.get("vault.confirmPassword"), setupConfirm);
        JButton create = Buttons.primary(I18n.get("vault.create"));
        create.addActionListener(event -> create());
        setupConfirm.addActionListener(event -> create());
        return centered(I18n.get("vault.setup.title"),
                I18n.get("vault.setup.help"), fields, create);
    }

    private JComponent buildMigration() {
        FormGrid fields = new FormGrid();
        addField(fields, I18n.get("vault.masterPassword"), migrationPassword);
        addField(fields, I18n.get("vault.confirmPassword"), migrationConfirmation);
        JButton migrate = Buttons.primary(I18n.get("vault.migrate"));
        migrate.addActionListener(event -> migrate());
        migrationPassword.addActionListener(event -> migrate());
        return centered(I18n.get("vault.migrate.title"),
                I18n.get("vault.migrate.help"), fields, migrate);
    }

    private JComponent buildUnlock() {
        FormGrid fields = new FormGrid();
        addField(fields, I18n.get("vault.masterPassword"), unlockPassword);
        JButton unlock = Buttons.primary(I18n.get("vault.unlock"));
        unlock.addActionListener(event -> unlock());
        unlockPassword.addActionListener(event -> unlock());
        return centered(I18n.get("vault.unlock.title"), null, fields, unlock);
    }

    private JComponent buildBusy() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        JLabel label = new JLabel(I18n.get("vault.busy"));
        label.setFont(Tokens.fontTitle());
        label.setForeground(Tokens.mutedForeground());
        panel.add(label);
        return panel;
    }

    private JComponent buildContent(JComponent content) {
        JPanel panel = Layouts.box();
        JButton settings = Buttons.ghost(I18n.get("vault.settings"));
        JButton lock = Buttons.secondary(I18n.get("vault.lock"));
        settings.addActionListener(event -> {
            Window owner = SwingUtilities.getWindowAncestor(this);
            new VaultSettingsDialog(owner, service).setVisible(true);
        });
        lock.addActionListener(event -> service.lock());
        // 保险库级别的动作贴着内容区顶部右对齐；内容自身用 Layouts.page()，
        // 它的上外边距正好当作这条动作条与卡片之间的间隔，这里只补左右和顶部。
        ActionBar actions = new ActionBar();
        actions.right(settings);
        actions.right(lock);
        actions.setBorder(KitBorders.padding(
                Tokens.SPACE_LG, Tokens.SPACE_LG, 0, Tokens.SPACE_LG));
        panel.add(actions, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildError() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        JLabel label = new JLabel(I18n.get("vault.readOnly"));
        label.setFont(Tokens.fontBody());
        // 只读故障是错误态：仅着色，文案与原来完全一致
        label.setForeground(Tokens.danger());
        panel.add(label);
        return panel;
    }

    /**
     * 解锁 / 首次设置 / 迁移三种状态共用的居中表单。
     *
     * <p>这三张卡都只有一两个输入框，铺满整页会显得空旷；放进一张按首选尺寸居中的卡片里，
     * 视觉重心落在主密码输入上，主操作固定在卡片右下角。</p>
     */
    private JComponent centered(String title, String help,
                                JComponent fields, JButton action) {
        Card card = Card.titled(title);
        JPanel body = Layouts.box(0, Tokens.SPACE_MD);
        if (help != null) {
            JLabel helpLabel = new JLabel(
                    "<html><div style='width:" + HELP_WIDTH + "px'>" + help + "</div></html>");
            helpLabel.setFont(Tokens.fontCaption());
            helpLabel.setForeground(Tokens.mutedForeground());
            body.add(helpLabel, BorderLayout.NORTH);
        }
        body.add(fields, BorderLayout.CENTER);
        ActionBar actions = new ActionBar();
        actions.right(action);
        body.add(actions, BorderLayout.SOUTH);
        card.setContent(body);

        // GridBagLayout 不带约束时按首选尺寸居中，窗口再大也不会把表单拉散
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setOpaque(false);
        outer.setBorder(KitBorders.padding(Tokens.SPACE_LG));
        outer.add(card, new GridBagConstraints());
        return outer;
    }

    private static void addField(FormGrid form, String label, JPasswordField field) {
        field.getAccessibleContext().setAccessibleName(label);
        form.row(label, field);
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
