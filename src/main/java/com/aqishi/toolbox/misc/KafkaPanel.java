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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import java.awt.*;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * Kafka 管理工具面板：支持连接到 Kafka 集群，浏览主题和消费组，查看消费 Lag，拉取并检索消息，以及发送测试消息。
 */
public class KafkaPanel extends ToolPanel {

    private JComboBox<String> profileCombo;
    private JButton saveProfileBtn;
    private JButton delProfileBtn;
    private final Map<String, KafkaConfigProfile> profiles = new LinkedHashMap<>();
    private final Preferences prefs = Preferences.userNodeForPackage(KafkaPanel.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean ignoreProfileEvents = false;

    // Collapsible Connection Config
    private JPanel connHeaderPanel;
    private JPanel connBodyPanel;
    private JLabel connToggleLabel;
    private boolean connCollapsed = false;

    private JTextField serversField;
    private JTextArea customPropsArea;
    private JButton testBtn;
    private JButton connBtn;
    /** 连接状态：常驻连接卡片标题带右侧，文字 + 语义色双重表达 */
    private JLabel connStatusLabel;

    private boolean isConnected = false;
    private AdminClient adminClient = null;
    private String activeBootstrapServers = "";
    private Properties activeCustomProperties = new Properties();

    // Left Workspace: Topics & Consumer Groups JTabbedPane
    private JTabbedPane leftTabbedPane;
    
    // Topics Tab
    private JTextField topicSearchField;
    private JList<String> topicList;
    private DefaultListModel<String> topicListModel;
    private final List<String> allTopicsList = new ArrayList<>();
    private JButton refreshTopicsBtn;

    // Consumer Groups Tab
    private JTextField groupSearchField;
    private JList<String> groupList;
    private DefaultListModel<String> groupListModel;
    private final List<String> allGroupsList = new ArrayList<>();
    private JButton refreshGroupsBtn;

    // Right Workspace: Tabs
    private JTabbedPane rightTabbedPane;

    // Tab 1: Group Lag
    private JTable lagTable;
    private DefaultTableModel lagTableModel;
    private JLabel lagStatusLabel;

    // Tab 2: Message Viewer
    private JComboBox<String> partitionCombo;
    private JComboBox<String> offsetStrategyCombo;
    private JTextField msgSearchField;
    private JTable messageTable;
    private DefaultTableModel messageTableModel;
    private TableRowSorter<DefaultTableModel> messageTableSorter;
    private JTextArea messageDetailArea;
    private JTable messageHeadersTable;
    private DefaultTableModel messageHeadersTableModel;
    private JButton fetchBtn;
    private JLabel fetchStatusLabel;
    private final List<ConsumerRecord<String, String>> fetchedRecords = new ArrayList<>();

    // Tab 3: Message Producer
    private JTextField produceKeyField;
    private JTextArea produceHeadersArea;
    private JTextArea produceValueArea;
    private JButton produceSendBtn;
    private JLabel produceStatusLabel;

    // Tab 4: Topic Subscribers
    private JTable subscriberGroupTable;
    private DefaultTableModel subscriberGroupTableModel;
    private JTable subscriberMemberTable;
    private DefaultTableModel subscriberMemberTableModel;
    private JLabel subscribersStatusLabel;
    private final Map<String, List<MemberDescription>> groupTopicActiveMembers = new HashMap<>();

    // Tab 5: Logging / Console Tab
    private JTextArea consoleOutput;

    public KafkaPanel() {
        super("dev", "kafka.connector",
                "Kafka", "Message", "Consumer", "Group", "Lag", "Topic", "队列", "消息");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // 连接配置固定在顶部（高度自适应，可整块折叠）：连上之后把纵向空间全部让给下面的工作区
        root.add(buildConnectionCard(), BorderLayout.NORTH);

        // 运维控制台的固定动线是「选对象 → 看详情」，左窄右宽用可拖动分隔条按 3:7 分栏
        root.add(Layouts.splitHorizontal(buildBrowser(), buildWorkspace(), 0.3), BorderLayout.CENTER);

        // --- Hook Listeners ---
        setupListeners();

        // --- Load Profiles ---
        loadProfilesFromPrefs();

        return root;
    }

    /** 标签页内容容器：比 page() 更薄的一层内边距，免得和外层页边距叠加过厚 */
    private static JPanel tabPage() {
        JPanel page = Layouts.box(0, Tokens.SPACE_MD);
        page.setBorder(KitBorders.padding(Tokens.SPACE_MD));
        return page;
    }

    /** 卡片底部状态文字：小一号字 + 次要色，不跟表格内容抢视线 */
    private static JLabel statusLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Tokens.fontCaption());
        label.setForeground(Tokens.mutedForeground());
        return label;
    }

    // ==================== 顶部：连接配置卡片 ====================

    /**
     * 连接卡片：标题带本身就是折叠开关，右侧常驻连接状态。
     *
     * <p>没有直接用 {@code Card.titled(...)}，因为标题文案要跟着折叠状态变
     * （「点击折叠 / 点击展开」），所以自己拼一条与卡片标题带等高的行，
     * 再用 {@link Card.Hairline} 跟表单分隔，视觉上与其它卡片一致。</p>
     */
    private Card buildConnectionCard() {
        connToggleLabel = new JLabel("▼ Kafka 连接配置 (点击折叠)");
        connToggleLabel.setFont(Tokens.fontSectionTitle());
        connToggleLabel.setForeground(Tokens.foreground());

        // 连接状态先用文字说清楚，再叠加语义色：只靠颜色的话色觉障碍用户读不出来
        connStatusLabel = new JLabel();
        connStatusLabel.setFont(Tokens.fontCaption());

        connHeaderPanel = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        connHeaderPanel.setOpaque(false);
        connHeaderPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        connHeaderPanel.setBorder(KitBorders.padding(
                Tokens.SPACE_MD - 2, Tokens.CARD_PADDING, Tokens.SPACE_SM, Tokens.CARD_PADDING));
        connHeaderPanel.add(connToggleLabel, BorderLayout.WEST);
        connHeaderPanel.add(connStatusLabel, BorderLayout.EAST);

        // 三行共用同一个标签列，长标签（自定义属性）也能和输入列严格对齐
        profileCombo = Fields.combo(new String[0], 180);
        profileCombo.addActionListener(e -> onProfileSelected());
        saveProfileBtn = Buttons.secondary("保存配置");
        delProfileBtn = Buttons.danger("删除配置");

        serversField = Fields.text("127.0.0.1:9092");
        testBtn = Buttons.secondary("测试连接");
        connBtn = Buttons.primary("连接");

        customPropsArea = new JTextArea(2, 40);
        customPropsArea.setFont(Tokens.fontMono());
        customPropsArea.putClientProperty("JTextArea.placeholderText", "例如: \nrequest.timeout.ms=6000\nsecurity.protocol=PLAINTEXT");

        FormGrid form = new FormGrid();
        form.rowCompact("已存配置:", profileCombo, Layouts.wrapRow(saveProfileBtn, delProfileBtn));
        form.row("Bootstrap Servers:", serversField, Layouts.wrapRow(testBtn, connBtn));
        // 卡片底色与文本域底色相同，属性框放进有内边距的卡片时要靠细描边才看得出边界
        form.row("自定义属性 (Key=Value):", Fields.scrollBoxed(customPropsArea));

        connBodyPanel = Layouts.box();
        connBodyPanel.setBorder(KitBorders.padding(
                Tokens.SPACE_MD, Tokens.CARD_PADDING, Tokens.CARD_PADDING, Tokens.CARD_PADDING));
        connBodyPanel.add(form, BorderLayout.CENTER);

        JPanel headerBlock = Layouts.box();
        headerBlock.add(connHeaderPanel, BorderLayout.CENTER);
        headerBlock.add(new Card.Hairline(), BorderLayout.SOUTH);

        // 折叠时 connBodyPanel 整块隐藏，卡片自动收成一条标题带
        JPanel body = Layouts.box();
        body.add(headerBlock, BorderLayout.NORTH);
        body.add(connBodyPanel, BorderLayout.CENTER);

        Card card = Card.plain().setFlush(true);
        card.setContent(body);

        bindConnectionStatus();
        return card;
    }

    /**
     * 连接状态跟随「连接 / 断开」按钮的文案变化。
     *
     * <p>监听按钮文案而不是往 connect()/disconnect() 里插一行，是为了完全不碰连接管理代码。</p>
     */
    private void bindConnectionStatus() {
        updateConnStatus();
        connBtn.addPropertyChangeListener("text", e -> updateConnStatus());
    }

    private void updateConnStatus() {
        boolean connected = "断开".equals(connBtn.getText());
        connStatusLabel.setText(connected ? "状态：已连接" : "状态：未连接");
        connStatusLabel.setForeground(connected ? Tokens.success() : Tokens.danger());
    }

    // ==================== 左栏：对象浏览 ====================

    /** 左栏：主题 / 消费组两个列表，各自带过滤框与刷新按钮 */
    private JComponent buildBrowser() {
        leftTabbedPane = new JTabbedPane();
        leftTabbedPane.setBorder(null);

        topicSearchField = Fields.text("", "过滤主题...");
        refreshTopicsBtn = Buttons.secondary("刷新");
        topicListModel = new DefaultListModel<>();
        topicList = new JList<>(topicListModel);
        topicList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        leftTabbedPane.addTab("主题 (Topics)", listTab(topicSearchField, refreshTopicsBtn, topicList));

        groupSearchField = Fields.text("", "过滤消费组...");
        refreshGroupsBtn = Buttons.secondary("刷新");
        groupListModel = new DefaultListModel<>();
        groupList = new JList<>(groupListModel);
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        leftTabbedPane.addTab("消费组 (Groups)", listTab(groupSearchField, refreshGroupsBtn, groupList));

        return leftTabbedPane;
    }

    /** 列表页：过滤框吃掉整行宽度、刷新按钮贴右，列表卡片吸收剩余高度 */
    private static JComponent listTab(JTextField search, JButton refresh, JList<String> list) {
        JPanel bar = Layouts.box(Tokens.SPACE_SM, 0);
        bar.add(search, BorderLayout.CENTER);
        bar.add(refresh, BorderLayout.EAST);

        Card card = Card.plain().setFlush(true);
        card.setContent(Fields.scroll(list));

        JPanel page = tabPage();
        page.add(bar, BorderLayout.NORTH);
        page.add(card, BorderLayout.CENTER);
        return page;
    }

    // ==================== 右栏：详情与操作 ====================

    /** 右栏：Lag / 消息 / 生产 / 订阅者 / 日志五个视图，保留原有的标签页结构 */
    private JComponent buildWorkspace() {
        rightTabbedPane = new JTabbedPane();
        rightTabbedPane.setBorder(null);
        rightTabbedPane.addTab("消费 Lag 详情", buildLagTab());
        rightTabbedPane.addTab("消息查看", buildViewerTab());
        rightTabbedPane.addTab("发送消息模拟", buildProducerTab());
        rightTabbedPane.addTab("主题订阅者", buildSubscribersTab());
        rightTabbedPane.addTab("控制台日志", buildConsoleTab());
        return rightTabbedPane;
    }

    /** 消费 Lag：单表铺满，提示语挂在卡片底部状态条上，不再单独占一行 */
    private JComponent buildLagTab() {
        lagTableModel = new DefaultTableModel(new String[]{"主题", "分区 ID", "已提交 Offset", "最新 Log End Offset", "消费 Lag"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        lagTable = new JTable(lagTableModel);
        lagStatusLabel = statusLabel("选择左侧消费组查看消费详情。(提示: 双击主题可直接切换到相应主题)");

        Card card = Card.plain().setFlush(true);
        card.setContent(Fields.scroll(lagTable));
        card.setFooter(lagStatusLabel);

        JPanel page = tabPage();
        page.add(card, BorderLayout.CENTER);
        return page;
    }

    /** 消息查看：顶部一条过滤/拉取操作条，下面「列表 + 详情」上下分栏 */
    private JComponent buildViewerTab() {
        // 下拉框给固定宽度（组件库允许的例外），否则会被 ActionBar 拉成几倍宽
        partitionCombo = Fields.combo(new String[0], 110);
        offsetStrategyCombo = Fields.combo(new String[]{"最新 50 条 (Latest 50)", "从头开始 (Earliest)", "最新 100 条 (Latest 100)", "最新 500 条 (Latest 500)"}, 170);
        offsetStrategyCombo.setSelectedItem("最新 50 条 (Latest 50)");
        msgSearchField = Fields.text("", "过滤 Value/Key...");
        fetchBtn = Buttons.primary("拉取消息");
        // ActionBar 只锁最大宽度，窄窗口下按钮仍会被压到最小宽度而把文案省略成「拉取...」；
        // 主操作不该被截断，所以把最小宽度提到首选宽度，让检索框先让出空间
        fetchBtn.setMinimumSize(fetchBtn.getPreferredSize());

        // 用 ActionBar：窄窗口下先压缩检索框，「拉取消息」不会被挤到第二行，下拉框也不会被拉宽
        ActionBar bar = new ActionBar();
        bar.left(Fields.label("分区:"));
        bar.left(partitionCombo);
        bar.left(Fields.label("策略:"));
        bar.left(offsetStrategyCombo);
        bar.left(Fields.label("检索:"));
        bar.left(msgSearchField);
        bar.right(fetchBtn);

        Card controls = Card.plain();
        controls.setContent(bar);

        messageTableModel = new DefaultTableModel(new String[]{"分区 ID", "Offset", "时间戳", "Headers 数量", "Key 长度", "Key", "Value 长度", "Value"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                switch (columnIndex) {
                    case 0: return Integer.class;
                    case 1: return Long.class;
                    case 3: return Integer.class;
                    case 4: return Integer.class;
                    case 6: return Integer.class;
                    default: return String.class;
                }
            }
        };
        messageTable = new JTable(messageTableModel);
        messageTableSorter = new TableRowSorter<>(messageTableModel);
        messageTable.setRowSorter(messageTableSorter);

        fetchStatusLabel = statusLabel("选择左侧主题。");
        Card listCard = Card.plain().setFlush(true);
        listCard.setContent(Fields.scroll(messageTable));
        listCard.setFooter(fetchStatusLabel);

        messageDetailArea = new JTextArea();
        messageDetailArea.setFont(Tokens.fontMono());
        messageDetailArea.setEditable(false);
        messageDetailArea.setLineWrap(true);
        messageDetailArea.setWrapStyleWord(true);
        messageDetailArea.setBorder(KitBorders.padding(Tokens.SPACE_SM));

        messageHeadersTableModel = new DefaultTableModel(new String[]{"属性名称 (Key)", "属性值 (Value)"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        messageHeadersTable = new JTable(messageHeadersTableModel);

        JTabbedPane messageDetailTabbedPane = new JTabbedPane();
        messageDetailTabbedPane.setBorder(null);
        messageDetailTabbedPane.addTab("消息内容 (Value)", Fields.scroll(messageDetailArea));
        messageDetailTabbedPane.addTab("消息属性 (Headers)", Fields.scroll(messageHeadersTable));

        Card detailCard = Card.plain().setFlush(true);
        detailCard.setContent(messageDetailTabbedPane);

        JPanel page = tabPage();
        page.add(controls, BorderLayout.NORTH);
        page.add(Layouts.splitVertical(listCard, detailCard, 0.5), BorderLayout.CENTER);
        return page;
    }

    /** 发送消息：Key / Headers 是短表单放上面，Value 需要空间所以独占一张铺满卡片 */
    private JComponent buildProducerTab() {
        produceKeyField = Fields.text("");

        produceHeadersArea = new JTextArea(3, 40);
        produceHeadersArea.setFont(Tokens.fontMono());
        produceHeadersArea.putClientProperty("JTextArea.placeholderText", "例如:\ntraceId=123456\napp=toolbox\ncontent-type=application/json");

        FormGrid form = new FormGrid();
        form.row("Key (可选):", produceKeyField);
        form.row("自定义属性 (Headers, Key=Value):", Fields.scrollBoxed(produceHeadersArea));

        Card metaCard = Card.titled("消息元数据");
        metaCard.setContent(form);

        produceValueArea = new JTextArea();
        produceValueArea.setFont(Tokens.fontMono());
        produceValueArea.setLineWrap(true);
        produceValueArea.setWrapStyleWord(true);
        produceValueArea.setBorder(KitBorders.padding(Tokens.SPACE_SM));

        produceSendBtn = Buttons.primary("发送消息");
        produceStatusLabel = statusLabel("");

        // 发送按钮放卡片标题右侧，发送结果放底部状态条，正文区因此能占满剩余高度
        Card valueCard = Card.flush("Value (内容)");
        valueCard.setContent(Fields.scroll(produceValueArea));
        valueCard.addHeaderAction(produceSendBtn);
        valueCard.setFooter(produceStatusLabel);

        JPanel page = tabPage();
        page.add(metaCard, BorderLayout.NORTH);
        page.add(valueCard, BorderLayout.CENTER);
        return page;
    }

    /** 主题订阅者：消费组与其成员是主从关系，用上下分栏代替两个 TitledBorder */
    private JComponent buildSubscribersTab() {
        subscriberGroupTableModel = new DefaultTableModel(new String[]{"消费组 ID", "状态 (State)", "订阅类型", "当前总成员数"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        subscriberGroupTable = new JTable(subscriberGroupTableModel);
        subscriberGroupTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Card groupCard = Card.flush("订阅了该主题的消费组");
        groupCard.setContent(Fields.scroll(subscriberGroupTable));

        subscriberMemberTableModel = new DefaultTableModel(new String[]{"消费者成员 ID", "客户端 ID (ClientId)", "主机 (Host)", "分配的分区"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        subscriberMemberTable = new JTable(subscriberMemberTableModel);
        subscriberMemberTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        subscribersStatusLabel = statusLabel("在左侧选择主题以查询订阅者详情。");
        Card memberCard = Card.flush("选中消费组的活跃消费者成员 (分配了该主题分区)");
        memberCard.setContent(Fields.scroll(subscriberMemberTable));
        memberCard.setFooter(subscribersStatusLabel);

        JPanel page = tabPage();
        page.add(Layouts.splitVertical(groupCard, memberCard, 0.45), BorderLayout.CENTER);
        return page;
    }

    /** 控制台日志：铺满型卡片，长堆栈才有地方展开 */
    private JComponent buildConsoleTab() {
        consoleOutput = new JTextArea();
        consoleOutput.setFont(Tokens.fontMono());
        consoleOutput.setEditable(false);
        consoleOutput.setBorder(KitBorders.padding(Tokens.SPACE_SM));

        Card card = Card.plain().setFlush(true);
        card.setContent(Fields.scroll(consoleOutput));

        JPanel page = tabPage();
        page.add(card, BorderLayout.CENTER);
        return page;
    }

    private void setupListeners() {
        connHeaderPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                toggleConnPanel();
            }
        });

        saveProfileBtn.addActionListener(e -> saveProfile());
        delProfileBtn.addActionListener(e -> deleteProfile());

        testBtn.addActionListener(e -> testConnection());
        connBtn.addActionListener(e -> toggleConnection());

        refreshTopicsBtn.addActionListener(e -> loadTopicsList());
        refreshGroupsBtn.addActionListener(e -> loadGroupsList());

        // Sidebar List Selection Listeners
        topicList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            String topic = topicList.getSelectedValue();
            if (topic != null) {
                onTopicSelected(topic);
            }
        });

        // Topic List Double Click -> Auto fetch latest 50 messages
        topicList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && topicList.getSelectedValue() != null) {
                    selectTopic(topicList.getSelectedValue(), true);
                }
            }
        });

        groupList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            String group = groupList.getSelectedValue();
            if (group != null) {
                onGroupSelected(group);
            }
        });

        // Filter Topics
        topicSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterTopics(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterTopics(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterTopics(); }
        });

        // Filter Consumer Groups
        groupSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterGroups(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterGroups(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterGroups(); }
        });

        // Filter Messages
        msgSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterMessages(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterMessages(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterMessages(); }
        });

        // Lag Table double click -> Switch to Topic & Auto fetch latest 50
        lagTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && lagTable.getSelectedRow() != -1) {
                    int row = lagTable.getSelectedRow();
                    String topic = (String) lagTableModel.getValueAt(row, 0);
                    if (topic != null && !topic.trim().isEmpty()) {
                        selectTopic(topic.trim(), true);
                    }
                }
            }
        });

        // Message Table selection -> Value formatting & Headers list
        messageTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewIdx = messageTable.getSelectedRow();
            messageHeadersTableModel.setRowCount(0);
            if (viewIdx >= 0) {
                int modelIdx = messageTable.convertRowIndexToModel(viewIdx);
                if (modelIdx >= 0 && modelIdx < fetchedRecords.size()) {
                    ConsumerRecord<String, String> rec = fetchedRecords.get(modelIdx);
                    String val = rec.value();
                    messageDetailArea.setText(tryFormatJson(val));
                    messageDetailArea.setCaretPosition(0);

                    if (rec.headers() != null) {
                        for (Header header : rec.headers()) {
                            String hKey = header.key();
                            String hVal = header.value() != null ? new String(header.value(), StandardCharsets.UTF_8) : "[null]";
                            messageHeadersTableModel.addRow(new Object[]{hKey, hVal});
                        }
                    }
                } else {
                    messageDetailArea.setText("");
                }
            } else {
                messageDetailArea.setText("");
            }
        });

        // Topic Subscribers Group Table Selection -> Refresh Member Table
        subscriberGroupTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = subscriberGroupTable.getSelectedRow();
            subscriberMemberTableModel.setRowCount(0);
            if (row >= 0) {
                String groupId = (String) subscriberGroupTable.getValueAt(row, 0);
                java.util.List<MemberDescription> members = groupTopicActiveMembers.get(groupId);
                if (members != null) {
                    String selectedTopic = topicList.getSelectedValue();
                    for (MemberDescription m : members) {
                        java.util.List<Integer> partitions = new java.util.ArrayList<>();
                        for (TopicPartition tp : m.assignment().topicPartitions()) {
                            if (tp.topic().equals(selectedTopic)) {
                                partitions.add(tp.partition());
                            }
                        }
                        java.util.Collections.sort(partitions);
                        String partitionStr = partitions.toString();

                        subscriberMemberTableModel.addRow(new Object[]{
                                m.consumerId(), m.clientId(), m.host(), partitionStr
                        });
                    }
                }
            }
        });

        // Subscriber Group Table Double Click -> Switch to Consumer Group Lag
        subscriberGroupTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && subscriberGroupTable.getSelectedRow() != -1) {
                    int row = subscriberGroupTable.getSelectedRow();
                    String groupId = (String) subscriberGroupTableModel.getValueAt(row, 0);
                    if (groupId != null && !groupId.trim().isEmpty()) {
                        selectGroup(groupId.trim());
                    }
                }
            }
        });

        // Fetch & Produce actions
        fetchBtn.addActionListener(e -> fetchMessages());
        produceSendBtn.addActionListener(e -> produceMessage());
    }

    private void filterGroups() {
        String filter = groupSearchField.getText().trim().toLowerCase();
        groupListModel.clear();
        for (String g : allGroupsList) {
            if (filter.isEmpty() || g.toLowerCase().contains(filter)) {
                groupListModel.addElement(g);
            }
        }
    }

    private void filterMessages() {
        String rawInput = msgSearchField.getText();
        if (rawInput == null || rawInput.trim().isEmpty()) {
            messageTableSorter.setRowFilter(null);
            return;
        }

        String query = rawInput.trim().toLowerCase();
        String normalizedQuery = query.replaceAll("\\s+", "");

        messageTableSorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                int modelRow = entry.getIdentifier();
                if (modelRow >= 0 && modelRow < fetchedRecords.size()) {
                    ConsumerRecord<String, String> rec = fetchedRecords.get(modelRow);
                    String val = rec.value();
                    String key = rec.key();

                    if (val != null) {
                        String lowerVal = val.toLowerCase();
                        if (lowerVal.contains(query)) return true;
                        if (!normalizedQuery.isEmpty() && lowerVal.replaceAll("\\s+", "").contains(normalizedQuery)) {
                            return true;
                        }
                    }

                    if (key != null) {
                        String lowerKey = key.toLowerCase();
                        if (lowerKey.contains(query)) return true;
                        if (!normalizedQuery.isEmpty() && lowerKey.replaceAll("\\s+", "").contains(normalizedQuery)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        });
    }

    private void selectTopic(String topicName) {
        selectTopic(topicName, false);
    }

    private void selectTopic(String topicName, boolean autoFetch) {
        leftTabbedPane.setSelectedIndex(0);
        if (!topicSearchField.getText().isEmpty()) {
            topicSearchField.setText("");
        }
        if (allTopicsList.contains(topicName)) {
            topicList.setSelectedValue(topicName, true);
        }
        onTopicSelected(topicName);
        rightTabbedPane.setSelectedIndex(1);

        if (autoFetch) {
            offsetStrategyCombo.setSelectedItem("最新 50 条 (Latest 50)");
            fetchMessages();
        }
    }

    private void selectGroup(String groupId) {
        leftTabbedPane.setSelectedIndex(1);
        if (groupSearchField != null && !groupSearchField.getText().isEmpty()) {
            groupSearchField.setText("");
        }
        if (allGroupsList.contains(groupId)) {
            groupList.setSelectedValue(groupId, true);
        }
        onGroupSelected(groupId);
        rightTabbedPane.setSelectedIndex(0);
    }

    private void toggleConnPanel() {
        connCollapsed = !connCollapsed;
        connBodyPanel.setVisible(!connCollapsed);
        connToggleLabel.setText(connCollapsed ? "▶ Kafka 连接配置 (点击展开)" : "▼ Kafka 连接配置 (点击折叠)");
        JComponent v = getView();
        if (v != null) {
            v.revalidate();
            v.repaint();
        }
    }

    private void consoleLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            consoleOutput.append(msg + "\n\n");
            consoleOutput.setCaretPosition(consoleOutput.getDocument().getLength());
        });
    }

    private Properties parseCustomProperties() {
        Properties p = new Properties();
        String txt = customPropsArea.getText().trim();
        if (txt.isEmpty()) return p;
        try {
            p.load(new StringReader(txt));
        } catch (Exception ex) {
            consoleLog("解析自定义属性出错: " + ex.getMessage());
        }
        return p;
    }

    private void testConnection() {
        String servers = serversField.getText().trim();
        Properties custom = parseCustomProperties();

        testBtn.setEnabled(false);
        consoleLog("正在测试连接: " + servers);

        new SwingWorker<Void, Void>() {
            private String err = null;
            @Override
            protected Void doInBackground() throws Exception {
                Properties props = new Properties();
                props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
                props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
                props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000");
                for (String key : custom.stringPropertyNames()) {
                    props.put(key, custom.getProperty(key));
                }

                try (AdminClient testClient = AdminClient.create(props)) {
                    // Force a lightweight connection call to test
                    testClient.listTopics().names().get();
                }
                return null;
            }

            @Override
            protected void done() {
                testBtn.setEnabled(true);
                try {
                    get();
                    UIUtils.info(getView(), "Kafka 连接测试成功！");
                    consoleLog("连接测试成功。");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    err = c.getMessage();
                    UIUtils.error(getView(), "Kafka 连接测试失败:\n" + err);
                    consoleLog("连接测试失败: " + err);
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
        String servers = serversField.getText().trim();
        Properties custom = parseCustomProperties();

        connBtn.setEnabled(false);
        testBtn.setEnabled(false);
        consoleLog("正在建立 Kafka 连接: " + servers);

        new SwingWorker<AdminClient, Void>() {
            private String err = null;

            @Override
            protected AdminClient doInBackground() throws Exception {
                Properties props = new Properties();
                props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
                props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
                props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
                for (String key : custom.stringPropertyNames()) {
                    props.put(key, custom.getProperty(key));
                }
                AdminClient client = AdminClient.create(props);
                // Dry run list to verify connection
                client.listTopics().names().get();
                return client;
            }

            @Override
            protected void done() {
                try {
                    adminClient = get();
                    isConnected = true;
                    activeBootstrapServers = servers;
                    activeCustomProperties = custom;

                    connBtn.setText("断开");
                    connBtn.setEnabled(true);

                    // Disable inputs
                    serversField.setEnabled(false);
                    customPropsArea.setEnabled(false);

                    consoleLog("成功连接到 Kafka 集群！");

                    if (!connCollapsed) {
                        toggleConnPanel();
                    }

                    // Load topics & groups
                    loadTopicsList();
                    loadGroupsList();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    err = c.getMessage();
                    connBtn.setEnabled(true);
                    testBtn.setEnabled(true);
                    UIUtils.error(getView(), "连接 Kafka 失败:\n" + err);
                    consoleLog("连接 Kafka 失败: " + err);
                }
            }
        }.execute();
    }

    private void disconnect() {
        if (adminClient != null) {
            try {
                adminClient.close();
                consoleLog("Kafka 管理端连接已关闭。");
            } catch (Exception ex) {
                consoleLog("关闭连接异常: " + ex.getMessage());
            }
            adminClient = null;
        }

        isConnected = false;
        connBtn.setText("连接");
        testBtn.setEnabled(true);

        serversField.setEnabled(true);
        customPropsArea.setEnabled(true);

        if (connCollapsed) {
            toggleConnPanel();
        }

        // Clear UI contents
        allTopicsList.clear();
        topicListModel.clear();
        allGroupsList.clear();
        groupListModel.clear();

        lagTableModel.setRowCount(0);
        lagStatusLabel.setText("连接断开。");

        partitionCombo.removeAllItems();
        messageTableModel.setRowCount(0);
        messageDetailArea.setText("");
        fetchedRecords.clear();
        fetchStatusLabel.setText("连接断开。");

        subscriberGroupTableModel.setRowCount(0);
        subscriberMemberTableModel.setRowCount(0);
        subscribersStatusLabel.setText("连接断开。");
        groupTopicActiveMembers.clear();
    }

    private void loadTopicsList() {
        if (!isConnected || adminClient == null) return;
        consoleLog("正在加载 Kafka 主题列表...");
        new SwingWorker<Set<String>, Void>() {
            @Override
            protected Set<String> doInBackground() throws Exception {
                return adminClient.listTopics().names().get();
            }

            @Override
            protected void done() {
                try {
                    Set<String> names = get();
                    allTopicsList.clear();
                    allTopicsList.addAll(names);
                    Collections.sort(allTopicsList);

                    filterTopics();
                    consoleLog("主题列表加载成功，共 " + allTopicsList.size() + " 个主题。");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    consoleLog("加载主题列表失败: " + c.getMessage());
                }
            }
        }.execute();
    }

    private void filterTopics() {
        String filter = topicSearchField.getText().trim().toLowerCase();
        topicListModel.clear();
        for (String topic : allTopicsList) {
            if (filter.isEmpty() || topic.toLowerCase().contains(filter)) {
                topicListModel.addElement(topic);
            }
        }
    }

    private void loadGroupsList() {
        if (!isConnected || adminClient == null) return;
        consoleLog("正在加载消费组列表...");
        new SwingWorker<Collection<ConsumerGroupListing>, Void>() {
            @Override
            protected Collection<ConsumerGroupListing> doInBackground() throws Exception {
                return adminClient.listConsumerGroups().all().get();
            }

            @Override
            protected void done() {
                try {
                    Collection<ConsumerGroupListing> groups = get();
                    allGroupsList.clear();
                    for (ConsumerGroupListing g : groups) {
                        allGroupsList.add(g.groupId());
                    }
                    Collections.sort(allGroupsList);

                    filterGroups();
                    consoleLog("消费组列表加载成功，共 " + allGroupsList.size() + " 个消费组。");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    consoleLog("加载消费组失败: " + c.getMessage());
                }
            }
        }.execute();
    }

    private void onTopicSelected(String topicName) {
        rightTabbedPane.setSelectedIndex(1); // Switch to Message Viewer
        fetchStatusLabel.setText("当前选定主题: " + topicName);
        produceStatusLabel.setText("发布至主题: " + topicName);
        subscribersStatusLabel.setText("正在查询主题 '" + topicName + "' 的订阅者...");
        subscriberGroupTableModel.setRowCount(0);
        subscriberMemberTableModel.setRowCount(0);
        groupTopicActiveMembers.clear();

        // Load partitions into partitionCombo
        new SwingWorker<List<Integer>, Void>() {
            @Override
            protected List<Integer> doInBackground() throws Exception {
                DescribeTopicsResult desc = adminClient.describeTopics(Collections.singletonList(topicName));
                TopicDescription details = desc.allTopicNames().get().get(topicName);
                return details.partitions().stream().map(TopicPartitionInfo::partition).collect(Collectors.toList());
            }

            @Override
            protected void done() {
                try {
                    List<Integer> list = get();
                    partitionCombo.removeAllItems();
                    partitionCombo.addItem("所有分区 (All)");
                    for (Integer p : list) {
                        partitionCombo.addItem(String.valueOf(p));
                    }
                } catch (Exception ex) {
                    consoleLog("获取主题分区失败: " + ex.getMessage());
                }
            }
        }.execute();

        loadTopicSubscribers(topicName);
    }

    private void onGroupSelected(String groupId) {
        rightTabbedPane.setSelectedIndex(0); // Switch to Lag View
        lagStatusLabel.setText("正在查询消费组消费情况: " + groupId);
        lagTableModel.setRowCount(0);

        new SwingWorker<List<LagInfo>, Void>() {
            @Override
            protected List<LagInfo> doInBackground() throws Exception {
                List<LagInfo> list = new ArrayList<>();
                // 1. Get committed offsets
                Map<TopicPartition, OffsetAndMetadata> committed = 
                        adminClient.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();

                if (committed.isEmpty()) return list;

                // 2. Query log end offsets for these partitions
                Map<TopicPartition, OffsetSpec> offsetSpecs = new HashMap<>();
                for (TopicPartition tp : committed.keySet()) {
                    offsetSpecs.put(tp, OffsetSpec.latest());
                }
                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = 
                        adminClient.listOffsets(offsetSpecs).all().get();

                // 3. Assemble
                for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
                    TopicPartition tp = entry.getKey();
                    long committedOffset = entry.getValue() != null ? entry.getValue().offset() : 0;
                    long latestOffset = 0;
                    if (endOffsets.containsKey(tp)) {
                        latestOffset = endOffsets.get(tp).offset();
                    }
                    long lag = Math.max(0, latestOffset - committedOffset);
                    list.add(new LagInfo(tp.topic(), tp.partition(), committedOffset, latestOffset, lag));
                }
                list.sort(Comparator.comparing(LagInfo::getTopic).thenComparing(LagInfo::getPartition));
                return list;
            }

            @Override
            protected void done() {
                try {
                    List<LagInfo> data = get();
                    lagTableModel.setRowCount(0);
                    for (LagInfo info : data) {
                        lagTableModel.addRow(new Object[]{
                                info.topic, info.partition, info.committedOffset, info.latestOffset, info.lag
                        });
                    }
                    lagStatusLabel.setText("消费组 '" + groupId + "' 消费状态已更新，共计监测 " + data.size() + " 个分区。");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    lagStatusLabel.setText("查询 Lag 详情失败: " + c.getMessage());
                }
            }
        }.execute();
    }

    private void fetchMessages() {
        String topic = topicList.getSelectedValue();
        if (topic == null) {
            UIUtils.info(getView(), "请在左侧选择一个主题！");
            return;
        }

        String partStr = (String) partitionCombo.getSelectedItem();
        String strategy = (String) offsetStrategyCombo.getSelectedItem();

        fetchBtn.setEnabled(false);
        fetchStatusLabel.setText("正在拉取消息...");
        messageTableModel.setRowCount(0);
        messageDetailArea.setText("");
        fetchedRecords.clear();

        new SwingWorker<List<ConsumerRecord<String, String>>, Void>() {
            @Override
            protected List<ConsumerRecord<String, String>> doInBackground() throws Exception {
                List<ConsumerRecord<String, String>> list = new ArrayList<>();
                
                // Configure consumer
                Properties props = new Properties();
                props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, activeBootstrapServers);
                props.put(ConsumerConfig.GROUP_ID_CONFIG, "java-toolbox-temp-group-" + UUID.randomUUID());
                props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
                props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                // Merge custom props
                for (String k : activeCustomProperties.stringPropertyNames()) {
                    props.put(k, activeCustomProperties.getProperty(k));
                }

                try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                    // Determine which partitions to query
                    List<TopicPartition> tps = new ArrayList<>();
                    if (partStr == null || partStr.startsWith("所有分区")) {
                        List<PartitionInfo> infos = consumer.partitionsFor(topic);
                        if (infos != null) {
                            for (PartitionInfo info : infos) {
                                tps.add(new TopicPartition(topic, info.partition()));
                            }
                        }
                    } else {
                        tps.add(new TopicPartition(topic, Integer.parseInt(partStr)));
                    }

                    if (tps.isEmpty()) return list;

                    consumer.assign(tps);

                    // Parse limit / strategy
                    int limit = 100;
                    if (strategy.contains("50")) limit = 50;
                    else if (strategy.contains("500")) limit = 500;

                    if (strategy.startsWith("从头开始")) {
                        consumer.seekToBeginning(tps);
                    } else {
                        // "Latest N": Seek to end, then backoff per partition
                        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(tps);
                        Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(tps);
                        
                        // Apportion N limit among partitions (at least 1 per partition)
                        int perPartitionLimit = Math.max(1, limit / tps.size());
                        for (TopicPartition tp : tps) {
                            long end = endOffsets.getOrDefault(tp, 0L);
                            long beg = beginningOffsets.getOrDefault(tp, 0L);
                            long start = Math.max(beg, end - perPartitionLimit);
                            consumer.seek(tp, start);
                        }
                    }

                    // Poll loop
                    long deadline = System.currentTimeMillis() + 4000; // max 4 seconds wait
                    while (System.currentTimeMillis() < deadline && list.size() < limit) {
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                        if (records.isEmpty()) {
                            // If we already have messages and did a poll returning empty, break early to save time
                            if (!list.isEmpty()) break;
                        }
                        for (ConsumerRecord<String, String> rec : records) {
                            list.add(rec);
                            if (list.size() >= limit) break;
                        }
                    }
                }
                
                // Sort records by timestamp (or offset)
                list.sort((r1, r2) -> Long.compare(r2.timestamp(), r1.timestamp())); // Latest first
                return list;
            }

            @Override
            protected void done() {
                fetchBtn.setEnabled(true);
                try {
                    List<ConsumerRecord<String, String>> list = get();
                    fetchedRecords.addAll(list);

                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                    for (ConsumerRecord<String, String> rec : list) {
                        String timeStr = "";
                        try {
                            LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(rec.timestamp()), ZoneId.systemDefault());
                            timeStr = formatter.format(ldt);
                        } catch (Exception ignored) {}

                        String k = rec.key();
                        int kLen = k != null ? k.length() : 0;
                        String kVal = k != null ? k : "[null]";

                        String v = rec.value();
                        int vLen = v != null ? v.length() : 0;
                        String vVal = v != null ? v : "[null]";
                        
                        int hCount = rec.headers() != null ? rec.headers().toArray().length : 0;

                        // Clean values for grid display
                        if (vVal.length() > 60) {
                            vVal = vVal.substring(0, 60) + "...";
                        }

                        messageTableModel.addRow(new Object[]{
                                rec.partition(), rec.offset(), timeStr, hCount, kLen, kVal, vLen, vVal
                        });
                    }

                    fetchStatusLabel.setText("已拉取 " + list.size() + " 条消息。");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    fetchStatusLabel.setText("拉取失败: " + c.getMessage());
                    consoleLog("拉取消息失败: " + c.getMessage());
                }
            }
        }.execute();
    }

    private void produceMessage() {
        String topic = topicList.getSelectedValue();
        if (topic == null) {
            UIUtils.info(getView(), "请在左侧选择目标主题！");
            return;
        }

        String key = produceKeyField.getText().trim();
        String headersTxt = produceHeadersArea.getText().trim();
        String val = produceValueArea.getText();

        produceSendBtn.setEnabled(false);
        produceStatusLabel.setText("正在发送中...");

        new SwingWorker<RecordMetadata, Void>() {
            @Override
            protected RecordMetadata doInBackground() throws Exception {
                Properties props = new Properties();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, activeBootstrapServers);
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                // Merge custom
                for (String k : activeCustomProperties.stringPropertyNames()) {
                    props.put(k, activeCustomProperties.getProperty(k));
                }

                try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                    String k = key.isEmpty() ? null : key;
                    ProducerRecord<String, String> record = new ProducerRecord<>(topic, k, val);

                    if (!headersTxt.isEmpty()) {
                        String[] lines = headersTxt.split("\n");
                        for (String line : lines) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            int eqIdx = line.indexOf('=');
                            if (eqIdx == -1) eqIdx = line.indexOf(':');
                            if (eqIdx > 0) {
                                String hKey = line.substring(0, eqIdx).trim();
                                String hVal = line.substring(eqIdx + 1).trim();
                                record.headers().add(hKey, hVal.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    }

                    return producer.send(record).get();
                }
            }

            @Override
            protected void done() {
                produceSendBtn.setEnabled(true);
                try {
                    RecordMetadata meta = get();
                    produceStatusLabel.setText("发送成功！分区: " + meta.partition() + " | Offset: " + meta.offset());
                    consoleLog("消息发布成功！主题: " + meta.topic() + " | 分区: " + meta.partition() + " | Offset: " + meta.offset());
                    produceKeyField.setText("");
                    produceHeadersArea.setText("");
                    produceValueArea.setText("");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    produceStatusLabel.setText("发送失败！");
                    UIUtils.error(getView(), "发送消息失败:\n" + c.getMessage());
                    consoleLog("发送消息失败: " + c.getMessage());
                }
            }
        }.execute();
    }

    private String tryFormatJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String trimmed = raw.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                Object json = mapper.readValue(trimmed, Object.class);
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            } catch (Exception ignored) {}
        }
        return raw;
    }

    // --- Saved Profiles Management ---
    private void saveProfile() {
        String name = UIUtils.input(getView(), "请输入要保存的 Kafka 配置名称:", "");
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim();

        KafkaConfigProfile p = new KafkaConfigProfile(
                name,
                serversField.getText().trim(),
                customPropsArea.getText().trim()
        );
        profiles.put(name, p);
        saveProfilesToPrefs();
        refreshProfilesCombo(name);
        UIUtils.info(getView(), "Kafka 配置 '" + name + "' 保存成功！");
    }

    private void deleteProfile() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null) return;
        int opt = JOptionPane.showConfirmDialog(getView(), "确定要删除 Kafka 配置 '" + name + "' 吗？", "确认删除", JOptionPane.YES_NO_OPTION);
        if (opt == JOptionPane.YES_OPTION) {
            profiles.remove(name);
            saveProfilesToPrefs();
            refreshProfilesCombo(null);
            UIUtils.info(getView(), "配置已删除。");
        }
    }

    private void loadProfilesFromPrefs() {
        try {
            String json = prefs.get("kafka_profiles", null);
            if (json != null && !json.trim().isEmpty()) {
                Map<String, KafkaConfigProfile> loaded = mapper.readValue(json, new TypeReference<LinkedHashMap<String, KafkaConfigProfile>>(){});
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
            prefs.put("kafka_profiles", json);
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
        KafkaConfigProfile p = profiles.get(name);
        if (p == null) return;

        ignoreProfileEvents = true;
        serversField.setText(p.bootstrapServers);
        customPropsArea.setText(p.customProperties);
        ignoreProfileEvents = false;
    }

    // Profile DTO
    public static class KafkaConfigProfile {
        public String name;
        public String bootstrapServers;
        public String customProperties;

        public KafkaConfigProfile() {}

        public KafkaConfigProfile(String name, String bootstrapServers, String customProperties) {
            this.name = name;
            this.bootstrapServers = bootstrapServers;
            this.customProperties = customProperties;
        }
    }

    // Consumer Group Lag DTO
    private static class LagInfo {
        public String topic;
        public int partition;
        public long committedOffset;
        public long latestOffset;
        public long lag;

        public LagInfo(String topic, int partition, long committedOffset, long latestOffset, long lag) {
            this.topic = topic;
            this.partition = partition;
            this.committedOffset = committedOffset;
            this.latestOffset = latestOffset;
            this.lag = lag;
        }

        public String getTopic() { return topic; }
        public int getPartition() { return partition; }
    }

    private void loadTopicSubscribers(String topicName) {
        if (!isConnected || adminClient == null) return;

        new SwingWorker<java.util.List<SubscriberGroupInfo>, Void>() {
            @Override
            protected java.util.List<SubscriberGroupInfo> doInBackground() throws Exception {
                java.util.List<SubscriberGroupInfo> subscribers = new java.util.ArrayList<>();

                // 1. List all consumer groups
                Collection<ConsumerGroupListing> groups = adminClient.listConsumerGroups().all().get();
                java.util.List<String> groupIds = groups.stream().map(ConsumerGroupListing::groupId).collect(Collectors.toList());
                if (groupIds.isEmpty()) return subscribers;

                // 2. Describe consumer groups in batch
                Map<String, ConsumerGroupDescription> descriptions = adminClient.describeConsumerGroups(groupIds).all().get();

                // 3. Find active subscribers
                Set<String> activeGroups = new HashSet<>();
                for (Map.Entry<String, ConsumerGroupDescription> entry : descriptions.entrySet()) {
                    String groupId = entry.getKey();
                    ConsumerGroupDescription desc = entry.getValue();
                    java.util.List<MemberDescription> activeMembers = new java.util.ArrayList<>();

                    for (MemberDescription member : desc.members()) {
                        boolean assignedToTopic = false;
                        for (TopicPartition tp : member.assignment().topicPartitions()) {
                            if (tp.topic().equals(topicName)) {
                                assignedToTopic = true;
                                break;
                            }
                        }
                        if (assignedToTopic) {
                            activeMembers.add(member);
                        }
                    }

                    if (!activeMembers.isEmpty()) {
                        activeGroups.add(groupId);
                        groupTopicActiveMembers.put(groupId, activeMembers);
                    }
                }

                // 4. Find historical/offset subscribers concurrently to avoid single group failure
                Set<String> offsetGroups = new HashSet<>();
                try {
                    Map<String, org.apache.kafka.common.KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> futures = new HashMap<>();
                    ListConsumerGroupOffsetsOptions options = new ListConsumerGroupOffsetsOptions().timeoutMs(5000);
                    for (String gid : groupIds) {
                        futures.put(gid, adminClient.listConsumerGroupOffsets(gid, options).partitionsToOffsetAndMetadata());
                    }
                    for (Map.Entry<String, org.apache.kafka.common.KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> entry : futures.entrySet()) {
                        String gid = entry.getKey();
                        try {
                            Map<TopicPartition, OffsetAndMetadata> offsets = entry.getValue().get(3, java.util.concurrent.TimeUnit.SECONDS);
                            if (offsets != null) {
                                for (TopicPartition tp : offsets.keySet()) {
                                    if (tp.topic().equals(topicName)) {
                                        offsetGroups.add(gid);
                                        break;
                                    }
                                }
                            }
                        } catch (java.util.concurrent.TimeoutException ex) {
                            // Ignore timeout for individual group
                            consoleLog("查询消费组 " + gid + " Offset 超时");
                        } catch (Exception ex) {
                            // Ignore other individual errors
                        }
                    }
                } catch (Exception ex) {
                    consoleLog("查询消费组 Offset 失败: " + ex.getMessage());
                }

                // Combine active and offset-only subscribers
                Set<String> allSubs = new HashSet<>();
                allSubs.addAll(activeGroups);
                allSubs.addAll(offsetGroups);

                for (String gid : allSubs) {
                    boolean active = activeGroups.contains(gid);
                    ConsumerGroupDescription desc = descriptions.get(gid);
                    String state = desc != null ? desc.state().toString() : "UNKNOWN";
                    int totalMembers = desc != null ? desc.members().size() : 0;
                    String typeStr = active ? "活动中 (Active)" : "历史/仅含Offset (Inactive)";
                    subscribers.add(new SubscriberGroupInfo(gid, state, typeStr, totalMembers));
                }

                subscribers.sort(Comparator.comparing(SubscriberGroupInfo::getGroupId));
                return subscribers;
            }

            @Override
            protected void done() {
                try {
                    java.util.List<SubscriberGroupInfo> list = get();
                    subscriberGroupTableModel.setRowCount(0);
                    subscriberMemberTableModel.setRowCount(0);

                    for (SubscriberGroupInfo s : list) {
                        subscriberGroupTableModel.addRow(new Object[]{
                                s.groupId, s.state, s.subType, s.totalMembers
                        });
                    }

                    subscribersStatusLabel.setText("主题 '" + topicName + "' 订阅者查询成功，找到 " + list.size() + " 个订阅消费组。");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    subscribersStatusLabel.setText("查询订阅者失败: " + c.getMessage());
                    consoleLog("查询主题订阅者失败: " + c.getMessage());
                }
            }
        }.execute();
    }

    // SubscriberGroupInfo DTO
    private static class SubscriberGroupInfo {
        public String groupId;
        public String state;
        public String subType;
        public int totalMembers;

        public SubscriberGroupInfo(String groupId, String state, String subType, int totalMembers) {
            this.groupId = groupId;
            this.state = state;
            this.subType = subType;
            this.totalMembers = totalMembers;
        }

        public String getGroupId() { return groupId; }
    }
}
