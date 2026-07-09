package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 数据库客户端面板：支持 MySQL、PostgreSQL、Oracle 及自定义驱动链接自定义数据库。
 * 支持连接折叠、函数/视图/表树形浏览、数据库/Schema切换、SQL 智能提示及可视化条件编辑器。
 */
public class DatabasePanel extends ToolPanel {

    private JComboBox<String> profileCombo;
    private JButton saveProfileBtn;
    private JButton delProfileBtn;
    private final Map<String, DbConfigProfile> profiles = new LinkedHashMap<>();
    private final Preferences prefs = Preferences.userNodeForPackage(DatabasePanel.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean ignoreProfileEvents = false;
    private boolean ignoreUrlUpdate = false;

    // Collapsible Connection Panel
    private JPanel connHeaderPanel;
    private JPanel connBodyPanel;
    private JLabel connToggleLabel;
    private boolean connCollapsed = false;

    private JComboBox<String> dbTypeCombo;
    private JTextField hostField;
    private JTextField portField;
    private JTextField databaseField;
    private JTextField userField;
    private JPasswordField passField;
    private JTextField driverClassField;
    private JTextField jarPathField;
    private JButton browseJarBtn;
    private JTextField urlField;

    private JButton testBtn;
    private JButton connBtn;
    private boolean isConnected = false;
    private Connection connection = null;

    // Database & Schema Switchers in Workspace
    private JComboBox<String> dbSwitchCombo;
    private JComboBox<String> schemaSwitchCombo;
    private boolean ignoreSwitchEvents = false;

    // Left Panel: Metadata Tree Browser
    private JTextField tableSearchField;
    private JTree metadataTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode treeRoot;
    private final List<String> allTablesList = new ArrayList<>();
    private final List<String> allViewsList = new ArrayList<>();
    private final List<String> allFuncsList = new ArrayList<>();
    private JButton refreshTablesBtn;

    // SQL Autocomplete Support
    private JWindow suggestionWindow;
    private JList<String> suggestionList;
    private DefaultListModel<String> suggestionListModel;
    private final List<String> autocompleteKeywords = Arrays.asList(
            "SELECT", "FROM", "WHERE", "AND", "OR", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
            "DELETE", "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "ON", "GROUP BY", "ORDER BY",
            "LIMIT", "HAVING", "COUNT", "SUM", "AVG", "MIN", "MAX", "DISTINCT", "AS", "IN", "LIKE", "IS NULL", "IS NOT NULL"
    );
    private final List<String> currentTableColumns = new ArrayList<>();
    private final Map<String, List<String>> tableColumnsCache = new ConcurrentHashMap<>();

    // Right Panel Tabs
    private JTabbedPane editorTabbedPane;

    // SQL Editor
    private JTextArea sqlEditor;
    private JButton runBtn;
    private JButton formatBtn;
    private JButton clearSqlBtn;

    // Visual Condition Editor (Query Builder)
    private JComboBox<String> visualTableCombo;
    private JPanel visualConditionsContainer;
    private final List<ConditionRow> conditionRowsList = new ArrayList<>();
    private List<String> currentVisualColumnsList = new ArrayList<>();

    // Results panel
    private JTabbedPane rightTabbedPane;
    private JTable resultTable;
    private JLabel resultStatusLabel;
    private JTextArea consoleOutput;

    public DatabasePanel() {
        super("dev", "database.connector",
                "Database", "SQL", "MySQL", "Postgres", "Oracle", "JDBC", "连接器", "客户端");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 1. Collapsible Connection Panel
        JPanel connPanel = new JPanel(new BorderLayout());
        connPanel.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1));

        // Header Panel (Toggles collapse)
        connHeaderPanel = new JPanel(new BorderLayout());
        connHeaderPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        connHeaderPanel.setBackground(UIManager.getColor("Panel.background"));
        connHeaderPanel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        connToggleLabel = new JLabel("▼ 数据库连接配置 (点击折叠)");
        connToggleLabel.setFont(UIUtils.titleFont());
        connHeaderPanel.add(connToggleLabel, BorderLayout.WEST);
        connPanel.add(connHeaderPanel, BorderLayout.NORTH);

