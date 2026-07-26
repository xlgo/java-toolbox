package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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
    /** 连接状态：常驻连接卡片标题右侧，文字 + 语义色双重表达 */
    private JLabel connStatusLabel;
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
    private JButton runCmdBtn;

    private Jedis jedis;
    private String connHost;
    private int connPort;
    private String connPassword;
    private int connDb;
    private String currentSelectedKey = null;

    public RedisPanel() {
        super("dev", "redis.management",
                "Redis", "缓存", "NoSQL", "Key-Value", "数据库", "命令行", "Console");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // 连接配置固定在顶部，高度自适应；主体是「键浏览 → 值详情」的左右两栏
        root.add(buildConnectionCard(), BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(buildKeyBrowser(), buildWorkspace(), 0.3), BorderLayout.CENTER);

        // Initialize connection state & actions
        toggleState(false);
        initActions(runCmdBtn);

        return root;
    }

    /** 标签页内容容器：比 page() 更薄的一层内边距，免得和外层页边距叠加过厚 */
    private static JPanel tabPage() {
        JPanel page = Layouts.box(0, Tokens.SPACE_MD);
        page.setBorder(KitBorders.padding(Tokens.SPACE_MD));
        return page;
    }

    // ==================== 顶部：连接配置卡片 ====================

    /** 连接卡片：三行表单 + 标题右侧的「连接 / 断开」主操作与状态文字 */
    private Card buildConnectionCard() {
        profileCombo = Fields.combo(new String[0], 160);
        saveProfileBtn = Buttons.secondary("保存配置");
        delProfileBtn = Buttons.danger("删除配置");

        hostField = Fields.text("127.0.0.1");
        portField = Fields.text("6379");
        passField = Fields.password();

        Integer[] dbs = new Integer[16];
        for (int i = 0; i < 16; i++) dbs[i] = i;
        dbCombo = Fields.combo(dbs, 80);

        connBtn = Buttons.primary("连接");

        // 连接状态先用文字说清楚，再叠加语义色：只靠颜色的话色觉障碍用户读不出来。
        // 必须在 addHeaderAction 之前填好文案——卡片会按加入时的首选宽度锁定最大宽度
        connStatusLabel = new JLabel();
        connStatusLabel.setFont(Tokens.fontCaption());
        bindConnectionStatus();

        // 主机/端口、密码/DB 各自成对，定长的一半靠右，可变长的一半吃掉剩余宽度
        JPanel hostRow = Layouts.box(Tokens.SPACE_MD, 0);
        hostRow.add(hostField, BorderLayout.CENTER);
        hostRow.add(trailingField("端口:", portField), BorderLayout.EAST);

        JPanel passRow = Layouts.box(Tokens.SPACE_MD, 0);
        passRow.add(passField, BorderLayout.CENTER);
        passRow.add(trailingField("DB:", dbCombo), BorderLayout.EAST);

        FormGrid form = new FormGrid();
        form.rowCompact("已存配置:", profileCombo, Layouts.wrapRow(saveProfileBtn, delProfileBtn));
        form.row("主机:", hostRow);
        form.row("密码:", passRow);

        Card card = Card.titled("Redis 连接配置");
        card.setContent(form);
        card.addHeaderAction(connStatusLabel);
        card.addHeaderAction(connBtn);
        return card;
    }

    /**
     * 让行尾按钮在窄窗口下保持完整文案。
     *
     * <p>{@code GridBagLayout} 在容器小于首选尺寸时会整体退回「最小尺寸」布局，
     * 没有权重的行尾列会被压到最小宽度、文字省略成「修…」；把最小宽度提到首选宽度后，
     * 有权重的输入列会先让出空间。</p>
     */
    private static JButton keepWidth(JButton button) {
        button.setMinimumSize(button.getPreferredSize());
        return button;
    }

    /** 行尾的「小标签 + 定长输入」组合，保持标签紧贴输入框 */
    private static JPanel trailingField(String label, JComponent field) {
        JPanel group = Layouts.box(Tokens.SPACE_SM, 0);
        group.add(Fields.label(label), BorderLayout.WEST);
        group.add(field, BorderLayout.EAST);
        return group;
    }

    /**
     * 连接状态跟随「连接 / 断开」按钮的文案变化。
     *
     * <p>监听按钮文案而不是往 toggleState()/connectRedis() 里插 UI 代码，是为了不碰连接管理逻辑。</p>
     */
    private void bindConnectionStatus() {
        updateConnStatus();
        connBtn.addPropertyChangeListener("text", e -> updateConnStatus());
    }

    private void updateConnStatus() {
        String text = connBtn.getText();
        if ("断开".equals(text)) {
            connStatusLabel.setText("状态：已连接");
            connStatusLabel.setForeground(Tokens.success());
        } else if ("连接中...".equals(text)) {
            connStatusLabel.setText("状态：连接中");
            connStatusLabel.setForeground(Tokens.warning());
        } else {
            connStatusLabel.setText("状态：未连接");
            connStatusLabel.setForeground(Tokens.danger());
        }
    }

    // ==================== 左栏：键浏览器 ====================

    /** 左栏：匹配模式 + 列表/树两种视图 + 增删按钮，全部装进一张卡片 */
    private Card buildKeyBrowser() {
        searchField = Fields.text("*", "匹配模式, 如 *");
        refreshBtn = Buttons.secondary("刷新");

        JPanel searchRow = Layouts.box(Tokens.SPACE_SM, 0);
        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(refreshBtn, BorderLayout.EAST);

        treeCheck = Fields.check("树形展示", false);
        delimiterField = Fields.text(":");
        delimiterField.setToolTipText("命名空间分隔符");
        delimiterField.setEnabled(false); // initially disabled since treeCheck is not selected

        // 分隔符输入吃掉复选框之后的剩余宽度，窄栏下也不会把标签挤没
        JPanel treeRow = Layouts.box(Tokens.SPACE_MD, 0);
        treeRow.add(treeCheck, BorderLayout.WEST);
        JPanel delimiterGroup = Layouts.box(Tokens.SPACE_SM, 0);
        delimiterGroup.add(Fields.label("分隔符:"), BorderLayout.WEST);
        delimiterGroup.add(delimiterField, BorderLayout.CENTER);
        treeRow.add(delimiterGroup, BorderLayout.CENTER);

        listOrTreeLayout = new CardLayout();
        listOrTreeCardPanel = new JPanel(listOrTreeLayout);
        listOrTreeCardPanel.setOpaque(false);

        keyListModel = new DefaultListModel<>();
        keyList = new JList<>(keyListModel);
        keyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // 卡片底色与列表底色相同，放进有内边距的卡片要靠细描边才看得出边界
        listOrTreeCardPanel.add(Fields.scrollBoxed(keyList), "LIST");

        treeModel = new DefaultTreeModel(new RedisKeyNode("Keys", null));
        keyTree = new JTree(treeModel);
        keyTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        keyTree.setCellRenderer(new RedisTreeCellRenderer());
        listOrTreeCardPanel.add(Fields.scrollBoxed(keyTree), "TREE");

        addKeyBtn = Buttons.secondary("添加 Key");
        delKeyBtn = Buttons.danger("删除 Key");

        JPanel body = Layouts.box(0, Tokens.SPACE_SM);
        body.add(Layouts.stack(Tokens.SPACE_SM, searchRow, treeRow), BorderLayout.NORTH);
        body.add(listOrTreeCardPanel, BorderLayout.CENTER);
        // 两个按钮等宽平分整行，与原来的 GridLayout(1,2) 一致
        body.add(Layouts.columns(Tokens.SPACE_SM, addKeyBtn, delKeyBtn), BorderLayout.SOUTH);

        Card card = Card.titled("键浏览器");
        card.setContent(body);
        return card;
    }

    // ==================== 右栏：值编辑与命令控制台 ====================

    private JComponent buildWorkspace() {
        JTabbedPane rightTabbedPane = new JTabbedPane();
        rightTabbedPane.setBorder(null);
        rightTabbedPane.addTab("数据编辑器", buildValueTab());
        rightTabbedPane.addTab("命令控制台", buildConsoleTab());
        return rightTabbedPane;
    }

    /** 数据编辑器：上面是键信息表单，下面按值类型切换的编辑区占满剩余空间 */
    private JComponent buildValueTab() {
        keyNameLabel = new JLabel("未选择");
        keyNameLabel.setFont(Tokens.fontBodyStrong());
        keyTypeLabel = new JLabel("none");
        keyTypeLabel.setFont(Tokens.fontBodyStrong());
        ttlField = Fields.text("");
        updateTtlBtn = keepWidth(Buttons.secondary("修改 TTL / 持续化"));

        FormGrid metaForm = new FormGrid();
        metaForm.row("名称:", keyNameLabel, trailingLabel("类型:", keyTypeLabel));
        metaForm.row("TTL (秒):", ttlField, updateTtlBtn);

        Card metaCard = Card.titled("键信息");
        metaCard.setContent(metaForm);

        // ----- 六种值类型共用一个 CardLayout，卡片外壳只画一次 -----
        valueCardLayout = new CardLayout();
        valueCardPanel = new JPanel(valueCardLayout);
        valueCardPanel.setOpaque(false);

        JPanel noneCard = new JPanel(new GridBagLayout());
        noneCard.setOpaque(false);
        noneCard.add(Fields.caption("请从左侧选择一个 Key 或连接到 Redis"));
        valueCardPanel.add(noneCard, "NONE");

        stringArea = new JTextArea();
        stringArea.setFont(Tokens.fontMono());
        stringArea.setBorder(KitBorders.padding(Tokens.SPACE_SM));
        valueCardPanel.add(Fields.scroll(stringArea), "STRING");

        hashModel = new DefaultTableModel(new Object[]{"字段 (Field)", "值 (Value)"}, 0);
        hashTable = new JTable(hashModel);
        addHashRowBtn = Buttons.secondary("+ 添加字段");
        delHashRowBtn = Buttons.danger("- 删除字段");
        valueCardPanel.add(tableEditor(hashTable, addHashRowBtn, delHashRowBtn), "HASH");

        listModel = new DefaultTableModel(new Object[]{"索引 (Index)", "值 (Value)"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1; // Index column is read-only
            }
        };
        listTable = new JTable(listModel);
        addListRowBtn = Buttons.secondary("+ 追加元素");
        delListRowBtn = Buttons.danger("- 删除所选");
        valueCardPanel.add(tableEditor(listTable, addListRowBtn, delListRowBtn), "LIST");

        setModel = new DefaultTableModel(new Object[]{"成员 (Member)"}, 0);
        setTable = new JTable(setModel);
        addSetRowBtn = Buttons.secondary("+ 添加成员");
        delSetRowBtn = Buttons.danger("- 删除所选");
        valueCardPanel.add(tableEditor(setTable, addSetRowBtn, delSetRowBtn), "SET");

        zsetModel = new DefaultTableModel(new Object[]{"分值 (Score)", "成员 (Member)"}, 0);
        zsetTable = new JTable(zsetModel);
        addZsetRowBtn = Buttons.secondary("+ 添加成员");
        delZsetRowBtn = Buttons.danger("- 删除所选");
        valueCardPanel.add(tableEditor(zsetTable, addZsetRowBtn, delZsetRowBtn), "ZSET");

        // 保存动作放卡片标题右侧，避免在页面底部单独占一整行
        saveValueBtn = Buttons.primary("保存当前键的值");
        Card valueCard = Card.flush("当前键的值");
        valueCard.setContent(valueCardPanel);
        valueCard.addHeaderAction(saveValueBtn);

        JPanel page = tabPage();
        page.add(metaCard, BorderLayout.NORTH);
        page.add(valueCard, BorderLayout.CENTER);
        return page;
    }

    /** 表格型值编辑器：表格铺满，增删按钮压在底部并用细线与表格分开 */
    private static JComponent tableEditor(JTable table, JButton add, JButton remove) {
        ActionBar actions = new ActionBar();
        actions.left(add);
        actions.left(remove);
        actions.setBorder(BorderFactory.createCompoundBorder(
                KitBorders.lineSubtle(1, 0, 0, 0),
                KitBorders.padding(Tokens.SPACE_SM, Tokens.CARD_PADDING, Tokens.SPACE_SM, Tokens.CARD_PADDING)));

        JPanel panel = Layouts.box();
        panel.add(Fields.scroll(table), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    /** 行尾的「小标签 + 取值文字」组合，用于键信息里的类型显示 */
    private static JPanel trailingLabel(String label, JLabel value) {
        JPanel group = Layouts.box(Tokens.SPACE_SM, 0);
        group.add(Fields.label(label), BorderLayout.WEST);
        group.add(value, BorderLayout.CENTER);
        return group;
    }

    /** 命令控制台：输出铺满卡片，输入行固定在卡片底部状态条位置 */
    private JComponent buildConsoleTab() {
        consoleOutput = new JTextArea();
        consoleOutput.setEditable(false);
        consoleOutput.setFont(Tokens.fontMono().deriveFont(12f));
        consoleOutput.setBorder(KitBorders.padding(Tokens.SPACE_SM));
        consoleOutput.setText("=== Redis 命令终端 ===\n支持直接输入 Redis 常见命令，如: PING, INFO, KEYS *, GET key 等\n\n");

        consoleInput = Fields.mono("");
        runCmdBtn = Buttons.primary("执行");

        JPanel cmdInputRow = Layouts.box(Tokens.SPACE_SM, 0);
        cmdInputRow.add(new JLabel(" Redis > "), BorderLayout.WEST);
        cmdInputRow.add(consoleInput, BorderLayout.CENTER);
        cmdInputRow.add(runCmdBtn, BorderLayout.EAST);

        Card card = Card.plain().setFlush(true);
        card.setContent(Fields.scroll(consoleOutput));
        card.setFooter(cmdInputRow);

        JPanel page = tabPage();
        page.add(card, BorderLayout.CENTER);
        return page;
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
            closeJedis();
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

    /** 检查连接是否有效，无效则自动重连 */
    private boolean checkConnection() {
        if (jedis == null) return false;
        try {
            jedis.ping();
            return true;
        } catch (Exception e) {
            // 连接已断开，自动重连
            closeJedis();
            try {
                Jedis client = new Jedis(connHost, connPort, 5000);
                if (!connPassword.isEmpty()) client.auth(connPassword);
                client.select(connDb);
                client.ping();
                jedis = client;
                consoleOutput.append("自动重连成功\n");
                return true;
            } catch (Exception ex) {
                jedis = null;
                return false;
            }
        }
    }

    private void closeJedis() {
        if (jedis != null) {
            try { jedis.close(); } catch (Exception ignored) {}
            jedis = null;
        }
    }

    private void connectRedis() {
        connHost = hostField.getText().trim();
        try {
            connPort = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            UIUtils.error(connBtn, "端口格式不正确");
            return;
        }
        connPassword = new String(passField.getPassword());
        connDb = (Integer) dbCombo.getSelectedItem();

        connBtn.setEnabled(false);
        connBtn.setText("连接中...");

        new SwingWorker<Jedis, Void>() {
            @Override
            protected Jedis doInBackground() throws Exception {
                Jedis client = new Jedis(connHost, connPort, 5000);
                if (!connPassword.isEmpty()) {
                    client.auth(connPassword);
                }
                client.select(connDb);
                client.ping(); // test connection
                return client;
            }

            @Override
            protected void done() {
                try {
                    jedis = get();
                    toggleState(true);
                    refreshKeys();
                    consoleOutput.append("成功连接至 Redis 服务器: " + connHost + ":" + connPort + ", DB: " + connDb + "\n");
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
                if (!checkConnection()) throw new RuntimeException("Redis 连接已断开");
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
                if (!checkConnection()) throw new RuntimeException("Redis 连接已断开");
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
                    if (!checkConnection()) throw new RuntimeException("Redis 连接已断开");
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
                    if (!checkConnection()) throw new RuntimeException("Redis 连接已断开");
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
                if (!checkConnection()) throw new RuntimeException("Redis 连接已断开");
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
                if (!checkConnection()) throw new RuntimeException("Redis 连接已断开");
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
                    if (!checkConnection()) return "ERROR: Redis 连接已断开";
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
