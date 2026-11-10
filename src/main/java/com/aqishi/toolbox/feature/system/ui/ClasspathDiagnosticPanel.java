package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.system.application.ClasspathDiagnosticService;
import com.aqishi.toolbox.feature.system.domain.ClassAbiSignature;
import com.aqishi.toolbox.feature.system.domain.ClassConflict;
import com.aqishi.toolbox.feature.system.domain.ClasspathDiagnosticReport;
import com.aqishi.toolbox.feature.system.domain.ClasspathParser;
import com.aqishi.toolbox.feature.system.domain.KnownConflictDetector;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Classpath 拓扑与类冲突诊断面板：模拟 ClassLoader 加载顺序，深度比对公开方法与字段 ABI 签名。
 */
public final class ClasspathDiagnosticPanel extends ToolPanel implements ManagedResourceOwner {

    private final ClasspathDiagnosticService service;

    // 输入与顺序模型
    private final JTextArea inputArea = Fields.area(4, 30);
    private final DefaultListModel<Path> sourcesListModel = new DefaultListModel<>();
    private final JList<Path> sourcesList = new JList<>(sourcesListModel);

    // 核心操作按钮
    private final JButton parseBtn = Buttons.secondary(I18n.get("tool.classpath.diagnostic.btn.parse"));
    private final JButton addJarBtn = Buttons.secondary(I18n.get("tool.classpath.diagnostic.btn.addJar"));
    private final JButton addDirBtn = Buttons.secondary(I18n.get("tool.classpath.diagnostic.btn.addDir"));
    private final JButton clearBtn = Buttons.secondary(I18n.get("tool.classpath.diagnostic.btn.clear"));
    private final JButton moveUpBtn = Buttons.secondary(I18n.get("tool.classpath.diagnostic.btn.moveUp"));
    private final JButton moveDownBtn = Buttons.secondary(I18n.get("tool.classpath.diagnostic.btn.moveDown"));

    private final JButton diagnoseBtn = Buttons.primary(I18n.get("tool.classpath.diagnostic.btn.diagnose"));
    private final JButton cancelBtn = Buttons.danger(I18n.get("tool.classpath.diagnostic.btn.cancel"));
    private final JButton exportBtn = Buttons.secondary(I18n.get("tool.classpath.diagnostic.btn.export"));

    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = Fields.caption(I18n.get("tool.classpath.diagnostic.status.ready"));

    // 统计指示牌标签
    private final JLabel breakingBadge = new JLabel("0");
    private final JLabel warningBadge = new JLabel("0");
    private final JLabel safeBadge = new JLabel("0");
    private final JLabel knownBadge = new JLabel("0");

    // 过滤控制
    private final JTextField filterField = Fields.text("", I18n.get("tool.classpath.diagnostic.filter.placeholder"));
    private final JComboBox<String> riskFilterCombo = new JComboBox<>(new String[]{
            I18n.get("tool.classpath.diagnostic.filter.all"),
            I18n.get("tool.classpath.diagnostic.filter.breaking"),
            I18n.get("tool.classpath.diagnostic.filter.warning"),
            I18n.get("tool.classpath.diagnostic.filter.safe")
    });

    // 结果模型
    private final JarUi.RowsModel<ClassConflict> conflictTableModel = new JarUi.RowsModel<>();
    private final JTable conflictTable = new JTable(conflictTableModel);

    // 详情区域
    private final JTabbedPane detailTabs = new JTabbedPane();
    private final DefaultListModel<String> missingMethodsModel = new DefaultListModel<>();
    private final JList<String> missingMethodsList = new JList<>(missingMethodsModel);
    private final JButton copyMethodBtn = Buttons.secondary(I18n.get("tool.classpath.diagnostic.btn.copyMethod"));

    private final JTextArea incompatibleArea = JarUi.detailArea();
    private final JTextArea knownClashesArea = JarUi.detailArea();
    private final JTextArea abiOverviewArea = JarUi.detailArea();

