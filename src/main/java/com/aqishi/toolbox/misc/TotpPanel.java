package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.crypto.OtpUtils;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.VaultAccessPanel;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.SecureClipboard;
import com.aqishi.toolbox.vault.TotpAccount;
import com.aqishi.toolbox.vault.VaultCrypto;
import com.aqishi.toolbox.vault.VaultException;
import com.aqishi.toolbox.vault.VaultListener;
import com.aqishi.toolbox.vault.VaultService;
import com.aqishi.toolbox.vault.VaultState;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** TOTP accounts UI backed exclusively by the shared encrypted vault. */
public final class TotpPanel extends ToolPanel {
    private final VaultService service;
    private final SecureClipboard clipboard;
    private final List<TotpAccount> accounts = new ArrayList<>();
    private final List<AccountCard> cards = new ArrayList<>();
    private final VaultListener listener = this::onVaultStateChanged;

    private JPanel grid;
    private JScrollPane scroll;
    private JCheckBox showDirectly;
    private Timer refreshTimer;
    private boolean globalShowDirectly;

    public TotpPanel(VaultService service, SecureClipboard clipboard) {
        super("crypto", "totp.authenticator",
                "谷歌验证器", "Google Authenticator", "2FA", "OTP", "MFA",
                "双因素认证", "身份验证", "totp", "authenticator");
        this.service = Objects.requireNonNull(service, "service");
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        this.globalShowDirectly = Boolean.parseBoolean(
                ConfigManager.get("totp.show_directly", "true"));
    }

