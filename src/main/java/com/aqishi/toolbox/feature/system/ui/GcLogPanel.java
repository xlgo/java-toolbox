package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.system.domain.GcAdvice;
import com.aqishi.toolbox.feature.system.domain.GcAnalyzer;
import com.aqishi.toolbox.feature.system.domain.GcLog;
import com.aqishi.toolbox.feature.system.domain.GcLogInput;
import com.aqishi.toolbox.feature.system.domain.GcLogParser;
import com.aqishi.toolbox.feature.system.domain.GcReport;
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
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM GC 日志分析面板：粘贴或打开日志，得到停顿统计、吞吐量、堆趋势图与调优建议。
 *
 * <p>打开文件时不把整份日志塞进输入框（几百兆文本会让 EDT 卡死），只放开头一段预览，分析时
 * 直接从文件流式解析；用户一编辑输入框就改为分析框里的文本。解析与分析都在后台线程，每次领一个
 * 递增序号，回到 EDT 时序号过期就丢弃，界面只呈现最后一次的结果。</p>
 */
public class GcLogPanel extends ToolPanel implements ManagedResourceOwner {

    /** 输入框预览的字符数。 */
    private static final int PREVIEW_CHARS = 1_000_000;

    private final GcLogParser parser;
    private final GcAnalyzer analyzer;

    private JTextArea inputArea;
    private JLabel statusLabel;
    private JSpinner targetSpinner;
    private JPanel summaryRow;
    private JTabbedPane tabs;
    private final GcChart.Viewport viewport = new GcChart.Viewport();
    private GcHeapChart heapChart;
    private GcPauseChart pauseChart;
    private final GcTables tables = new GcTables();
    private int adviceTab;
    private int safepointTab;
    private int unparsedTab;

    /** 打开的文件：输入框里只是它的预览，分析时从文件读；用户编辑输入框后作废。 */
    private Path loadedFile;
    private boolean settingInput;
    private File lastDirectory;

    private GcReport report;
    /** 上次成功解析的来源与结果：只改停顿目标时不必重新解析。 */
    private Object parsedSource;
    private GcLog parsedLog;

    private final AtomicLong analysisGeneration = new AtomicLong();
    private final AtomicLong loadGeneration = new AtomicLong();
    private final AtomicReference<SwingWorker<?, ?>> analysisWorker = new AtomicReference<>();
    private final AtomicReference<SwingWorker<?, ?>> loadWorker = new AtomicReference<>();

    public GcLogPanel() {
        this(ToolCatalog.GC_LOG, new GcLogParser(), new GcAnalyzer());
    }

