package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 账号密码管理器面板。
 * 支持主密码设置、验证，AES 密文保存，账号检索以及增删改查。
 */
public class AccountManagerPanel extends ToolPanel {

    private static final String DATA_FILE_NAME = "toolbox-passwords.enc";
    private static final String MAGIC_HEADER = "TOOLBOX_PWD_MGR";

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel container = new JPanel(cardLayout);
    private final ObjectMapper mapper = new ObjectMapper();

    // 内存中缓存的密钥字节数组与原始主密码（用于重设密码等）
    private byte[] aesKey;
    private String masterPassword;
    // 加密后的账号列表节点
    private ArrayNode accountsNode;

    // UI 组件：解锁卡片
    private JPasswordField unlockPassField;
    // UI 组件：初始化卡片
    private JPasswordField setupPassField;
    private JPasswordField setupConfirmField;

    // UI 组件：主视图卡片
    private JTable accountTable;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> rowSorter;
    private JTextField searchField;
    private JComboBox<String> categoryFilterCombo;
    private boolean isUpdatingCombo = false;
    private JButton copyUserBtn;
    private JButton copyPassBtn;
    private JButton copyUrlBtn;
    private JButton goUrlBtn;
    private JButton editBtn;
    private JButton deleteBtn;

    public AccountManagerPanel() {
        super("crypto", "account.manager",
                "密码管理", "账号密码", "密码簿", "Password Manager", "Account", "Keeper");
    }

    @Override
    protected JComponent build() {
        // 构建三种卡片
        container.add(buildUnlockCard(), "UNLOCK");
        container.add(buildSetupCard(), "SETUP");
        container.add(buildMainCard(), "MAIN");

        // 根据文件是否存在切换卡片
        checkStateAndSwitch();

        return container;
    }

    private void checkStateAndSwitch() {
        File f = new File(DATA_FILE_NAME);
        if (!f.exists()) {
            cardLayout.show(container, "SETUP");
        } else {
            unlockPassField.setText("");
            cardLayout.show(container, "UNLOCK");
        }
    }

