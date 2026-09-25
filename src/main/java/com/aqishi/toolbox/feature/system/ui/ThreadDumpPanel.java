package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.system.domain.ThreadDump;
import com.aqishi.toolbox.feature.system.domain.ThreadDumpAnalyzer;
import com.aqishi.toolbox.feature.system.domain.ThreadDumpParser;
import com.aqishi.toolbox.feature.system.domain.ThreadInfo;
import com.aqishi.toolbox.feature.system.domain.ThreadState;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
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
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Java 线程转储分析面板。
 *
 * <p>解析与分析都在后台线程完成；每次分析领一个递增的序号，结果回到 EDT 时序号已过期就丢弃——
 * 用户连点「分析」或在读文件期间改了输入，界面上只会出现最后一次的结果。</p>
 */
public class ThreadDumpPanel extends ToolPanel implements ManagedResourceOwner {

    /** 输入框里最多放多少字符：几十 MB 的文本塞进 JTextArea 会让 EDT 卡上好几秒。 */
    private static final int PREVIEW_CHARS = 2_000_000;
    /** 复制报告时每一节最多列出的条目数。 */
    private static final int REPORT_LIMIT = 20;
    /** 报告里同栈分组展示的栈帧数。 */
    private static final int REPORT_GROUP_FRAMES = 8;

    private static final ThreadState[] STATE_ORDER = {
            ThreadState.RUNNABLE, ThreadState.BLOCKED, ThreadState.WAITING, ThreadState.TIMED_WAITING,
            ThreadState.NEW, ThreadState.TERMINATED, ThreadState.NONE};

    private static final int TAB_THREADS = 0;
    private static final int TAB_DEADLOCKS = 1;
    private static final int TAB_ACROSS = 6;

    private final ThreadDumpParser parser;
    private final ThreadDumpAnalyzer analyzer;

    private JTextArea inputArea;
    private JLabel statusLabel;
    /** 读入的完整文件文本；输入框只放了预览时由它提供分析内容，用户一编辑就作废。 */
    private String loadedText;
    private boolean settingInput;
    private File lastDirectory;

    private JPanel summaryRow;
    private JComboBox<String> dumpCombo;
    private boolean populatingDumps;
    private JSpinner depthSpinner;
    private JTabbedPane tabs;

    private RowsModel<ThreadInfo> threadModel;
    private JTable threadTable;
    private TableRowSorter<RowsModel<ThreadInfo>> threadSorter;
    private JComboBox<String> stateFilter;
    private JTextField textFilter;
    private JTextArea threadDetail;
    private List<String> threadSearchIndex = List.of();

    private JTextArea deadlockArea;

    private RowsModel<ThreadDumpAnalyzer.LockContention> lockModel;
    private JTable lockTable;
    private JTextArea lockDetail;

    private RowsModel<ThreadDumpAnalyzer.StackGroup> groupModel;
    private JTable groupTable;
    private JTextArea groupDetail;

    private RowsModel<ThreadDumpAnalyzer.PoolGroup> poolModel;

    private RowsModel<ThreadDumpAnalyzer.HotMethod> hotModel;
    private JTable hotTable;
    private JTextArea hotDetail;

    private RowsModel<ThreadDumpAnalyzer.ThreadTimeline> acrossModel;
    private JTable acrossTable;
    private JTextArea acrossDetail;

    private ThreadDumpAnalyzer.Report report;
    /** 上次成功解析的输入与结果：只改「同栈比较帧数」时无需重新解析几十 MB 文本。 */
    private String parsedText;
    private List<ThreadDump> parsedDumps;

    private final AtomicLong analysisGeneration = new AtomicLong();
    private final AtomicLong loadGeneration = new AtomicLong();
    private final AtomicReference<SwingWorker<?, ?>> analysisWorker = new AtomicReference<>();
    private final AtomicReference<SwingWorker<?, ?>> loadWorker = new AtomicReference<>();

    public ThreadDumpPanel() {
        this(ToolCatalog.THREAD_DUMP, new ThreadDumpParser(), new ThreadDumpAnalyzer());
    }