    public GcLogPanel(ToolDescriptor descriptor, GcLogParser parser, GcAnalyzer analyzer) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.parser = Objects.requireNonNull(parser, "parser");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        root.add(Layouts.splitVertical(buildInputCard(), buildResultCard(), 0.25, 0.25), BorderLayout.CENTER);
        return root;
    }

    // ==========================================
    // 输入
    // ==========================================
    private Card buildInputCard() {
        inputArea = Fields.area(6, 40);
        inputArea.setLineWrap(false);
        inputArea.putClientProperty("JTextField.placeholderText", I18n.get("tool.gclog.placeholder.input"));
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
                "gclog.analyze");
        inputArea.getActionMap().put("gclog.analyze", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                runAnalysis();
            }
        });

        Card card = Card.titled(I18n.get("tool.gclog.card.input"), I18n.get("tool.gclog.card.input.subtitle"));
        card.setContent(Fields.scrollBoxed(inputArea));
        JButton openBtn = Buttons.secondary(I18n.get("tool.gclog.btn.open"));
        JButton clearBtn = Buttons.ghost(I18n.get("tool.gclog.btn.clear"));
        JButton analyzeBtn = Buttons.primary(I18n.get("tool.gclog.btn.analyze"));
        card.addHeaderAction(openBtn);
        card.addHeaderAction(clearBtn);
        card.addHeaderAction(analyzeBtn);
        statusLabel = Fields.caption(I18n.get("tool.gclog.status.ready"));
        card.setFooter(statusLabel);

        openBtn.addActionListener(event -> openFile(card));
        clearBtn.addActionListener(event -> clearAll());
        analyzeBtn.addActionListener(event -> runAnalysis());
        return card;
    }

    private void inputEdited() {
        if (!settingInput) {
            loadedFile = null;
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

    private void openFile(Component parent) {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(I18n.get("tool.gclog.btn.open"));
        if (chooser.showOpenDialog(SwingUtilities.getWindowAncestor(parent)) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        lastDirectory = file.getParentFile();
        Path path = file.toPath();
        long ticket = loadGeneration.incrementAndGet();
        cancel(loadWorker);
        setStatus(I18n.get("tool.gclog.status.loading", file.getName()), Tokens.mutedForeground());

        SwingWorker<GcLogInput.Preview, Void> worker = new SwingWorker<>() {
            private long size;

            @Override
            protected GcLogInput.Preview doInBackground() throws IOException {
                // 先按大小拒绝：超限的文件连预览都不读。
                size = Files.size(path);
                if (size > GcLogParser.MAX_INPUT_BYTES) {
                    throw new FileTooLargeException();
                }
                return GcLogInput.preview(path, PREVIEW_CHARS);
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != loadGeneration.get()) {
                    return;
                }
                try {
                    GcLogInput.Preview preview = get();
                    setInput(preview.text());
                    loadedFile = path;
                    parsedSource = null;
                    setStatus(I18n.get("tool.gclog.status.loaded", file.getName(),
                            preview.charset().displayName(Locale.ROOT), formatSize(size)), Tokens.mutedForeground());
                    runAnalysis();
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof FileTooLargeException) {
                        setStatus(I18n.get("tool.gclog.error.tooLarge",
                                String.valueOf(GcLogParser.MAX_INPUT_BYTES / (1024 * 1024))), Tokens.danger());
                    } else {
                        setStatus(I18n.get("tool.gclog.error.read", Errors.describeRoot(error)), Tokens.danger());
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
        loadedFile = null;
        parsedSource = null;
        parsedLog = null;
        viewport.reset();
        applyReport(null);
        setStatus(I18n.get("tool.gclog.status.ready"), Tokens.mutedForeground());
    }

    // ==========================================
    // 分析
    // ==========================================
    private void runAnalysis() {
        runAnalysis(false);
    }

    /**
     * @param reuseParsed 来源未变时复用上次的解析结果（只改停顿目标）；点「分析」时总是重新读取，
     *                    因为打开的日志文件可能还在被 JVM 追加
     */
    private void runAnalysis(boolean reuseParsed) {
        Path file = loadedFile;
        String text = file == null ? inputArea.getText() : null;
        if (file == null && (text == null || text.isBlank())) {
            setStatus(I18n.get("tool.gclog.status.empty"), Tokens.warning());
            return;
        }
        Object source = file != null ? file : text;
        double target = ((Number) targetSpinner.getValue()).doubleValue();
        long ticket = analysisGeneration.incrementAndGet();
        cancel(analysisWorker);
        GcLog cached = reuseParsed && source.equals(parsedSource) ? parsedLog : null;
        setStatus(I18n.get("tool.gclog.status.analyzing"), Tokens.mutedForeground());

        SwingWorker<GcReport, Void> worker = new SwingWorker<>() {
            private long elapsed;

            @Override
            protected GcReport doInBackground() throws IOException {
                long start = System.nanoTime();
                GcLog log = cached;
                if (log == null) {
                    if (file != null) {
                        GcLogInput.Opened opened = GcLogInput.open(file, GcLogParser.MAX_INPUT_BYTES);
                        try (Reader reader = opened.reader()) {
                            log = parser.parse(reader);
                        }
                        if (opened.size() > GcLogParser.MAX_INPUT_BYTES) {
                            log = GcLogParser.markTruncated(log);
                        }
                    } else {
                        log = parser.parse(text);
                    }
                }
                GcReport result = analyzer.analyze(log, target);
                elapsed = (System.nanoTime() - start) / 1_000_000L;
                return result;
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != analysisGeneration.get()) {
                    return;
                }
                try {
                    GcReport result = get();
                    parsedSource = source;
                    parsedLog = result.log();
                    if (cached == null) {
                        viewport.reset();
                    }
                    applyReport(result);
                    GcLog log = result.log();
                    if (log.events().isEmpty()) {
                        setStatus(I18n.get("tool.gclog.status.noEvents", String.valueOf(log.totalLines()),
                                String.valueOf(log.unparsedLines())), Tokens.warning());
                    } else {
                        setStatus(I18n.get(log.truncated() ? "tool.gclog.status.doneTruncated" : "tool.gclog.status.done",
                                String.valueOf(log.events().size()), String.valueOf(result.pauses().count()),
                                String.valueOf(elapsed)), log.truncated() ? Tokens.warning() : Tokens.mutedForeground());
                    }
                } catch (ExecutionException error) {
                    applyReport(null);
                    setStatus(I18n.get("tool.gclog.error.analyze", Errors.describeRoot(error)), Tokens.danger());
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
        targetSpinner = Fields.spinner((int) GcAnalyzer.DEFAULT_PAUSE_TARGET_MS, 1, 600_000, 10);
        targetSpinner.setToolTipText(I18n.get("tool.gclog.label.target.tip"));
        targetSpinner.addChangeListener(event -> {
            if (parsedLog != null) {
                runAnalysis(true);
            }
        });
        JLabel targetLabel = Fields.label(I18n.get("tool.gclog.label.target"));
        targetLabel.setToolTipText(I18n.get("tool.gclog.label.target.tip"));
        JPanel controls = Layouts.wrapRow(targetLabel, targetSpinner,
                Fields.caption(I18n.get("tool.gclog.chart.hint")));

        heapChart = new GcHeapChart(viewport);
        pauseChart = new GcPauseChart(viewport);
        JPanel charts = new JPanel(new GridLayout(2, 1, 0, Tokens.SPACE_SM));
        charts.setOpaque(false);
        charts.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        charts.add(heapChart);
        charts.add(pauseChart);

        tabs = new JTabbedPane();
        tabs.addTab(I18n.get("tool.gclog.tab.charts"), charts);
        tabs.addTab(I18n.get("tool.gclog.tab.pauses"), tables.pausesTab());
        tabs.addTab(I18n.get("tool.gclog.tab.causes"), tables.causesTab());
        tabs.addTab(I18n.get("tool.gclog.tab.phases"), tables.phasesTab());
        safepointTab = tabs.getTabCount();
        tabs.addTab(I18n.get("tool.gclog.tab.safepoints"), tables.safepointsTab());
        adviceTab = tabs.getTabCount();
        tabs.addTab(I18n.get("tool.gclog.tab.advice"), tables.adviceTab());
        unparsedTab = tabs.getTabCount();
        tabs.addTab(I18n.get("tool.gclog.tab.unparsed"), tables.unparsedTab());

        JPanel body = Layouts.box(0, Tokens.SPACE_SM);
        body.add(Layouts.stack(Tokens.SPACE_XS, summaryRow, controls), BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);

        Card card = Card.titled(I18n.get("tool.gclog.card.result"));
        card.setContent(body);
        JButton copyBtn = Buttons.snug(I18n.get("tool.gclog.btn.copyReport"));
        card.addHeaderAction(copyBtn);
        copyBtn.addActionListener(event -> {
            if (report == null) {
                setStatus(I18n.get("tool.gclog.status.empty"), Tokens.warning());
                return;
            }
            UIUtils.copyToClipboard(GcReportText.build(report));
            setStatus(I18n.get("tool.gclog.status.copied"), Tokens.success());
        });
        applyReport(null);
        return card;
    }

    /** 包内可见：测试直接灌入分析结果，覆盖各页签的填充逻辑。 */
    void applyReport(GcReport newReport) {
        report = newReport;
        GcSummary.render(summaryRow, newReport);
        heapChart.setReport(newReport, GcHeapChart.heapEvents(newReport));
        pauseChart.setReport(newReport, GcPauseChart.pauseEvents(newReport));
        tables.fill(newReport);

        int adviceCount = newReport == null ? 0 : newReport.advice().size();
        boolean critical = newReport != null && newReport.advice().stream()
                .anyMatch(advice -> advice.severity() == GcAdvice.Severity.CRITICAL);
        tabs.setTitleAt(adviceTab, adviceCount == 0 ? I18n.get("tool.gclog.tab.advice")
                : I18n.get("tool.gclog.tab.advice") + " (" + adviceCount + ")");
        tabs.setForegroundAt(adviceTab, critical ? Tokens.danger() : adviceCount > 0 ? Tokens.warning() : null);
        long unparsed = newReport == null ? 0 : newReport.log().unparsedLines();
        tabs.setTitleAt(unparsedTab, unparsed == 0 ? I18n.get("tool.gclog.tab.unparsed")
                : I18n.get("tool.gclog.tab.unparsed") + " (" + unparsed + ")");
        tabs.setEnabledAt(safepointTab, newReport != null && newReport.safepoints() != null);
        if (!tabs.isEnabledAt(tabs.getSelectedIndex())) {
            tabs.setSelectedIndex(0);
        }
    }

    @Override
    public void closeResources() {
        analysisGeneration.incrementAndGet();
        loadGeneration.incrementAndGet();
        cancel(analysisWorker);
        cancel(loadWorker);
    }

    private static final class FileTooLargeException extends IOException {
        FileTooLargeException() {
            super("GC log file exceeds " + GcLogParser.MAX_INPUT_BYTES + " bytes");
        }
    }
}