    /**
     * 构建设置主密码界面
     */
    private JPanel buildSetupCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "首次使用：初始化主密码",
                TitledBorder.LEFT, TitledBorder.TOP, UIUtils.titleFont().deriveFont(14f)
        ));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 15, 10, 15);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 提示信息
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        JLabel tipLabel = new JLabel("<html>请设置您的主密码。该密码将作为本工具所有账号数据的加密密钥。<br><font color='red'>注意：主密码无法找回，请务必牢记！</font></html>");
        tipLabel.setFont(UIUtils.plainFont());
        form.add(tipLabel, gbc);

        // 主密码输入
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0;
        JLabel label1 = new JLabel("主 密 码：");
        label1.setFont(UIUtils.plainFont());
        form.add(label1, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        setupPassField = new JPasswordField(20);
        setupPassField.setFont(UIUtils.monoFont());
        form.add(setupPassField, gbc);

        // 确认主密码
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        JLabel label2 = new JLabel("确认密码：");
        label2.setFont(UIUtils.plainFont());
        form.add(label2, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        setupConfirmField = new JPasswordField(20);
        setupConfirmField.setFont(UIUtils.monoFont());
        form.add(setupConfirmField, gbc);

        // 初始化按钮
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        JButton initBtn = UIUtils.button("初始化数据库", 150);
        initBtn.addActionListener(e -> handleSetup());
        form.add(initBtn, gbc);

        panel.add(form);
        return panel;
    }

    /**
     * 处理主密码设置
     */
    private void handleSetup() {
        char[] pass = setupPassField.getPassword();
        char[] confirm = setupConfirmField.getPassword();
        if (pass.length == 0) {
            UIUtils.error(setupPassField, "主密码不能为空！");
            return;
        }
        if (!java.util.Arrays.equals(pass, confirm)) {
            UIUtils.error(setupConfirmField, "两次输入的密码不一致！");
            return;
        }

        String pwd = new String(pass);
        try {
            // 生成 AES 密钥
            byte[] keyBytes = deriveKey(pwd);
            ObjectNode rootNode = mapper.createObjectNode();
            rootNode.put("magic", MAGIC_HEADER);
            rootNode.putArray("accounts");

            // 加密保存
            String jsonStr = mapper.writeValueAsString(rootNode);
            byte[] encrypted = encryptAES(jsonStr, keyBytes);
            Files.write(new File(DATA_FILE_NAME).toPath(), encrypted);

            // 存入内存
            this.aesKey = keyBytes;
            this.masterPassword = pwd;
            this.accountsNode = (ArrayNode) rootNode.get("accounts");

            // 切换到主界面
            loadTableData();
            cardLayout.show(container, "MAIN");

            setupPassField.setText("");
            setupConfirmField.setText("");
        } catch (Exception ex) {
            UIUtils.error(setupPassField, "初始化失败: " + ex.getMessage());
        }
    }

    /**
     * 构建输入主密码解锁界面
     */
    private JPanel buildUnlockCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "输入主密码解锁密码簿",
                TitledBorder.LEFT, TitledBorder.TOP, UIUtils.titleFont().deriveFont(14f)
        ));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 15, 10, 15);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JLabel label = new JLabel("主密码：");
        label.setFont(UIUtils.plainFont());
        form.add(label, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        unlockPassField = new JPasswordField(20);
        unlockPassField.setFont(UIUtils.monoFont());
        // 回车直接触发解锁
        unlockPassField.addActionListener(e -> handleUnlock());
        form.add(unlockPassField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        JButton unlockBtn = UIUtils.button("解锁密码簿", 120);
        unlockBtn.addActionListener(e -> handleUnlock());
        form.add(unlockBtn, gbc);

        panel.add(form);
        return panel;
    }

    /**
     * 处理解锁逻辑
     */
    private void handleUnlock() {
        char[] pass = unlockPassField.getPassword();
        if (pass.length == 0) {
            UIUtils.error(unlockPassField, "主密码不能为空！");
            return;
        }

        String pwd = new String(pass);
        try {
            byte[] keyBytes = deriveKey(pwd);
            File f = new File(DATA_FILE_NAME);
            if (!f.exists()) {
                checkStateAndSwitch();
                return;
            }

            byte[] cipherBytes = Files.readAllBytes(f.toPath());
            String decryptedJson;
            try {
                decryptedJson = decryptAES(cipherBytes, keyBytes);
            } catch (Exception ex) {
                UIUtils.error(unlockPassField, "主密码错误，加解密失败！");
                return;
            }

            ObjectNode rootNode = (ObjectNode) mapper.readTree(decryptedJson);
            if (rootNode == null || !MAGIC_HEADER.equals(rootNode.path("magic").asText())) {
                UIUtils.error(unlockPassField, "数据校验失败，可能是密码错误或文件损坏！");
                return;
            }

            this.aesKey = keyBytes;
            this.masterPassword = pwd;
            this.accountsNode = (ArrayNode) rootNode.get("accounts");

            // 加载主列表
            loadTableData();
            cardLayout.show(container, "MAIN");
            unlockPassField.setText("");
        } catch (Exception ex) {
            UIUtils.error(unlockPassField, "加载失败: " + ex.getMessage());
        }
    }

    /**
     * 构建主账号管理界面
     */
    private JPanel buildMainCard() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // --- 顶部栏：搜索 + 新建/修改主密码/锁定 ---
        JPanel topPanel = new JPanel(new BorderLayout(10, 0));

        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        searchField = new JTextField(15);
        searchField.putClientProperty("JTextField.placeholderText", "搜索账号/用户名/网址...");
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
        });
        searchBar.add(searchField);

        categoryFilterCombo = new JComboBox<>(new String[]{"全部"});
        categoryFilterCombo.setPreferredSize(new Dimension(120, 30));
        categoryFilterCombo.addActionListener(e -> filterTable());
        searchBar.add(new JLabel(" 快速筛选:"));
        searchBar.add(categoryFilterCombo);

        topPanel.add(searchBar, BorderLayout.WEST);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton addBtn = UIUtils.button("添加账号", 100);
        addBtn.addActionListener(e -> showAccountDialog(null, -1));

        JButton changePwdBtn = UIUtils.button("修改主密码", 110);
        changePwdBtn.addActionListener(e -> showChangeMasterPwdDialog());

        JButton lockBtn = UIUtils.button("锁定密码簿", 100);
        lockBtn.addActionListener(e -> handleLock());

        rightActions.add(addBtn);
        rightActions.add(changePwdBtn);
        rightActions.add(lockBtn);
        topPanel.add(rightActions, BorderLayout.EAST);

        root.add(topPanel, BorderLayout.NORTH);

        // --- 中部：账号列表表格 ---
        String[] columns = {"名称/用途", "账号/用户名", "密码", "网址/地址"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // 不可双击编辑
            }
        };

        accountTable = new JTable(tableModel);
        accountTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accountTable.setFont(UIUtils.plainFont());
        accountTable.setRowHeight(28);
        accountTable.getTableHeader().setFont(UIUtils.titleFont().deriveFont(12f));

        rowSorter = new TableRowSorter<>(tableModel);
        accountTable.setRowSorter(rowSorter);

        JScrollPane tableScroll = new JScrollPane(accountTable);
        root.add(tableScroll, BorderLayout.CENTER);

        // --- 底部：选中记录的快捷复制与操作 ---
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        bottomPanel.setBorder(BorderFactory.createTitledBorder("记录操作栏"));

        JPanel copyBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        copyUserBtn = UIUtils.button("复制用户名", 100);
        copyPassBtn = UIUtils.button("复制密码", 100);
        copyUrlBtn = UIUtils.button("复制网址", 100);
        goUrlBtn = UIUtils.button("访问网址", 100);

        copyUserBtn.setEnabled(false);
        copyPassBtn.setEnabled(false);
        copyUrlBtn.setEnabled(false);
        goUrlBtn.setEnabled(false);

        copyBar.add(copyUserBtn);
        copyBar.add(copyPassBtn);
        copyBar.add(copyUrlBtn);
        copyBar.add(goUrlBtn);
        bottomPanel.add(copyBar, BorderLayout.WEST);

        JPanel editBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        editBtn = UIUtils.button("编辑", 80);
        deleteBtn = UIUtils.button("删除", 80);

        editBtn.setEnabled(false);
        deleteBtn.setEnabled(false);

        editBar.add(editBtn);
        editBar.add(deleteBtn);
        bottomPanel.add(editBar, BorderLayout.EAST);

        root.add(bottomPanel, BorderLayout.SOUTH);

        // 表格选择监听
        accountTable.getSelectionModel().addListSelectionListener(e -> {
            boolean selected = accountTable.getSelectedRow() != -1;
            copyUserBtn.setEnabled(selected);
            copyPassBtn.setEnabled(selected);
            copyUrlBtn.setEnabled(selected);
            goUrlBtn.setEnabled(selected);
            editBtn.setEnabled(selected);
            deleteBtn.setEnabled(selected);
        });

        // 绑定按钮动作
        copyUserBtn.addActionListener(e -> copyField(1));
        copyPassBtn.addActionListener(e -> copyField(2));
        copyUrlBtn.addActionListener(e -> copyField(3));
        goUrlBtn.addActionListener(e -> visitUrl());
        editBtn.addActionListener(e -> handleEditSelected());
        deleteBtn.addActionListener(e -> handleDeleteSelected());

        return root;
    }

    private void filterTable() {
        if (isUpdatingCombo) return;
        String q = searchField.getText().trim();
        String cat = (categoryFilterCombo != null) ? (String) categoryFilterCombo.getSelectedItem() : "全部";

        java.util.List<RowFilter<Object, Object>> filters = new java.util.ArrayList<>();
        if (!q.isEmpty()) {
            filters.add(RowFilter.regexFilter("(?i)" + Pattern.quote(q)));
        }
        if (cat != null && !"全部".equals(cat)) {
            filters.add(RowFilter.regexFilter("^" + Pattern.quote(cat) + "$", 0));
        }

        if (filters.isEmpty()) {
            rowSorter.setRowFilter(null);
        } else {
            rowSorter.setRowFilter(RowFilter.andFilter(filters));
        }
    }

    private void loadTableData() {
        tableModel.setRowCount(0);
        if (accountsNode == null) return;
        java.util.Set<String> categories = new java.util.TreeSet<>();
        for (int i = 0; i < accountsNode.size(); i++) {
            ObjectNode acct = (ObjectNode) accountsNode.get(i);
            String nameVal = acct.path("name").asText();
            tableModel.addRow(new Object[]{
                    nameVal,
                    acct.path("username").asText(),
                    "●●●●●●", // 隐藏显示
                    acct.path("url").asText()
            });
            if (!nameVal.trim().isEmpty()) {
                categories.add(nameVal.trim());
            }
        }

        if (categoryFilterCombo != null) {
            String selected = (String) categoryFilterCombo.getSelectedItem();
            isUpdatingCombo = true;
            categoryFilterCombo.removeAllItems();
            categoryFilterCombo.addItem("全部");
            for (String cat : categories) {
                categoryFilterCombo.addItem(cat);
            }
            if (selected != null) {
                categoryFilterCombo.setSelectedItem(selected);
            } else {
                categoryFilterCombo.setSelectedIndex(0);
            }
            isUpdatingCombo = false;
        }
    }

    private int getSelectedModelIndex() {
        int viewRow = accountTable.getSelectedRow();
        if (viewRow == -1) return -1;
        return accountTable.convertRowIndexToModel(viewRow);
    }

    private void copyField(int colIndex) {
        int modelRow = getSelectedModelIndex();
        if (modelRow == -1) return;
        ObjectNode acct = (ObjectNode) accountsNode.get(modelRow);
        String val = "";
        switch (colIndex) {
            case 1: val = acct.path("username").asText(); break;
            case 2: val = acct.path("password").asText(); break;
            case 3: val = acct.path("url").asText(); break;
        }
        UIUtils.copyToClipboard(val);
    }

    private void visitUrl() {
        int modelRow = getSelectedModelIndex();
        if (modelRow == -1) return;
        ObjectNode acct = (ObjectNode) accountsNode.get(modelRow);
        String url = acct.path("url").asText();
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(container, "无法打开网址: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleEditSelected() {
        int modelRow = getSelectedModelIndex();
        if (modelRow == -1) return;
        ObjectNode acct = (ObjectNode) accountsNode.get(modelRow);
        showAccountDialog(acct, modelRow);
    }

    private void handleDeleteSelected() {
        int modelRow = getSelectedModelIndex();
        if (modelRow == -1) return;
        ObjectNode acct = (ObjectNode) accountsNode.get(modelRow);
        int opt = JOptionPane.showConfirmDialog(
                container, "确定要删除账号记录 [" + acct.path("name").asText() + "] 吗？",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        );
        if (opt == JOptionPane.YES_OPTION) {
            accountsNode.remove(modelRow);
            saveDataFile();
            loadTableData();
        }
    }

    private void handleLock() {
        // 清空密码缓冲
        this.aesKey = null;
        this.masterPassword = null;
        this.accountsNode = null;
        checkStateAndSwitch();
    }

    /**
     * 弹出账号表单对话框（新增/编辑）
     */
    private void showAccountDialog(ObjectNode account, int index) {
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(container),
                account == null ? "添加账号" : "编辑账号", Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new GridBagLayout());
        dlg.setSize(380, 240);
        dlg.setResizable(false);
        dlg.setLocationRelativeTo(container);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 12, 6, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        dlg.add(new JLabel("用途/名称："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        java.util.Set<String> defaultNames = new java.util.TreeSet<>();
        defaultNames.add("GitHub");
        defaultNames.add("邮箱");
        defaultNames.add("服务器");
        defaultNames.add("数据库");
        defaultNames.add("网站");
        defaultNames.add("Wi-Fi");
        if (accountsNode != null) {
            for (int i = 0; i < accountsNode.size(); i++) {
                String n = accountsNode.get(i).path("name").asText().trim();
                if (!n.isEmpty()) {
                    defaultNames.add(n);
                }
            }
        }
        DefaultComboBoxModel<String> comboModel = new DefaultComboBoxModel<>();
        for (String dn : defaultNames) {
            comboModel.addElement(dn);
        }
        JComboBox<String> nameCombo = new JComboBox<>(comboModel);
        nameCombo.setEditable(true);
        dlg.add(nameCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        dlg.add(new JLabel("用户名/账号："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField userF = new JTextField();
        dlg.add(userF, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        dlg.add(new JLabel("密      码："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JPasswordField passF = new JPasswordField();
        dlg.add(passF, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        dlg.add(new JLabel("网址/地址："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField urlF = new JTextField();
        dlg.add(urlF, gbc);

        // 回填数据
        if (account != null) {
            nameCombo.setSelectedItem(account.path("name").asText());
            userF.setText(account.path("username").asText());
            passF.setText(account.path("password").asText());
            urlF.setText(account.path("url").asText());
        }

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        JButton saveBtn = UIUtils.button("保存", 80);
        saveBtn.addActionListener(e -> {
            Object selItem = nameCombo.getSelectedItem();
            String name = (selItem == null ? "" : selItem.toString()).trim();
            String user = userF.getText().trim();
            String pass = new String(passF.getPassword()).trim();
            String url = urlF.getText().trim();

            if (name.isEmpty()) {
                UIUtils.error(nameCombo, "名称不能为空！");
                return;
            }

            ObjectNode target;
            if (account == null) {
                target = accountsNode.addObject();
            } else {
                target = (ObjectNode) accountsNode.get(index);
            }
            target.put("name", name);
            target.put("username", user);
            target.put("password", pass);
            target.put("url", url);

            saveDataFile();
            loadTableData();
            dlg.dispose();
        });
        dlg.add(saveBtn, gbc);

        dlg.setVisible(true);
    }

    /**
     * 修改主密码的对话框
     */
    private void showChangeMasterPwdDialog() {
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(container),
                "修改主密码", Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new GridBagLayout());
        dlg.setSize(380, 200);
        dlg.setResizable(false);
        dlg.setLocationRelativeTo(container);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 12, 6, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        dlg.add(new JLabel("当前主密码："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JPasswordField oldF = new JPasswordField();
        dlg.add(oldF, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        dlg.add(new JLabel("新 主 密 码："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JPasswordField newF = new JPasswordField();
        dlg.add(newF, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        dlg.add(new JLabel("确认新密码："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JPasswordField confirmF = new JPasswordField();
        dlg.add(confirmF, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        JButton saveBtn = UIUtils.button("确定修改", 100);
        saveBtn.addActionListener(e -> {
            String oldP = new String(oldF.getPassword());
            String newP = new String(newF.getPassword());
            String confP = new String(confirmF.getPassword());

            if (!oldP.equals(this.masterPassword)) {
                UIUtils.error(oldF, "当前主密码错误！");
                return;
            }
            if (newP.isEmpty()) {
                UIUtils.error(newF, "新密码不能为空！");
                return;
            }
            if (!newP.equals(confP)) {
                UIUtils.error(confirmF, "两次输入的新密码不一致！");
                return;
            }

            try {
                byte[] newKey = deriveKey(newP);
                this.masterPassword = newP;
                this.aesKey = newKey;
                saveDataFile();
                dlg.dispose();
                JOptionPane.showMessageDialog(container, "主密码修改成功！", "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                UIUtils.error(newF, "修改失败: " + ex.getMessage());
            }
        });
        dlg.add(saveBtn, gbc);

        dlg.setVisible(true);
    }

    private void saveDataFile() {
        try {
            ObjectNode rootNode = mapper.createObjectNode();
            rootNode.put("magic", MAGIC_HEADER);
            rootNode.set("accounts", accountsNode);

            String jsonStr = mapper.writeValueAsString(rootNode);
            byte[] encrypted = encryptAES(jsonStr, aesKey);
            Files.write(new File(DATA_FILE_NAME).toPath(), encrypted);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(container, "保存数据失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // --- AES 核心加密解密辅助函数 ---

    private byte[] deriveKey(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        // 取前 16 字节作为 128 位 AES 密钥
        byte[] key = new byte[16];
        System.arraycopy(hash, 0, key, 0, 16);
        return key;
    }

    private byte[] encryptAES(String plainText, byte[] keyBytes) throws Exception {
        byte[] ivBytes = new byte[16];
        new SecureRandom().nextBytes(ivBytes);

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));

        byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] cipherBytes = cipher.doFinal(plainBytes);

        // 拼接 IV + Ciphertext
        byte[] result = new byte[16 + cipherBytes.length];
        System.arraycopy(ivBytes, 0, result, 0, 16);
        System.arraycopy(cipherBytes, 0, result, 16, cipherBytes.length);
        return result;
    }

    private String decryptAES(byte[] cipherBytes, byte[] keyBytes) throws Exception {
        if (cipherBytes.length < 16) {
            throw new IllegalArgumentException("密文过短");
        }
        byte[] ivBytes = new byte[16];
        System.arraycopy(cipherBytes, 0, ivBytes, 0, 16);

        byte[] encryptedData = new byte[cipherBytes.length - 16];
        System.arraycopy(cipherBytes, 16, encryptedData, 0, encryptedData.length);

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));

        byte[] plainBytes = cipher.doFinal(encryptedData);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }
}