    private JPanel root;
    private SwingWorker<ClasspathDiagnosticReport, Void> currentWorker;
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private ClasspathDiagnosticReport currentReport;
    private List<ClassConflict> allConflicts = Collections.emptyList();
    private File lastDirectory;

    public ClasspathDiagnosticPanel() {
        this(new ClasspathDiagnosticService());
    }

    public ClasspathDiagnosticPanel(ClasspathDiagnosticService service) {
        super(ToolCatalog.CLASSPATH_DIAGNOSTIC);
        this.service = service;
    }

    @Override
    protected JComponent build() {
        initModels();
        initListeners();

        root = Layouts.page();
        root.add(buildInputCard(), BorderLayout.NORTH);
        root.add(buildResultCard(), BorderLayout.CENTER);

        cancelBtn.setEnabled(false);
        exportBtn.setEnabled(false);
        progressBar.setVisible(false);

        return root;
    }

    private void initModels() {
        conflictTableModel
                .column(I18n.get("tool.classpath.diagnostic.col.risk"), String.class,
                        c -> formatRisk(c.worstRiskLevel()))
                .column(I18n.get("tool.classpath.diagnostic.col.className"), String.class,
                        ClassConflict::className)
                .column(I18n.get("tool.classpath.diagnostic.col.winner"), String.class,
                        c -> c.winnerSource().orderLabel())
                .column(I18n.get("tool.classpath.diagnostic.col.masked"), String.class,
                        this::formatMaskedSources)
                .column(I18n.get("tool.classpath.diagnostic.col.missingMethods"), Integer.class,
                        ClassConflict::totalMissingMethods);

        conflictTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        conflictTable.getTableHeader().setReorderingAllowed(false);
        conflictTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        if (conflictTable.getColumnModel().getColumnCount() > 0) {
            conflictTable.getColumnModel().getColumn(0).setPreferredWidth(110);
            conflictTable.getColumnModel().getColumn(0).setMaxWidth(130);
            conflictTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
        }
    }

    private String formatRisk(ClassConflict.RiskLevel level) {
        return switch (level) {
            case BREAKING -> "[!] BREAKING";
            case WARNING -> "[?] WARNING";
            case SAFE -> "[v] SAFE";
        };
    }

    private String formatMaskedSources(ClassConflict conflict) {
        List<String> labels = new ArrayList<>();
        for (ClassConflict.MaskedOccurrence m : conflict.maskedOccurrences()) {
            labels.add(m.source().orderLabel());
        }
        return String.join(", ", labels);
    }

