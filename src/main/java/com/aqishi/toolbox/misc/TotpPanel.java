package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.crypto.OtpUtils;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.VaultAccessPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** TOTP accounts UI backed exclusively by the shared encrypted vault. */
public final class TotpPanel extends ToolPanel {
    /** 验证码字号：这是本页唯一需要一眼扫到的信息，明显大于正文 */
    private static final float CODE_FONT_SIZE = 28f;

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
        JPanel root = Layouts.page();

        grid = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
        grid.setOpaque(false);
        // 网格贴顶放：视口比内容高时 JViewport 会把视图拉到与自己等高，
        // GridLayout 随之把每行拉成几百像素，验证码卡片里就会空出一大块
        JPanel gridHolder = Layouts.box();
        gridHolder.add(grid, BorderLayout.NORTH);
        // 卡片网格自带内边距，用透明滚动区承载才不会在卡片上盖出一块控件底色
        scroll = Fields.scrollTransparent(gridHolder);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { adjustColumns(); }
        });

        // 账号列表独占一张铺满型卡片，添加 / 导入 / 全局显示开关收进标题栏，
        // 页面就不再需要一条单独的工具条，验证码网格吃掉全部剩余高度
        Card list = Card.flush("身份验证账户");
        list.setContent(scroll);
        for (JComponent action : buildToolbarActions()) {
            list.addHeaderAction(action);
        }
        root.add(list, BorderLayout.CENTER);

        refreshTimer = new Timer(250, event -> tick());
        service.addListener(listener);
        refreshFromService();
        return new VaultAccessPanel(service, root);
    }

    /** 卡片标题栏动作，按从左到右的顺序返回；主操作「手动添加」在最右 */
    private List<JComponent> buildToolbarActions() {
        JButton add = Buttons.primary("手动添加");
        JButton importLink = Buttons.secondary("导入链接");
        add.addActionListener(event -> showEditor(null));
        importLink.addActionListener(event -> importLink());
        showDirectly = Fields.check("直接显示动态验证码", globalShowDirectly);
        showDirectly.addActionListener(event -> {
            globalShowDirectly = showDirectly.isSelected();
            ConfigManager.set("totp.show_directly", Boolean.toString(globalShowDirectly));
            ConfigManager.save();
            service.touch();
            cards.forEach(card -> card.setDefaultVisibility(globalShowDirectly));
        });
        List<JComponent> actions = new ArrayList<>();
        actions.add(showDirectly);
        actions.add(importLink);
        actions.add(add);
        return actions;
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
            grid.setBorder(KitBorders.padding(60, 20, 60, 20));
            JLabel empty = Fields.caption("暂无身份验证账户，请使用上方按钮添加或导入。");
            grid.add(empty);
        } else {
            grid.setBorder(KitBorders.padding(Tokens.SPACE_MD));
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
        JPanel form = Layouts.box(0, Tokens.SPACE_LG);
        form.setOpaque(true);
        form.setBorder(KitBorders.padding(Tokens.SPACE_LG));
        JTextField label = Fields.text(existing == null ? "" : existing.getLabel());
        JTextField issuer = Fields.text(existing == null ? "" : existing.getIssuer());
        JTextField secret = Fields.mono(existing == null ? "" : existing.getSecret());
        JComboBox<String> algorithm = Fields.combo(new String[]{"SHA1", "SHA256", "SHA512"}, 120);
        JComboBox<Integer> digits = Fields.combo(new Integer[]{6, 8}, 90);
        JTextField period = Fields.text(
                Integer.toString(existing == null ? 30 : existing.getPeriod()));
        JCheckBox direct = Fields.check("默认显示验证码",
                existing == null || existing.isShowDirectly());
        if (existing != null) {
            algorithm.setSelectedItem(existing.getAlgorithm());
            digits.setSelectedItem(existing.getDigits());
        }
        // 下拉框用 rowCompact 保持自身宽度；三个文本框拉满，标签列右对齐后整体对齐
        FormGrid fields = new FormGrid();
        fields.row("名称：", label);
        fields.row("发行方：", issuer);
        fields.row("Base32 密钥：", secret);
        fields.rowCompact("算法：", algorithm);
        fields.rowCompact("位数：", digits);
        fields.row("周期（秒）：", period);
        fields.fullRow(direct);
        form.add(fields, BorderLayout.CENTER);
        JButton save = Buttons.primary("保存");
        ActionBar actions = new ActionBar();
        actions.right(save);
        form.add(actions, BorderLayout.SOUTH);
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

    /**
     * 单个验证器账户卡片：标题带放账户名与编辑 / 删除，中间是大号等宽验证码，
     * 紧跟一条剩余有效期进度条，底部状态条放显示切换、秒数与复制。
     */
    private final class AccountCard extends Card {
        private final TotpAccount account;
        private final JLabel code = new JLabel("", SwingConstants.CENTER);
        private final JLabel remaining = new JLabel("", SwingConstants.CENTER);
        private final JProgressBar countdown = new JProgressBar();
        private boolean revealed;

        private AccountCard(TotpAccount account) {
            super(account.getLabel(), null);
            this.account = account;
            this.revealed = globalShowDirectly && account.isShowDirectly();

            JButton edit = Buttons.ghost("编辑");
            JButton delete = Buttons.danger("删除");
            edit.addActionListener(event -> showEditor(account));
            delete.addActionListener(event -> deleteAccount(account));
            addHeaderAction(edit);
            addHeaderAction(delete);

            code.setFont(Tokens.fontMono().deriveFont(Font.BOLD, CODE_FONT_SIZE));
            code.setForeground(Tokens.foreground());
            // 进度条紧贴验证码：剩余秒数用长度表达，比只读数字更容易被余光捕捉
            countdown.setMinimum(0);
            countdown.setMaximum(account.getPeriod());
            countdown.setBorderPainted(false);
            countdown.setStringPainted(false);
            JPanel body = Layouts.box(0, Tokens.SPACE_SM);
            body.add(code, BorderLayout.CENTER);
            body.add(countdown, BorderLayout.SOUTH);
            setContent(body);

            JButton reveal = Buttons.ghost("显示/隐藏");
            JButton copy = Buttons.secondary("复制");
            reveal.addActionListener(event -> {
                revealed = !revealed;
                service.touch();
                refreshCode();
            });
            copy.addActionListener(event -> copyCode(account));
            remaining.setFont(Tokens.fontCaption());
            remaining.setForeground(Tokens.mutedForeground());
            JPanel footer = Layouts.box(Tokens.SPACE_SM, 0);
            footer.add(reveal, BorderLayout.WEST);
            footer.add(remaining, BorderLayout.CENTER);
            footer.add(copy, BorderLayout.EAST);
            setFooter(footer);
            refreshCode();
        }

        private void refreshCode() {
            String value = revealed ? currentCode(account)
                    : account.getDigits() == 8 ? "**** ****" : "*** ***";
            if (revealed && value.length() == 6) value = value.substring(0, 3) + " " + value.substring(3);
            if (revealed && value.length() == 8) value = value.substring(0, 4) + " " + value.substring(4);
            code.setText(value);
            long seconds = System.currentTimeMillis() / 1000L;
            long left = account.getPeriod() - seconds % account.getPeriod();
            remaining.setText(left + "s");
            countdown.setValue((int) left);
        }

        private void setDefaultVisibility(boolean visible) {
            revealed = visible && account.isShowDirectly();
            refreshCode();
        }
    }
}