    @Override
    protected JComponent build() {
        JPanel content = new JPanel(new BorderLayout(0, UIUtils.SPACE_SM));
        content.setBorder(UIUtils.CONTENT_PADDING);
        content.add(buildToolbar(), BorderLayout.NORTH);
        grid = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
        scroll = new JScrollPane(grid);
        scroll.setBorder(BorderFactory.createLineBorder(
                javax.swing.UIManager.getColor("Component.borderColor"), 1));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { adjustColumns(); }
        });
        content.add(scroll, BorderLayout.CENTER);
        refreshTimer = new Timer(250, event -> tick());
        service.addListener(listener);
        refreshFromService();
        return new VaultAccessPanel(service, content);
    }

    private JComponent buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(UIUtils.SPACE_SM, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, UIUtils.SPACE_XS, 0));
        JButton add = UIUtils.button("手动添加", 96);
        JButton importLink = UIUtils.button("导入链接", 96);
        add.addActionListener(event -> showEditor(null));
        importLink.addActionListener(event -> importLink());
        actions.add(add);
        actions.add(importLink);
        toolbar.add(actions, BorderLayout.WEST);
        showDirectly = new JCheckBox("直接显示动态验证码", globalShowDirectly);
        showDirectly.addActionListener(event -> {
            globalShowDirectly = showDirectly.isSelected();
            ConfigManager.set("totp.show_directly", Boolean.toString(globalShowDirectly));
            ConfigManager.save();
            service.touch();
            cards.forEach(card -> card.setDefaultVisibility(globalShowDirectly));
        });
        toolbar.add(showDirectly, BorderLayout.EAST);
        return toolbar;
    }

    private void onVaultStateChanged(VaultState state) {
        Runnable refresh = this::refreshFromService;
        if (SwingUtilities.isEventDispatchThread()) refresh.run();
        else SwingUtilities.invokeLater(refresh);
    }

    private void refreshFromService() {
        accounts.clear();
        if (service.getState() == VaultState.UNLOCKED) {
            accounts.addAll(service.getTotpAccounts());
            if (refreshTimer != null && !refreshTimer.isRunning()) refreshTimer.start();
        } else if (refreshTimer != null) {
            refreshTimer.stop();
        }
        if (grid == null) return;
        grid.removeAll();
        cards.clear();
        if (accounts.isEmpty()) {
            grid.setLayout(new GridBagLayout());
            grid.setBorder(new EmptyBorder(60, 20, 60, 20));
            JLabel empty = new JLabel("暂无身份验证账户，请使用上方按钮添加或导入。");
            empty.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
            grid.add(empty);
        } else {
            grid.setBorder(new EmptyBorder(8, 8, 8, 8));
            for (TotpAccount account : accounts) {
                AccountCard card = new AccountCard(account);
                cards.add(card);
                grid.add(card);
            }
            adjustColumns();
        }
        grid.revalidate();
        grid.repaint();
    }

    private void adjustColumns() {
        if (grid == null || accounts.isEmpty()) return;
        int width = scroll == null ? 0 : scroll.getViewport().getWidth();
        int columns = Math.max(1, width / 276);
        grid.setLayout(new GridLayout(0, columns, 12, 12));
    }

    private void tick() {
        for (AccountCard card : cards) card.refreshCode();
    }

    private void importLink() {
        String input = JOptionPane.showInputDialog(getView(),
                "粘贴 otpauth://totp/... 链接：", "导入 TOTP",
                JOptionPane.PLAIN_MESSAGE);
        if (input == null || input.trim().isEmpty()) return;
        try {
            OtpUtils.OtpConfig parsed = OtpUtils.parseOtpAuthUrl(input.trim());
            TotpAccount account = new TotpAccount(
                    UUID.randomUUID().toString(), parsed.label, parsed.secret,
                    parsed.issuer, parsed.algorithm, parsed.digits, parsed.period, true);
            saveAccount(account);
        } catch (Exception error) {
            UIUtils.error(getView(), "导入链接失败，请检查格式");
        }
    }

    private void showEditor(TotpAccount existing) {
        service.touch();
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(getView()),
                existing == null ? "添加验证器账户" : "编辑验证器账户",
                Dialog.ModalityType.APPLICATION_MODAL);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 14, 12, 14));
        JTextField label = new JTextField(existing == null ? "" : existing.getLabel(), 22);
        JTextField issuer = new JTextField(existing == null ? "" : existing.getIssuer(), 22);
        JTextField secret = new JTextField(existing == null ? "" : existing.getSecret(), 22);
        JComboBox<String> algorithm = new JComboBox<>(new String[]{"SHA1", "SHA256", "SHA512"});
        JComboBox<Integer> digits = new JComboBox<>(new Integer[]{6, 8});
        JTextField period = new JTextField(
                Integer.toString(existing == null ? 30 : existing.getPeriod()), 8);
        JCheckBox direct = new JCheckBox("默认显示验证码",
                existing == null || existing.isShowDirectly());
        if (existing != null) {
            algorithm.setSelectedItem(existing.getAlgorithm());
            digits.setSelectedItem(existing.getDigits());
        }
        addField(form, 0, "名称：", label);
        addField(form, 1, "发行方：", issuer);
        addField(form, 2, "Base32 密钥：", secret);
        addField(form, 3, "算法：", algorithm);
        addField(form, 4, "位数：", digits);
        addField(form, 5, "周期（秒）：", period);
        GridBagConstraints directConstraints = constraints(0, 6);
        directConstraints.gridwidth = 2;
        form.add(direct, directConstraints);
        JButton save = new JButton("保存");
        GridBagConstraints button = constraints(0, 7);
        button.gridwidth = 2;
        button.anchor = GridBagConstraints.CENTER;
        form.add(save, button);
        save.addActionListener(event -> {
            String accountLabel = label.getText().trim();
            String accountSecret = secret.getText().replace(" ", "").replace("-", "").toUpperCase();
            int accountPeriod;
            try {
                accountPeriod = Integer.parseInt(period.getText().trim());
                if (accountPeriod <= 0) throw new NumberFormatException();
                if (accountLabel.isEmpty() || OtpUtils.decodeBase32(accountSecret).length == 0) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception error) {
                UIUtils.error(form, "名称、密钥或周期无效");
                return;
            }
            TotpAccount changed = new TotpAccount(
                    existing == null ? UUID.randomUUID().toString() : existing.getId(),
                    accountLabel, accountSecret, issuer.getText().trim(),
                    Objects.toString(algorithm.getSelectedItem(), "SHA1"),
                    (Integer) digits.getSelectedItem(), accountPeriod, direct.isSelected());
            save.setEnabled(false);
            persistAccount(changed).whenComplete((ignored, error) ->
                    SwingUtilities.invokeLater(() -> {
                        refreshFromService();
                        if (error == null) dialog.dispose();
                        else {
                            save.setEnabled(true);
                            UIUtils.error(form, I18n.get("vault.error.generic"));
                        }
                    }));
        });
        dialog.setContentPane(form);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(getView());
        dialog.setVisible(true);
    }

    private void saveAccount(TotpAccount account) {
        persistAccount(account).whenComplete((ignored, error) ->
                SwingUtilities.invokeLater(() -> {
                    refreshFromService();
                    if (error != null) UIUtils.error(getView(), I18n.get("vault.error.generic"));
                }));
    }

    private java.util.concurrent.CompletableFuture<Void> persistAccount(TotpAccount account) {
        List<TotpAccount> copy = new ArrayList<>(service.getTotpAccounts());
        boolean replaced = false;
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i).getId().equals(account.getId())) {
                copy.set(i, account);
                replaced = true;
                break;
            }
        }
        if (!replaced) copy.add(account);
        service.touch();
        return service.replaceTotpAccounts(copy);
    }

    private void deleteAccount(TotpAccount account) {
        if (JOptionPane.showConfirmDialog(getView(),
                "确定删除验证器账户 [" + account.getLabel() + "]？", "确认删除",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) return;
        List<TotpAccount> copy = new ArrayList<>(service.getTotpAccounts());
        for (Iterator<TotpAccount> iterator = copy.iterator(); iterator.hasNext(); ) {
            if (iterator.next().getId().equals(account.getId())) iterator.remove();
        }
        service.replaceTotpAccounts(copy).whenComplete((ignored, error) ->
                SwingUtilities.invokeLater(() -> {
                    refreshFromService();
                    if (error != null) UIUtils.error(getView(), I18n.get("vault.error.generic"));
                }));
    }

    private String currentCode(TotpAccount account) {
        byte[] key = null;
        try {
            key = OtpUtils.decodeBase32(account.getSecret());
            long step = System.currentTimeMillis() / 1000L / account.getPeriod();
            return OtpUtils.generateTOTP(key, step, account.getDigits(),
                    "Hmac" + account.getAlgorithm());
        } catch (Exception error) {
            return account.getDigits() == 8 ? "--------" : "------";
        } finally {
            VaultCrypto.wipe(key);
        }
    }

    private void copyCode(TotpAccount account) {
        try {
            clipboard.copySensitive(currentCode(account));
            service.touch();
        } catch (VaultException error) {
            UIUtils.error(getView(), I18n.get("vault.clipboard.error"));
        }
    }

    private static void addField(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints left = constraints(0, row);
        left.weightx = 0;
        form.add(new JLabel(label), left);
        GridBagConstraints right = constraints(1, row);
        right.weightx = 1;
        form.add(field, right);
    }

    private static GridBagConstraints constraints(int column, int row) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.insets = new Insets(5, 6, 5, 6);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        return constraints;
    }

    private final class AccountCard extends JPanel {
        private final TotpAccount account;
        private final JLabel code = new JLabel("", SwingConstants.CENTER);
        private final JLabel remaining = new JLabel("", SwingConstants.CENTER);
        private boolean revealed;

        private AccountCard(TotpAccount account) {
            super(new BorderLayout(8, 8));
            this.account = account;
            this.revealed = globalShowDirectly && account.isShowDirectly();
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(
                            javax.swing.UIManager.getColor("Component.borderColor")),
                    new EmptyBorder(10, 12, 10, 12)));
            JPanel header = new JPanel(new BorderLayout(6, 0));
            JLabel title = new JLabel(account.getLabel());
            title.setFont(UIUtils.titleFont().deriveFont(14f));
            header.add(title, BorderLayout.CENTER);
            JPanel commands = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
            JButton edit = new JButton("编辑");
            JButton delete = new JButton("删除");
            edit.addActionListener(event -> showEditor(account));
            delete.addActionListener(event -> deleteAccount(account));
            commands.add(edit);
            commands.add(delete);
            header.add(commands, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);

            code.setFont(new Font(Font.MONOSPACED, Font.BOLD, 26));
            add(code, BorderLayout.CENTER);
            JPanel footer = new JPanel(new BorderLayout(6, 0));
            JButton reveal = new JButton("显示/隐藏");
            JButton copy = new JButton("复制");
            reveal.addActionListener(event -> {
                revealed = !revealed;
                service.touch();
                refreshCode();
            });
            copy.addActionListener(event -> copyCode(account));
            footer.add(reveal, BorderLayout.WEST);
            footer.add(remaining, BorderLayout.CENTER);
            footer.add(copy, BorderLayout.EAST);
            add(footer, BorderLayout.SOUTH);
            refreshCode();
        }

        private void refreshCode() {
            String value = revealed ? currentCode(account)
                    : account.getDigits() == 8 ? "**** ****" : "*** ***";
            if (revealed && value.length() == 6) value = value.substring(0, 3) + " " + value.substring(3);
            if (revealed && value.length() == 8) value = value.substring(0, 4) + " " + value.substring(4);
            code.setText(value);
            long seconds = System.currentTimeMillis() / 1000L;
            remaining.setText((account.getPeriod() - seconds % account.getPeriod()) + "s");
        }

        private void setDefaultVisibility(boolean visible) {
            revealed = visible && account.isShowDirectly();
            refreshCode();
        }
    }
}