    private void initListeners() {
        parseBtn.addActionListener(e -> parseInputArea());
        addJarBtn.addActionListener(e -> pickJarFiles());
        addDirBtn.addActionListener(e -> pickDirectory());
        clearBtn.addActionListener(e -> clearAll());
        moveUpBtn.addActionListener(e -> moveSelectedSource(-1));
        moveDownBtn.addActionListener(e -> moveSelectedSource(1));

        diagnoseBtn.addActionListener(e -> startDiagnosis());
        cancelBtn.addActionListener(e -> cancelDiagnosis());
        exportBtn.addActionListener(e -> exportReport());
        copyMethodBtn.addActionListener(e -> copySelectedMethod());

        sourcesList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Path p) {
                    setText("[#" + (index + 1) + "] " + p.getFileName() + " (" + p + ")");
                }
                return this;
            }
        });

        conflictTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = conflictTable.getSelectedRow();
                if (row >= 0 && row < conflictTableModel.getRowCount()) {
                    showConflictDetails(conflictTableModel.rowAt(row));
                } else {
                    clearConflictDetails();
                }
            }
        });

        DocumentListener filterDocListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        };
        filterField.getDocument().addDocumentListener(filterDocListener);
        riskFilterCombo.addActionListener(e -> applyFilter());
    }

    private Card buildInputCard() {
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        inputArea.setToolTipText(I18n.get("tool.classpath.diagnostic.card.input.subtitle"));
        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setPreferredSize(new Dimension(600, 75));

        JPanel sourceActionRow = Layouts.wrapRow(Tokens.SPACE_XS, Tokens.SPACE_XS,
                parseBtn, addJarBtn, addDirBtn, moveUpBtn, moveDownBtn, clearBtn);

        JScrollPane listScroll = new JScrollPane(sourcesList);
        listScroll.setPreferredSize(new Dimension(600, 95));

        JPanel execRow = Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS,
                diagnoseBtn, cancelBtn, exportBtn, progressBar, statusLabel);

        form.add(inputScroll);
        form.add(Box.createVerticalStrut(Tokens.SPACE_XS));
        form.add(sourceActionRow);
        form.add(Box.createVerticalStrut(Tokens.SPACE_XS));
        form.add(listScroll);
        form.add(Box.createVerticalStrut(Tokens.SPACE_XS));
        form.add(execRow);

        Card card = Card.titled(
                I18n.get("tool.classpath.diagnostic.card.input"),
                I18n.get("tool.classpath.diagnostic.card.input.subtitle")
        );
        card.setContent(form);
        return card;
    }

    private Card buildResultCard() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        // 顶部指标仪表盘
        JPanel metricsPanel = new JPanel(new GridLayout(1, 4, Tokens.SPACE_SM, 0));
        metricsPanel.setOpaque(false);
        metricsPanel.add(buildMetricBadge(I18n.get("tool.classpath.diagnostic.metric.breaking"), breakingBadge, new Color(0xd9, 0x30, 0x25)));
        metricsPanel.add(buildMetricBadge(I18n.get("tool.classpath.diagnostic.metric.warning"), warningBadge, new Color(0xf2, 0x99, 0x00)));
        metricsPanel.add(buildMetricBadge(I18n.get("tool.classpath.diagnostic.metric.safe"), safeBadge, new Color(0x18, 0x80, 0x38)));
        metricsPanel.add(buildMetricBadge(I18n.get("tool.classpath.diagnostic.metric.known"), knownBadge, new Color(0x1a, 0x73, 0xe8)));

        // 过滤栏
        filterField.setPreferredSize(new Dimension(320, 30));
        riskFilterCombo.setPreferredSize(new Dimension(170, 30));
        JPanel filterRow = Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS, filterField, riskFilterCombo);

        // 主分割视图：左表，右详情
        JScrollPane tableScroll = new JScrollPane(conflictTable);
        tableScroll.setMinimumSize(new Dimension(420, 260));

        buildDetailTabs();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detailTabs);
        splitPane.setResizeWeight(0.55);
        splitPane.setDividerLocation(520);
        splitPane.setPreferredSize(new Dimension(800, 360));

        content.add(metricsPanel);
        content.add(Box.createVerticalStrut(Tokens.SPACE_SM));
        content.add(filterRow);
        content.add(Box.createVerticalStrut(Tokens.SPACE_SM));
        content.add(splitPane);

        Card card = Card.titled(I18n.get("tool.classpath.diagnostic.tab.conflicts"));
        card.setContent(content);
        return card;
    }

    private JPanel buildMetricBadge(String title, JLabel valueLabel, Color accent) {
        JPanel p = new JPanel(new BorderLayout(Tokens.SPACE_XS, Tokens.SPACE_XS));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xe0, 0xe0, 0xe0), 1),
                new EmptyBorder(8, 12, 8, 12)
        ));
        JLabel titleLabel = Fields.caption(title);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 18f));
        valueLabel.setForeground(accent);

        p.add(titleLabel, BorderLayout.NORTH);
        p.add(valueLabel, BorderLayout.CENTER);
        return p;
    }

    private void buildDetailTabs() {
        // Tab 1: 缺失方法
        JPanel missingPanel = new JPanel(new BorderLayout(Tokens.SPACE_XS, Tokens.SPACE_XS));
        missingPanel.setOpaque(false);
        JScrollPane missingScroll = new JScrollPane(missingMethodsList);
        JPanel missingToolRow = Layouts.wrapRow(Tokens.SPACE_XS, Tokens.SPACE_XS, copyMethodBtn);
        missingPanel.add(missingScroll, BorderLayout.CENTER);
        missingPanel.add(missingToolRow, BorderLayout.SOUTH);

        detailTabs.addTab(I18n.get("tool.classpath.diagnostic.tab.missingMethods"), missingPanel);
        detailTabs.addTab(I18n.get("tool.classpath.diagnostic.tab.incompatible"), new JScrollPane(incompatibleArea));
        detailTabs.addTab(I18n.get("tool.classpath.diagnostic.tab.overview"), new JScrollPane(abiOverviewArea));
        detailTabs.addTab(I18n.get("tool.classpath.diagnostic.tab.known"), new JScrollPane(knownClashesArea));
    }

    private void parseInputArea() {
        String text = inputArea.getText();
        List<Path> paths = ClasspathParser.parse(text);
        for (Path p : paths) {
            if (!sourcesListModel.contains(p)) {
                sourcesListModel.addElement(p);
            }
        }
        statusLabel.setText(I18n.get("tool.classpath.diagnostic.status.ready"));
    }

    private void pickJarFiles() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Java Archives (*.jar, *.war)", "jar", "war"));
        if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            File[] files = chooser.getSelectedFiles();
            if (files != null && files.length > 0) {
                lastDirectory = files[0].getParentFile();
                for (File f : files) {
                    Path p = f.toPath();
                    if (!sourcesListModel.contains(p)) {
                        sourcesListModel.addElement(p);
                    }
                }
            }
        }
    }

    private void pickDirectory() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            File dir = chooser.getSelectedFile();
            if (dir != null) {
                lastDirectory = dir;
                Path p = dir.toPath();
                if (!sourcesListModel.contains(p)) {
                    sourcesListModel.addElement(p);
                }
            }
        }
    }

    private void clearAll() {
        inputArea.setText("");
        sourcesListModel.clear();
        conflictTableModel.setRows(Collections.emptyList());
        allConflicts = Collections.emptyList();
        clearConflictDetails();
        breakingBadge.setText("0");
        warningBadge.setText("0");
        safeBadge.setText("0");
        knownBadge.setText("0");
        exportBtn.setEnabled(false);
        statusLabel.setText(I18n.get("tool.classpath.diagnostic.status.ready"));
    }

    private void moveSelectedSource(int direction) {
        int index = sourcesList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        int target = index + direction;
        if (target >= 0 && target < sourcesListModel.getSize()) {
            Path item = sourcesListModel.remove(index);
            sourcesListModel.add(target, item);
            sourcesList.setSelectedIndex(target);
            sourcesList.ensureIndexIsVisible(target);
        }
    }

    private void startDiagnosis() {
        if (sourcesListModel.isEmpty()) {
            parseInputArea();
        }
        if (sourcesListModel.isEmpty()) {
            statusLabel.setText(I18n.get("tool.classpath.diagnostic.status.empty"));
            return;
        }

        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < sourcesListModel.getSize(); i++) {
            paths.add(sourcesListModel.get(i));
        }

        diagnoseBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        cancelFlag.set(false);

        currentWorker = new SwingWorker<>() {
            @Override
            protected ClasspathDiagnosticReport doInBackground() {
                return service.diagnose(paths, (done, total, cur) -> {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setIndeterminate(false);
                        progressBar.setMaximum(total);
                        progressBar.setValue(done);
                        statusLabel.setText(I18n.get("tool.classpath.diagnostic.status.scanning", done, total, cur));
                    });
                }, cancelFlag::get);
            }

            @Override
            protected void done() {
                diagnoseBtn.setEnabled(true);
                cancelBtn.setEnabled(false);
                progressBar.setVisible(false);

                if (cancelFlag.get()) {
                    statusLabel.setText(I18n.get("tool.classpath.diagnostic.status.cancelled"));
                    return;
                }

                try {
                    ClasspathDiagnosticReport report = get();
                    applyReport(report);
                } catch (Exception ex) {
                    statusLabel.setText(ex.getMessage());
                }
            }
        };

        currentWorker.execute();
    }

    private void cancelDiagnosis() {
        cancelFlag.set(true);
        if (currentWorker != null) {
            currentWorker.cancel(true);
        }
        cancelBtn.setEnabled(false);
        statusLabel.setText(I18n.get("tool.classpath.diagnostic.status.cancelled"));
    }

    private void applyReport(ClasspathDiagnosticReport report) {
        this.currentReport = report;
        this.allConflicts = report.conflicts();

        breakingBadge.setText(String.valueOf(report.breakingCount()));
        warningBadge.setText(String.valueOf(report.warningCount()));
        safeBadge.setText(String.valueOf(report.safeCount()));
        knownBadge.setText(String.valueOf(report.knownCount()));

        exportBtn.setEnabled(true);
        statusLabel.setText(I18n.get("tool.classpath.diagnostic.status.done",
                report.sources().size(), report.totalClassesScanned(), report.conflicts().size(), report.elapsedMillis()));

        renderKnownClashes(report.knownMatches());
        applyFilter();
    }

    private void renderKnownClashes(List<KnownConflictDetector.Match> matches) {
        if (matches == null || matches.isEmpty()) {
            knownClashesArea.setText(I18n.get("tool.classpath.diagnostic.detail.noIncompatible"));
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (KnownConflictDetector.Match m : matches) {
            sb.append("=== ").append(I18n.get(m.rule().categoryKey())).append(" ===\n");
            sb.append("Winner: ").append(m.firstSource().displayName()).append("\n");
            sb.append("Clashing: ").append(m.secondSource().displayName()).append("\n\n");
            sb.append("Issue:\n").append(I18n.get(m.rule().descriptionKey())).append("\n\n");
            sb.append("Suggestion:\n").append(I18n.get(m.rule().suggestionKey())).append("\n");
            sb.append("--------------------------------------------------\n\n");
        }
        knownClashesArea.setText(sb.toString());
        knownClashesArea.setCaretPosition(0);
    }

    private void applyFilter() {
        String keyword = filterField.getText().trim().toLowerCase(Locale.ROOT);
        int riskIndex = riskFilterCombo.getSelectedIndex();

        List<ClassConflict> filtered = new ArrayList<>();
        for (ClassConflict conflict : allConflicts) {
            if (!keyword.isEmpty() && !conflict.className().toLowerCase(Locale.ROOT).contains(keyword)) {
                continue;
            }
            if (riskIndex == 1 && conflict.worstRiskLevel() != ClassConflict.RiskLevel.BREAKING) {
                continue;
            }
            if (riskIndex == 2 && conflict.worstRiskLevel() != ClassConflict.RiskLevel.WARNING) {
                continue;
            }
            if (riskIndex == 3 && conflict.worstRiskLevel() != ClassConflict.RiskLevel.SAFE) {
                continue;
            }
            filtered.add(conflict);
        }

        conflictTableModel.setRows(filtered);
        if (conflictTableModel.getRowCount() > 0) {
            conflictTable.setRowSelectionInterval(0, 0);
        } else {
            clearConflictDetails();
        }
    }

    private void showConflictDetails(ClassConflict conflict) {
        missingMethodsModel.clear();
        StringBuilder incompBuilder = new StringBuilder();
        StringBuilder overviewBuilder = new StringBuilder();

        overviewBuilder.append("Class: ").append(conflict.className()).append("\n\n");
        overviewBuilder.append("[WINNER] ").append(conflict.winnerSource().orderLabel()).append("\n");
        ClassAbiSignature wAbi = conflict.winnerAbi();
        overviewBuilder.append("  - Major Version: ").append(wAbi.majorVersion()).append("\n");
        overviewBuilder.append("  - Public Methods: ").append(wAbi.publicMethods().size()).append("\n");
        overviewBuilder.append("  - Public Fields: ").append(wAbi.publicFields().size()).append("\n");
        overviewBuilder.append("  - SHA-256: ").append(wAbi.sha256()).append("\n\n");

        for (ClassConflict.MaskedOccurrence masked : conflict.maskedOccurrences()) {
            overviewBuilder.append("[MASKED] ").append(masked.source().orderLabel()).append("\n");
            ClassAbiSignature mAbi = masked.abi();
            overviewBuilder.append("  - Major Version: ").append(mAbi.majorVersion()).append("\n");
            overviewBuilder.append("  - Public Methods: ").append(mAbi.publicMethods().size()).append("\n");
            overviewBuilder.append("  - Public Fields: ").append(mAbi.publicFields().size()).append("\n");
            overviewBuilder.append("  - SHA-256: ").append(mAbi.sha256()).append("\n");
            overviewBuilder.append("  - Risk Assessment: ").append(masked.riskLevel()).append("\n\n");

            if (!masked.missingMethods().isEmpty()) {
                for (ClassAbiSignature.MethodSignature m : masked.missingMethods()) {
                    missingMethodsModel.addElement("[" + masked.source().displayName() + "] " + m.renderedSignature());
                }
            }

            if (!masked.incompatibleMethods().isEmpty()) {
                incompBuilder.append("Incompatible Method Modifiers in ").append(masked.source().displayName()).append(":\n");
                for (ClassConflict.IncompatibleMethodChange im : masked.incompatibleMethods()) {
                    incompBuilder.append("  - ").append(im.masked().renderedSignature())
                            .append(" (").append(im.reason()).append(")\n");
                }
                incompBuilder.append("\n");
            }
            if (!masked.missingFields().isEmpty()) {
                incompBuilder.append("Missing Public Fields in ").append(masked.source().displayName()).append(":\n");
                for (ClassAbiSignature.FieldSignature f : masked.missingFields()) {
                    incompBuilder.append("  - ").append(f.renderedType()).append(" ").append(f.name()).append("\n");
                }
                incompBuilder.append("\n");
            }
        }

        if (missingMethodsModel.isEmpty()) {
            missingMethodsModel.addElement(I18n.get("tool.classpath.diagnostic.detail.noMissing"));
        }

        if (incompBuilder.isEmpty()) {
            incompatibleArea.setText(I18n.get("tool.classpath.diagnostic.detail.noIncompatible"));
        } else {
            incompatibleArea.setText(incompBuilder.toString());
            incompatibleArea.setCaretPosition(0);
        }

        abiOverviewArea.setText(overviewBuilder.toString());
        abiOverviewArea.setCaretPosition(0);
    }

    private void clearConflictDetails() {
        missingMethodsModel.clear();
        missingMethodsModel.addElement(I18n.get("tool.classpath.diagnostic.detail.empty"));
        incompatibleArea.setText(I18n.get("tool.classpath.diagnostic.detail.empty"));
        abiOverviewArea.setText(I18n.get("tool.classpath.diagnostic.detail.empty"));
    }

    private void copySelectedMethod() {
        String selected = missingMethodsList.getSelectedValue();
        if (selected != null && !selected.isBlank()) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(selected), null);
            statusLabel.setText(I18n.get("tool.classpath.diagnostic.status.copied"));
        }
    }

    private void exportReport() {
        if (currentReport != null) {
            String md = currentReport.toMarkdown();
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(md), null);
            statusLabel.setText(I18n.get("tool.classpath.diagnostic.status.copied"));
        }
    }

    @Override
    public void closeResources() {
        cancelDiagnosis();
    }
}
