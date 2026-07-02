package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Tuple;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.prefs.Preferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Redis 管理工具：连接管理、键浏览器、值编辑、命令控制台。
 */
public class RedisPanel extends ToolPanel {
    // Profile Management Support
    private JComboBox<String> profileCombo;
    private JButton saveProfileBtn;
    private JButton delProfileBtn;
    private final Map<String, RedisConfigProfile> profiles = new LinkedHashMap<>();
    private final Preferences prefs = Preferences.userNodeForPackage(RedisPanel.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean ignoreProfileEvents = false;
    private JTextField hostField;
    private JTextField portField;
    private JPasswordField passField;
    private JComboBox<Integer> dbCombo;
    private JButton connBtn;
    private boolean isConnected = false;

    private JTextField searchField;
    private JButton refreshBtn;
    private JList<String> keyList;
    private DefaultListModel<String> keyListModel;
    private JButton addKeyBtn;
    private JButton delKeyBtn;

    // Tree View Support
    private JCheckBox treeCheck;
    private JTextField delimiterField;
    private JPanel listOrTreeCardPanel;
    private CardLayout listOrTreeLayout;
    private JTree keyTree;
    private DefaultTreeModel treeModel;

    private JLabel keyNameLabel;
    private JLabel keyTypeLabel;
    private JTextField ttlField;
    private JButton updateTtlBtn;

    private JPanel valueCardPanel;
    private CardLayout valueCardLayout;

    // String Editor
    private JTextArea stringArea;

    // Hash Editor
    private JTable hashTable;
    private DefaultTableModel hashModel;
    private JButton addHashRowBtn;
    private JButton delHashRowBtn;

    // List Editor
    private JTable listTable;
    private DefaultTableModel listModel;
    private JButton addListRowBtn;
    private JButton delListRowBtn;

    // Set Editor
    private JTable setTable;
    private DefaultTableModel setModel;
    private JButton addSetRowBtn;
    private JButton delSetRowBtn;

    // ZSet Editor
    private JTable zsetTable;
    private DefaultTableModel zsetModel;
    private JButton addZsetRowBtn;
    private JButton delZsetRowBtn;

    private JButton saveValueBtn;

    // Console
    private JTextArea consoleOutput;
    private JTextField consoleInput;

    private Jedis jedis;
    private String currentSelectedKey = null;

    public RedisPanel() {
        super("dev", "redis.management",
                "Redis", "缓存", "NoSQL", "Key-Value", "数据库", "命令行", "Console");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 1. Connection Panel (North)
        JPanel connPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        connPanel.setBorder(BorderFactory.createTitledBorder("Redis 连接配置"));

        profileCombo = new JComboBox<>();
        profileCombo.setPreferredSize(new Dimension(130, 30));
        saveProfileBtn = new JButton("保存配置");
        delProfileBtn = new JButton("删除配置");

        hostField = new JTextField("127.0.0.1", 12);
        portField = new JTextField("6379", 5);
        passField = new JPasswordField(10);
        
        Integer[] dbs = new Integer[16];
        for (int i = 0; i < 16; i++) dbs[i] = i;
        dbCombo = new JComboBox<>(dbs);

        connBtn = UIUtils.button("连接", 80);

        connPanel.add(new JLabel("已存配置:"));
        connPanel.add(profileCombo);
        connPanel.add(saveProfileBtn);
        connPanel.add(delProfileBtn);
        connPanel.add(new JLabel(" | 主机:"));
        connPanel.add(hostField);
        connPanel.add(new JLabel("端口:"));
        connPanel.add(portField);
        connPanel.add(new JLabel("密码:"));
        connPanel.add(passField);
        connPanel.add(new JLabel("DB:"));
        connPanel.add(dbCombo);
        connPanel.add(connBtn);

        root.add(connPanel, BorderLayout.NORTH);

        // Main Workspace Split: Left (Keys), Right (Value Details & Console)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setDividerLocation(260);

        // 2. Left Panel: Key Browser
        JPanel leftPanel = new JPanel(new BorderLayout(6, 6));
        leftPanel.setBorder(BorderFactory.createTitledBorder("键浏览器"));

        JPanel leftTopPanel = new JPanel(new BorderLayout(4, 4));

        JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
        searchField = new JTextField("*");
        searchField.putClientProperty("JTextField.placeholderText", "匹配模式, 如 *");
        refreshBtn = new JButton("刷新");
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(refreshBtn, BorderLayout.EAST);
        leftTopPanel.add(searchPanel, BorderLayout.NORTH);

        JPanel treeCtrlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        treeCheck = new JCheckBox("树形展示");
        delimiterField = new JTextField(":", 2);
        delimiterField.setToolTipText("命名空间分隔符");
        delimiterField.setEnabled(false); // initially disabled since treeCheck is not selected
        treeCtrlPanel.add(treeCheck);
        treeCtrlPanel.add(new JLabel("分隔符:"));
        treeCtrlPanel.add(delimiterField);
        leftTopPanel.add(treeCtrlPanel, BorderLayout.SOUTH);

        leftPanel.add(leftTopPanel, BorderLayout.NORTH);

        listOrTreeLayout = new CardLayout();
        listOrTreeCardPanel = new JPanel(listOrTreeLayout);

        keyListModel = new DefaultListModel<>();
        keyList = new JList<>(keyListModel);
        keyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listOrTreeCardPanel.add(new JScrollPane(keyList), "LIST");

        treeModel = new DefaultTreeModel(new RedisKeyNode("Keys", null));
        keyTree = new JTree(treeModel);
        keyTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        keyTree.setCellRenderer(new RedisTreeCellRenderer());
        listOrTreeCardPanel.add(new JScrollPane(keyTree), "TREE");

        leftPanel.add(listOrTreeCardPanel, BorderLayout.CENTER);

        JPanel keyActionPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        addKeyBtn = new JButton("添加 Key");
        delKeyBtn = new JButton("删除 Key");
        keyActionPanel.add(addKeyBtn);
        keyActionPanel.add(delKeyBtn);
        leftPanel.add(keyActionPanel, BorderLayout.SOUTH);

        mainSplit.setLeftComponent(leftPanel);

        // 3. Right Panel: Workspace Tabs (Value & Console)
        JTabbedPane rightTabbedPane = new JTabbedPane();

        // Tab A: Value Editor
        JPanel valEditTab = new JPanel(new BorderLayout(8, 8));
        valEditTab.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // Metadata Subpanel (Key info & TTL)
        JPanel metaPanel = new JPanel(new GridBagLayout());
        metaPanel.setBorder(BorderFactory.createTitledBorder("键信息"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 6, 4, 6);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        metaPanel.add(new JLabel("名称:"), gbc);
        keyNameLabel = new JLabel("未选择");
        keyNameLabel.setFont(UIUtils.plainFont().deriveFont(Font.BOLD));
        gbc.gridx = 1; gbc.weightx = 1.0;
        metaPanel.add(keyNameLabel, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0;
        metaPanel.add(new JLabel("类型:"), gbc);
        keyTypeLabel = new JLabel("none");
        keyTypeLabel.setFont(UIUtils.plainFont().deriveFont(Font.BOLD));
        gbc.gridx = 3; gbc.weightx = 0.5;
        metaPanel.add(keyTypeLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        metaPanel.add(new JLabel("TTL (秒):"), gbc);
        ttlField = new JTextField(8);
        gbc.gridx = 1; gbc.weightx = 1.0;
        metaPanel.add(ttlField, gbc);

        updateTtlBtn = new JButton("修改 TTL / 持续化");
        gbc.gridx = 2; gbc.gridwidth = 2; gbc.weightx = 0.5;
        metaPanel.add(updateTtlBtn, gbc);

        valEditTab.add(metaPanel, BorderLayout.NORTH);

        // Value Card Container
        valueCardLayout = new CardLayout();
        valueCardPanel = new JPanel(valueCardLayout);

        // Card 1: None/Placeholder
        JPanel noneCard = new JPanel(new GridBagLayout());
        noneCard.add(new JLabel("请从左侧选择一个 Key 或连接到 Redis"));
        valueCardPanel.add(noneCard, "NONE");

        // Card 2: String Editor
        JPanel stringCard = new JPanel(new BorderLayout(4, 4));
        stringArea = new JTextArea();
        stringCard.add(new JScrollPane(stringArea), BorderLayout.CENTER);
        valueCardPanel.add(stringCard, "STRING");

        // Card 3: Hash Editor
        JPanel hashCard = new JPanel(new BorderLayout(4, 4));
        hashModel = new DefaultTableModel(new Object[]{"字段 (Field)", "值 (Value)"}, 0);
        hashTable = new JTable(hashModel);
        hashCard.add(new JScrollPane(hashTable), BorderLayout.CENTER);
        JPanel hashBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        addHashRowBtn = new JButton("+ 添加字段");
        delHashRowBtn = new JButton("- 删除字段");
        hashBtnRow.add(addHashRowBtn);
        hashBtnRow.add(delHashRowBtn);
        hashCard.add(hashBtnRow, BorderLayout.SOUTH);
        valueCardPanel.add(hashCard, "HASH");

        // Card 4: List Editor
        JPanel listCard = new JPanel(new BorderLayout(4, 4));
        listModel = new DefaultTableModel(new Object[]{"索引 (Index)", "值 (Value)"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1; // Index column is read-only
            }
        };
        listTable = new JTable(listModel);
        listCard.add(new JScrollPane(listTable), BorderLayout.CENTER);
        JPanel listBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        addListRowBtn = new JButton("+ 追加元素");
        delListRowBtn = new JButton("- 删除所选");
        listBtnRow.add(addListRowBtn);
        listBtnRow.add(delListRowBtn);
        listCard.add(listBtnRow, BorderLayout.SOUTH);
        valueCardPanel.add(listCard, "LIST");

        // Card 5: Set Editor
        JPanel setCard = new JPanel(new BorderLayout(4, 4));
        setModel = new DefaultTableModel(new Object[]{"成员 (Member)"}, 0);
        setTable = new JTable(setModel);
        setCard.add(new JScrollPane(setTable), BorderLayout.CENTER);
        JPanel setBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        addSetRowBtn = new JButton("+ 添加成员");
        delSetRowBtn = new JButton("- 删除所选");
        setBtnRow.add(addSetRowBtn);
        setBtnRow.add(delSetRowBtn);
        setCard.add(setBtnRow, BorderLayout.SOUTH);
        valueCardPanel.add(setCard, "SET");

        // Card 6: ZSet Editor
        JPanel zsetCard = new JPanel(new BorderLayout(4, 4));
        zsetModel = new DefaultTableModel(new Object[]{"分值 (Score)", "成员 (Member)"}, 0);
        zsetTable = new JTable(zsetModel);
        zsetCard.add(new JScrollPane(zsetTable), BorderLayout.CENTER);
        JPanel zsetBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        addZsetRowBtn = new JButton("+ 添加成员");
        delZsetRowBtn = new JButton("- 删除所选");
        zsetBtnRow.add(addZsetRowBtn);
        zsetBtnRow.add(delZsetRowBtn);
        zsetCard.add(zsetBtnRow, BorderLayout.SOUTH);
        valueCardPanel.add(zsetCard, "ZSET");

        valEditTab.add(valueCardPanel, BorderLayout.CENTER);

        // South Button (Save value)
        JPanel savePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        saveValueBtn = UIUtils.button("保存当前键的值", 150);
        savePanel.add(saveValueBtn);
        valEditTab.add(savePanel, BorderLayout.SOUTH);

        rightTabbedPane.addTab("数据编辑器", valEditTab);

        // Tab B: Console
        JPanel consoleTab = new JPanel(new BorderLayout(6, 6));
        consoleOutput = new JTextArea();
        consoleOutput.setEditable(false);
        consoleOutput.setFont(UIUtils.monoFont().deriveFont(12f));
        consoleOutput.setText("=== Redis 命令终端 ===\n支持直接输入 Redis 常见命令，如: PING, INFO, KEYS *, GET key 等\n\n");
        consoleTab.add(new JScrollPane(consoleOutput), BorderLayout.CENTER);

        JPanel cmdInputRow = new JPanel(new BorderLayout(4, 0));
        consoleInput = new JTextField();
        consoleInput.setFont(UIUtils.monoFont().deriveFont(13f));
        JButton runCmdBtn = new JButton("执行");
        cmdInputRow.add(new JLabel(" Redis > "), BorderLayout.WEST);
        cmdInputRow.add(consoleInput, BorderLayout.CENTER);
        cmdInputRow.add(runCmdBtn, BorderLayout.EAST);
        consoleTab.add(cmdInputRow, BorderLayout.SOUTH);

        rightTabbedPane.addTab("命令控制台", consoleTab);

        mainSplit.setRightComponent(rightTabbedPane);
        root.add(mainSplit, BorderLayout.CENTER);

        // Initialize connection state & actions
        toggleState(false);
        initActions(runCmdBtn);

        return root;
    }

    private void toggleState(boolean connected) {
        this.isConnected = connected;
        connBtn.setText(connected ? "断开" : "连接");
        connBtn.setEnabled(true);
        profileCombo.setEnabled(!connected);
        saveProfileBtn.setEnabled(!connected);
        delProfileBtn.setEnabled(!connected);
        hostField.setEnabled(!connected);
        portField.setEnabled(!connected);
        passField.setEnabled(!connected);
        dbCombo.setEnabled(!connected);

        searchField.setEnabled(connected);
        refreshBtn.setEnabled(connected);
        keyList.setEnabled(connected);
        keyTree.setEnabled(connected);
        treeCheck.setEnabled(connected);
        delimiterField.setEnabled(connected && treeCheck.isSelected());
        addKeyBtn.setEnabled(connected);
        delKeyBtn.setEnabled(connected);

        ttlField.setEnabled(connected);
        updateTtlBtn.setEnabled(connected);
        saveValueBtn.setEnabled(connected);
        consoleInput.setEnabled(connected);

        if (!connected) {
            keyListModel.clear();
            if (treeModel != null) {
                treeModel.setRoot(new RedisKeyNode("Keys", null));
            }
            currentSelectedKey = null;
            keyNameLabel.setText("未选择");
            keyTypeLabel.setText("none");
            ttlField.setText("");
            valueCardLayout.show(valueCardPanel, "NONE");
            if (jedis != null) {
                try { jedis.close(); } catch (Exception ignored) {}
                jedis = null;
            }
        }
    }

    private void initActions(JButton runCmdBtn) {
        // Profile Management Actions
        profileCombo.addActionListener(e -> {
            if (ignoreProfileEvents) return;
            String selectedName = (String) profileCombo.getSelectedItem();
            if (selectedName != null && profiles.containsKey(selectedName)) {
                RedisConfigProfile p = profiles.get(selectedName);
                hostField.setText(p.host);
                portField.setText(String.valueOf(p.port));
                passField.setText(p.password);
                dbCombo.setSelectedItem(p.db);
            }
        });

        saveProfileBtn.addActionListener(e -> {
            String name = UIUtils.input(null, "请输入连接配置名称：", "本地Redis");
            if (name == null || name.trim().isEmpty()) return;
            name = name.trim();
            
            int port = 6379;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                UIUtils.error(null, "端口格式不正确！");
                return;
            }
            
            RedisConfigProfile p = new RedisConfigProfile(
                name,
                hostField.getText().trim(),
                port,
                new String(passField.getPassword()),
                (Integer) dbCombo.getSelectedItem()
            );
            profiles.put(name, p);
            saveProfilesToPrefs();
            refreshProfilesCombo(name);
        });

        delProfileBtn.addActionListener(e -> {
            String selectedName = (String) profileCombo.getSelectedItem();
            if (selectedName == null || !profiles.containsKey(selectedName)) {
                UIUtils.info(null, "请先选择需要删除的配置项目。");
                return;
            }
            int opt = JOptionPane.showConfirmDialog(null, "确定要删除配置 \"" + selectedName + "\" 吗？", "提示", JOptionPane.YES_NO_OPTION);
            if (opt == JOptionPane.YES_OPTION) {
                profiles.remove(selectedName);
                saveProfilesToPrefs();
                refreshProfilesCombo(null);
            }
        });

        // Connect / Disconnect
        connBtn.addActionListener(e -> {
            if (isConnected) {
                toggleState(false);
            } else {
                connectRedis();
            }
        });

        // Key List Actions
        refreshBtn.addActionListener(e -> refreshKeys());
        searchField.addActionListener(e -> refreshKeys());

        keyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadKeyDetail(keyList.getSelectedValue());
            }
        });

        keyTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) keyTree.getLastSelectedPathComponent();
            if (node instanceof RedisKeyNode) {
                RedisKeyNode keyNode = (RedisKeyNode) node;
                if (keyNode.getFullName() != null) {
                    loadKeyDetail(keyNode.getFullName());
                } else {
                    loadKeyDetail(null);
                }
            } else {
                loadKeyDetail(null);
            }
        });

        treeCheck.addActionListener(e -> {
            boolean isTree = treeCheck.isSelected();
            delimiterField.setEnabled(isTree);
            listOrTreeLayout.show(listOrTreeCardPanel, isTree ? "TREE" : "LIST");
            refreshKeys();
        });

        delimiterField.addActionListener(e -> refreshKeys());

        addKeyBtn.addActionListener(e -> createNewKey());
        delKeyBtn.addActionListener(e -> deleteSelectedKey());

        // TTL and Value actions
        updateTtlBtn.addActionListener(e -> updateKeyTtl());
        saveValueBtn.addActionListener(e -> saveKeyValues());

        // Row adding / deleting for tables
        addHashRowBtn.addActionListener(e -> hashModel.addRow(new Object[]{"", ""}));
        delHashRowBtn.addActionListener(e -> removeSelectedRow(hashTable, hashModel));

        addListRowBtn.addActionListener(e -> listModel.addRow(new Object[]{listModel.getRowCount(), ""}));
        delListRowBtn.addActionListener(e -> removeSelectedRow(listTable, listModel));

        addSetRowBtn.addActionListener(e -> setModel.addRow(new Object[]{""}));
        delSetRowBtn.addActionListener(e -> removeSelectedRow(setTable, setModel));

        addZsetRowBtn.addActionListener(e -> zsetModel.addRow(new Object[]{"0", ""}));
        delZsetRowBtn.addActionListener(e -> removeSelectedRow(zsetTable, zsetModel));

        // Console Actions
        consoleInput.addActionListener(e -> executeConsoleCommand());
        runCmdBtn.addActionListener(e -> executeConsoleCommand());

        // Load profiles
        loadProfilesFromPrefs();
    }

    private void connectRedis() {
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            UIUtils.error(connBtn, "端口格式不正确");
            return;
        }
        String password = new String(passField.getPassword());
        int db = (Integer) dbCombo.getSelectedItem();

        connBtn.setEnabled(false);
        connBtn.setText("连接中...");

        new SwingWorker<Jedis, Void>() {
            @Override
            protected Jedis doInBackground() throws Exception {
                Jedis client = new Jedis(host, port, 5000);
                if (!password.isEmpty()) {
                    client.auth(password);
                }
                client.select(db);
                client.ping(); // test connection
                return client;
            }

            @Override
            protected void done() {
                try {
                    jedis = get();
                    toggleState(true);
                    refreshKeys();
                    consoleOutput.append("成功连接至 Redis 服务器: " + host + ":" + port + ", DB: " + db + "\n");
                } catch (Exception ex) {
                    toggleState(false);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    UIUtils.error(connBtn, "连接失败: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void refreshKeys() {
        if (jedis == null) return;
        String pattern = searchField.getText().trim();
        if (pattern.isEmpty()) pattern = "*";

        final String finalPattern = pattern;
        new SwingWorker<Set<String>, Void>() {
            @Override
            protected Set<String> doInBackground() {
                return jedis.keys(finalPattern);
            }

            @Override
            protected void done() {
                try {
                    Set<String> keys = get();
                    keyListModel.clear();
                    List<String> sortedKeys = new ArrayList<>(keys);
                    Collections.sort(sortedKeys);
                    for (String key : sortedKeys) {
                        keyListModel.addElement(key);
                    }
                    buildTree(keys);
                    if (keys.isEmpty()) {
                        valueCardLayout.show(valueCardPanel, "NONE");
                    }
                } catch (Exception ex) {
                    UIUtils.error(refreshBtn, "刷新键失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void loadKeyDetail(String key) {
        if (jedis == null || key == null) {
            currentSelectedKey = null;
            keyNameLabel.setText("未选择");
            keyTypeLabel.setText("none");
            ttlField.setText("");
            valueCardLayout.show(valueCardPanel, "NONE");
            return;
        }

        currentSelectedKey = key;
        keyNameLabel.setText(key);

        new SwingWorker<Map<String, Object>, Void>() {
            @Override
            protected Map<String, Object> doInBackground() {
                Map<String, Object> res = new HashMap<>();
                String type = jedis.type(key);
                long ttl = jedis.ttl(key);
                res.put("type", type);
                res.put("ttl", ttl);

                if ("string".equals(type)) {
                    res.put("value", jedis.get(key));
                } else if ("hash".equals(type)) {
                    res.put("value", jedis.hgetAll(key));
                } else if ("list".equals(type)) {
                    res.put("value", jedis.lrange(key, 0, -1));
                } else if ("set".equals(type)) {
                    res.put("value", jedis.smembers(key));
                } else if ("zset".equals(type)) {
                    res.put("value", jedis.zrangeWithScores(key, 0, -1));
                }
                return res;
            }

            @Override
            protected void done() {
                try {
                    Map<String, Object> data = get();
                    String type = (String) data.get("type");
                    long ttl = (Long) data.get("ttl");
                    keyTypeLabel.setText(type);
                    ttlField.setText(String.valueOf(ttl));

                    if ("string".equals(type)) {
                        stringArea.setText((String) data.get("value"));
                        valueCardLayout.show(valueCardPanel, "STRING");
                    } else if ("hash".equals(type)) {
                        hashModel.setRowCount(0);
                        @SuppressWarnings("unchecked")
                        Map<String, String> hash = (Map<String, String>) data.get("value");
                        for (Map.Entry<String, String> entry : hash.entrySet()) {
                            hashModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
                        }
                        valueCardLayout.show(valueCardPanel, "HASH");
                    } else if ("list".equals(type)) {
                        listModel.setRowCount(0);
                        @SuppressWarnings("unchecked")
                        List<String> list = (List<String>) data.get("value");
                        for (int i = 0; i < list.size(); i++) {
                            listModel.addRow(new Object[]{i, list.get(i)});
                        }
                        valueCardLayout.show(valueCardPanel, "LIST");
                    } else if ("set".equals(type)) {
                        setModel.setRowCount(0);
                        @SuppressWarnings("unchecked")
                        Set<String> set = (Set<String>) data.get("value");
                        for (String val : set) {
                            setModel.addRow(new Object[]{val});
                        }
                        valueCardLayout.show(valueCardPanel, "SET");
                    } else if ("zset".equals(type)) {
                        zsetModel.setRowCount(0);
                        @SuppressWarnings("unchecked")
                        Set<Tuple> zset = (Set<Tuple>) data.get("value");
                        for (Tuple t : zset) {
                            zsetModel.addRow(new Object[]{t.getScore(), t.getElement()});
                        }
                        valueCardLayout.show(valueCardPanel, "ZSET");
                    } else {
                        valueCardLayout.show(valueCardPanel, "NONE");
                    }
                } catch (Exception ex) {
                    UIUtils.error(keyList, "加载键内容失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void createNewKey() {
        if (jedis == null) return;

        JPanel p = new JPanel(new GridLayout(3, 2, 6, 6));
        JTextField newKeyField = new JTextField();
        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"string", "hash", "list", "set", "zset"});
        JTextField newValField = new JTextField();

        p.add(new JLabel("键名:"));
        p.add(newKeyField);
        p.add(new JLabel("类型:"));
        p.add(typeCombo);
        p.add(new JLabel("初始值 (Hash字段形如 k:v):"));
        p.add(newValField);

        int opt = JOptionPane.showConfirmDialog(null, p, "新建 Redis Key", JOptionPane.OK_CANCEL_OPTION);
        if (opt == JOptionPane.OK_OPTION) {
            String key = newKeyField.getText().trim();
            String type = (String) typeCombo.getSelectedItem();
            String val = newValField.getText().trim();

            if (key.isEmpty()) {
                UIUtils.error(addKeyBtn, "键名不能为空");
                return;
            }

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    if (jedis.exists(key)) {
                        throw new RuntimeException("该键已存在");
                    }
                    if ("string".equals(type)) {
                        jedis.set(key, val);
                    } else if ("hash".equals(type)) {
                        if (val.contains(":")) {
                            String[] parts = val.split(":", 2);
                            jedis.hset(key, parts[0], parts[1]);
                        } else {
                            jedis.hset(key, "default", val);
                        }
                    } else if ("list".equals(type)) {
                        jedis.rpush(key, val);
                    } else if ("set".equals(type)) {
                        jedis.sadd(key, val);
                    } else if ("zset".equals(type)) {
                        jedis.zadd(key, 0.0, val);
                    }
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        refreshKeys();
                        keyList.setSelectedValue(key, true);
                    } catch (Exception ex) {
                        UIUtils.error(addKeyBtn, "创建键失败: " + ex.getCause().getMessage());
                    }
                }
            }.execute();
        }
    }

    private void deleteSelectedKey() {
        if (jedis == null || currentSelectedKey == null) return;
        int opt = JOptionPane.showConfirmDialog(null, "确定删除键: " + currentSelectedKey + " 吗？", "提示", JOptionPane.YES_NO_OPTION);
        if (opt == JOptionPane.YES_OPTION) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    jedis.del(currentSelectedKey);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        refreshKeys();
                    } catch (Exception ex) {
                        UIUtils.error(delKeyBtn, "删除失败: " + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    private void updateKeyTtl() {
        if (jedis == null || currentSelectedKey == null) return;
        String ttlStr = ttlField.getText().trim();
        int ttl;
        try {
            ttl = Integer.parseInt(ttlStr);
        } catch (NumberFormatException ex) {
            UIUtils.error(ttlField, "TTL格式应为整数");
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                if (ttl < 0) {
                    jedis.persist(currentSelectedKey);
                } else {
                    jedis.expire(currentSelectedKey, ttl);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    UIUtils.info(null, "TTL 更新成功！");
                    loadKeyDetail(currentSelectedKey);
                } catch (Exception ex) {
                    UIUtils.error(ttlField, "修改 TTL 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void removeSelectedRow(JTable table, DefaultTableModel model) {
        int idx = table.getSelectedRow();
        if (idx >= 0) {
            model.removeRow(idx);
        }
    }

    private void saveKeyValues() {
        if (jedis == null || currentSelectedKey == null) return;
        String type = keyTypeLabel.getText();

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                // Remove first to rewrite list/set/zset
                if (!"string".equals(type) && !"hash".equals(type)) {
                    jedis.del(currentSelectedKey);
                }

                if ("string".equals(type)) {
                    jedis.set(currentSelectedKey, stringArea.getText());
                } else if ("hash".equals(type)) {
                    // Collect modified values
                    Map<String, String> currentHash = jedis.hgetAll(currentSelectedKey);
                    Set<String> fieldsInTable = new HashSet<>();
                    for (int i = 0; i < hashModel.getRowCount(); i++) {
                        String field = (String) hashModel.getValueAt(i, 0);
                        String value = (String) hashModel.getValueAt(i, 1);
                        if (field != null && !field.trim().isEmpty()) {
                            jedis.hset(currentSelectedKey, field, value);
                            fieldsInTable.add(field);
                        }
                    }
                    // Remove fields deleted in GUI
                    for (String field : currentHash.keySet()) {
                        if (!fieldsInTable.contains(field)) {
                            jedis.hdel(currentSelectedKey, field);
                        }
                    }
                } else if ("list".equals(type)) {
                    for (int i = 0; i < listModel.getRowCount(); i++) {
                        String val = (String) listModel.getValueAt(i, 1);
                        if (val != null) {
                            jedis.rpush(currentSelectedKey, val);
                        }
                    }
                } else if ("set".equals(type)) {
                    for (int i = 0; i < setModel.getRowCount(); i++) {
                        String val = (String) setModel.getValueAt(i, 0);
                        if (val != null && !val.trim().isEmpty()) {
                            jedis.sadd(currentSelectedKey, val);
                        }
                    }
                } else if ("zset".equals(type)) {
                    for (int i = 0; i < zsetModel.getRowCount(); i++) {
                        String scoreStr = String.valueOf(zsetModel.getValueAt(i, 0));
                        String member = (String) zsetModel.getValueAt(i, 1);
                        if (member != null && !member.trim().isEmpty()) {
                            double score = Double.parseDouble(scoreStr);
                            jedis.zadd(currentSelectedKey, score, member);
                        }
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    UIUtils.info(null, "保存成功！");
                    loadKeyDetail(currentSelectedKey);
                } catch (Exception ex) {
                    UIUtils.error(saveValueBtn, "保存失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void executeConsoleCommand() {
        if (jedis == null) return;
        String line = consoleInput.getText().trim();
        if (line.isEmpty()) return;

        consoleOutput.append("> " + line + "\n");
        consoleInput.setText("");

        // Parse args
        String[] args = line.split("\\s+");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    // Execute simple arbitrary Redis commands
                    String cmd = args[0].toUpperCase();
                    if ("PING".equals(cmd)) {
                        return jedis.ping();
                    } else if ("SET".equals(cmd) && args.length >= 3) {
                        return jedis.set(args[1], args[2]);
                    } else if ("GET".equals(cmd) && args.length >= 2) {
                        return jedis.get(args[1]);
                    } else if ("DEL".equals(cmd) && args.length >= 2) {
                        return String.valueOf(jedis.del(args[1]));
                    } else if ("KEYS".equals(cmd) && args.length >= 2) {
                        return String.valueOf(jedis.keys(args[1]));
                    } else if ("EXISTS".equals(cmd) && args.length >= 2) {
                        return String.valueOf(jedis.exists(args[1]));
                    } else if ("TTL".equals(cmd) && args.length >= 2) {
                        return String.valueOf(jedis.ttl(args[1]));
                    } else if ("TYPE".equals(cmd) && args.length >= 2) {
                        return jedis.type(args[1]);
                    } else if ("DBSIZE".equals(cmd)) {
                        return String.valueOf(jedis.dbSize());
                    } else if ("FLUSHDB".equals(cmd)) {
                        return jedis.flushDB();
                    } else if ("FLUSHALL".equals(cmd)) {
                        return jedis.flushAll();
                    } else if ("INFO".equals(cmd)) {
                        return jedis.info();
                    } else {
                        // General commands backup
                        return "暂未支持的控制台指令。可使用标准 PING/SET/GET/DEL/KEYS/EXISTS/TTL/TYPE/INFO 等。";
                    }
                } catch (Exception ex) {
                    return "ERROR: " + ex.getMessage();
                }
            }

            @Override
            protected void done() {
                try {
                    String res = get();
                    consoleOutput.append(res + "\n\n");
                    consoleOutput.setCaretPosition(consoleOutput.getDocument().getLength());
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void buildTree(Set<String> keys) {
        String delim = delimiterField.getText();
        if (delim.isEmpty()) {
            delim = ":";
        }

        RedisKeyNode newRoot = new RedisKeyNode("Keys", null);

        for (String key : keys) {
            String[] parts = key.split(Pattern.quote(delim));
            RedisKeyNode current = newRoot;
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (prefix.length() > 0) {
                    prefix.append(delim);
                }
                prefix.append(part);

                String fullPathForThisNode = (i == parts.length - 1) ? key : null;
                if (fullPathForThisNode == null && keys.contains(prefix.toString())) {
                    fullPathForThisNode = prefix.toString();
                }

                RedisKeyNode child = findChild(current, part);
                if (child == null) {
                    child = new RedisKeyNode(part, fullPathForThisNode);
                    current.add(child);
                } else {
                    if (fullPathForThisNode != null && child.getFullName() == null) {
                        child.setFullName(fullPathForThisNode);
                    }
                }
                current = child;
            }
        }

        treeModel.setRoot(newRoot);
        // Expand the root node by default
        keyTree.expandRow(0);
    }

    private RedisKeyNode findChild(RedisKeyNode parent, String name) {
        int count = parent.getChildCount();
        for (int i = 0; i < count; i++) {
            RedisKeyNode child = (RedisKeyNode) parent.getChildAt(i);
            if (child.getUserObject().equals(name)) {
                return child;
            }
        }
        return null;
    }

    static class RedisKeyNode extends DefaultMutableTreeNode {
        private String fullName; // null if not a real key
        private final String displayName;

        public RedisKeyNode(String displayName, String fullName) {
            super(displayName);
            this.displayName = displayName;
            this.fullName = fullName;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    static class RedisTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof RedisKeyNode) {
                RedisKeyNode node = (RedisKeyNode) value;
                if (node.getFullName() != null) {
                    setFont(getFont().deriveFont(Font.BOLD));
                    if (!sel) {
                        setForeground(UIManager.getColor("Component.accentColor"));
                    }
                } else {
                    setFont(getFont().deriveFont(Font.PLAIN));
                    if (!sel) {
                        setForeground(UIManager.getColor("Label.foreground"));
                    }
                }
            }
            return this;
        }
    }

    private void saveProfilesToPrefs() {
        try {
            String json = mapper.writeValueAsString(profiles);
            prefs.put("redis_profiles", json);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void loadProfilesFromPrefs() {
        try {
            String json = prefs.get("redis_profiles", null);
            if (json != null && !json.trim().isEmpty()) {
                Map<String, RedisConfigProfile> loaded = mapper.readValue(json, new TypeReference<LinkedHashMap<String, RedisConfigProfile>>(){});
                profiles.clear();
                profiles.putAll(loaded);
            }
            refreshProfilesCombo(null);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void refreshProfilesCombo(String selectName) {
        ignoreProfileEvents = true;
        profileCombo.removeAllItems();
        for (String name : profiles.keySet()) {
            profileCombo.addItem(name);
        }
        if (selectName != null) {
            profileCombo.setSelectedItem(selectName);
        } else if (profileCombo.getItemCount() > 0) {
            profileCombo.setSelectedIndex(0);
            String first = profileCombo.getItemAt(0);
            RedisConfigProfile p = profiles.get(first);
            hostField.setText(p.host);
            portField.setText(String.valueOf(p.port));
            passField.setText(p.password);
            dbCombo.setSelectedItem(p.db);
        }
        ignoreProfileEvents = false;
    }

    public static class RedisConfigProfile {
        public String name;
        public String host;
        public int port;
        public String password;
        public int db;

        public RedisConfigProfile() {}

        public RedisConfigProfile(String name, String host, int port, String password, int db) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.password = password;
            this.db = db;
        }
    }
}