    public ThreadDumpPanel(ToolDescriptor descriptor, ThreadDumpParser parser, ThreadDumpAnalyzer analyzer) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.parser = Objects.requireNonNull(parser, "parser");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        root.add(Layouts.splitVertical(buildInputCard(), buildResultCard(), 0.3, 0.3), BorderLayout.CENTER);
        return root;
    }

    // ==========================================
    // 输入
    // ==========================================
    private Card buildInputCard() {
        inputArea = Fields.area(8, 40);
        // 转储行很长，自动换行既难读又会让大文本的布局计算变慢。
        inputArea.setLineWrap(false);
        inputArea.putClientProperty("JTextField.placeholderText", I18n.get("tool.threaddump.placeholder.input"));
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                inputEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                inputEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                inputEdited();
            }
        });
        inputArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                "threaddump.analyze");
        inputArea.getActionMap().put("threaddump.analyze", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                runAnalysis();
            }
        });

        Card card = Card.titled(I18n.get("tool.threaddump.card.input"),
                I18n.get("tool.threaddump.card.input.subtitle"));
        card.setContent(Fields.scrollBoxed(inputArea));

        JButton openBtn = Buttons.secondary(I18n.get("tool.threaddump.btn.open"));
        JButton clearBtn = Buttons.ghost(I18n.get("tool.threaddump.btn.clear"));
        JButton analyzeBtn = Buttons.primary(I18n.get("tool.threaddump.btn.analyze"));
        card.addHeaderAction(openBtn);
        card.addHeaderAction(clearBtn);
        card.addHeaderAction(analyzeBtn);

        statusLabel = Fields.caption(I18n.get("tool.threaddump.status.ready"));
        card.setFooter(statusLabel);

        openBtn.addActionListener(event -> openFile(card));
        clearBtn.addActionListener(event -> clearAll());
        analyzeBtn.addActionListener(event -> runAnalysis());
        return card;
    }

    private void inputEdited() {
        if (!settingInput) {
            loadedText = null;
        }
    }

    private void setInput(String text) {
        settingInput = true;
        try {
            inputArea.setText(text);
            inputArea.setCaretPosition(0);
        } finally {
            settingInput = false;
        }
    }

    private String currentInput() {
        return loadedText != null ? loadedText : inputArea.getText();
    }

    private void openFile(Component parent) {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(I18n.get("tool.threaddump.btn.open"));
        if (chooser.showOpenDialog(SwingUtilities.getWindowAncestor(parent)) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        lastDirectory = file.getParentFile();
        long ticket = loadGeneration.incrementAndGet();
        cancel(loadWorker);
        setStatus(I18n.get("tool.threaddump.status.loading", file.getName()), Tokens.mutedForeground());

        SwingWorker<ThreadDumpParser.Decoded, Void> worker = new SwingWorker<>() {
            private long size;

            @Override
            protected ThreadDumpParser.Decoded doInBackground() throws IOException {
                byte[] bytes;
                // 多读一个字节即可判断是否超限，不必相信可能随时变化的 File.length()。
                try (InputStream in = Files.newInputStream(file.toPath())) {
                    bytes = in.readNBytes((int) ThreadDumpParser.MAX_INPUT_BYTES + 1);
                }
                if (bytes.length > ThreadDumpParser.MAX_INPUT_BYTES) {
                    throw new FileTooLargeException();
                }
                size = bytes.length;
                return ThreadDumpParser.decode(bytes);
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != loadGeneration.get()) {
                    return;
                }
                try {
                    ThreadDumpParser.Decoded decoded = get();
                    String text = decoded.text();
                    String charset = decoded.charset().displayName(Locale.ROOT);
                    if (text.length() > PREVIEW_CHARS) {
                        setInput(text.substring(0, PREVIEW_CHARS));
                        loadedText = text;
                        setStatus(I18n.get("tool.threaddump.status.preview", file.getName(), charset,
                                formatSize(size), PREVIEW_CHARS), Tokens.warning());
                    } else {
                        setInput(text);
                        loadedText = null;
                        setStatus(I18n.get("tool.threaddump.status.loaded", file.getName(), charset,
                                formatSize(size)), Tokens.mutedForeground());
                    }
                    runAnalysis();
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof FileTooLargeException) {
                        setStatus(I18n.get("tool.threaddump.error.tooLarge",
                                String.valueOf(ThreadDumpParser.MAX_INPUT_BYTES / (1024 * 1024))), Tokens.danger());
                    } else {
                        setStatus(I18n.get("tool.threaddump.error.read", Errors.describeRoot(error)), Tokens.danger());
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        loadWorker.set(worker);
        worker.execute();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void clearAll() {
        loadGeneration.incrementAndGet();
        analysisGeneration.incrementAndGet();
        cancel(loadWorker);
        cancel(analysisWorker);
        setInput("");
        loadedText = null;
        parsedText = null;
        parsedDumps = null;
        applyReport(null);
        setStatus(I18n.get("tool.threaddump.status.ready"), Tokens.mutedForeground());
    }

    // ==========================================
    // 分析
    // ==========================================
    private void runAnalysis() {
        String text = currentInput();
        if (text == null || text.isBlank()) {
            setStatus(I18n.get("tool.threaddump.status.empty"), Tokens.warning());
            return;
        }
        int depth = ((Number) depthSpinner.getValue()).intValue();
        long ticket = analysisGeneration.incrementAndGet();
        cancel(analysisWorker);
        List<ThreadDump> cached = text.equals(parsedText) ? parsedDumps : null;
        setStatus(I18n.get("tool.threaddump.status.analyzing"), Tokens.mutedForeground());

        SwingWorker<ThreadDumpAnalyzer.Report, Void> worker = new SwingWorker<>() {
            private List<ThreadDump> dumps;
            private long elapsed;

            @Override
            protected ThreadDumpAnalyzer.Report doInBackground() {
                long start = System.nanoTime();
                dumps = cached != null ? cached : parser.parse(text);
                ThreadDumpAnalyzer.Report result = analyzer.analyze(dumps, depth);
                elapsed = (System.nanoTime() - start) / 1_000_000L;
                return result;
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != analysisGeneration.get()) {
                    return;
                }
                try {
                    ThreadDumpAnalyzer.Report result = get();
                    parsedText = text;
                    parsedDumps = dumps;
                    if (result.dumps().isEmpty()) {
                        applyReport(null);
                        setStatus(I18n.get("tool.threaddump.status.noThreads"), Tokens.warning());
                        return;
                    }
                    applyReport(result);
                    setStatus(I18n.get("tool.threaddump.status.done", result.dumps().size(),
                            String.valueOf(elapsed)), Tokens.mutedForeground());
                } catch (ExecutionException error) {
                    applyReport(null);
                    setStatus(I18n.get("tool.threaddump.error.analyze", Errors.describeRoot(error)), Tokens.danger());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        analysisWorker.set(worker);
        worker.execute();
    }

    private void cancel(AtomicReference<SwingWorker<?, ?>> slot) {
        SwingWorker<?, ?> previous = slot.getAndSet(null);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    // ==========================================
    // 结果区
    // ==========================================
    private Card buildResultCard() {
        summaryRow = Layouts.wrapRow();
        dumpCombo = Fields.combo(new String[0]);
        dumpCombo.setEnabled(false);
        dumpCombo.addActionListener(event -> {
            if (!populatingDumps) {
                showDump(dumpCombo.getSelectedIndex());
            }
        });
        depthSpinner = Fields.spinner(ThreadDumpAnalyzer.ALL_FRAMES, 0, 500, 1);
        depthSpinner.setToolTipText(I18n.get("tool.threaddump.label.depth.tip"));
        depthSpinner.addChangeListener(event -> {
            if (report != null) {
                runAnalysis();
            }
        });
        JLabel depthLabel = Fields.label(I18n.get("tool.threaddump.label.depth"));
        depthLabel.setToolTipText(I18n.get("tool.threaddump.label.depth.tip"));

        JPanel controls = Layouts.wrapRow(Fields.label(I18n.get("tool.threaddump.label.dump")), dumpCombo,
                depthLabel, depthSpinner);

        tabs = new JTabbedPane();
        tabs.addTab(I18n.get("tool.threaddump.tab.threads"), buildThreadsTab());
        tabs.addTab(I18n.get("tool.threaddump.tab.deadlocks"), buildDeadlocksTab());
        tabs.addTab(I18n.get("tool.threaddump.tab.locks"), buildLocksTab());
        tabs.addTab(I18n.get("tool.threaddump.tab.groups"), buildGroupsTab());
        tabs.addTab(I18n.get("tool.threaddump.tab.pools"), buildPoolsTab());
        tabs.addTab(I18n.get("tool.threaddump.tab.hot"), buildHotTab());
        tabs.addTab(I18n.get("tool.threaddump.tab.across"), buildAcrossTab());
        tabs.setEnabledAt(TAB_ACROSS, false);

        JPanel body = Layouts.box(0, Tokens.SPACE_SM);
        body.add(Layouts.stack(Tokens.SPACE_XS, summaryRow, controls), BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);

        Card card = Card.titled(I18n.get("tool.threaddump.card.result"));
        card.setContent(body);
        JButton copyBtn = Buttons.snug(I18n.get("tool.threaddump.btn.copyReport"));
        card.addHeaderAction(copyBtn);
        copyBtn.addActionListener(event -> {
            if (report == null) {
                setStatus(I18n.get("tool.threaddump.status.empty"), Tokens.warning());
                return;
            }
            UIUtils.copyToClipboard(buildReport(report));
            setStatus(I18n.get("tool.threaddump.status.copied"), Tokens.success());
        });
        renderSummary(null);
        return card;
    }

    private JComponent buildThreadsTab() {
        threadModel = new RowsModel<ThreadInfo>()
                .column(I18n.get("tool.threaddump.column.name"), String.class, ThreadInfo::name)
                .column(I18n.get("tool.threaddump.column.state"), ThreadState.class, ThreadInfo::state)
                .column(I18n.get("tool.threaddump.column.daemon"), Boolean.class, ThreadInfo::daemon)
                .column(I18n.get("tool.threaddump.column.topFrame"), String.class, ThreadDumpPanel::topFrameText);
        threadTable = table(threadModel, false);
        threadSorter = new TableRowSorter<>(threadModel);
        threadTable.setRowSorter(threadSorter);
        threadTable.getColumnModel().getColumn(1).setCellRenderer(new StateRenderer());
        threadTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        threadTable.getColumnModel().getColumn(1).setPreferredWidth(110);
        threadTable.getColumnModel().getColumn(2).setMaxWidth(70);
        threadTable.getColumnModel().getColumn(3).setPreferredWidth(420);

        String[] stateItems = new String[STATE_ORDER.length + 1];
        stateItems[0] = I18n.get("tool.threaddump.filter.all");
        for (int i = 0; i < STATE_ORDER.length; i++) {
            stateItems[i + 1] = stateText(STATE_ORDER[i]);
        }
        stateFilter = Fields.combo(stateItems);
        textFilter = Fields.text("", I18n.get("tool.threaddump.placeholder.filter"));
        textFilter.setColumns(22);
        stateFilter.addActionListener(event -> applyThreadFilter());
        textFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyThreadFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyThreadFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyThreadFilter();
            }
        });

        threadDetail = detailArea();
        onSelect(threadTable, threadModel, thread -> {
            threadDetail.setText(renderThread(thread));
            threadDetail.setCaretPosition(0);
        });

        JPanel filters = Layouts.wrapRow(Fields.label(I18n.get("tool.threaddump.label.state")), stateFilter,
                Fields.label(I18n.get("tool.threaddump.label.filter")), textFilter);
        JPanel panel = tabShell();
        panel.add(filters, BorderLayout.NORTH);
        panel.add(Layouts.splitHorizontal(Fields.scrollBoxed(threadTable), Fields.scrollBoxed(threadDetail),
                0.55, 0.55), BorderLayout.CENTER);
        return panel;
    }

    private void applyThreadFilter() {
        if (threadSorter == null) {
            return;
        }
        int stateIndex = stateFilter.getSelectedIndex();
        ThreadState wanted = stateIndex <= 0 ? null : STATE_ORDER[stateIndex - 1];
        String needle = textFilter.getText().trim().toLowerCase(Locale.ROOT);
        if (wanted == null && needle.isEmpty()) {
            threadSorter.setRowFilter(null);
            return;
        }
        List<String> index = threadSearchIndex;
        threadSorter.setRowFilter(new RowFilter<RowsModel<ThreadInfo>, Integer>() {
            @Override
            public boolean include(Entry<? extends RowsModel<ThreadInfo>, ? extends Integer> entry) {
                int row = entry.getIdentifier();
                ThreadInfo thread = threadModel.rowAt(row);
                if (wanted != null && thread.state() != wanted) {
                    return false;
                }
                return needle.isEmpty() || (row < index.size() && index.get(row).contains(needle));
            }
        });
    }

    private JComponent buildDeadlocksTab() {
        deadlockArea = detailArea();
        JPanel panel = tabShell();
        panel.add(Fields.scrollBoxed(deadlockArea), BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildLocksTab() {
        lockModel = new RowsModel<ThreadDumpAnalyzer.LockContention>()
                .column(I18n.get("tool.threaddump.column.lock"), String.class,
                        ThreadDumpAnalyzer.LockContention::address)
                .column(I18n.get("tool.threaddump.column.class"), String.class,
                        item -> orUnknown(item.className()))
                .column(I18n.get("tool.threaddump.column.owner"), String.class,
                        item -> item.owner() == null ? "-" : item.owner().name())
                .column(I18n.get("tool.threaddump.column.waiters"), Integer.class, item -> item.waiters().size())
                .column(I18n.get("tool.threaddump.column.waitingThreads"), String.class,
                        item -> joinNames(item.waiters(), 10));
        lockTable = table(lockModel, true);
        lockTable.getColumnModel().getColumn(3).setMaxWidth(90);
        lockDetail = detailArea();
        onSelect(lockTable, lockModel, item -> {
            lockDetail.setText(renderContention(item));
            lockDetail.setCaretPosition(0);
        });
        return tableWithDetail(lockTable, lockDetail);
    }

    private JComponent buildGroupsTab() {
        groupModel = new RowsModel<ThreadDumpAnalyzer.StackGroup>()
                .column(I18n.get("tool.threaddump.column.count"), Integer.class, group -> group.members().size())
                .column(I18n.get("tool.threaddump.column.states"), String.class, group -> statesText(group.states()))
                .column(I18n.get("tool.threaddump.column.topFrame"), String.class,
                        group -> group.frames().isEmpty() ? "-" : group.frames().get(0).text());
        groupTable = table(groupModel, true);
        groupTable.getColumnModel().getColumn(0).setMaxWidth(90);
        groupDetail = detailArea();
        onSelect(groupTable, groupModel, group -> {
            groupDetail.setText(renderGroup(group));
            groupDetail.setCaretPosition(0);
        });
        return tableWithDetail(groupTable, groupDetail);
    }

    private JComponent buildPoolsTab() {
        poolModel = new RowsModel<ThreadDumpAnalyzer.PoolGroup>()
                .column(I18n.get("tool.threaddump.column.pool"), String.class, ThreadDumpAnalyzer.PoolGroup::pattern)
                .column(I18n.get("tool.threaddump.column.count"), Integer.class, ThreadDumpAnalyzer.PoolGroup::total)
                .column(ThreadState.RUNNABLE.name(), Integer.class, pool -> pool.count(ThreadState.RUNNABLE))
                .column(ThreadState.BLOCKED.name(), Integer.class, pool -> pool.count(ThreadState.BLOCKED))
                .column(ThreadState.WAITING.name(), Integer.class, pool -> pool.count(ThreadState.WAITING))
                .column(ThreadState.TIMED_WAITING.name(), Integer.class,
                        pool -> pool.count(ThreadState.TIMED_WAITING))
                .column(I18n.get("tool.threaddump.column.other"), Integer.class,
                        pool -> pool.total() - pool.count(ThreadState.RUNNABLE) - pool.count(ThreadState.BLOCKED)
                                - pool.count(ThreadState.WAITING) - pool.count(ThreadState.TIMED_WAITING));
        JTable table = table(poolModel, true);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        JPanel panel = tabShell();
        panel.add(Fields.scrollBoxed(table), BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildHotTab() {
        hotModel = new RowsModel<ThreadDumpAnalyzer.HotMethod>()
                .column(I18n.get("tool.threaddump.column.method"), String.class, ThreadDumpAnalyzer.HotMethod::method)
                .column(I18n.get("tool.threaddump.column.count"), Integer.class, ThreadDumpAnalyzer.HotMethod::count);
        hotTable = table(hotModel, true);
        hotTable.getColumnModel().getColumn(0).setPreferredWidth(420);
        hotTable.getColumnModel().getColumn(1).setMaxWidth(90);
        hotDetail = detailArea();
        onSelect(hotTable, hotModel, hot -> {
            StringBuilder text = new StringBuilder(I18n.get("tool.threaddump.detail.hotThreads", hot.count()))
                    .append('\n');
            for (ThreadInfo thread : hot.threads()) {
                text.append("  ").append(thread.name()).append('\n');
            }
            text.append('\n');
            if (!hot.threads().isEmpty()) {
                text.append(renderThread(hot.threads().get(0)));
            }
            hotDetail.setText(text.toString());
            hotDetail.setCaretPosition(0);
        });
        return tableWithDetail(hotTable, hotDetail);
    }

    private JComponent buildAcrossTab() {
        acrossModel = new RowsModel<>();
        acrossTable = table(acrossModel, true);
        acrossDetail = detailArea();
        onSelect(acrossTable, acrossModel, timeline -> {
            acrossDetail.setText(renderTimeline(timeline));
            acrossDetail.setCaretPosition(0);
        });
        return tableWithDetail(acrossTable, acrossDetail);
    }

    private JPanel tabShell() {
        JPanel panel = Layouts.box(0, Tokens.SPACE_SM);
        panel.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        return panel;
    }

    private JComponent tableWithDetail(JTable table, JTextArea detail) {
        JPanel panel = tabShell();
        panel.add(Layouts.splitHorizontal(Fields.scrollBoxed(table), Fields.scrollBoxed(detail), 0.55, 0.55),
                BorderLayout.CENTER);
        return panel;
    }

    private static JTextArea detailArea() {
        JTextArea area = Fields.output(8, 30);
        area.setLineWrap(false);
        return area;
    }

    private static JTable table(RowsModel<?> model, boolean autoSorter) {
        JTable table = new JTable(model);
        table.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(autoSorter);
        return table;
    }

    private static <T> void onSelect(JTable table, RowsModel<T> model, Consumer<T> handler) {
        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            int view = table.getSelectedRow();
            if (view < 0) {
                return;
            }
            int row = table.convertRowIndexToModel(view);
            if (row >= 0 && row < model.getRowCount()) {
                handler.accept(model.rowAt(row));
            }
        });
    }

    // ==========================================
    // 呈现
    // ==========================================
    /** 包内可见：测试直接灌入分析结果，覆盖各页签的填充逻辑。 */
    void applyReport(ThreadDumpAnalyzer.Report newReport) {
        report = newReport;
        populatingDumps = true;
        try {
            DefaultComboBoxModel<String> items = new DefaultComboBoxModel<>();
            if (newReport != null) {
                for (ThreadDumpAnalyzer.DumpAnalysis analysis : newReport.dumps()) {
                    ThreadDump dump = analysis.dump();
                    items.addElement(I18n.get("tool.threaddump.dump.item", String.valueOf(dump.index() + 1),
                            dump.timestamp() == null ? I18n.get("tool.threaddump.dump.noTimestamp") : dump.timestamp(),
                            String.valueOf(analysis.total())));
                }
            }
            dumpCombo.setModel(items);
            dumpCombo.setEnabled(items.getSize() > 1);
        } finally {
            populatingDumps = false;
        }
        boolean multi = newReport != null && newReport.isMultiDump();
        tabs.setEnabledAt(TAB_ACROSS, multi);
        if (!multi && tabs.getSelectedIndex() == TAB_ACROSS) {
            tabs.setSelectedIndex(TAB_THREADS);
        }
        fillAcross(newReport);
        showDump(newReport == null ? -1 : 0);
    }

    private void showDump(int index) {
        ThreadDumpAnalyzer.DumpAnalysis analysis =
                report == null || index < 0 || index >= report.dumps().size() ? null : report.dumps().get(index);
        renderSummary(analysis);

        List<ThreadInfo> threads = analysis == null ? List.of() : analysis.dump().threads();
        List<String> search = new ArrayList<>(threads.size());
        for (ThreadInfo thread : threads) {
            StringBuilder text = new StringBuilder(thread.name());
            for (ThreadInfo.Frame frame : thread.frames()) {
                text.append('\n').append(frame.text());
            }
            search.add(text.toString().toLowerCase(Locale.ROOT));
        }
        threadSearchIndex = search;
        threadModel.setRows(threads);
        applyThreadFilter();
        threadDetail.setText("");

        deadlockArea.setText(analysis == null ? "" : renderDeadlocks(analysis));
        deadlockArea.setCaretPosition(0);
        int deadlocks = analysis == null ? 0 : analysis.deadlocks().size();
        tabs.setTitleAt(TAB_DEADLOCKS, deadlocks == 0 ? I18n.get("tool.threaddump.tab.deadlocks")
                : I18n.get("tool.threaddump.tab.deadlocks") + " (" + deadlocks + ")");
        tabs.setForegroundAt(TAB_DEADLOCKS, deadlocks == 0 ? null : Tokens.danger());

        lockModel.setRows(analysis == null ? List.of() : analysis.contention());
        lockDetail.setText("");
        groupModel.setRows(analysis == null ? List.of() : analysis.stackGroups());
        groupDetail.setText("");
        poolModel.setRows(analysis == null ? List.of() : analysis.pools());
        hotModel.setRows(analysis == null ? List.of() : analysis.hotMethods());
        hotDetail.setText("");
    }

    private void renderSummary(ThreadDumpAnalyzer.DumpAnalysis analysis) {
        summaryRow.removeAll();
        if (analysis == null) {
            summaryRow.add(Fields.caption(I18n.get("tool.threaddump.summary.empty")));
        } else {
            JLabel total = Fields.caption(I18n.get("tool.threaddump.summary.total", String.valueOf(analysis.total())));
            total.setFont(Tokens.fontBodyStrong());
            total.setForeground(Tokens.foreground());
            summaryRow.add(total);
            for (ThreadState state : STATE_ORDER) {
                int count = analysis.count(state);
                if (count > 0) {
                    JLabel label = Fields.caption(stateText(state) + " " + count);
                    if (state == ThreadState.BLOCKED) {
                        label.setForeground(Tokens.warning());
                    }
                    summaryRow.add(label);
                }
            }
            int deadlocks = analysis.deadlocks().size();
            JLabel deadlockLabel = Fields.caption(deadlocks > 0
                    ? I18n.get("tool.threaddump.summary.deadlocks", String.valueOf(deadlocks))
                    : I18n.get("tool.threaddump.summary.noDeadlock"));
            if (deadlocks > 0) {
                deadlockLabel.setFont(Tokens.fontBodyStrong());
                deadlockLabel.setForeground(Tokens.danger());
            } else {
                deadlockLabel.setForeground(Tokens.success());
            }
            summaryRow.add(deadlockLabel);
        }
        summaryRow.revalidate();
        summaryRow.repaint();
    }

    private void fillAcross(ThreadDumpAnalyzer.Report newReport) {
        // 列数随转储份数变化：同一个 model 换列后通知结构变化，JTable 会重建列与排序器。
        acrossModel.clearColumns();
        acrossModel.column(I18n.get("tool.threaddump.column.name"), String.class,
                ThreadDumpAnalyzer.ThreadTimeline::name);
        acrossModel.column(I18n.get("tool.threaddump.column.nid"), String.class,
                timeline -> timeline.nid() == null ? "" : timeline.nid());
        int dumps = newReport == null ? 0 : newReport.dumps().size();
        for (int i = 0; i < dumps; i++) {
            int index = i;
            acrossModel.column(I18n.get("tool.threaddump.column.dumpN", String.valueOf(i + 1)), ThreadState.class,
                    timeline -> timeline.stateAt(index));
        }
        acrossModel.column(I18n.get("tool.threaddump.column.sameStack"), Boolean.class,
                ThreadDumpAnalyzer.ThreadTimeline::sameStackInAll);
        acrossModel.column(I18n.get("tool.threaddump.column.suspicious"), Boolean.class,
                ThreadDumpAnalyzer.ThreadTimeline::suspicious);

        List<ThreadDumpAnalyzer.ThreadTimeline> rows =
                new ArrayList<>(newReport == null ? List.of() : newReport.timelines());
        // 疑似卡死的排最前，其次是栈始终不变的，余下按名字。
        rows.sort(Comparator.comparing((ThreadDumpAnalyzer.ThreadTimeline t) -> !t.suspicious())
                .thenComparing(t -> !t.sameStackInAll())
                .thenComparing(ThreadDumpAnalyzer.ThreadTimeline::name));
        acrossModel.setRows(rows, true);

        StateRenderer renderer = new StateRenderer();
        for (int i = 0; i < dumps; i++) {
            acrossTable.getColumnModel().getColumn(2 + i).setCellRenderer(renderer);
        }
        if (acrossTable.getColumnCount() > 0) {
            acrossTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        }
        acrossDetail.setText("");
    }

    // ==========================================
    // 文本渲染
    // ==========================================
    private static String topFrameText(ThreadInfo thread) {
        ThreadInfo.Frame top = thread.topFrame();
        if (top != null) {
            return top.text();
        }
        return thread.javaThread() ? "-" : I18n.get("tool.threaddump.value.vmThread");
    }

    static String stateText(ThreadState state) {
        return state == ThreadState.NONE ? I18n.get("tool.threaddump.state.none") : state.name();
    }

    private static String statesText(Map<ThreadState, Integer> states) {
        StringBuilder text = new StringBuilder();
        for (ThreadState state : STATE_ORDER) {
            Integer count = states.get(state);
            if (count != null && count > 0) {
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(stateText(state)).append(' ').append(count);
            }
        }
        return text.toString();
    }

    private static String orUnknown(String value) {
        return value == null ? I18n.get("tool.threaddump.value.unknown") : value;
    }

    private static String joinNames(List<ThreadInfo> threads, int limit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < threads.size() && i < limit; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(threads.get(i).name());
        }
        if (threads.size() > limit) {
            text.append(", ... +").append(threads.size() - limit);
        }
        return text.toString();
    }

    /** 按 jstack 的格式还原单个线程，方便直接对照原始转储。 */
    static String renderThread(ThreadInfo thread) {
        StringBuilder text = new StringBuilder();
        text.append('"').append(thread.name()).append('"');
        if (thread.number() >= 0) {
            text.append(" #").append(thread.number());
        }
        if (thread.daemon()) {
            text.append(" daemon");
        }
        if (thread.virtual()) {
            text.append(" virtual");
        }
        if (thread.priority() >= 0) {
            text.append(" prio=").append(thread.priority());
        }
        if (thread.osPriority() >= 0) {
            text.append(" os_prio=").append(thread.osPriority());
        }
        appendField(text, "cpu", thread.cpu());
        appendField(text, "elapsed", thread.elapsed());
        appendField(text, "tid", thread.tid());
        appendField(text, "nid", thread.nid());
        if (thread.headerState() != null) {
            text.append(' ').append(thread.headerState());
        }
        text.append('\n');
        if (thread.state() != ThreadState.NONE) {
            text.append("   java.lang.Thread.State: ").append(thread.state().name());
            if (thread.stateDetail() != null) {
                text.append(" (").append(thread.stateDetail()).append(')');
            }
            text.append('\n');
        }
        if (!thread.javaThread()) {
            text.append("   ").append(I18n.get("tool.threaddump.value.vmThread")).append('\n');
        }
        for (ThreadInfo.Frame frame : thread.frames()) {
            text.append("\tat ").append(frame.text()).append('\n');
            for (ThreadInfo.Lock lock : frame.locks()) {
                text.append("\t- ").append(lockVerb(lock.kind())).append(' ')
                        .append(lockTarget(lock)).append('\n');
            }
        }
        if (!thread.ownableSynchronizers().isEmpty()) {
            text.append("\n   Locked ownable synchronizers:\n");
            for (ThreadInfo.Lock lock : thread.ownableSynchronizers()) {
                text.append("\t- ").append(lockTarget(lock)).append('\n');
            }
        }
        return text.toString();
    }

    private static void appendField(StringBuilder text, String name, String value) {
        if (value != null) {
            text.append(' ').append(name).append('=').append(value);
        }
    }

    private static String lockVerb(ThreadInfo.LockKind kind) {
        switch (kind) {
            case LOCKED:
                return "locked";
            case WAITING_TO_LOCK:
                return "waiting to lock";
            case WAITING_TO_RELOCK:
                return "waiting to re-lock in wait()";
            case WAITING_ON:
                return "waiting on";
            case PARKING:
                return "parking to wait for";
            case ELIMINATED:
                return "eliminated";
            default:
                return "owns";
        }
    }

    private static String lockTarget(ThreadInfo.Lock lock) {
        String address = lock.address() == null ? "?" : lock.address();
        String target = address.startsWith("0x") ? "<" + address + ">" : address;
        return lock.className() == null ? target : target + " (a " + lock.className() + ")";
    }

    private String renderDeadlocks(ThreadDumpAnalyzer.DumpAnalysis analysis) {
        if (analysis.deadlocks().isEmpty()) {
            return I18n.get("tool.threaddump.deadlock.none");
        }
        StringBuilder text = new StringBuilder();
        int number = 1;
        for (ThreadDumpAnalyzer.Deadlock deadlock : analysis.deadlocks()) {
            appendDeadlock(text, deadlock, number++);
            text.append(I18n.get("tool.threaddump.deadlock.stacks")).append('\n');
            for (String name : deadlock.threadNames()) {
                for (ThreadInfo thread : analysis.dump().threads()) {
                    if (thread.name().equals(name)) {
                        text.append(renderThread(thread)).append('\n');
                        break;
                    }
                }
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static void appendDeadlock(StringBuilder text, ThreadDumpAnalyzer.Deadlock deadlock, int number) {
        String source = deadlock.detectedFromStacks() && deadlock.reportedByJvm()
                ? I18n.get("tool.threaddump.deadlock.source.both")
                : deadlock.detectedFromStacks()
                ? I18n.get("tool.threaddump.deadlock.source.stacks")
                : I18n.get("tool.threaddump.deadlock.source.jvm");
        text.append(I18n.get("tool.threaddump.deadlock.title", String.valueOf(number)))
                .append("  [").append(source).append("]\n");
        for (ThreadDump.Link link : deadlock.links()) {
            String key = link.waitKind() == ThreadInfo.LockKind.PARKING
                    ? "tool.threaddump.deadlock.link.sync" : "tool.threaddump.deadlock.link.monitor";
            text.append("  ").append(I18n.get(key, link.threadName(),
                    link.lockAddress() == null ? "?" : link.lockAddress(),
                    orUnknown(link.lockClass()),
                    link.ownerName() == null ? "?" : link.ownerName())).append('\n');
        }
    }

    private static String renderContention(ThreadDumpAnalyzer.LockContention item) {
        StringBuilder text = new StringBuilder();
        text.append(item.address()).append("  (").append(orUnknown(item.className())).append(")\n\n");
        text.append(I18n.get("tool.threaddump.detail.waiters", String.valueOf(item.waiters().size()))).append('\n');
        for (ThreadInfo waiter : item.waiters()) {
            text.append("  ").append(waiter.name()).append("  [").append(stateText(waiter.state())).append("]\n");
        }
        text.append('\n').append(I18n.get("tool.threaddump.detail.owner")).append('\n');
        if (item.owner() == null) {
            text.append("  ").append(I18n.get("tool.threaddump.detail.noOwner")).append('\n');
        } else {
            text.append(renderThread(item.owner()));
        }
        return text.toString();
    }

    private static String renderGroup(ThreadDumpAnalyzer.StackGroup group) {
        StringBuilder text = new StringBuilder();
        text.append(I18n.get("tool.threaddump.detail.sharedStack", String.valueOf(group.frames().size())))
                .append('\n');
        for (ThreadInfo.Frame frame : group.frames()) {
            text.append("\tat ").append(frame.text()).append('\n');
        }
        text.append('\n').append(I18n.get("tool.threaddump.detail.members", String.valueOf(group.members().size())))
                .append('\n');
        for (ThreadInfo member : group.members()) {
            text.append("  ").append(member.name()).append("  [").append(stateText(member.state())).append("]\n");
        }
        return text.toString();
    }

    private String renderTimeline(ThreadDumpAnalyzer.ThreadTimeline timeline) {
        StringBuilder text = new StringBuilder();
        text.append('"').append(timeline.name()).append('"');
        if (timeline.nid() != null) {
            text.append("  nid=").append(timeline.nid());
        }
        text.append("\n\n").append(I18n.get("tool.threaddump.detail.timeline")).append('\n');
        for (int i = 0; i < timeline.perDump().size(); i++) {
            ThreadState state = timeline.stateAt(i);
            String when = report == null ? null : report.dumps().get(i).dump().timestamp();
            text.append("  #").append(i + 1);
            if (when != null) {
                text.append("  ").append(when);
            }
            text.append("  ").append(state == null ? "-" : stateText(state)).append('\n');
        }
        ThreadInfo latest = timeline.latest();
        if (latest != null) {
            text.append('\n').append(I18n.get("tool.threaddump.detail.latestStack")).append('\n')
                    .append(renderThread(latest));
        }
        return text.toString();
    }

    /** 纯文本报告：贴进工单或聊天时不依赖界面也能读懂。 */
    String buildReport(ThreadDumpAnalyzer.Report source) {
        StringBuilder text = new StringBuilder();
        text.append(I18n.get("tool.threaddump.report.title")).append('\n');
        text.append("==========================================\n");
        for (ThreadDumpAnalyzer.DumpAnalysis analysis : source.dumps()) {
            ThreadDump dump = analysis.dump();
            text.append('\n').append(I18n.get("tool.threaddump.report.dump", String.valueOf(dump.index() + 1),
                    dump.timestamp() == null ? "" : dump.timestamp(),
                    dump.vmInfo() == null ? "" : dump.vmInfo()).trim()).append('\n');
            text.append("------------------------------------------\n");
            text.append(I18n.get("tool.threaddump.summary.total", String.valueOf(analysis.total())));
            for (ThreadState state : STATE_ORDER) {
                if (analysis.count(state) > 0) {
                    text.append(", ").append(stateText(state)).append(' ').append(analysis.count(state));
                }
            }
            text.append("\n\n");

            text.append(I18n.get("tool.threaddump.tab.deadlocks")).append('\n');
            if (analysis.deadlocks().isEmpty()) {
                text.append("  ").append(I18n.get("tool.threaddump.deadlock.none")).append('\n');
            } else {
                int number = 1;
                for (ThreadDumpAnalyzer.Deadlock deadlock : analysis.deadlocks()) {
                    appendDeadlock(text, deadlock, number++);
                }
            }

            text.append('\n').append(I18n.get("tool.threaddump.report.contention", String.valueOf(REPORT_LIMIT)))
                    .append('\n');
            appendNone(text, analysis.contention().isEmpty());
            for (ThreadDumpAnalyzer.LockContention item : head(analysis.contention())) {
                text.append("  ").append(I18n.get("tool.threaddump.report.contentionLine", item.address(),
                        orUnknown(item.className()), item.owner() == null ? "?" : item.owner().name(),
                        String.valueOf(item.waiters().size()))).append('\n');
            }

            text.append('\n').append(I18n.get("tool.threaddump.report.groups", String.valueOf(REPORT_LIMIT)))
                    .append('\n');
            appendNone(text, analysis.stackGroups().isEmpty());
            for (ThreadDumpAnalyzer.StackGroup group : head(analysis.stackGroups())) {
                text.append("  ").append(I18n.get("tool.threaddump.report.groupLine",
                        String.valueOf(group.members().size()), statesText(group.states()))).append('\n');
                List<ThreadInfo.Frame> frames = group.frames();
                for (int i = 0; i < frames.size() && i < REPORT_GROUP_FRAMES; i++) {
                    text.append("\tat ").append(frames.get(i).text()).append('\n');
                }
                if (frames.size() > REPORT_GROUP_FRAMES) {
                    text.append("\t...\n");
                }
            }

            text.append('\n').append(I18n.get("tool.threaddump.report.pools", String.valueOf(REPORT_LIMIT)))
                    .append('\n');
            for (ThreadDumpAnalyzer.PoolGroup pool : head(analysis.pools())) {
                text.append("  ").append(pool.pattern()).append("  ").append(pool.total())
                        .append("  [").append(statesText(pool.states())).append("]\n");
            }

            text.append('\n').append(I18n.get("tool.threaddump.report.hot", String.valueOf(REPORT_LIMIT)))
                    .append('\n');
            appendNone(text, analysis.hotMethods().isEmpty());
            for (ThreadDumpAnalyzer.HotMethod hot : head(analysis.hotMethods())) {
                text.append("  ").append(hot.count()).append("  ").append(hot.method()).append('\n');
            }
        }

        if (source.isMultiDump()) {
            text.append('\n').append(I18n.get("tool.threaddump.report.across")).append('\n');
            text.append("------------------------------------------\n");
            boolean any = false;
            for (ThreadDumpAnalyzer.ThreadTimeline timeline : source.timelines()) {
                if (!timeline.suspicious()) {
                    continue;
                }
                any = true;
                ThreadInfo latest = timeline.latest();
                text.append("  \"").append(timeline.name()).append("\"  ")
                        .append(latest == null ? "" : stateText(latest.state()));
                if (latest != null && latest.topFrame() != null) {
                    text.append("  at ").append(latest.topFrame().text());
                }
                text.append('\n');
            }
            if (!any) {
                text.append("  ").append(I18n.get("tool.threaddump.report.acrossNone")).append('\n');
            }
        }
        return text.toString();
    }

    private static void appendNone(StringBuilder text, boolean empty) {
        if (empty) {
            text.append("  ").append(I18n.get("tool.threaddump.report.none")).append('\n');
        }
    }

    private static <T> List<T> head(List<T> items) {
        return items.size() <= REPORT_LIMIT ? items : items.subList(0, REPORT_LIMIT);
    }

    @Override
    public void closeResources() {
        analysisGeneration.incrementAndGet();
        loadGeneration.incrementAndGet();
        cancel(analysisWorker);
        cancel(loadWorker);
    }

    // ==========================================
    // 表格支撑
    // ==========================================

    /**
     * 以对象列表为行的只读表格模型。
     *
     * <p>一次性替换整张表只触发一次 {@code fireTableDataChanged}；逐行 {@code addRow} 在排序器存在时
     * 每插一行都会重排，上万线程的转储会卡住界面。</p>
     */
    static final class RowsModel<T> extends AbstractTableModel {
        private final List<String> names = new ArrayList<>();
        private final List<Class<?>> types = new ArrayList<>();
        private final List<Function<T, Object>> getters = new ArrayList<>();
        private List<T> rows = List.of();

        RowsModel<T> column(String name, Class<?> type, Function<T, Object> getter) {
            names.add(name);
            types.add(type);
            getters.add(getter);
            return this;
        }

        void setRows(List<T> newRows) {
            setRows(newRows, false);
        }

        /** @param structureChanged 列定义是否变过；变过时通知 JTable 重建列 */
        void setRows(List<T> newRows, boolean structureChanged) {
            rows = List.copyOf(newRows);
            if (structureChanged) {
                fireTableStructureChanged();
            } else {
                fireTableDataChanged();
            }
        }

        void clearColumns() {
            names.clear();
            types.clear();
            getters.clear();
        }

        T rowAt(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return names.size();
        }

        @Override
        public String getColumnName(int column) {
            return names.get(column);
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return types.get(column);
        }

        @Override
        public Object getValueAt(int row, int column) {
            return getters.get(column).apply(rows.get(row));
        }
    }

    /** 状态列：NONE 显示为本地化文案，BLOCKED 用警示色，缺席（跨转储对比）显示为横线。 */
    private static final class StateRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            String text = value instanceof ThreadState ? stateText((ThreadState) value) : "-";
            Component component = super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
            if (!isSelected) {
                if (value == ThreadState.BLOCKED) {
                    component.setForeground(Tokens.danger());
                } else if (value == null || value == ThreadState.NONE) {
                    component.setForeground(Tokens.mutedForeground());
                } else {
                    component.setForeground(table.getForeground());
                }
            }
            return component;
        }
    }

    private static final class FileTooLargeException extends IOException {
        FileTooLargeException() {
            super("Thread dump file exceeds " + ThreadDumpParser.MAX_INPUT_BYTES + " bytes");
        }
    }
}
