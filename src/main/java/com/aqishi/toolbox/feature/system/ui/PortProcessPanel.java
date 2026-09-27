package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.system.domain.KillResult;
import com.aqishi.toolbox.feature.system.domain.PortCheck;
import com.aqishi.toolbox.feature.system.domain.PortEntry;
import com.aqishi.toolbox.feature.system.domain.PortQuery;
import com.aqishi.toolbox.feature.system.domain.PortSnapshot;
import com.aqishi.toolbox.feature.system.domain.ProcessDetails;
import com.aqishi.toolbox.feature.system.domain.ProcessKillPolicy;
import com.aqishi.toolbox.feature.system.infra.PortProcessProbe;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 端口与进程面板：列出监听端口 / 连接及其所属进程，按端口查找，查看命令行，结束占用端口的进程。
 *
 * <p>所有系统命令都在后台线程执行。刷新、详情、结束进程各领一个递增序号，结果回到 EDT 时
 * 序号已过期就丢弃——自动刷新与手动刷新叠在一起、或者用户在命令行查询期间换了选中行，
 * 界面上都只会出现最后一次的结果。</p>
 */
public class PortProcessPanel extends ToolPanel implements ManagedResourceOwner {

    /** 自动刷新间隔。 */
    static final int AUTO_REFRESH_MS = 3000;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PortProcessProbe probe;

    private JPanel root;
    private JTextField portField;
    private JTextField filterField;
    private JCheckBox listenOnlyCheck;
    private JCheckBox tcpCheck;
    private JCheckBox udpCheck;
    private JCheckBox autoRefreshCheck;
    private JButton refreshBtn;
    private JTextField hostField;
    private JButton checkFreeBtn;
    private JLabel freeLabel;

    private PortTableModel model;
    private JTable table;
    private TableRowSorter<PortTableModel> sorter;
    private JLabel statusLabel;
    private JLabel hintLabel;

    private JTextArea detailArea;
    private JButton killBtn;
    private JButton killTreeBtn;

    private Timer autoTimer;
    private PortQuery portQuery = PortQuery.any();
    private PortSnapshot snapshot;
    /** 命令行查询较慢（Windows 上要起 PowerShell），同一 PID 只查一次；刷新后清掉已消失的 PID。 */
    private final Map<Long, ProcessDetails> detailCache = new HashMap<>();

    private final AtomicLong refreshGeneration = new AtomicLong();
    private final AtomicLong detailGeneration = new AtomicLong();
    private final AtomicLong killGeneration = new AtomicLong();
    private final AtomicLong checkGeneration = new AtomicLong();
    private final AtomicReference<SwingWorker<?, ?>> refreshWorker = new AtomicReference<>();
    private final AtomicReference<SwingWorker<?, ?>> detailWorker = new AtomicReference<>();
    private final AtomicReference<SwingWorker<?, ?>> killWorker = new AtomicReference<>();
    private final AtomicReference<SwingWorker<?, ?>> checkWorker = new AtomicReference<>();
    private boolean refreshing;
    private boolean killing;
    /** 刷新替换表格数据期间选中会先被清空再恢复，这段时间的选中事件不能打断正在进行的详情查询。 */
    private boolean repopulating;
    /** 结束进程后的结果提示：随后的自动刷新会改写状态栏，刷新完成时再显示一次，免得一闪而过。 */
    private String notice;
    private Color noticeColor;

    public PortProcessPanel() {
        this(ToolCatalog.PORT_PROCESS, new PortProcessProbe());
    }

    public PortProcessPanel(PortProcessProbe probe) {
        this(ToolCatalog.PORT_PROCESS, probe);
    }