        // Body Panel (GridBag Layout Inputs)
        connBodyPanel = new JPanel(new GridBagLayout());
        connBodyPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 6, 4, 6);

        // Row 0: Profiles
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("已存配置:"), gbc);
        
        profileCombo = new JComboBox<>();
        profileCombo.setPreferredSize(new Dimension(150, 30));
        profileCombo.addActionListener(e -> onProfileSelected());
        gbc.gridx = 1; gbc.weightx = 0.5;
        connBodyPanel.add(profileCombo, gbc);

        JPanel profileBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        saveProfileBtn = new JButton("保存配置");
        delProfileBtn = new JButton("删除配置");
        profileBtnPanel.add(saveProfileBtn);
        profileBtnPanel.add(delProfileBtn);
        gbc.gridx = 2; gbc.gridwidth = 4; gbc.weightx = 0.5;
        connBodyPanel.add(profileBtnPanel, gbc);

        // Row 1: DB Type, Host, Port
        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("数据库类型:"), gbc);

        dbTypeCombo = new JComboBox<>(new String[]{"MySQL", "PostgreSQL", "Oracle", "Custom"});
        gbc.gridx = 1; gbc.weightx = 0.3;
        connBodyPanel.add(dbTypeCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("主机:"), gbc);

        hostField = new JTextField("127.0.0.1", 12);
        gbc.gridx = 3; gbc.weightx = 0.4;
        connBodyPanel.add(hostField, gbc);

        gbc.gridx = 4; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("端口:"), gbc);

        portField = new JTextField("3306", 5);
        gbc.gridx = 5; gbc.weightx = 0.3;
        connBodyPanel.add(portField, gbc);

        // Row 2: Database, Username, Password
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("库名/服务名:"), gbc);

        databaseField = new JTextField("test", 10);
        gbc.gridx = 1; gbc.weightx = 0.3;
        connBodyPanel.add(databaseField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("用户名:"), gbc);

        userField = new JTextField("root", 10);
        gbc.gridx = 3; gbc.weightx = 0.4;
        connBodyPanel.add(userField, gbc);

        gbc.gridx = 4; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("密码:"), gbc);

        passField = new JPasswordField(10);
        gbc.gridx = 5; gbc.weightx = 0.3;
        connBodyPanel.add(passField, gbc);

        // Row 3: Driver Class, Driver Jar
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("驱动类名:"), gbc);

        driverClassField = new JTextField("com.mysql.cj.jdbc.Driver");
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 0.5;
        connBodyPanel.add(driverClassField, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 3; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("驱动 JAR 路径:"), gbc);

        JPanel jarPanel = new JPanel(new BorderLayout(4, 0));
        jarPathField = new JTextField();
        jarPathField.setToolTipText("对于非内置的数据库，可在此指定其 JDBC 驱动的 JAR 包路径");
        browseJarBtn = new JButton("浏览...");
        browseJarBtn.addActionListener(e -> browseJar());
        jarPanel.add(jarPathField, BorderLayout.CENTER);
        jarPanel.add(browseJarBtn, BorderLayout.EAST);
        gbc.gridx = 4; gbc.gridwidth = 2; gbc.weightx = 0.5;
        connBodyPanel.add(jarPanel, gbc);

        // Row 4: JDBC URL, Buttons
        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0;
        connBodyPanel.add(new JLabel("JDBC URL:"), gbc);

        urlField = new JTextField("jdbc:mysql://127.0.0.1:3306/test?useSSL=false&serverTimezone=UTC&characterEncoding=utf-8");
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 0.8;
        connBodyPanel.add(urlField, gbc);

        JPanel actionBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        testBtn = UIUtils.button("测试连接", 90);
        connBtn = UIUtils.button("连接", 90);
        actionBtnPanel.add(testBtn);
        actionBtnPanel.add(connBtn);
        gbc.gridx = 4; gbc.gridwidth = 2; gbc.weightx = 0.2;
        connBodyPanel.add(actionBtnPanel, gbc);

        connPanel.add(connBodyPanel, BorderLayout.CENTER);
        root.add(connPanel, BorderLayout.NORTH);

        // 2. Main Workspace Split: Left (Metadata Tree), Right (Editor & Results)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setDividerLocation(240);

        // Left Component: Metadata Tree Browser
        JPanel leftPanel = new JPanel(new BorderLayout(6, 6));
        leftPanel.setBorder(BorderFactory.createTitledBorder("数据源浏览器"));

        // Header Panel for Database & Schema Switchers
        JPanel leftHeaderPanel = new JPanel(new GridBagLayout());
        GridBagConstraints lhGbc = new GridBagConstraints();
        lhGbc.fill = GridBagConstraints.HORIZONTAL;
        lhGbc.insets = new Insets(2, 2, 2, 2);

        lhGbc.gridx = 0; lhGbc.gridy = 0; lhGbc.weightx = 0;
        leftHeaderPanel.add(new JLabel("数据库:"), lhGbc);

        dbSwitchCombo = new JComboBox<>();
        dbSwitchCombo.setPreferredSize(new Dimension(140, 28));
        lhGbc.gridx = 1; lhGbc.weightx = 1.0;
        leftHeaderPanel.add(dbSwitchCombo, lhGbc);

        lhGbc.gridx = 0; lhGbc.gridy = 1; lhGbc.weightx = 0;
        leftHeaderPanel.add(new JLabel("模式/Schema:"), lhGbc);

        schemaSwitchCombo = new JComboBox<>();
        schemaSwitchCombo.setPreferredSize(new Dimension(140, 28));
        lhGbc.gridx = 1; lhGbc.weightx = 1.0;
        leftHeaderPanel.add(schemaSwitchCombo, lhGbc);

        // Search panel for filtering tree nodes
        JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
        tableSearchField = new JTextField();
        tableSearchField.putClientProperty("JTextField.placeholderText", "过滤元数据...");
        refreshTablesBtn = new JButton("刷新");
        searchPanel.add(tableSearchField, BorderLayout.CENTER);
        searchPanel.add(refreshTablesBtn, BorderLayout.EAST);

        lhGbc.gridx = 0; lhGbc.gridy = 2; lhGbc.gridwidth = 2; lhGbc.weightx = 1.0;
        leftHeaderPanel.add(searchPanel, lhGbc);
        leftPanel.add(leftHeaderPanel, BorderLayout.NORTH);

        // Metadata JTree
        treeRoot = new DefaultMutableTreeNode("未连接");
        treeModel = new DefaultTreeModel(treeRoot);
        metadataTree = new JTree(treeModel);
        metadataTree.setCellRenderer(new MetadataTreeCellRenderer());
        leftPanel.add(new JScrollPane(metadataTree), BorderLayout.CENTER);

        mainSplit.setLeftComponent(leftPanel);

        // Right Component: Editor Tabs (Top) & Results (Bottom)
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplit.setDividerLocation(220);

        editorTabbedPane = new JTabbedPane();

        // Sub-Tab 1: SQL Editor Panel
        JPanel editorPanel = new JPanel(new BorderLayout(4, 4));
        sqlEditor = new JTextArea();
        sqlEditor.setFont(UIUtils.monoFont());
        sqlEditor.setText("SELECT * FROM test LIMIT 100;");
        editorPanel.add(new JScrollPane(sqlEditor), BorderLayout.CENTER);

        JPanel editorCtrlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        runBtn = UIUtils.button("运行 SQL", 90);
        formatBtn = UIUtils.button("格式化", 80);
        clearSqlBtn = UIUtils.button("清空", 80);
        editorCtrlPanel.add(runBtn);
        editorCtrlPanel.add(formatBtn);
        editorCtrlPanel.add(clearSqlBtn);
        editorPanel.add(editorCtrlPanel, BorderLayout.SOUTH);
        editorTabbedPane.addTab("SQL 编辑器", editorPanel);

        // Sub-Tab 2: Visual Query Tab
        editorTabbedPane.addTab("可视化查询 (条件编辑器)", buildVisualQueryTab());

        rightSplit.setTopComponent(editorTabbedPane);

        // Results Container
        JPanel resultsPanel = new JPanel(new BorderLayout(4, 4));
        resultsPanel.setBorder(BorderFactory.createTitledBorder("执行结果"));

        rightTabbedPane = new JTabbedPane();
        
        // Tab 1: JTable for results
        JPanel resultTab = new JPanel(new BorderLayout(4, 4));
        resultTable = new JTable();
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // allow horizontal scroll bar
        resultTab.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        
        resultStatusLabel = new JLabel("未执行任何查询。");
        resultStatusLabel.setFont(UIUtils.plainFont());
        resultTab.add(resultStatusLabel, BorderLayout.SOUTH);
        rightTabbedPane.addTab("查询数据", resultTab);

        // Tab 2: Console Log
        consoleOutput = new JTextArea();
        consoleOutput.setFont(UIUtils.monoFont());
        consoleOutput.setEditable(false);
        rightTabbedPane.addTab("控制台日志", new JScrollPane(consoleOutput));

        resultsPanel.add(rightTabbedPane, BorderLayout.CENTER);
        rightSplit.setBottomComponent(resultsPanel);

        mainSplit.setRightComponent(rightSplit);
        root.add(mainSplit, BorderLayout.CENTER);

        // --- Hook Listeners ---
        setupListeners();
        
        // --- Init SQL Autocomplete ---
        initAutocomplete();

        // --- Load Profiles ---
        loadProfilesFromPrefs();
        
        // Initial setup for default selection
        if (dbTypeCombo.getItemCount() > 0 && profileCombo.getItemCount() == 0) {
            dbTypeCombo.setSelectedIndex(0);
        }

        return root;
    }

    private void setupListeners() {
        // Collapsible connection config listener
        connHeaderPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                toggleConnPanel();
            }
        });

        // Automatically update ports and URLs on DB Type change
        dbTypeCombo.addActionListener(e -> {
            if (ignoreProfileEvents) return;
            String type = (String) dbTypeCombo.getSelectedItem();
            boolean isCustom = "Custom".equals(type);
            
            driverClassField.setEditable(isCustom);
            jarPathField.setEnabled(isCustom);
            browseJarBtn.setEnabled(isCustom);

            if ("MySQL".equals(type)) {
                portField.setText("3306");
                driverClassField.setText("com.mysql.cj.jdbc.Driver");
            } else if ("PostgreSQL".equals(type)) {
                portField.setText("5432");
                driverClassField.setText("org.postgresql.Driver");
            } else if ("Oracle".equals(type)) {
                portField.setText("1521");
                driverClassField.setText("oracle.jdbc.OracleDriver");
            } else {
                portField.setText("");
                driverClassField.setText("");
            }
            updateJdbcUrl();
        });

        // Trigger URL update on field edits
        addUrlUpdateListener(hostField);
        addUrlUpdateListener(portField);
        addUrlUpdateListener(databaseField);

        // Profiles Save & Delete
        saveProfileBtn.addActionListener(e -> saveProfile());
        delProfileBtn.addActionListener(e -> deleteProfile());

        // Test & Connection Actions
        testBtn.addActionListener(e -> testConnection());
        connBtn.addActionListener(e -> toggleConnection());

        // SQL Buttons
        runBtn.addActionListener(e -> executeSql());
        formatBtn.addActionListener(e -> {
            sqlEditor.setText(formatSql(sqlEditor.getText()));
        });
        clearSqlBtn.addActionListener(e -> sqlEditor.setText(""));

        // Database Switching dropdown listener
        dbSwitchCombo.addActionListener(e -> {
            if (ignoreSwitchEvents) return;
            String db = (String) dbSwitchCombo.getSelectedItem();
            if (db == null || db.isEmpty()) return;
            switchDatabase(db);
        });

        // Schema Switching dropdown listener
        schemaSwitchCombo.addActionListener(e -> {
            if (ignoreSwitchEvents) return;
            String schema = (String) schemaSwitchCombo.getSelectedItem();
            if (schema == null || schema.isEmpty()) return;
            switchSchema(schema);
        });

        // JTree Selection -> prefill SQL Editor & Load columns for autocomplete/conditions
        metadataTree.addTreeSelectionListener(e -> {
            TreePath path = metadataTree.getSelectionPath();
            if (path != null && path.getLastPathComponent() instanceof MetadataNode) {
                MetadataNode node = (MetadataNode) path.getLastPathComponent();
                String name = node.getName();
                String dbType = (String) dbTypeCombo.getSelectedItem();

                if (node.getType() == MetadataType.TABLE || node.getType() == MetadataType.VIEW) {
                    if ("Oracle".equals(dbType)) {
                        sqlEditor.setText("SELECT * FROM " + name + " WHERE ROWNUM <= 100");
                    } else {
                        sqlEditor.setText("SELECT * FROM " + name + " LIMIT 100");
                    }
                } else if (node.getType() == MetadataType.FUNCTION) {
                    if ("Oracle".equals(dbType)) {
                        sqlEditor.setText("SELECT " + name + "() FROM DUAL");
                    } else {
                        sqlEditor.setText("SELECT " + name + "()");
                    }
                }
                
                // Automatically retrieve columns for suggestions and condition builder
                updateConditionEditorColumns(name);
            }
        });

        // Refresh Tables Manual Action
        refreshTablesBtn.addActionListener(e -> loadMetadataTree());

        // Table List Search
        tableSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterMetadataTree(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterMetadataTree(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterMetadataTree(); }
        });
    }

    private void toggleConnPanel() {
        connCollapsed = !connCollapsed;
        connBodyPanel.setVisible(!connCollapsed);
        connToggleLabel.setText(connCollapsed ? "▶ 数据库连接配置 (点击展开)" : "▼ 数据库连接配置 (点击折叠)");
        JComponent v = getView();
        if (v != null) {
            v.revalidate();
            v.repaint();
        }
    }

    private void addUrlUpdateListener(JTextField field) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateJdbcUrl(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateJdbcUrl(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateJdbcUrl(); }
        });
    }

    private void updateJdbcUrl() {
        if (ignoreUrlUpdate) return;
        String type = (String) dbTypeCombo.getSelectedItem();
        if ("Custom".equals(type)) return;

        String host = hostField.getText().trim();
        String port = portField.getText().trim();
        String db = databaseField.getText().trim();

        if ("MySQL".equals(type)) {
            urlField.setText("jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&serverTimezone=UTC&characterEncoding=utf-8");
        } else if ("PostgreSQL".equals(type)) {
            urlField.setText("jdbc:postgresql://" + host + ":" + port + "/" + db);
        } else if ("Oracle".equals(type)) {
            urlField.setText("jdbc:oracle:thin:@//" + host + ":" + port + "/" + db);
        }
    }

    private void browseJar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JDBC Driver JAR (*.jar)", "jar"));
        int ret = chooser.showOpenDialog(getView());
        if (ret == JFileChooser.APPROVE_OPTION) {
            jarPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void consoleLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            consoleOutput.append(msg + "\n\n");
            consoleOutput.setCaretPosition(consoleOutput.getDocument().getLength());
        });
    }

    // Dynamic Connection Factory
    private Connection createConnection(String url, String user, String pwd, String driverClass, String jarPath) throws Exception {
        if (jarPath != null && !jarPath.trim().isEmpty()) {
            File jarFile = new File(jarPath.trim());
            if (!jarFile.exists()) {
                throw new FileNotFoundException("未找到驱动 JAR 文件：" + jarPath);
            }
            URL[] urls = new URL[]{ jarFile.toURI().toURL() };
            URLClassLoader loader = new URLClassLoader(urls, DatabasePanel.class.getClassLoader());
            Class<?> clazz = Class.forName(driverClass.trim(), true, loader);
            Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
            Properties props = new Properties();
            if (user != null && !user.isEmpty()) props.setProperty("user", user);
            if (pwd != null && !pwd.isEmpty()) props.setProperty("password", pwd);
            Connection conn = driver.connect(url.trim(), props);
            if (conn == null) {
                throw new SQLException("驱动程序未接受此 JDBC URL，请检查格式是否匹配。");
            }
            return conn;
        } else {
            if (driverClass != null && !driverClass.trim().isEmpty()) {
                try {
                    Class.forName(driverClass.trim());
                } catch (Exception ignored) {}
            }
            return DriverManager.getConnection(url.trim(), user, pwd);
        }
    }

    private void testConnection() {
        String url = urlField.getText().trim();
        String user = userField.getText().trim();
        String pwd = new String(passField.getPassword());
        String driverClass = driverClassField.getText().trim();
        String jarPath = jarPathField.getText().trim();

        testBtn.setEnabled(false);
        consoleLog("正在测试连接：" + url);
        new SwingWorker<Void, Void>() {
            private String error = null;
            @Override
            protected Void doInBackground() throws Exception {
                Connection conn = null;
                try {
                    conn = createConnection(url, user, pwd, driverClass, jarPath);
                } catch (Exception ex) {
                    error = ex.getMessage();
                } finally {
                    if (conn != null) {
                        try { conn.close(); } catch (Exception ignored) {}
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                testBtn.setEnabled(true);
                if (error == null) {
                    UIUtils.info(getView(), "连接测试成功！");
                    consoleLog("连接测试成功。");
                } else {
                    UIUtils.error(getView(), "连接测试失败：\n" + error);
                    consoleLog("连接测试失败：" + error);
                }
            }
        }.execute();
    }

    private void toggleConnection() {
        if (isConnected) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        String url = urlField.getText().trim();
        String user = userField.getText().trim();
        String pwd = new String(passField.getPassword());
        String driverClass = driverClassField.getText().trim();
        String jarPath = jarPathField.getText().trim();

        connBtn.setEnabled(false);
        testBtn.setEnabled(false);
        consoleLog("正在建立连接：" + url);

        new SwingWorker<Connection, Void>() {
            private String error = null;

            @Override
            protected Connection doInBackground() throws Exception {
                return createConnection(url, user, pwd, driverClass, jarPath);
            }

            @Override
            protected void done() {
                try {
                    connection = get();
                    isConnected = true;
                    connBtn.setText("断开");
                    connBtn.setEnabled(true);
                    
                    // Disable inputs while connected
                    dbTypeCombo.setEnabled(false);
                    hostField.setEnabled(false);
                    portField.setEnabled(false);
                    databaseField.setEnabled(false);
                    userField.setEnabled(false);
                    passField.setEnabled(false);
                    driverClassField.setEnabled(false);
                    jarPathField.setEnabled(false);
                    browseJarBtn.setEnabled(false);
                    urlField.setEnabled(false);

                    consoleLog("成功连接到数据库！");
                    
                    // Collapse panel upon success
                    if (!connCollapsed) {
                        toggleConnPanel();
                    }

                    // Load database & schema lists
                    loadDatabaseNames();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    error = cause.getMessage();
                    connBtn.setEnabled(true);
                    testBtn.setEnabled(true);
                    UIUtils.error(getView(), "连接失败：\n" + error);
                    consoleLog("连接建立失败：" + error);
                }
            }
        }.execute();
    }

    private void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                consoleLog("数据库连接已关闭。");
            } catch (Exception ex) {
                consoleLog("关闭连接时出错：" + ex.getMessage());
            }
            connection = null;
        }
        isConnected = false;
        connBtn.setText("连接");
        testBtn.setEnabled(true);

        // Re-enable inputs
        dbTypeCombo.setEnabled(true);
        hostField.setEnabled(true);
        portField.setEnabled(true);
        databaseField.setEnabled(true);
        userField.setEnabled(true);
        passField.setEnabled(true);
        
        String type = (String) dbTypeCombo.getSelectedItem();
        boolean isCustom = "Custom".equals(type);
        driverClassField.setEnabled(true);
        driverClassField.setEditable(isCustom);
        jarPathField.setEnabled(isCustom);
        browseJarBtn.setEnabled(isCustom);
        urlField.setEnabled(true);

        // Expand panel upon disconnect
        if (connCollapsed) {
            toggleConnPanel();
        }

        // Clear Switcher Combos
        ignoreSwitchEvents = true;
        dbSwitchCombo.removeAllItems();
        schemaSwitchCombo.removeAllItems();
        ignoreSwitchEvents = false;

        // Clear metadata tree & cache
        allTablesList.clear();
        allViewsList.clear();
        allFuncsList.clear();
        tableColumnsCache.clear();
        treeRoot = new DefaultMutableTreeNode("未连接");
        treeModel.setRoot(treeRoot);
        
        visualTableCombo.removeAllItems();
        
        resultTable.setModel(new DefaultTableModel());
        resultStatusLabel.setText("连接断开。");
    }

    private void loadDatabaseNames() {
        if (!isConnected || connection == null) return;
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                List<String> dbs = new ArrayList<>();
                DatabaseMetaData meta = connection.getMetaData();
                String dbType = (String) dbTypeCombo.getSelectedItem();

                if ("PostgreSQL".equals(dbType)) {
                    // Query pg_database for PG databases
                    try (Statement stmt = connection.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT datname FROM pg_database WHERE datistemplate = false AND datallowconn = true ORDER BY datname")) {
                        while (rs.next()) {
                            dbs.add(rs.getString(1));
                        }
                    } catch (Throwable e) {
                        // Fallback to catalogs
                        try (ResultSet rs = meta.getCatalogs()) {
                            while (rs.next()) {
                                String s = rs.getString("TABLE_CAT");
                                if (s != null && !s.isEmpty()) {
                                    dbs.add(s);
                                }
                            }
                        }
                    }
                } else if ("MySQL".equals(dbType)) {
                    // Query SHOW DATABASES for MySQL
                    try (Statement stmt = connection.createStatement();
                         ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
                        while (rs.next()) {
                            dbs.add(rs.getString(1));
                        }
                    } catch (Throwable e) {
                        // Fallback to catalogs
                        try (ResultSet rs = meta.getCatalogs()) {
                            while (rs.next()) {
                                String s = rs.getString("TABLE_CAT");
                                if (s != null && !s.isEmpty()) {
                                    dbs.add(s);
                                }
                            }
                        }
                    }
                } else {
                    // Standard Catalogs for other databases
                    try (ResultSet rs = meta.getCatalogs()) {
                        while (rs.next()) {
                            String s = rs.getString("TABLE_CAT");
                            if (s != null && !s.isEmpty()) {
                                dbs.add(s);
                            }
                        }
                    }
                }

                // If still empty, add current database name
                if (dbs.isEmpty()) {
                    String curCatalog = null;
                    try { curCatalog = connection.getCatalog(); } catch (Throwable ignored) {}
                    if (curCatalog != null && !curCatalog.isEmpty()) {
                        dbs.add(curCatalog);
                    } else {
                        String urlDb = databaseField.getText().trim();
                        if (!urlDb.isEmpty()) {
                            dbs.add(urlDb);
                        }
                    }
                }

                Collections.sort(dbs);
                Set<String> set = new LinkedHashSet<>(dbs);
                return new ArrayList<>(set);
            }

            @Override
            protected void done() {
                try {
                    List<String> dbs = get();
                    ignoreSwitchEvents = true;
                    dbSwitchCombo.removeAllItems();
                    for (String db : dbs) {
                        dbSwitchCombo.addItem(db);
                    }
                    
                    // Try auto selecting active catalog/schema
                    String activeDb = "";
                    try {
                        activeDb = connection.getCatalog();
                    } catch (Throwable ignored) {}

                    if (activeDb != null && !activeDb.isEmpty()) {
                        dbSwitchCombo.setSelectedItem(activeDb);
                    }
                    ignoreSwitchEvents = false;

                    // Load schemas next
                    loadSchemaNames();
                } catch (Exception ex) {
                    consoleLog("加载数据库切换列表失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void loadSchemaNames() {
        if (!isConnected || connection == null) return;
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                List<String> schemas = new ArrayList<>();
                DatabaseMetaData meta = connection.getMetaData();

                try (ResultSet rs = meta.getSchemas()) {
                    while (rs.next()) {
                        String s = rs.getString("TABLE_SCHEM");
                        if (s != null && !s.isEmpty()) {
                            schemas.add(s);
                        }
                    }
                }
                Collections.sort(schemas);
                return schemas;
            }

            @Override
            protected void done() {
                try {
                    List<String> schemas = get();
                    ignoreSwitchEvents = true;
                    schemaSwitchCombo.removeAllItems();
                    for (String s : schemas) {
                        schemaSwitchCombo.addItem(s);
                    }

                    // Try auto selecting active schema
                    String activeSchema = "";
                    try {
                        activeSchema = connection.getSchema();
                    } catch (Throwable ignored) {}

                    if (activeSchema != null && !activeSchema.isEmpty()) {
                        schemaSwitchCombo.setSelectedItem(activeSchema);
                    } else {
                        // Fallbacks
                        String dbType = (String) dbTypeCombo.getSelectedItem();
                        if ("PostgreSQL".equals(dbType) && schemas.contains("public")) {
                            schemaSwitchCombo.setSelectedItem("public");
                        } else if ("Oracle".equals(dbType) && !userField.getText().trim().isEmpty()) {
                            String oracleUser = userField.getText().trim().toUpperCase();
                            if (schemas.contains(oracleUser)) {
                                schemaSwitchCombo.setSelectedItem(oracleUser);
                            }
                        }
                    }
                    ignoreSwitchEvents = false;

                    // Finally load tree
                    loadMetadataTree();
                } catch (Exception ex) {
                    consoleLog("加载模式/Schema切换列表失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void switchDatabase(String dbName) {
        if (!isConnected || connection == null) return;
        String dbType = (String) dbTypeCombo.getSelectedItem();

        consoleLog("正在切换当前数据库为: " + dbName);
        new SwingWorker<Void, Void>() {
            private String err = null;
            @Override
            protected Void doInBackground() throws Exception {
                if ("PostgreSQL".equals(dbType)) {
                    // PostgreSQL does not support switching catalogs inside session; must reconnect!
                    String currentUrl = urlField.getText().trim();
                    String newUrl = currentUrl.replaceAll("(?i)(postgresql://[^/]+/)([^?#/]+)", "$1" + dbName);
                    
                    consoleLog("PostgreSQL 需要重新建立物理连接。新 URL: " + newUrl);
                    try {
                        connection.close();
                    } catch (Exception ignored) {}

                    String user = userField.getText().trim();
                    String pwd = new String(passField.getPassword());
                    String driverClass = driverClassField.getText().trim();
                    String jarPath = jarPathField.getText().trim();
                    connection = createConnection(newUrl, user, pwd, driverClass, jarPath);
                    
                    SwingUtilities.invokeLater(() -> urlField.setText(newUrl));
                } else {
                    // Standard MySQL switching catalog
                    connection.setCatalog(dbName);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    consoleLog("数据库切换成功！");
                    loadSchemaNames();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    err = cause.getMessage();
                    UIUtils.error(getView(), "切换数据库失败:\n" + err);
                    consoleLog("切换数据库失败：" + err);
                }
            }
        }.execute();
    }

    private void switchSchema(String schemaName) {
        if (!isConnected || connection == null) return;
        String dbType = (String) dbTypeCombo.getSelectedItem();
        consoleLog("正在切换当前模式为: " + schemaName);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    connection.setSchema(schemaName);
                } catch (Throwable ex) {
                    if ("Oracle".equals(dbType)) {
                        try (Statement stmt = connection.createStatement()) {
                            stmt.execute("ALTER SESSION SET CURRENT_SCHEMA = " + schemaName);
                        }
                    } else if ("PostgreSQL".equals(dbType)) {
                        try (Statement stmt = connection.createStatement()) {
                            stmt.execute("SET search_path TO " + schemaName);
                        }
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    consoleLog("模式切换成功！");
                    loadMetadataTree();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    consoleLog("切换模式警告（已继续刷新树）：" + cause.getMessage());
                    loadMetadataTree();
                }
            }
        }.execute();
    }

    private void loadMetadataTree() {
        if (!isConnected || connection == null) return;
        
        String selectedDb = (String) dbSwitchCombo.getSelectedItem();
        String selectedSchema = (String) schemaSwitchCombo.getSelectedItem();

        String rootName = (selectedDb != null ? selectedDb : "") + 
                         (selectedSchema != null ? " / " + selectedSchema : "");
        if (rootName.isEmpty()) rootName = "Database";

        treeRoot = new DefaultMutableTreeNode(rootName);
        treeModel.setRoot(treeRoot);
        
        DefaultMutableTreeNode tablesNode = new DefaultMutableTreeNode("表 (Tables)");
        DefaultMutableTreeNode viewsNode = new DefaultMutableTreeNode("视图 (Views)");
        DefaultMutableTreeNode funcsNode = new DefaultMutableTreeNode("函数/过程 (Functions)");
        
        treeRoot.add(tablesNode);
        treeRoot.add(viewsNode);
        treeRoot.add(funcsNode);

        consoleLog("正在加载元数据列表...");
        new SwingWorker<Map<String, List<String>>, Void>() {
            @Override
            protected Map<String, List<String>> doInBackground() throws Exception {
                Map<String, List<String>> map = new HashMap<>();
                List<String> tables = new ArrayList<>();
                List<String> views = new ArrayList<>();
                List<String> funcs = new ArrayList<>();

                DatabaseMetaData metaData = connection.getMetaData();
                
                String catalog = selectedDb != null ? selectedDb : connection.getCatalog();
                String schema = selectedSchema != null ? selectedSchema : null;
                
                if (schema == null) {
                    try {
                        schema = connection.getSchema();
                    } catch (Throwable ignored) {}
                }

                // 1. Fetch tables & views
                try (ResultSet rs = metaData.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        String type = rs.getString("TABLE_TYPE");
                        String name = rs.getString("TABLE_NAME");
                        if ("VIEW".equals(type)) {
                            views.add(name);
                        } else {
                            tables.add(name);
                        }
                    }
                }

                // 2. Fetch functions
                try (ResultSet rs = metaData.getFunctions(catalog, schema, "%")) {
                    while (rs.next()) {
                        String name = rs.getString("FUNCTION_NAME");
                        if (name != null && !name.isEmpty()) {
                            funcs.add(name);
                        }
                    }
                } catch (Throwable ignored) {}

                Collections.sort(tables);
                Collections.sort(views);
                Collections.sort(funcs);

                map.put("TABLE", tables);
                map.put("VIEW", views);
                map.put("FUNCTION", funcs);
                return map;
            }

            @Override
            protected void done() {
                try {
                    Map<String, List<String>> map = get();
                    List<String> tables = map.get("TABLE");
                    List<String> views = map.get("VIEW");
                    List<String> funcs = map.get("FUNCTION");

                    for (String t : tables) {
                        tablesNode.add(new MetadataNode(t, MetadataType.TABLE));
                    }
                    for (String v : views) {
                        viewsNode.add(new MetadataNode(v, MetadataType.VIEW));
                    }
                    for (String f : funcs) {
                        funcsNode.add(new MetadataNode(f, MetadataType.FUNCTION));
                    }

                    treeModel.reload();
                    metadataTree.expandPath(new TreePath(tablesNode.getPath()));
                    metadataTree.expandPath(new TreePath(viewsNode.getPath()));

                    allTablesList.clear();
                    allTablesList.addAll(tables);
                    allViewsList.clear();
                    allViewsList.addAll(views);
                    allFuncsList.clear();
                    allFuncsList.addAll(funcs);

                    // Sync Visual Query table dropdown
                    visualTableCombo.removeAllItems();
                    for (String table : tables) {
                        visualTableCombo.addItem(table);
                    }

                    // Background cache for autocomplete column suggestions
                    tableColumnsCache.clear();
                    cacheAllTableColumns(tables);

                    consoleLog("元数据加载成功，表共计 " + tables.size() + " 个，视图 " + views.size() + " 个，函数 " + funcs.size() + " 个。");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    consoleLog("加载元数据树失败: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void cacheAllTableColumns(List<String> tables) {
        new Thread(() -> {
            for (String table : tables) {
                if (!isConnected || connection == null) break;
                try {
                    DatabaseMetaData metaData = connection.getMetaData();
                    String selectedDb = (String) dbSwitchCombo.getSelectedItem();
                    String selectedSchema = (String) schemaSwitchCombo.getSelectedItem();

                    String catalog = selectedDb != null ? selectedDb : connection.getCatalog();
                    String schema = selectedSchema != null ? selectedSchema : null;
                    if (schema == null) {
                        try { schema = connection.getSchema(); } catch (Throwable ignored) {}
                    }

                    List<String> cols = new ArrayList<>();
                    try (ResultSet rs = metaData.getColumns(catalog, schema, table, "%")) {
                        while (rs.next()) {
                            cols.add(rs.getString("COLUMN_NAME"));
                        }
                    }
                    tableColumnsCache.put(table.toLowerCase(), cols);
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private void filterMetadataTree() {
        String query = tableSearchField.getText().trim().toLowerCase();
        if (treeRoot == null || treeRoot.getChildCount() < 3) return;

        DefaultMutableTreeNode tablesNode = (DefaultMutableTreeNode) treeRoot.getChildAt(0);
        DefaultMutableTreeNode viewsNode = (DefaultMutableTreeNode) treeRoot.getChildAt(1);
        DefaultMutableTreeNode funcsNode = (DefaultMutableTreeNode) treeRoot.getChildAt(2);

        tablesNode.removeAllChildren();
        viewsNode.removeAllChildren();
        funcsNode.removeAllChildren();

        for (String t : allTablesList) {
            if (query.isEmpty() || t.toLowerCase().contains(query)) {
                tablesNode.add(new MetadataNode(t, MetadataType.TABLE));
            }
        }
        for (String v : allViewsList) {
            if (query.isEmpty() || v.toLowerCase().contains(query)) {
                viewsNode.add(new MetadataNode(v, MetadataType.VIEW));
            }
        }
        for (String f : allFuncsList) {
            if (query.isEmpty() || f.toLowerCase().contains(query)) {
                funcsNode.add(new MetadataNode(f, MetadataType.FUNCTION));
            }
        }

        treeModel.reload();
        metadataTree.expandPath(new TreePath(tablesNode.getPath()));
        metadataTree.expandPath(new TreePath(viewsNode.getPath()));
    }

    // --- Autocomplete Implementation ---
    private void initAutocomplete() {
        suggestionListModel = new DefaultListModel<>();
        suggestionList = new JList<>(suggestionListModel);
        suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestionList.setBackground(UIManager.getColor("List.background"));
        suggestionList.setForeground(UIManager.getColor("List.foreground"));

        suggestionList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    insertSelectedSuggestion();
                }
            }
        });

        sqlEditor.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (suggestionWindow != null && suggestionWindow.isVisible()) {
                    int code = e.getKeyCode();
                    if (code == java.awt.event.KeyEvent.VK_UP) {
                        int index = suggestionList.getSelectedIndex();
                        if (index > 0) {
                            suggestionList.setSelectedIndex(index - 1);
                            suggestionList.ensureIndexIsVisible(index - 1);
                        }
                        e.consume();
                    } else if (code == java.awt.event.KeyEvent.VK_DOWN) {
                        int index = suggestionList.getSelectedIndex();
                        if (index < suggestionListModel.getSize() - 1) {
                            suggestionList.setSelectedIndex(index + 1);
                            suggestionList.ensureIndexIsVisible(index + 1);
                        }
                        e.consume();
                    } else if (code == java.awt.event.KeyEvent.VK_ENTER || code == java.awt.event.KeyEvent.VK_TAB) {
                        insertSelectedSuggestion();
                        e.consume();
                    } else if (code == java.awt.event.KeyEvent.VK_ESCAPE) {
                        suggestionWindow.setVisible(false);
                        e.consume();
                    }
                }
            }
        });

        sqlEditor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(() -> showSuggestionsLater());
            }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(() -> showSuggestionsLater());
            }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });

        sqlEditor.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                SwingUtilities.invokeLater(() -> {
                    if (suggestionWindow != null && e.getOppositeComponent() != suggestionList) {
                        suggestionWindow.setVisible(false);
                    }
                });
            }
        });
    }

    private void showSuggestionsLater() {
        if (!sqlEditor.isFocusOwner()) {
            if (suggestionWindow != null) {
                suggestionWindow.setVisible(false);
            }
            return;
        }

        int caretPos = sqlEditor.getCaretPosition();
        if (caretPos == 0) {
            if (suggestionWindow != null) {
                suggestionWindow.setVisible(false);
            }
            return;
        }

        try {
            String text = sqlEditor.getText(0, caretPos);
            int start = caretPos - 1;
            while (start >= 0) {
                char ch = text.charAt(start);
                if (Character.isWhitespace(ch) || ch == ',' || ch == '(' || ch == ')' || ch == '.' || ch == ';') {
                    break;
                }
                start--;
            }
            String prefix = text.substring(start + 1).trim();
            if (prefix.isEmpty()) {
                if (suggestionWindow != null) {
                    suggestionWindow.setVisible(false);
                }
                return;
            }

            // --- Context Parser ---
            String textBeforePrefix = text.substring(0, Math.max(0, start + 1)).trim();
            String textBeforePrefixUpper = textBeforePrefix.toUpperCase();

            boolean isTableContext = false;
            boolean isColumnContext = false;

            if (textBeforePrefixUpper.endsWith("FROM") || textBeforePrefixUpper.endsWith("JOIN") 
                || textBeforePrefixUpper.endsWith("INTO") || textBeforePrefixUpper.endsWith("UPDATE")) {
                isTableContext = true;
            } else if (textBeforePrefixUpper.contains("WHERE") || textBeforePrefixUpper.contains(" ON ") 
                       || textBeforePrefixUpper.contains(" SET ") || textBeforePrefixUpper.contains("SELECT")) {
                if (textBeforePrefixUpper.contains("WHERE") || textBeforePrefixUpper.contains(" ON ") || textBeforePrefixUpper.contains(" SET ")) {
                    isColumnContext = true;
                } else if (textBeforePrefixUpper.contains("SELECT") && !textBeforePrefixUpper.contains("FROM")) {
                    isColumnContext = true;
                }
            }

            List<String> candidates = new ArrayList<>();

            if (isTableContext) {
                // Table context: Tables & views only
                for (String tbl : allTablesList) {
                    if (tbl.toLowerCase().startsWith(prefix.toLowerCase())) {
                        candidates.add(tbl);
                    }
                }
                for (String view : allViewsList) {
                    if (view.toLowerCase().startsWith(prefix.toLowerCase())) {
                        candidates.add(view);
                    }
                }
            } else if (isColumnContext) {
                // Column context: columns of the active table
                String activeTable = extractTableNameFromSql(text);
                List<String> cols = null;
                if (activeTable != null) {
                    cols = tableColumnsCache.get(activeTable.toLowerCase());
                }
                if (cols == null) {
                    cols = currentTableColumns;
                }
                for (String col : cols) {
                    if (col.toLowerCase().startsWith(prefix.toLowerCase())) {
                        candidates.add(col);
                    }
                }
            } else {
                // General context
                for (String kw : autocompleteKeywords) {
                    if (kw.toLowerCase().startsWith(prefix.toLowerCase())) {
                        candidates.add(kw);
                    }
                }
                for (String tbl : allTablesList) {
                    if (tbl.toLowerCase().startsWith(prefix.toLowerCase())) {
                        candidates.add(tbl);
                    }
                }
                for (String view : allViewsList) {
                    if (view.toLowerCase().startsWith(prefix.toLowerCase())) {
                        candidates.add(view);
                    }
                }
                for (String fn : allFuncsList) {
                    if (fn.toLowerCase().startsWith(prefix.toLowerCase())) {
                        candidates.add(fn);
                    }
                }
                for (String col : currentTableColumns) {
                    if (col.toLowerCase().startsWith(prefix.toLowerCase())) {
                        candidates.add(col);
                    }
                }
            }

            if (candidates.isEmpty()) {
                if (suggestionWindow != null) {
                    suggestionWindow.setVisible(false);
                }
                return;
            }

            suggestionListModel.clear();
            for (String c : candidates) {
                suggestionListModel.addElement(c);
            }
            suggestionList.setSelectedIndex(0);

            // Lazy initialization of JWindow
            if (suggestionWindow == null) {
                Window parent = SwingUtilities.getWindowAncestor(sqlEditor);
                suggestionWindow = new JWindow(parent);
                suggestionWindow.setFocusableWindowState(false); // Focus-free window to allow typing
                JScrollPane sp = new JScrollPane(suggestionList);
                sp.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1));
                suggestionWindow.setContentPane(sp);
                suggestionWindow.setSize(220, 150);
            }

            // Locate editor caret screen coords
            Rectangle caretRect = sqlEditor.modelToView(caretPos);
            if (caretRect != null) {
                Point screenPos = sqlEditor.getLocationOnScreen();
                int x = screenPos.x + caretRect.x;
                int y = screenPos.y + caretRect.y + caretRect.height + 4;
                suggestionWindow.setLocation(x, y);
                suggestionWindow.setVisible(true);
            }
        } catch (Exception ex) {
            if (suggestionWindow != null) {
                suggestionWindow.setVisible(false);
            }
        }
    }

    private String extractTableNameFromSql(String sql) {
        Pattern p = Pattern.compile("(?i)\\b(FROM|JOIN|UPDATE|INTO)\\s+([a-zA-Z0-9_]+)");
        Matcher m = p.matcher(sql);
        String tbl = null;
        while (m.find()) {
            tbl = m.group(2);
        }
        if (tbl == null && visualTableCombo != null) {
            tbl = (String) visualTableCombo.getSelectedItem();
        }
        return tbl;
    }

    private void insertSelectedSuggestion() {
        String selected = suggestionList.getSelectedValue();
        if (selected == null) return;

        int caretPos = sqlEditor.getCaretPosition();
        try {
            String text = sqlEditor.getText(0, caretPos);
            int start = caretPos - 1;
            while (start >= 0) {
                char ch = text.charAt(start);
                if (Character.isWhitespace(ch) || ch == ',' || ch == '(' || ch == ')' || ch == '.' || ch == ';') {
                    break;
                }
                start--;
            }
            sqlEditor.replaceRange(selected + " ", start + 1, caretPos);
            if (suggestionWindow != null) {
                suggestionWindow.setVisible(false);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // --- Condition Editor Tab ---
    private JComponent buildVisualQueryTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topPanel.add(new JLabel("目标表:"));
        visualTableCombo = new JComboBox<>();
        visualTableCombo.setPreferredSize(new Dimension(180, 28));
        visualTableCombo.addActionListener(e -> onVisualTableSelected());
        topPanel.add(visualTableCombo);

        JButton addCondBtn = new JButton("+ 新增条件");
        addCondBtn.addActionListener(e -> addVisualConditionRow());
        topPanel.add(addCondBtn);

        panel.add(topPanel, BorderLayout.NORTH);

        visualConditionsContainer = new JPanel();
        visualConditionsContainer.setLayout(new BoxLayout(visualConditionsContainer, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JScrollPane(visualConditionsContainer);
        scrollPane.setBorder(BorderFactory.createTitledBorder("过滤条件 (AND 拼接)"));
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        JButton genSqlBtn = UIUtils.button("生成 SQL", 100);
        JButton execVisualBtn = UIUtils.button("立即查询", 100);

        bottomPanel.add(genSqlBtn);
        bottomPanel.add(execVisualBtn);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        genSqlBtn.addActionListener(e -> generateVisualSql(true));
        execVisualBtn.addActionListener(e -> executeVisualSql());

        return panel;
    }

    private void onVisualTableSelected() {
        String tableName = (String) visualTableCombo.getSelectedItem();
        if (tableName == null) return;

        visualConditionsContainer.removeAll();
        conditionRowsList.clear();
        visualConditionsContainer.revalidate();
        visualConditionsContainer.repaint();

        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                List<String> cols = new ArrayList<>();
                DatabaseMetaData metaData = connection.getMetaData();
                
                String selectedDb = (String) dbSwitchCombo.getSelectedItem();
                String selectedSchema = (String) schemaSwitchCombo.getSelectedItem();
                String catalog = selectedDb != null ? selectedDb : connection.getCatalog();
                String schema = selectedSchema != null ? selectedSchema : null;
                if (schema == null) {
                    try { schema = connection.getSchema(); } catch (Throwable ignored) {}
                }

                try (ResultSet rs = metaData.getColumns(catalog, schema, tableName, "%")) {
                    while (rs.next()) {
                        cols.add(rs.getString("COLUMN_NAME"));
                    }
                }
                return cols;
            }

            @Override
            protected void done() {
                try {
                    List<String> cols = get();
                    currentVisualColumnsList = cols;
                    currentTableColumns.clear();
                    currentTableColumns.addAll(cols);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }.execute();
    }

    private void addVisualConditionRow() {
        String tableName = (String) visualTableCombo.getSelectedItem();
        if (tableName == null) {
            UIUtils.info(getView(), "请先选择目标表！");
            return;
        }

        ConditionRow row = new ConditionRow(currentVisualColumnsList, r -> {
            visualConditionsContainer.remove(r.panel);
            conditionRowsList.remove(r);
            visualConditionsContainer.revalidate();
            visualConditionsContainer.repaint();
        });
        conditionRowsList.add(row);
        visualConditionsContainer.add(row.panel);
        visualConditionsContainer.revalidate();
        visualConditionsContainer.repaint();
    }

    private void populateVisualConditionColumns(List<String> cols) {
        currentVisualColumnsList = cols;
        for (ConditionRow r : conditionRowsList) {
            r.updateColumns(cols);
        }
    }

    private void updateConditionEditorColumns(String tableName) {
        if (!isConnected || connection == null || tableName == null) return;
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                List<String> cols = new ArrayList<>();
                DatabaseMetaData metaData = connection.getMetaData();
                
                String selectedDb = (String) dbSwitchCombo.getSelectedItem();
                String selectedSchema = (String) schemaSwitchCombo.getSelectedItem();
                String catalog = selectedDb != null ? selectedDb : connection.getCatalog();
                String schema = selectedSchema != null ? selectedSchema : null;
                if (schema == null) {
                    try { schema = connection.getSchema(); } catch (Throwable ignored) {}
                }

                try (ResultSet rs = metaData.getColumns(catalog, schema, tableName, "%")) {
                    while (rs.next()) {
                        cols.add(rs.getString("COLUMN_NAME"));
                    }
                }
                return cols;
            }

            @Override
            protected void done() {
                try {
                    List<String> cols = get();
                    currentTableColumns.clear();
                    currentTableColumns.addAll(cols);

                    if (visualTableCombo.getSelectedItem() != null && visualTableCombo.getSelectedItem().equals(tableName)) {
                        populateVisualConditionColumns(cols);
                    } else {
                        visualTableCombo.setSelectedItem(tableName);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }.execute();
    }

    private String generateVisualSql(boolean switchToEditor) {
        String tableName = (String) visualTableCombo.getSelectedItem();
        if (tableName == null || tableName.isEmpty()) {
            UIUtils.info(getView(), "请先选择数据表！");
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM ").append(tableName);

        if (!conditionRowsList.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < conditionRowsList.size(); i++) {
                ConditionRow row = conditionRowsList.get(i);
                String col = (String) row.colCombo.getSelectedItem();
                String op = (String) row.opCombo.getSelectedItem();
                String val = row.valField.getText().trim();

                if (col == null || col.isEmpty()) continue;
                if (i > 0) {
                    sb.append(" AND ");
                }

                sb.append(col).append(" ").append(op);

                if (!"IS NULL".equals(op) && !"IS NOT NULL".equals(op)) {
                    sb.append(" ");
                    boolean isNumeric = val.matches("-?\\d+(\\.\\d+)?");
                    if (isNumeric) {
                        sb.append(val);
                    } else {
                        sb.append("'").append(val.replace("'", "''")).append("'");
                    }
                }
            }
        }

        String dbType = (String) dbTypeCombo.getSelectedItem();
        if ("Oracle".equals(dbType)) {
            if (conditionRowsList.isEmpty()) {
                sb.append(" WHERE ROWNUM <= 100");
            } else {
                sb.append(" AND ROWNUM <= 100");
            }
        } else {
            sb.append(" LIMIT 100");
        }

        String sql = sb.toString();
        if (switchToEditor) {
            sqlEditor.setText(sql);
            editorTabbedPane.setSelectedIndex(0);
        }
        return sql;
    }

    private void executeVisualSql() {
        String sql = generateVisualSql(false);
        if (sql == null || sql.isEmpty()) return;

        sqlEditor.setText(sql);
        executeSql();
    }

    // --- Core Operations ---
    private void executeSql() {
        if (!isConnected || connection == null) {
            UIUtils.info(getView(), "请先连接数据库！");
            return;
        }
        String sql = sqlEditor.getText().trim();
        if (sql.isEmpty()) return;

        runBtn.setEnabled(false);
        resultStatusLabel.setText("正在执行中...");
        resultTable.setModel(new DefaultTableModel());
        consoleLog("执行 SQL: " + sql);

        new SwingWorker<SqlResult, Void>() {
            @Override
            protected SqlResult doInBackground() throws Exception {
                long startTime = System.currentTimeMillis();
                SqlResult result = new SqlResult();
                try (Statement stmt = connection.createStatement()) {
                    boolean isResultSet = stmt.execute(sql);
                    result.durationMs = System.currentTimeMillis() - startTime;
                    if (isResultSet) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            int colCount = meta.getColumnCount();
                            for (int i = 1; i <= colCount; i++) {
                                result.columnNames.add(meta.getColumnLabel(i));
                            }
                            int count = 0;
                            while (rs.next()) {
                                Vector<Object> row = new Vector<>();
                                for (int i = 1; i <= colCount; i++) {
                                    Object val = rs.getObject(i);
                                    if (val instanceof byte[]) {
                                        row.add("[Binary: " + ((byte[]) val).length + " bytes]");
                                    } else if (val != null) {
                                        row.add(val.toString());
                                    } else {
                                        row.add(null);
                                    }
                                }
                                result.data.add(row);
                                count++;
                                if (count >= 5000) {
                                    result.warning = "数据集超过 5000 行限制，已自动截断。";
                                    break;
                                }
                            }
                        }
                    } else {
                        result.updateCount = stmt.getUpdateCount();
                    }
                }
                return result;
            }

            @Override
            protected void done() {
                runBtn.setEnabled(true);
                try {
                    SqlResult res = get();
                    String statusText = "执行成功，耗时: " + res.durationMs + " ms. ";
                    if (res.updateCount != -1) {
                        statusText += "影响行数: " + res.updateCount;
                        resultStatusLabel.setText("影响行数: " + res.updateCount + " | 耗时: " + res.durationMs + " ms");
                        rightTabbedPane.setSelectedIndex(1);
                    } else {
                        statusText += "返回记录数: " + res.data.size();
                        if (res.warning != null) {
                            statusText += " (" + res.warning + ")";
                        }
                        DefaultTableModel model = new DefaultTableModel(res.data, res.columnNames) {
                            @Override
                            public boolean isCellEditable(int row, int column) {
                                return false;
                            }
                        };
                        resultTable.setModel(model);
                        String sText = "返回行数: " + res.data.size() + " | 耗时: " + res.durationMs + " ms";
                        if (res.warning != null) {
                            sText += " [" + res.warning + "]";
                        }
                        resultStatusLabel.setText(sText);
                        rightTabbedPane.setSelectedIndex(0);
                    }
                    consoleLog(statusText);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String err = cause.getMessage();
                    consoleLog("SQL 执行错误: " + err);
                    resultStatusLabel.setText("执行出错，请查看日志。");
                    rightTabbedPane.setSelectedIndex(1);
                }
            }
        }.execute();
    }

    private void saveProfile() {
        String name = UIUtils.input(getView(), "请输入要保存的配置名称:", "");
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim();

        DbConfigProfile p = new DbConfigProfile(
                name,
                (String) dbTypeCombo.getSelectedItem(),
                hostField.getText().trim(),
                portField.getText().trim(),
                databaseField.getText().trim(),
                userField.getText().trim(),
                new String(passField.getPassword()),
                driverClassField.getText().trim(),
                urlField.getText().trim(),
                jarPathField.getText().trim()
        );
        profiles.put(name, p);
        saveProfilesToPrefs();
        refreshProfilesCombo(name);
        UIUtils.info(getView(), "配置 '" + name + "' 保存成功！");
    }

    private void deleteProfile() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null) return;
        int opt = JOptionPane.showConfirmDialog(getView(), "确定要删除配置 '" + name + "' 吗？", "确认删除", JOptionPane.YES_NO_OPTION);
        if (opt == JOptionPane.YES_OPTION) {
            profiles.remove(name);
            saveProfilesToPrefs();
            refreshProfilesCombo(null);
            UIUtils.info(getView(), "配置已删除。");
        }
    }

    private void loadProfilesFromPrefs() {
        try {
            String json = prefs.get("db_profiles", null);
            if (json != null && !json.trim().isEmpty()) {
                Map<String, DbConfigProfile> loaded = mapper.readValue(json, new TypeReference<LinkedHashMap<String, DbConfigProfile>>(){});
                profiles.clear();
                profiles.putAll(loaded);
            }
            refreshProfilesCombo(null);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void saveProfilesToPrefs() {
        try {
            String json = mapper.writeValueAsString(profiles);
            prefs.put("db_profiles", json);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void refreshProfilesCombo(String selectName) {
        ignoreProfileEvents = true;
        profileCombo.removeAllItems();
        for (String n : profiles.keySet()) {
            profileCombo.addItem(n);
        }
        if (selectName != null) {
            profileCombo.setSelectedItem(selectName);
        } else if (profileCombo.getItemCount() > 0) {
            profileCombo.setSelectedIndex(0);
            onProfileSelected();
        }
        ignoreProfileEvents = false;
    }

    private void onProfileSelected() {
        if (ignoreProfileEvents) return;
        String name = (String) profileCombo.getSelectedItem();
        if (name == null) return;
        DbConfigProfile p = profiles.get(name);
        if (p == null) return;

        ignoreProfileEvents = true;
        ignoreUrlUpdate = true;
        try {
            dbTypeCombo.setSelectedItem(p.dbType);
            hostField.setText(p.host);
            portField.setText(p.port);
            databaseField.setText(p.database);
            userField.setText(p.username);
            passField.setText(p.password);
            driverClassField.setText(p.driverClass);
            urlField.setText(p.url);
            jarPathField.setText(p.jarPath);

            String type = p.dbType;
            boolean isCustom = "Custom".equals(type);
            driverClassField.setEditable(isCustom);
            jarPathField.setEnabled(isCustom);
            browseJarBtn.setEnabled(isCustom);
        } finally {
            ignoreProfileEvents = false;
            ignoreUrlUpdate = false;
        }
    }

    private String formatSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) return "";
        sql = sql.replaceAll("\\s+", " ").trim();
        Pattern pattern = Pattern.compile("'[^']*'|\"[^\"]*\"|`[^`]*`|\\w+|\\S");
        Matcher matcher = pattern.matcher(sql);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        Set<String> keywords = new HashSet<>(Arrays.asList(
                "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
                "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT",
                "AND", "OR", "UNION", "ALL", "AS", "IN", "IS", "NOT", "NULL", "LIKE", "EXISTS", "BETWEEN", "CASE", "WHEN", "THEN", "ELSE", "END"
        ));
        Set<String> newlineKeywords = new HashSet<>(Arrays.asList(
                "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "JOIN", "GROUP", "ORDER", "SET", "VALUES", "UNION"
        ));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String upperToken = token.toUpperCase();
            boolean isKeyword = keywords.contains(upperToken);
            String displayToken = isKeyword ? upperToken : token;

            if (isKeyword) {
                String nextToken = (i + 1 < tokens.size()) ? tokens.get(i + 1).toUpperCase() : "";
                String prevToken = (i - 1 >= 0) ? tokens.get(i - 1).toUpperCase() : "";

                boolean isFirstOfMultiWord = false;
                if (upperToken.equals("GROUP") && nextToken.equals("BY")) isFirstOfMultiWord = true;
                if (upperToken.equals("ORDER") && nextToken.equals("BY")) isFirstOfMultiWord = true;
                if ((upperToken.equals("LEFT") || upperToken.equals("RIGHT") || upperToken.equals("INNER")) && nextToken.equals("JOIN")) isFirstOfMultiWord = true;

                boolean isSecondOfMultiWord = false;
                if (upperToken.equals("BY") && (prevToken.equals("GROUP") || prevToken.equals("ORDER"))) isSecondOfMultiWord = true;
                if (upperToken.equals("JOIN") && (prevToken.equals("LEFT") || prevToken.equals("RIGHT") || prevToken.equals("INNER"))) isSecondOfMultiWord = true;

                if ((newlineKeywords.contains(upperToken) && !isSecondOfMultiWord) || isFirstOfMultiWord) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                } else if (upperToken.equals("AND") || upperToken.equals("OR")) {
                    sb.append("\n  ");
                }
            }

            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n' && sb.charAt(sb.length() - 1) != ' ') {
                if (!displayToken.equals(",") && !displayToken.equals(")") && !displayToken.equals("(")) {
                    sb.append(" ");
                }
            }
            sb.append(displayToken);
            if (displayToken.equals(",")) {
                sb.append(" ");
            }
        }
        return sb.toString().trim();
    }

    // Tree Node Types
    private enum MetadataType {
        TABLE, VIEW, FUNCTION
    }

    // Custom Node class for database metadata JTree
    private static class MetadataNode extends DefaultMutableTreeNode {
        private final String name;
        private final MetadataType type;

        public MetadataNode(String name, MetadataType type) {
            super(name);
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public MetadataType getType() {
            return type;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Custom Cell Renderer to represent Tables, Views, Functions distinctively
    private static class MetadataTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof MetadataNode) {
                MetadataNode node = (MetadataNode) value;
                setText(node.getName()); // Clean name display (no type prefixes)
                if (node.getType() == MetadataType.TABLE) {
                    setFont(getFont().deriveFont(Font.BOLD));
                    if (!sel) setForeground(UIManager.getColor("Component.accentColor"));
                } else if (node.getType() == MetadataType.VIEW) {
                    setFont(getFont().deriveFont(Font.PLAIN));
                    if (!sel) setForeground(UIManager.getColor("Label.foreground"));
                } else if (node.getType() == MetadataType.FUNCTION) {
                    setFont(getFont().deriveFont(Font.ITALIC));
                    if (!sel) setForeground(UIManager.getColor("Label.disabledForeground"));
                }
            } else {
                setFont(getFont().deriveFont(Font.BOLD));
            }
            return this;
        }
    }

    // Visual Condition Row representation
    private static class ConditionRow {
        public JPanel panel;
        public JComboBox<String> colCombo;
        public JComboBox<String> opCombo;
        public JTextField valField;

        public ConditionRow(List<String> cols, java.util.function.Consumer<ConditionRow> onDelete) {
            panel = new JPanel(new GridBagLayout());
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 4, 2, 4);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            colCombo = new JComboBox<>();
            colCombo.setPreferredSize(new Dimension(140, 26));
            updateColumns(cols);

            opCombo = new JComboBox<>(new String[]{"=", "!=", ">", ">=", "<", "<=", "LIKE", "IS NULL", "IS NOT NULL"});
            opCombo.setPreferredSize(new Dimension(110, 26));

            valField = new JTextField(12);
            valField.setPreferredSize(new Dimension(120, 26));

            JButton delBtn = new JButton("删除");
            delBtn.addActionListener(e -> onDelete.accept(this));

            opCombo.addActionListener(e -> {
                String op = (String) opCombo.getSelectedItem();
                boolean needsVal = !"IS NULL".equals(op) && !"IS NOT NULL".equals(op);
                valField.setEnabled(needsVal);
                if (!needsVal) {
                    valField.setText("");
                }
            });

            gbc.gridx = 0; gbc.weightx = 0.3;
            panel.add(colCombo, gbc);

            gbc.gridx = 1; gbc.weightx = 0.2;
            panel.add(opCombo, gbc);

            gbc.gridx = 2; gbc.weightx = 0.4;
            panel.add(valField, gbc);

            gbc.gridx = 3; gbc.weightx = 0.1;
            panel.add(delBtn, gbc);
        }

        public void updateColumns(List<String> cols) {
            colCombo.removeAllItems();
            for (String col : cols) {
                colCombo.addItem(col);
            }
        }
    }

    private static class SqlResult {
        public Vector<String> columnNames = new Vector<>();
        public Vector<Vector<Object>> data = new Vector<>();
        public int updateCount = -1;
        public long durationMs;
        public String warning;
    }

    public static class DbConfigProfile {
        public String name;
        public String dbType;
        public String host;
        public String port;
        public String database;
        public String username;
        public String password;
        public String driverClass;
        public String url;
        public String jarPath;

        public DbConfigProfile() {}

        public DbConfigProfile(String name, String dbType, String host, String port, String database, String username, String password, String driverClass, String url, String jarPath) {
            this.name = name;
            this.dbType = dbType;
            this.host = host;
            this.port = port;
            this.database = database;
            this.username = username;
            this.password = password;
            this.driverClass = driverClass;
            this.url = url;
            this.jarPath = jarPath;
        }
    }
}
