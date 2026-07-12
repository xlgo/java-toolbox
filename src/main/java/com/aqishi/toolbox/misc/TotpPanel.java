package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.crypto.OtpUtils;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 谷歌身份验证器 (TOTP) 面板。
 * 响应式网格平铺卡片布局：一行展示多个账号卡片，随窗口宽度拉伸自适应重排；
 * 全局和账号级别均支持“是否直接显示动态验证码”隐私配置；
 * 卡片上所有操作融入图标按钮：右上角“×”删除，账户名后紧跟“✏”编辑，动态码左“👁”查看，右“❐”复制。
 */
public class TotpPanel extends ToolPanel {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<TotpAccount> accounts = new ArrayList<>();

    // UI 组件
    private JPanel cardsPanel;
    private JPanel gridContainer;
    private JScrollPane scrollPane;
    private JCheckBox showDirectlyCheckbox;

    // 定时器与卡片对象缓存
    private Timer refreshTimer;
    private final List<AccountCard> cardComponents = new ArrayList<>();

    // 全局是否直接显示动态码
    private boolean globalShowDirectly = true;

    public TotpPanel() {
        super("crypto", "totp.authenticator",
                "谷歌验证器", "Google Authenticator", "2FA", "OTP", "MFA", "双因素认证", "身份验证", "totp", "authenticator");
        loadConfig();
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ---- 1. 顶部工具栏 ----
        JPanel topBar = new JPanel(new BorderLayout(10, 0));
        topBar.setBorder(new EmptyBorder(0, 2, 8, 2));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton addBtn = UIUtils.button("手动添加", 95);
        JButton importBtn = UIUtils.button("导入链接", 95);
        addBtn.addActionListener(e -> showAddDialog());
        importBtn.addActionListener(e -> showImportDialog());
        leftActions.add(addBtn);
        leftActions.add(importBtn);
        topBar.add(leftActions, BorderLayout.WEST);

        JPanel rightSettings = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        showDirectlyCheckbox = new JCheckBox("直接显示动态验证码", globalShowDirectly);
        showDirectlyCheckbox.setFont(UIUtils.plainFont());
        showDirectlyCheckbox.addActionListener(e -> handleGlobalShowToggle(showDirectlyCheckbox.isSelected()));
        rightSettings.add(showDirectlyCheckbox);
        topBar.add(rightSettings, BorderLayout.EAST);

        root.add(topBar, BorderLayout.NORTH);

        // ---- 2. 中部自适应网格卡片区 ----
        cardsPanel = new JPanel(new BorderLayout());
        
        gridContainer = new JPanel();
        gridContainer.setLayout(new FlowLayout(FlowLayout.LEFT, 12, 12));
        cardsPanel.add(gridContainer, BorderLayout.NORTH);

        scrollPane = new JScrollPane(cardsPanel);
        scrollPane.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1));
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scrollPane, BorderLayout.CENTER);

        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                adjustGridCols();
            }
        });

        refreshAccountListUI();
        startGlobalRefreshTimer();

        return root;
    }

    private void refreshAccountListUI() {
        gridContainer.removeAll();
        cardComponents.clear();

        if (accounts.isEmpty()) {
            gridContainer.setLayout(new GridBagLayout());
            gridContainer.setBorder(new EmptyBorder(60, 20, 60, 20));
            JLabel label = new JLabel("暂无身份验证账户。请点击左上角“手动添加”或“导入链接”开始使用。");
            label.setFont(UIUtils.plainFont().deriveFont(14f));
            label.setForeground(UIManager.getColor("Label.disabledForeground"));
            gridContainer.add(label);
        } else {
            gridContainer.setBorder(new EmptyBorder(12, 12, 12, 12));
            for (TotpAccount acct : accounts) {
                AccountCard card = new AccountCard(acct);
                gridContainer.add(card);
                cardComponents.add(card);
            }
            SwingUtilities.invokeLater(this::adjustGridCols);
        }

        gridContainer.revalidate();
        gridContainer.repaint();
    }

    private void adjustGridCols() {
        if (accounts.isEmpty()) return;

        int panelWidth = scrollPane.getViewport().getWidth();
        if (panelWidth <= 0) {
            panelWidth = scrollPane.getWidth() - 20;
        }
        if (panelWidth <= 0) return;

        int cardPreferredWidth = 250; 
        int gap = 12;
        int cols = Math.max(1, panelWidth / (cardPreferredWidth + gap));

        if (gridContainer.getLayout() instanceof GridLayout) {
            GridLayout currentLayout = (GridLayout) gridContainer.getLayout();
            if (currentLayout.getColumns() == cols) {
                return;
            }
        }

        gridContainer.setLayout(new GridLayout(0, cols, gap, gap));
        gridContainer.revalidate();
        gridContainer.repaint();
    }

    private void handleGlobalShowToggle(boolean selected) {
        this.globalShowDirectly = selected;
        ConfigManager.set("totp.show_directly", String.valueOf(selected));
        ConfigManager.save();

        for (AccountCard card : cardComponents) {
            card.setLocalShowState(selected);
        }
    }

    private void startGlobalRefreshTimer() {
        if (refreshTimer != null && refreshTimer.isRunning()) return;

        refreshTimer = new Timer(100, e -> {
            for (AccountCard card : cardComponents) {
                card.tickRefresh();
            }
        });
        refreshTimer.start();
    }

    private void copyCodeToClipboard(String rawCode) {
        if (rawCode == null || rawCode.isEmpty() || rawCode.startsWith("●") || rawCode.equals("------")) {
            UIUtils.error(getView(), "请先显示或计算出正确的验证码再进行复制");
            return;
        }
        String clean = rawCode.replace(" ", "");
        UIUtils.copyToClipboard(clean);
    }

    // ================================================================
    //  新增、导入和编辑对话框
    // ================================================================

    private void showAddDialog() {
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(getView()),
                "添加验证器账户", Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new GridBagLayout());
        dlg.setSize(380, 270);
        dlg.setResizable(false);
        dlg.setLocationRelativeTo(getView());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 12, 6, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        dlg.add(new JLabel("账户名称 (如邮箱):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField labelF = new JTextField();
        dlg.add(labelF, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        dlg.add(new JLabel("签发服务 (如 GitHub):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField issuerF = new JTextField();
        dlg.add(issuerF, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        dlg.add(new JLabel("密钥 (Base32):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField secretF = new JTextField();
        dlg.add(secretF, gbc);

        // 可见性保护勾选框
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 0;
        JCheckBox showDirectlyCheck = new JCheckBox("直接显示动态验证码", true);
        showDirectlyCheck.setFont(UIUtils.plainFont().deriveFont(12f));
        dlg.add(showDirectlyCheck, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        JButton saveBtn = UIUtils.button("确认添加", 100);
        saveBtn.addActionListener(e -> {
            String label = labelF.getText().trim();
            String issuer = issuerF.getText().trim();
            String secret = secretF.getText().trim().toUpperCase();
            boolean showDirectly = showDirectlyCheck.isSelected();

            if (label.isEmpty()) {
                UIUtils.error(labelF, "账户名称不能为空");
                return;
            }
            if (secret.isEmpty()) {
                UIUtils.error(secretF, "密钥不能为空");
                return;
            }
            try {
                OtpUtils.decodeBase32(secret);
            } catch (Exception ex) {
                UIUtils.error(secretF, "密钥格式非法: " + ex.getMessage());
                return;
            }

            TotpAccount newAcct = new TotpAccount(UUID.randomUUID().toString(), label, secret);
            if (!issuer.isEmpty()) {
                newAcct.setIssuer(issuer);
            }
            newAcct.setShowDirectly(showDirectly);

            accounts.add(newAcct);
            saveAccounts();
            refreshAccountListUI();
            dlg.dispose();
            UIUtils.info(getView(), "新账户添加成功");
        });
        dlg.add(saveBtn, gbc);

        dlg.setVisible(true);
    }

    private void showImportDialog() {
        String input = JOptionPane.showInputDialog(getView(),
                "请粘贴 otpauth://totp/... 格式的链接：",
                "导入 2FA 账户链接", JOptionPane.PLAIN_MESSAGE);

        if (input == null || input.trim().isEmpty()) return;

        try {
            OtpUtils.OtpConfig config = OtpUtils.parseOtpAuthUrl(input.trim());

            TotpAccount newAcct = new TotpAccount(UUID.randomUUID().toString(), config.label, config.secret);
            newAcct.setIssuer(config.issuer);
            newAcct.setAlgorithm(config.algorithm);
            newAcct.setDigits(config.digits);
            newAcct.setPeriod(config.period);
            newAcct.setShowDirectly(true); // 默认直接显示

            accounts.add(newAcct);
            saveAccounts();
            refreshAccountListUI();
            UIUtils.info(getView(), "从链接导入账户成功");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(getView(),
                    "导入失败，解析错误：\n" + ex.getMessage(),
                    "导入错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showEditDialog(TotpAccount acct) {
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(getView()),
                "编辑验证器账户", Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new GridBagLayout());
        dlg.setSize(400, 320);
        dlg.setResizable(false);
        dlg.setLocationRelativeTo(getView());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 12, 6, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        dlg.add(new JLabel("账户名称 (Label):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField labelF = new JTextField(acct.getLabel());
        dlg.add(labelF, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        dlg.add(new JLabel("签发服务 (Issuer):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField issuerF = new JTextField(acct.getIssuer() == null ? "" : acct.getIssuer());
        dlg.add(issuerF, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        dlg.add(new JLabel("密钥 (Base32 Secret):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField secretF = new JTextField(acct.getSecret());
        dlg.add(secretF, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        dlg.add(new JLabel("哈希算法:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JComboBox<String> algCombo = new JComboBox<>(new String[]{"SHA1", "SHA256", "SHA512"});
        algCombo.setSelectedItem(acct.getAlgorithm());
        dlg.add(algCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0;
        dlg.add(new JLabel("参数选择:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JPanel smallPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        smallPanel.add(new JLabel("位数:"));
        JComboBox<Integer> digitsCombo = new JComboBox<>(new Integer[]{6, 8});
        digitsCombo.setSelectedItem(acct.getDigits());
        smallPanel.add(digitsCombo);

        smallPanel.add(new JLabel("  步长:"));
        JSpinner periodSpinner = new JSpinner(new SpinnerNumberModel(acct.getPeriod(), 5, 300, 5));
        smallPanel.add(periodSpinner);
        dlg.add(smallPanel, gbc);

        // 直接显示动态码勾选框
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2; gbc.weightx = 0;
        JCheckBox showDirectlyCheck = new JCheckBox("直接显示动态验证码", acct.isShowDirectly());
        showDirectlyCheck.setFont(UIUtils.plainFont().deriveFont(12f));
        dlg.add(showDirectlyCheck, gbc);

        // 保存动作
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        JButton saveBtn = UIUtils.button("保存修改", 100);
        saveBtn.addActionListener(e -> {
            String label = labelF.getText().trim();
            String issuer = issuerF.getText().trim();
            String secret = secretF.getText().trim().toUpperCase();
            String alg = (String) algCombo.getSelectedItem();
            Integer digits = (Integer) digitsCombo.getSelectedItem();
            Integer period = (Integer) periodSpinner.getValue();
            boolean showDirectly = showDirectlyCheck.isSelected();

            if (label.isEmpty()) {
                UIUtils.error(labelF, "账户名称不能为空");
                return;
            }
            if (secret.isEmpty()) {
                UIUtils.error(secretF, "密钥不能为空");
                return;
            }
            try {
                OtpUtils.decodeBase32(secret);
            } catch (Exception ex) {
                UIUtils.error(secretF, "密钥格式非法: " + ex.getMessage());
                return;
            }

            acct.setLabel(label);
            acct.setIssuer(issuer.isEmpty() ? null : issuer);
            acct.setSecret(secret);
            acct.setAlgorithm(alg);
            acct.setDigits(digits != null ? digits : 6);
            acct.setPeriod(period != null ? period : 30);
            acct.setShowDirectly(showDirectly);

            saveAccounts();
            refreshAccountListUI();
            dlg.dispose();
            UIUtils.info(getView(), "修改账户配置成功");
        });
        dlg.add(saveBtn, gbc);

        dlg.setVisible(true);
    }

    private void handleDeleteAccount(TotpAccount acct) {
        int opt = JOptionPane.showConfirmDialog(getView(),
                "确定要删除 2FA 账户 [" + acct.getLabel() + "] 吗？\n该操作不可撤销！",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (opt == JOptionPane.YES_OPTION) {
            accounts.remove(acct);
            saveAccounts();
            refreshAccountListUI();
            UIUtils.info(getView(), "账户已删除");
        }
    }

    // ================================================================
    //  自适应垂直精致网格卡片 (AccountCard)
    // ================================================================

    private class AccountCard extends JPanel {
        private final TotpAccount acct;

        private final JLabel codeLabel;
        private final JButton eyeBtn;
        private final JProgressBar timeProgress;

        private boolean localShowCode;
        private long toastExpireTime = 0;

        public AccountCard(TotpAccount acct) {
            this.acct = acct;
            // 初始化显示状态：当账号配置了直接展示且全局同样直接展示时，方默认展示明文
            this.localShowCode = acct.isShowDirectly() && globalShowDirectly;

            setLayout(new GridBagLayout());
            setPreferredSize(new Dimension(250, 165));
            setMinimumSize(new Dimension(220, 155));

            // 卡片瓷贴细线描边和内填充
            // 将顶部空白从 10 减少到 2，留给右上角删除按钮
            setBorder(new CompoundBorder(
                    new LineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                    new EmptyBorder(2, 12, 10, 12)
            ));
            setBackground(UIManager.getColor("Panel.background"));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 2, 2, 2);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            // ------------------------------------------------
            // 1. 顶部第一行：服务名 (删除按钮单独定位在右上角)
            // ------------------------------------------------
            String serviceName = acct.getIssuer() != null && !acct.getIssuer().isEmpty() ? acct.getIssuer() : "未命名服务";
            JLabel serviceLabel = new JLabel(serviceName);
            serviceLabel.setFont(UIUtils.titleFont().deriveFont(14f));

            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0; gbc.gridwidth = 1;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(6, 2, 2, 2); // 顶部稍微空一点，让文字排版好看
            add(serviceLabel, gbc);

            // ------------------------------------------------
            // 右上角：删除账户 × 按钮 (紧贴右上角边缘，移除顶部空白)
            // ------------------------------------------------
            JButton delIconBtn = new JButton("×");
            delIconBtn.setFont(new Font("Arial", Font.BOLD, 15));
            delIconBtn.setForeground(new Color(239, 83, 80)); // 柔红
            delIconBtn.putClientProperty("JButton.buttonType", "toolBarButton");
            delIconBtn.setFocusPainted(false);
            delIconBtn.setMargin(new Insets(2, 4, 2, 4));
            delIconBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
            delIconBtn.setToolTipText("删除账户");
            delIconBtn.addActionListener(e -> handleDeleteAccount(acct));

            gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.0; gbc.gridwidth = 1;
            gbc.anchor = GridBagConstraints.NORTHEAST;
            gbc.insets = new Insets(0, 0, 0, 0); // 顶部完全贴近边缘
            add(delIconBtn, gbc);

            // ------------------------------------------------
            // 2. 第二行：账户邮箱 + 紧贴其后的编辑按钮 ✎
            // ------------------------------------------------
            JPanel emailRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            emailRow.setOpaque(false);

            JLabel emailLabel = new JLabel(acct.getLabel());
            emailLabel.setFont(UIUtils.plainFont().deriveFont(11f));
            emailLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            emailRow.add(emailLabel);

            // 紧贴名字后面的 ✎ 编辑按钮，更换为更加清晰、容易识别的 ✎ 符号
            JButton editIconBtn = new JButton("✎");
            editIconBtn.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 12));
            editIconBtn.putClientProperty("JButton.buttonType", "toolBarButton");
            editIconBtn.setFocusPainted(false);
            editIconBtn.setMargin(new Insets(0, 2, 0, 2));
            editIconBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
            editIconBtn.setToolTipText("编辑账号");
            editIconBtn.addActionListener(e -> showEditDialog(acct));
            emailRow.add(editIconBtn);

            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 1.0; gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(0, 2, 4, 2);
            add(emailRow, gbc);

            // ------------------------------------------------
            // 3. 第三行：夹击排列的动态码区（👁 ＋ 动态码 ＋ ❐），改用 FlowLayout 紧密排列
            // ------------------------------------------------
            JPanel codeRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
            codeRow.setOpaque(false);

            // 左：眼睛查看按钮 (👁)
            eyeBtn = new JButton(localShowCode ? "🙈" : "👁");
            eyeBtn.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 13));
            eyeBtn.putClientProperty("JButton.buttonType", "toolBarButton");
            eyeBtn.setFocusPainted(false);
            eyeBtn.setMargin(new Insets(2, 4, 2, 4));
            eyeBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
            eyeBtn.setToolTipText(localShowCode ? "隐藏验证码" : "查看验证码");
            eyeBtn.addActionListener(e -> toggleShowCode());
            codeRow.add(eyeBtn);

            // 中：验证码主体 Label (大字号 33，相比原先 22 增大 50%)
            codeLabel = new JLabel("------");
            codeLabel.setFont(new Font("Monospaced", Font.BOLD, 33));
            codeLabel.setForeground(new Color(33, 150, 243));
            codeLabel.setHorizontalAlignment(SwingConstants.CENTER);
            codeLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
            codeLabel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        String val = getCalculatedCodeValue();
                        if (val != null && !val.isEmpty() && !val.startsWith("●") && !val.equals("------")) {
                            copyCodeToClipboard(val);
                            toastExpireTime = System.currentTimeMillis() + 1500;
                        } else {
                            copyCodeToClipboard(val);
                        }
                    } else {
                        toggleShowCode();
                    }
                }
            });
            codeRow.add(codeLabel);

            // 右：一键复制按钮 (❐) (紧贴验证码右侧)
            JButton copyIconBtn = new JButton("❐");
            copyIconBtn.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 12));
            copyIconBtn.putClientProperty("JButton.buttonType", "toolBarButton");
            copyIconBtn.setFocusPainted(false);
            copyIconBtn.setMargin(new Insets(2, 4, 2, 4));
            copyIconBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
            copyIconBtn.setToolTipText("复制验证码");
            copyIconBtn.addActionListener(e -> {
                String val = getCalculatedCodeValue();
                if (val != null && !val.isEmpty() && !val.startsWith("●") && !val.equals("------")) {
                    copyCodeToClipboard(val);
                    toastExpireTime = System.currentTimeMillis() + 1500;
                } else {
                    copyCodeToClipboard(val);
                }
            });
            codeRow.add(copyIconBtn);

            gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 1.0; gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(4, 2, 4, 2);
            add(codeRow, gbc);

            // ------------------------------------------------
            // 4. 最底部第四行：精致的倒计时进度条
            // ------------------------------------------------
            timeProgress = new JProgressBar(0, 100);
            timeProgress.setStringPainted(true);
            timeProgress.setFont(UIUtils.plainFont().deriveFont(9f));
            timeProgress.setPreferredSize(new Dimension(180, 8));

            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(2, 2, 2, 2);
            add(timeProgress, gbc);

            tickRefresh();
        }

        private void toggleShowCode() {
            this.localShowCode = !localShowCode;
            eyeBtn.setText(localShowCode ? "🙈" : "👁");
            eyeBtn.setToolTipText(localShowCode ? "隐藏验证码" : "查看验证码");
            tickRefresh();
        }

        private String getCalculatedCodeValue() {
            try {
                byte[] keyBytes = OtpUtils.decodeBase32(acct.getSecret());
                long nowSeconds = System.currentTimeMillis() / 1000L;
                long timeStep = nowSeconds / acct.getPeriod();
                String internalAlg = "Hmac" + acct.getAlgorithm().toUpperCase();
                return OtpUtils.generateTOTP(keyBytes, timeStep, acct.getDigits(), internalAlg);
            } catch (Exception e) {
                return "------";
            }
        }

        public void setLocalShowState(boolean show) {
            // 如果单账号被设置为禁止直接显示，全局开启时也需服从卡片自己的隐私设置
            this.localShowCode = acct.isShowDirectly() && show;
            eyeBtn.setText(localShowCode ? "🙈" : "👁");
            eyeBtn.setToolTipText(localShowCode ? "隐藏验证码" : "查看验证码");
            tickRefresh();
        }

        public void tickRefresh() {
            long now = System.currentTimeMillis();
            long periodMs = acct.getPeriod() * 1000L;
            long elapsed = now % periodMs;
            long remainingMs = periodMs - elapsed;
            int remainingSec = (int) Math.ceil(remainingMs / 1000.0);

            int progress = (int) (remainingMs * 100 / periodMs);
            timeProgress.setValue(progress);
            
            if (now < toastExpireTime) {
                timeProgress.setString("已复制");
            } else {
                timeProgress.setString(remainingSec + "s");
            }

            if (remainingSec <= 5) {
                timeProgress.setForeground(new Color(239, 83, 80));
            } else {
                timeProgress.setForeground(UIManager.getColor("ProgressBar.foreground"));
            }

            if (localShowCode) {
                String code = getCalculatedCodeValue();
                String formatted;
                if (code.length() == 6) {
                    formatted = code.substring(0, 3) + " " + code.substring(3);
                } else if (code.length() == 8) {
                    formatted = code.substring(0, 4) + " " + code.substring(4);
                } else {
                    formatted = code;
                }
                codeLabel.setText(formatted);
                codeLabel.setFont(new Font("Monospaced", Font.BOLD, 33)); // 变大50%
            } else {
                int digits = acct.getDigits();
                String mask = digits == 8 ? "●●●● ●●●●" : "●●● ●●●";
                codeLabel.setText(mask);
                codeLabel.setFont(new Font("Monospaced", Font.PLAIN, 27)); // 变大50%
            }
        }
    }

    // ================================================================
    //  序列化配置
    // ================================================================

    private void loadConfig() {
        accounts.clear();
        String json = ConfigManager.get("totp.accounts", "[]");
        try {
            List<TotpAccount> loaded = mapper.readValue(json, new TypeReference<List<TotpAccount>>() {});
            accounts.addAll(loaded);
        } catch (Exception ignored) {
        }

        String showStr = ConfigManager.get("totp.show_directly", "true");
        this.globalShowDirectly = Boolean.parseBoolean(showStr);
    }

    private void saveAccounts() {
        try {
            String json = mapper.writeValueAsString(accounts);
            ConfigManager.set("totp.accounts", json);
            ConfigManager.save();
        } catch (Exception ignored) {
        }
    }

    // ================================================================
    //  2FA 账户实体配置类
    // ================================================================

    public static class TotpAccount {
        private String id;
        private String label;
        private String secret;
        private String issuer;
        private String algorithm = "SHA1";
        private int digits = 6;
        private int period = 30;
        private boolean showDirectly = true; // 新增属性：默认启动时可直接展示

        public TotpAccount() {
        }

        public TotpAccount(String id, String label, String secret) {
            this.id = id;
            this.label = label;
            this.secret = secret;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }

        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

        public int getDigits() { return digits; }
        public void setDigits(int digits) { this.digits = digits; }

        public int getPeriod() { return period; }
        public void setPeriod(int period) { this.period = period; }

        public boolean isShowDirectly() { return showDirectly; }
        public void setShowDirectly(boolean showDirectly) { this.showDirectly = showDirectly; }

        @Override
        public String toString() {
            return label;
        }
    }
}