    public PortProcessPanel(ToolDescriptor descriptor, PortProcessProbe probe) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    @Override
    protected JComponent build() {
        root = Layouts.page();
        root.add(buildQueryCard(), BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(buildTableCard(), buildDetailCard(), 0.62, 0.62), BorderLayout.CENTER);

        autoTimer = new Timer(AUTO_REFRESH_MS, event -> autoRefreshTick());
        autoTimer.setRepeats(true);

        // 第一次真正显示出来时才采集：构建面板（例如搜索预览、测试）不应该触发系统命令。
        root.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && root.isShowing()
                    && snapshot == null && !refreshing) {
                refresh(true);
            }
        });
        updateActions();
        return root;
    }

    // ==========================================
    // 查询区
    // ==========================================
    private Card buildQueryCard() {
        portField = Fields.mono("");
        portField.setColumns(16);
        portField.putClientProperty("JTextField.placeholderText", I18n.get("tool.portprocess.placeholder.port"));
        JButton findBtn = Buttons.primary(I18n.get("tool.portprocess.btn.find"));
        filterField = Fields.text("", I18n.get("tool.portprocess.placeholder.filter"));
        filterField.setColumns(20);

        JPanel searchRow = Layouts.wrapRow(portField, findBtn,
                Fields.label(I18n.get("tool.portprocess.label.filter")), filterField);

        listenOnlyCheck = Fields.check(I18n.get("tool.portprocess.check.listenOnly"), true);
        tcpCheck = Fields.check("TCP", true);
        udpCheck = Fields.check("UDP", true);
        autoRefreshCheck = Fields.check(I18n.get("tool.portprocess.check.autoRefresh"), false);
        refreshBtn = Buttons.secondary(I18n.get("tool.portprocess.btn.refresh"));
        JPanel optionsRow = Layouts.wrapRow(listenOnlyCheck, tcpCheck, udpCheck, autoRefreshCheck, refreshBtn);

        hostField = Fields.mono("");
        hostField.setColumns(16);
        hostField.putClientProperty("JTextField.placeholderText", I18n.get("tool.portprocess.placeholder.host"));
        checkFreeBtn = Buttons.secondary(I18n.get("tool.portprocess.btn.checkFree"));
        freeLabel = Fields.caption("");
        JPanel freeRow = Layouts.wrapRow(hostField, checkFreeBtn, freeLabel);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.portprocess.label.port"), searchRow);
        form.row(I18n.get("tool.portprocess.label.options"), optionsRow);
        form.row(I18n.get("tool.portprocess.label.portFree"), freeRow);

        Card card = Card.titled(I18n.get("tool.portprocess.card.query"),
                I18n.get("tool.portprocess.card.query.subtitle"));
        card.setContent(form);

        findBtn.addActionListener(event -> find());
        portField.addActionListener(event -> find());
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFilter();
            }
        });
        tcpCheck.addActionListener(event -> applyFilter());
        udpCheck.addActionListener(event -> applyFilter());
        // 「仅监听」让命令自己过滤（lsof -sTCP:LISTEN、ss -l），切换时需要重新采集。
        listenOnlyCheck.addActionListener(event -> refresh(true));
        autoRefreshCheck.addActionListener(event -> {
            if (autoRefreshCheck.isSelected()) {
                autoTimer.start();
            } else {
                autoTimer.stop();
            }
        });
        refreshBtn.addActionListener(event -> refresh(true));
        checkFreeBtn.addActionListener(event -> checkPortFree());
        hostField.addActionListener(event -> checkPortFree());
        return card;
    }

    // ==========================================
    // 表格
    // ==========================================
    private Card buildTableCard() {
        model = new PortTableModel();
        table = new JTable(model);
        table.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.getColumnModel().getColumn(PortTableModel.COL_PROTOCOL).setMaxWidth(70);
        table.getColumnModel().getColumn(PortTableModel.COL_LOCAL).setPreferredWidth(150);
        table.getColumnModel().getColumn(PortTableModel.COL_PORT).setPreferredWidth(70);
        table.getColumnModel().getColumn(PortTableModel.COL_REMOTE).setPreferredWidth(170);
        table.getColumnModel().getColumn(PortTableModel.COL_STATE).setPreferredWidth(100);
        table.getColumnModel().getColumn(PortTableModel.COL_PID).setPreferredWidth(70);
        table.getColumnModel().getColumn(PortTableModel.COL_PROCESS).setPreferredWidth(160);
        table.getColumnModel().getColumn(PortTableModel.COL_PORT).setCellRenderer(new MissingValueRenderer("*"));
        table.getColumnModel().getColumn(PortTableModel.COL_PID).setCellRenderer(new MissingValueRenderer("-"));
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !repopulating) {
                selectionChanged();
            }
        });

        Card card = Card.flush(I18n.get("tool.portprocess.card.table"));
        card.setContent(Fields.scroll(table));
        statusLabel = Fields.caption(I18n.get("tool.portprocess.status.ready"));
        card.addHeaderAction(statusLabel);
        hintLabel = Fields.caption("");
        hintLabel.setForeground(Tokens.warning());
        hintLabel.setVisible(false);
        card.setFooter(hintLabel);
        return card;
    }

    private Card buildDetailCard() {
        detailArea = Fields.output(12, 30);
        detailArea.setText(I18n.get("tool.portprocess.detail.empty"));
        Card card = Card.flush(I18n.get("tool.portprocess.card.detail"));
        card.setContent(Fields.scroll(detailArea));

        JButton copyRowBtn = Buttons.snug(I18n.get("tool.portprocess.btn.copyRow"));
        JButton copyPidBtn = Buttons.snug(I18n.get("tool.portprocess.btn.copyPid"));
        killBtn = Buttons.danger(I18n.get("tool.portprocess.btn.kill"));
        killTreeBtn = Buttons.danger(I18n.get("tool.portprocess.btn.killTree"));
        card.addHeaderAction(copyRowBtn);
        card.addHeaderAction(copyPidBtn);
        card.addHeaderAction(killBtn);
        if (probe.supportsTreeKill()) {
            card.addHeaderAction(killTreeBtn);
        }

        copyRowBtn.addActionListener(event -> copySelected(false));
        copyPidBtn.addActionListener(event -> copySelected(true));
        killBtn.addActionListener(event -> requestKill(false));
        killTreeBtn.addActionListener(event -> requestKill(true));
        return card;
    }

    // ==========================================
    // 采集
    // ==========================================
    private void autoRefreshTick() {
        // 切到别的工具后面板不可见，不必每 3 秒跑一次 netstat；上一轮没跑完也不叠加。
        if (root == null || !root.isShowing() || refreshing || killing) {
            return;
        }
        refresh(false);
    }

    /** @param announce 是否在状态栏显示「正在读取」；自动刷新时不闪烁状态栏 */
    private void refresh(boolean announce) {
        long ticket = refreshGeneration.incrementAndGet();
        cancel(refreshWorker);
        boolean listeningOnly = listenOnlyCheck.isSelected();
        refreshing = true;
        refreshBtn.setEnabled(false);
        if (announce) {
            setStatus(I18n.get("tool.portprocess.status.loading"), Tokens.mutedForeground());
        }
        SwingWorker<PortSnapshot, Void> worker = new SwingWorker<>() {
            @Override
            protected PortSnapshot doInBackground() throws Exception {
                return probe.snapshot(listeningOnly);
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != refreshGeneration.get()) {
                    return;
                }
                refreshing = false;
                refreshBtn.setEnabled(true);
                try {
                    applySnapshot(get());
                } catch (ExecutionException error) {
                    setStatus(describeProbeFailure(error.getCause()), Tokens.danger());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        refreshWorker.set(worker);
        worker.execute();
    }

    /** 包内可见：测试直接灌入快照，覆盖过滤、选中保持与提示逻辑。 */
    void applySnapshot(PortSnapshot newSnapshot) {
        String selectedIdentity = selectedEntry().map(PortEntry::identity).orElse(null);
        snapshot = newSnapshot;
        boolean restored = false;
        repopulating = true;
        try {
            model.setRows(newSnapshot.entries());
            applyFilter();
            restored = selectedIdentity != null && reselect(selectedIdentity);
        } finally {
            repopulating = false;
        }
        if (selectedIdentity != null && !restored) {
            selectionChanged();
        }

        Set<Long> livePids = new HashSet<>();
        for (PortEntry entry : newSnapshot.entries()) {
            livePids.add(entry.pid());
        }
        detailCache.keySet().retainAll(livePids);
        switch (newSnapshot.limitation()) {
            case MISSING_PROCESS_INFO:
                showHint(I18n.get("tool.portprocess.hint.missingProcess"));
                break;
            case OWN_PROCESSES_ONLY:
                showHint(I18n.get("tool.portprocess.hint.ownProcesses"));
                break;
            default:
                showHint(null);
                break;
        }
        updateLoadedStatus();
        if (notice != null) {
            setStatus(notice, noticeColor);
            notice = null;
        }
    }

    private void updateLoadedStatus() {
        if (snapshot == null) {
            return;
        }
        String source = String.join(" ", PortProcessProbe.commandFor(snapshot.source(), listenOnlyCheck.isSelected()));
        if (snapshot.source() == PortSnapshot.Source.WINDOWS_NETSTAT) {
            source = source + " + tasklist";
        }
        setStatus(I18n.get("tool.portprocess.status.loaded", String.valueOf(snapshot.entries().size()),
                String.valueOf(table.getRowCount()), source, TIME_FORMAT.format(snapshot.takenAt())),
                Tokens.mutedForeground());
    }

    private void showHint(String text) {
        hintLabel.setText(text == null ? "" : text);
        hintLabel.setVisible(text != null);
    }

    private String describeProbeFailure(Throwable cause) {
        if (cause instanceof PortProcessProbe.ProbeException) {
            PortProcessProbe.ProbeException failure = (PortProcessProbe.ProbeException) cause;
            switch (failure.code()) {
                case NO_TOOL:
                    return I18n.get("tool.portprocess.error.probe.noTool", failure.detail());
                case TIMEOUT:
                    return I18n.get("tool.portprocess.error.probe.timeout", failure.detail());
                default:
                    return I18n.get("tool.portprocess.error.probe.failed", failure.detail());
            }
        }
        return I18n.get("tool.portprocess.error.unexpected", Errors.describeRoot(cause));
    }

    // ==========================================
    // 过滤
    // ==========================================
    private void find() {
        try {
            portQuery = PortQuery.parse(portField.getText());
        } catch (PortQuery.InvalidException error) {
            setStatus(describeQueryError(error), Tokens.danger());
            return;
        }
        applyFilter();
        refresh(true);
    }

    private String describeQueryError(PortQuery.InvalidException error) {
        switch (error.error()) {
            case OUT_OF_RANGE:
                return I18n.get("tool.portprocess.error.query.outOfRange", error.fragment());
            case REVERSED_RANGE:
                return I18n.get("tool.portprocess.error.query.reversed", error.fragment());
            default:
                return I18n.get("tool.portprocess.error.query.notNumber", error.fragment());
        }
    }

    private void applyFilter() {
        if (sorter == null) {
            return;
        }
        PortQuery query = portQuery;
        String needle = filterField.getText().trim().toLowerCase(Locale.ROOT);
        boolean tcp = tcpCheck.isSelected();
        boolean udp = udpCheck.isSelected();
        sorter.setRowFilter(new RowFilter<PortTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends PortTableModel, ? extends Integer> entry) {
                PortEntry row = model.rowAt(entry.getIdentifier());
                if (row.protocol() == PortEntry.Protocol.TCP ? !tcp : !udp) {
                    return false;
                }
                if (!query.matches(row.localPort())) {
                    return false;
                }
                return needle.isEmpty() || row.searchText().contains(needle);
            }
        });
        updateLoadedStatus();
    }

    private boolean reselect(String identity) {
        for (int view = 0; view < table.getRowCount(); view++) {
            PortEntry entry = model.rowAt(table.convertRowIndexToModel(view));
            if (entry.identity().equals(identity)) {
                table.setRowSelectionInterval(view, view);
                return true;
            }
        }
        return false;
    }

    // ==========================================
    // 详情
    // ==========================================
    private Optional<PortEntry> selectedEntry() {
        if (table == null) {
            return Optional.empty();
        }
        int view = table.getSelectedRow();
        if (view < 0) {
            return Optional.empty();
        }
        int row = table.convertRowIndexToModel(view);
        return row >= 0 && row < model.getRowCount() ? Optional.of(model.rowAt(row)) : Optional.empty();
    }

    private void selectionChanged() {
        updateActions();
        Optional<PortEntry> selected = selectedEntry();
        if (selected.isEmpty()) {
            detailGeneration.incrementAndGet();
            cancel(detailWorker);
            detailArea.setText(I18n.get("tool.portprocess.detail.empty"));
            return;
        }
        PortEntry entry = selected.get();
        ProcessDetails cached = detailCache.get(entry.pid());
        renderDetails(entry, cached, cached == null && entry.hasPid());
        if (cached == null && entry.hasPid()) {
            loadDetails(entry);
        }
    }

    private void loadDetails(PortEntry entry) {
        long ticket = detailGeneration.incrementAndGet();
        cancel(detailWorker);
        long pid = entry.pid();
        // 两段式：ProcessHandle 的信息毫秒级即可显示，命令行（Windows 上要冷启动 PowerShell）随后补上。
        SwingWorker<ProcessDetails, ProcessDetails> worker = new SwingWorker<>() {
            @Override
            protected ProcessDetails doInBackground() throws Exception {
                ProcessDetails basic = probe.basicDetails(pid);
                if (!basic.alive()) {
                    return basic;
                }
                publish(basic);
                return probe.withCommandLine(basic);
            }

            @Override
            protected void process(List<ProcessDetails> chunks) {
                if (isCancelled() || ticket != detailGeneration.get() || chunks.isEmpty()) {
                    return;
                }
                Optional<PortEntry> current = selectedEntry();
                if (current.isPresent() && current.get().pid() == pid) {
                    renderDetails(current.get(), chunks.get(chunks.size() - 1), true);
                }
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != detailGeneration.get()) {
                    return;
                }
                try {
                    ProcessDetails details = get();
                    if (details.alive()) {
                        detailCache.put(pid, details);
                    }
                    Optional<PortEntry> current = selectedEntry();
                    if (current.isPresent() && current.get().pid() == pid) {
                        renderDetails(current.get(), details, false);
                    }
                } catch (ExecutionException error) {
                    renderDetails(entry, null, false);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        detailWorker.set(worker);
        worker.execute();
    }

    /** @param loading 仍在查询：details 为 null 时整体在查，非 null 时只剩命令行在查 */
    private void renderDetails(PortEntry entry, ProcessDetails details, boolean loading) {
        StringBuilder text = new StringBuilder();
        line(text, "tool.portprocess.detail.protocol", entry.protocol().name());
        line(text, "tool.portprocess.detail.local", entry.localEndpoint());
        line(text, "tool.portprocess.detail.remote", entry.remoteEndpoint());
        line(text, "tool.portprocess.detail.state", entry.state());
        line(text, "tool.portprocess.detail.pid", entry.hasPid() ? String.valueOf(entry.pid()) : "-");
        line(text, "tool.portprocess.detail.process", entry.processName());
        text.append('\n');
        if (!entry.hasPid()) {
            text.append(I18n.get("tool.portprocess.error.noPid")).append('\n');
        } else if (loading && details == null) {
            text.append(I18n.get("tool.portprocess.detail.loading")).append('\n');
        } else if (details == null) {
            text.append(I18n.get("tool.portprocess.detail.unavailable")).append('\n');
        } else if (!details.alive()) {
            text.append(I18n.get("tool.portprocess.detail.exited")).append('\n');
        } else {
            String unavailable = I18n.get("tool.portprocess.detail.unavailable");
            line(text, "tool.portprocess.detail.executable", orElse(details.executable(), unavailable));
            line(text, "tool.portprocess.detail.commandLine", loading
                    ? I18n.get("tool.portprocess.detail.loading") : orElse(details.commandLine(), unavailable));
            line(text, "tool.portprocess.detail.user", details.user());
            line(text, "tool.portprocess.detail.started",
                    details.startTime() == null ? "" : DATE_TIME_FORMAT.format(details.startTime()));
            line(text, "tool.portprocess.detail.cpu", formatCpu(details.cpuTime()));
            line(text, "tool.portprocess.detail.parent",
                    details.parentPid() >= 0 ? String.valueOf(details.parentPid()) : "");
        }
        detailArea.setText(text.toString());
        detailArea.setCaretPosition(0);
    }

    private static void line(StringBuilder text, String key, String value) {
        text.append(I18n.get(key)).append(": ").append(value == null || value.isEmpty() ? "-" : value).append('\n');
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String formatCpu(Duration cpu) {
        if (cpu == null) {
            return "";
        }
        long millis = cpu.toMillis();
        return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", millis / 3_600_000, (millis / 60_000) % 60,
                (millis / 1000) % 60, millis % 1000);
    }

    private void copySelected(boolean pidOnly) {
        Optional<PortEntry> selected = selectedEntry();
        if (selected.isEmpty()) {
            setStatus(I18n.get("tool.portprocess.status.selectRow"), Tokens.warning());
            return;
        }
        PortEntry entry = selected.get();
        if (pidOnly) {
            if (!entry.hasPid()) {
                setStatus(I18n.get("tool.portprocess.error.noPid"), Tokens.warning());
                return;
            }
            UIUtils.copyToClipboard(String.valueOf(entry.pid()));
        } else {
            UIUtils.copyToClipboard(String.join("\t", entry.protocol().name(), entry.localEndpoint(),
                    entry.remoteEndpoint(), entry.state(), entry.hasPid() ? String.valueOf(entry.pid()) : "",
                    entry.processName()));
        }
        setStatus(I18n.get("tool.portprocess.status.copied"), Tokens.success());
    }

    private void updateActions() {
        boolean hasPid = selectedEntry().map(PortEntry::hasPid).orElse(false);
        killBtn.setEnabled(hasPid && !killing);
        killTreeBtn.setEnabled(hasPid && !killing);
    }

    // ==========================================
    // 结束进程
    // ==========================================
    private void requestKill(boolean tree) {
        Optional<PortEntry> selected = selectedEntry();
        if (selected.isEmpty()) {
            setStatus(I18n.get("tool.portprocess.status.selectRow"), Tokens.warning());
            return;
        }
        PortEntry entry = selected.get();
        if (!entry.hasPid()) {
            UIUtils.warn(root, I18n.get("tool.portprocess.error.noPid"), getLabel());
            return;
        }
        Optional<ProcessKillPolicy.Refusal> refusal = probe.checkKillable(entry.pid());
        if (refusal.isPresent()) {
            UIUtils.error(root, describeRefusal(refusal.get(), entry.pid()), getLabel());
            return;
        }
        String name = displayName(entry);
        String message = I18n.get(tree ? "tool.portprocess.confirm.killTree" : "tool.portprocess.confirm.kill",
                name, String.valueOf(entry.pid()), portsOf(entry.pid()));
        if (!UIUtils.confirm(root, message, I18n.get("tool.portprocess.confirm.title"))) {
            return;
        }
        runKill(entry, false, tree);
    }

    private void runKill(PortEntry entry, boolean force, boolean tree) {
        long ticket = killGeneration.incrementAndGet();
        cancel(killWorker);
        long pid = entry.pid();
        String name = displayName(entry);
        killing = true;
        updateActions();
        setStatus(I18n.get("tool.portprocess.status.killing", String.valueOf(pid)), Tokens.mutedForeground());
        SwingWorker<KillResult, Void> worker = new SwingWorker<>() {
            @Override
            protected KillResult doInBackground() throws Exception {
                return probe.kill(pid, force, tree);
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != killGeneration.get()) {
                    return;
                }
                killing = false;
                updateActions();
                try {
                    handleKillResult(entry, name, get(), tree);
                } catch (ExecutionException error) {
                    setStatus(I18n.get("tool.portprocess.status.killFailed"), Tokens.danger());
                    UIUtils.error(root, I18n.get("tool.portprocess.error.unexpected",
                            Errors.describeRoot(error)), getLabel());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        killWorker.set(worker);
        worker.execute();
    }

    private void handleKillResult(PortEntry entry, String name, KillResult result, boolean tree) {
        String pid = String.valueOf(result.pid());
        switch (result.outcome()) {
            case EXITED:
                detailCache.remove(result.pid());
                notice(I18n.get("tool.portprocess.status.killed", name, pid), Tokens.success());
                refresh(false);
                break;
            case NOT_FOUND:
                detailCache.remove(result.pid());
                notice(I18n.get("tool.portprocess.status.alreadyGone", pid), Tokens.mutedForeground());
                refresh(false);
                break;
            case STILL_RUNNING:
                setStatus(I18n.get("tool.portprocess.status.stillRunning", pid), Tokens.warning());
                if (!result.forced()) {
                    if (UIUtils.confirm(root, I18n.get("tool.portprocess.confirm.force", name, pid),
                            I18n.get("tool.portprocess.confirm.title"))) {
                        runKill(entry, true, tree);
                    }
                } else {
                    UIUtils.error(root, withDetail(I18n.get("tool.portprocess.dialog.stillRunningForced", name, pid),
                            result.detail()), getLabel());
                }
                break;
            case ACCESS_DENIED:
                setStatus(I18n.get("tool.portprocess.status.killFailed"), Tokens.danger());
                UIUtils.error(root, withDetail(I18n.get("tool.portprocess.dialog.accessDenied", name, pid),
                        result.detail()), getLabel());
                break;
            case REFUSED:
                setStatus(I18n.get("tool.portprocess.status.killFailed"), Tokens.danger());
                UIUtils.error(root, describeRefusal(result.refusal(), result.pid()), getLabel());
                break;
            default:
                setStatus(I18n.get("tool.portprocess.status.killFailed"), Tokens.danger());
                UIUtils.error(root, withDetail(I18n.get("tool.portprocess.dialog.killFailed", name, pid),
                        result.detail()), getLabel());
                break;
        }
    }

    private void notice(String text, Color color) {
        notice = text;
        noticeColor = color;
        setStatus(text, color);
    }

    private static String withDetail(String message, String detail) {
        if (detail == null || detail.isBlank()) {
            return message;
        }
        return message + "\n\n" + I18n.get("tool.portprocess.dialog.detail", detail.trim());
    }

    private static String describeRefusal(ProcessKillPolicy.Refusal refusal, long pid) {
        String value = String.valueOf(pid);
        switch (refusal) {
            case SYSTEM_PROCESS:
                return I18n.get("tool.portprocess.refusal.system", value);
            case SELF:
                return I18n.get("tool.portprocess.refusal.self", value);
            default:
                return I18n.get("tool.portprocess.refusal.invalidPid", value);
        }
    }

    private static String displayName(PortEntry entry) {
        return entry.processName().isEmpty() ? "-" : entry.processName();
    }

    /** 确认框里列出该进程占用的全部本地端口，让用户看清「结束它会断掉哪些服务」。 */
    private String portsOf(long pid) {
        Set<String> ports = new LinkedHashSet<>();
        if (snapshot != null) {
            for (PortEntry entry : snapshot.entries()) {
                if (entry.pid() == pid && entry.localPort() >= 0) {
                    ports.add(entry.protocol() + " " + entry.localPort());
                }
            }
        }
        return ports.isEmpty() ? "-" : String.join(", ", limit(new ArrayList<>(ports), 12));
    }

    private static List<String> limit(List<String> items, int max) {
        if (items.size() <= max) {
            return items;
        }
        List<String> head = new ArrayList<>(items.subList(0, max));
        head.add("...");
        return head;
    }

    // ==========================================
    // 端口是否空闲
    // ==========================================
    private void checkPortFree() {
        OptionalInt port;
        try {
            port = PortQuery.parse(portField.getText()).singlePort();
        } catch (PortQuery.InvalidException error) {
            setFree(describeQueryError(error), Tokens.danger());
            return;
        }
        if (port.isEmpty() || port.getAsInt() < 1) {
            setFree(I18n.get("tool.portprocess.error.singlePort"), Tokens.warning());
            return;
        }
        int value = port.getAsInt();
        String host = hostField.getText().trim();
        long ticket = checkGeneration.incrementAndGet();
        cancel(checkWorker);
        checkFreeBtn.setEnabled(false);
        setFree(I18n.get("tool.portprocess.status.checking", String.valueOf(value)), Tokens.mutedForeground());
        SwingWorker<PortCheck, Void> worker = new SwingWorker<>() {
            @Override
            protected PortCheck doInBackground() {
                return probe.checkPort(value, host);
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != checkGeneration.get()) {
                    return;
                }
                checkFreeBtn.setEnabled(true);
                try {
                    showPortCheck(get());
                } catch (ExecutionException error) {
                    setFree(I18n.get("tool.portprocess.error.unexpected", Errors.describeRoot(error)), Tokens.danger());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        checkWorker.set(worker);
        worker.execute();
    }

    private void showPortCheck(PortCheck check) {
        String port = String.valueOf(check.port());
        switch (check.status()) {
            case FREE:
                setFree(I18n.get("tool.portprocess.free.free", port, check.host()), Tokens.success());
                break;
            case IN_USE: {
                String owners = ownersOf(check.port());
                setFree(owners.isEmpty()
                                ? I18n.get("tool.portprocess.free.inUse", port, check.host())
                                : I18n.get("tool.portprocess.free.inUseBy", port, owners),
                        Tokens.danger());
                break;
            }
            case DENIED:
                setFree(I18n.get("tool.portprocess.free.denied", port), Tokens.warning());
                break;
            default:
                setFree(I18n.get("tool.portprocess.free.invalidHost", port, check.host()), Tokens.danger());
                break;
        }
        freeLabel.setToolTipText(check.detail().isEmpty() ? null : check.detail());
    }

    /** 用当前快照找出监听该端口的进程，省得用户再去表里翻。 */
    private String ownersOf(int port) {
        Set<String> owners = new LinkedHashSet<>();
        if (snapshot != null) {
            for (PortEntry entry : snapshot.entries()) {
                if (entry.localPort() == port && entry.protocol() == PortEntry.Protocol.TCP && entry.isListening()
                        && entry.hasPid()) {
                    owners.add(displayName(entry) + " (" + entry.pid() + ")");
                }
            }
        }
        return String.join(", ", owners);
    }

    private void setFree(String text, Color color) {
        freeLabel.setText(text);
        freeLabel.setForeground(color);
    }

    // ==========================================
    // 公共
    // ==========================================
    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void cancel(AtomicReference<SwingWorker<?, ?>> slot) {
        SwingWorker<?, ?> previous = slot.getAndSet(null);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
    }

    /** 包内可见：供测试检查表格内容。 */
    JTable table() {
        return table;
    }

    JTextField filterField() {
        return filterField;
    }

    JCheckBox udpCheck() {
        return udpCheck;
    }

    @Override
    public void closeResources() {
        if (autoTimer != null) {
            autoTimer.stop();
        }
        refreshGeneration.incrementAndGet();
        detailGeneration.incrementAndGet();
        killGeneration.incrementAndGet();
        checkGeneration.incrementAndGet();
        // 中断后台线程会让 PortProcessProbe 强杀正在执行的 netstat / powershell。
        cancel(refreshWorker);
        cancel(detailWorker);
        cancel(killWorker);
        cancel(checkWorker);
        refreshing = false;
        killing = false;
    }

    // ==========================================
    // 表格模型
    // ==========================================

    /** 端口、PID 列用数值类型，排序器才会按数值而不是字符串排序（否则 10000 排在 9 前面）。 */
    static final class PortTableModel extends AbstractTableModel {
        static final int COL_PROTOCOL = 0;
        static final int COL_LOCAL = 1;
        static final int COL_PORT = 2;
        static final int COL_REMOTE = 3;
        static final int COL_STATE = 4;
        static final int COL_PID = 5;
        static final int COL_PROCESS = 6;

        private static final String[] COLUMN_KEYS = {
                "tool.portprocess.column.protocol", "tool.portprocess.column.localAddress",
                "tool.portprocess.column.port", "tool.portprocess.column.remote",
                "tool.portprocess.column.state", "tool.portprocess.column.pid",
                "tool.portprocess.column.process"};

        private List<PortEntry> rows = List.of();

        void setRows(List<PortEntry> newRows) {
            rows = List.copyOf(newRows);
            fireTableDataChanged();
        }

        PortEntry rowAt(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_KEYS.length;
        }

        @Override
        public String getColumnName(int column) {
            return I18n.get(COLUMN_KEYS[column]);
        }

        @Override
        public Class<?> getColumnClass(int column) {
            switch (column) {
                case COL_PORT:
                    return Integer.class;
                case COL_PID:
                    return Long.class;
                default:
                    return String.class;
            }
        }

        @Override
        public Object getValueAt(int row, int column) {
            PortEntry entry = rows.get(row);
            switch (column) {
                case COL_PROTOCOL:
                    return entry.protocol().name();
                case COL_LOCAL:
                    return entry.localAddress();
                case COL_PORT:
                    return entry.localPort() >= 0 ? entry.localPort() : null;
                case COL_REMOTE:
                    return entry.remoteEndpoint();
                case COL_STATE:
                    return entry.state();
                case COL_PID:
                    return entry.hasPid() ? entry.pid() : null;
                default:
                    return entry.processName();
            }
        }
    }

    /** 数值列缺值时显示占位符，而不是空白格——空白容易被误读成「还没加载完」。 */
    private static final class MissingValueRenderer extends DefaultTableCellRenderer {
        private final String placeholder;

        MissingValueRenderer(String placeholder) {
            this.placeholder = placeholder;
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            setText(value == null ? placeholder : String.valueOf(value));
        }
    }
}
