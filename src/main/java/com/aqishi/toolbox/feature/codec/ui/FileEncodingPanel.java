package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService;
import com.aqishi.toolbox.feature.codec.domain.EncodingDetector;
import com.aqishi.toolbox.feature.codec.domain.MojibakeRepair;
import com.aqishi.toolbox.feature.codec.domain.TextFileConverter;
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
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 文件编码转换面板：批量探测与转换、单文件多编码预览、乱码修复。
 *
 * <p>扫描与转换都在后台线程执行；每次任务领一个递增序号，结果回到 EDT 时序号已过期就丢弃。
 * 取消只设置标志位、不中断线程——转换线程可能正处在「临时文件 → 原子替换」之间，
 * 由服务在文件边界上检查标志，保证每个文件要么完整替换、要么原样不动。</p>
 */
public class FileEncodingPanel extends ToolPanel implements ManagedResourceOwner {

    /** 单文件预览允许的最大文件。 */
    private static final long SINGLE_MAX_BYTES = EncodingBatchService.DEFAULT_MAX_BYTES;
    /** 预览区最多放多少字符：几十 MB 文本塞进 JTextArea 会卡住 EDT。 */
    private static final int PREVIEW_CHARS = 200_000;

    /** 目标编码下拉框的选项；null 表示沿用源编码。 */
    private static final String[] TARGET_CHARSETS = {
            null, "UTF-8", "GBK", "GB18030", "UTF-16LE", "UTF-16BE", "ISO-8859-1"};
    /** 可手动指定的源编码。 */
    private static final String[] SOURCE_CHARSETS = {
            "UTF-8", "GBK", "GB18030", "Big5", "windows-31j", "UTF-16LE", "UTF-16BE",
            "ISO-8859-1", "windows-1252", "US-ASCII"};

    private static final int COL_SELECTED = 0;
    private static final int COL_PATH = 1;
    private static final int COL_SIZE = 2;
    private static final int COL_CHARSET = 3;
    private static final int COL_CONFIDENCE = 4;
    private static final int COL_BOM = 5;
    private static final int COL_LINES = 6;
    private static final int COL_STATUS = 7;

    private final EncodingBatchService service;
    private final EncodingDetector detector;

    // ---- 批量转换 ----
    private JTextField rootField;
    private JTextField includeField;
    private JTextField excludeField;
    private JComboBox<String> targetCombo;
    private JComboBox<String> bomCombo;
    private JComboBox<String> lineCombo;
    private JCheckBox replaceCheck;
    private JComboBox<String> backupCombo;
    private JTextField backupDirField;
    private JButton backupDirButton;
    private JCheckBox preserveTimeCheck;
    private JSpinner maxSizeSpinner;
    private JButton scanButton;
    private JButton convertButton;
    private JButton cancelButton;
    private JProgressBar progressBar;
    private JLabel summaryLabel;
    private BatchTableModel tableModel;
    private JTable table;
    private Path scannedRoot;
    private File lastDirectory;

    private final AtomicLong batchGeneration = new AtomicLong();
    private final AtomicReference<SwingWorker<?, ?>> batchWorker = new AtomicReference<>();
    private final AtomicReference<AtomicBoolean> batchCancellation = new AtomicReference<>();

    // ---- 单文件 ----
    private JTextField hexField;
    private JLabel singleStatus;
    private DefaultListModel<String> previewListModel;
    private JList<String> previewList;
    private JTextArea previewArea;
    private JLabel previewStatus;
    private JComboBox<String> saveTargetCombo;
    private JComboBox<String> saveBomCombo;
    private JComboBox<String> saveLineCombo;
    private JCheckBox saveReplaceCheck;
    private Loaded loaded;
    private final AtomicLong singleGeneration = new AtomicLong();
    private final AtomicReference<SwingWorker<?, ?>> singleWorker = new AtomicReference<>();

    // ---- 乱码修复 ----
    private JTextArea mojibakeInput;
    private JTextArea mojibakeOutput;
    private DefaultListModel<String> mojibakeListModel;
    private JList<String> mojibakeList;
    private JLabel mojibakeStatus;
    private List<MojibakeRepair.Candidate> mojibakeCandidates = List.of();

    public FileEncodingPanel() {
        this(ToolCatalog.FILE_ENCODING, new EncodingBatchService(), new EncodingDetector());
    }

    public FileEncodingPanel(ToolDescriptor descriptor, EncodingBatchService service, EncodingDetector detector) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.service = Objects.requireNonNull(service, "service");
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    @Override
    protected JComponent build() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(I18n.get("tool.fileencoding.tab.batch"), buildBatchTab());
        tabs.addTab(I18n.get("tool.fileencoding.tab.single"), buildSingleTab());
        tabs.addTab(I18n.get("tool.fileencoding.tab.mojibake"), buildMojibakeTab());
        JPanel root = Layouts.page();
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    // ==========================================
    // 批量转换
    // ==========================================
    private JComponent buildBatchTab() {
        rootField = Fields.text("", I18n.get("tool.fileencoding.placeholder.root"));
        JButton browseButton = Buttons.snug(I18n.get("tool.fileencoding.btn.browse"));
        includeField = Fields.mono(String.join(",", EncodingBatchService.DEFAULT_INCLUDES));
        excludeField = Fields.mono(String.join(",", EncodingBatchService.DEFAULT_EXCLUDED_DIRS));

        targetCombo = Fields.combo(targetLabels());
        targetCombo.setSelectedIndex(1);
        bomCombo = Fields.combo(bomLabels());
        bomCombo.setSelectedIndex(2);
        lineCombo = Fields.combo(lineLabels());
        replaceCheck = Fields.check(I18n.get("tool.fileencoding.option.replace"), false);
        JPanel targetRow = Layouts.wrapRow(targetCombo,
                Fields.label(I18n.get("tool.fileencoding.label.bom")), bomCombo,
                Fields.label(I18n.get("tool.fileencoding.label.lineEnding")), lineCombo, replaceCheck);

        backupCombo = Fields.combo(new String[]{
                I18n.get("tool.fileencoding.backup.none"),
                I18n.get("tool.fileencoding.backup.sibling"),
                I18n.get("tool.fileencoding.backup.mirror")});
        backupCombo.setSelectedIndex(1);
        backupDirField = Fields.text("", I18n.get("tool.fileencoding.placeholder.backupDir"));
        backupDirField.setColumns(22);
        backupDirButton = Buttons.snug(I18n.get("tool.fileencoding.btn.browse"));
        preserveTimeCheck = Fields.check(I18n.get("tool.fileencoding.option.preserveTime"), false);
        maxSizeSpinner = Fields.spinner((int) (EncodingBatchService.DEFAULT_MAX_BYTES / (1024 * 1024)), 1, 512, 1);
        JPanel backupRow = Layouts.wrapRow(backupCombo, backupDirField, backupDirButton, preserveTimeCheck,
                Fields.label(I18n.get("tool.fileencoding.label.maxSize")), maxSizeSpinner);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.fileencoding.label.root"), rootField, browseButton);
        form.row(I18n.get("tool.fileencoding.label.include"), includeField);
        form.row(I18n.get("tool.fileencoding.label.exclude"), excludeField);
        form.row(I18n.get("tool.fileencoding.label.target"), targetRow);
        form.row(I18n.get("tool.fileencoding.label.backup"), backupRow);
        form.caption(I18n.get("tool.fileencoding.caption.batch",
                Math.round(EncodingBatchService.DEFAULT_CONFIDENCE_THRESHOLD * 100)));

        Card settings = Card.titled(I18n.get("tool.fileencoding.card.settings"));
        settings.setContent(form);

        tableModel = new BatchTableModel();
        table = new JTable(tableModel);
        table.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        StatusRenderer renderer = new StatusRenderer();
        for (int column = 1; column < tableModel.getColumnCount(); column++) {
            table.getColumnModel().getColumn(column).setCellRenderer(renderer);
        }
        table.getColumnModel().getColumn(COL_CHARSET).setCellEditor(new CharsetCellEditor());
        int[] widths = {36, 320, 80, 110, 70, 50, 140, 260};
        for (int column = 0; column < widths.length; column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }

        JButton selectAll = Buttons.ghost(I18n.get("tool.fileencoding.btn.selectAll"));
        JButton selectNone = Buttons.ghost(I18n.get("tool.fileencoding.btn.selectNone"));
        scanButton = Buttons.secondary(I18n.get("tool.fileencoding.btn.scan"));
        convertButton = Buttons.primary(I18n.get("tool.fileencoding.btn.convert"));
        cancelButton = Buttons.danger(I18n.get("tool.fileencoding.btn.cancel"));
        convertButton.setEnabled(false);
        cancelButton.setEnabled(false);

        Card tableCard = Card.flush(I18n.get("tool.fileencoding.card.files"));
        tableCard.setContent(Fields.scroll(table));
        tableCard.addHeaderAction(selectAll);
        tableCard.addHeaderAction(selectNone);
        tableCard.addHeaderAction(cancelButton);
        tableCard.addHeaderAction(scanButton);
        tableCard.addHeaderAction(convertButton);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(false);
        summaryLabel = Fields.caption(I18n.get("tool.fileencoding.status.ready"));
        JPanel footer = Layouts.box(Tokens.SPACE_MD, 0);
        footer.add(summaryLabel, BorderLayout.CENTER);
        progressBar.setPreferredSize(new Dimension(180, 10));
        footer.add(progressBar, BorderLayout.EAST);
        tableCard.setFooter(footer);

        browseButton.addActionListener(event -> chooseDirectory(rootField));
        backupDirButton.addActionListener(event -> chooseDirectory(backupDirField));
        backupCombo.addActionListener(event -> syncBackupControls());
        selectAll.addActionListener(event -> tableModel.selectAll(true));
        selectNone.addActionListener(event -> tableModel.selectAll(false));
        scanButton.addActionListener(event -> startScan());
        convertButton.addActionListener(event -> startConvert());
        cancelButton.addActionListener(event -> cancelBatch());
        syncBackupControls();

        JPanel tab = Layouts.box(0, Tokens.SPACE_MD);
        tab.setBorder(new EmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        tab.add(settings, BorderLayout.NORTH);
        tab.add(tableCard, BorderLayout.CENTER);
        return tab;
    }

    private void syncBackupControls() {
        boolean mirror = backupCombo.getSelectedIndex() == 2;
        backupDirField.setEnabled(mirror);
        backupDirButton.setEnabled(mirror);
    }

    private void chooseDirectory(JTextField field) {
        JFileChooser chooser = new JFileChooser(field.getText().isBlank() ? lastDirectory : new File(field.getText()));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            lastDirectory = selected;
            field.setText(selected.getAbsolutePath());
        }
    }

    private TextFileConverter.Options batchConversion() {
        return conversionOptions(targetCombo, bomCombo, lineCombo, replaceCheck);
    }

    private void startScan() {
        String rootText = rootField.getText().trim();
        Path root;
        try {
            root = rootText.isEmpty() ? null : Path.of(rootText);
        } catch (RuntimeException invalid) {
            root = null;
        }
        if (root == null || !Files.isDirectory(root)) {
            UIUtils.warn(getView(), I18n.get("tool.fileencoding.error.noRoot"), null);
            return;
        }
        long maxBytes = ((Number) maxSizeSpinner.getValue()).longValue() * 1024L * 1024L;
        EncodingBatchService.ScanOptions options = new EncodingBatchService.ScanOptions(root,
                EncodingBatchService.splitPatterns(includeField.getText()),
                EncodingBatchService.splitPatterns(excludeField.getText()),
                maxBytes, EncodingBatchService.DEFAULT_MAX_FILES,
                EncodingBatchService.DEFAULT_CONFIDENCE_THRESHOLD, batchConversion());

        long ticket = beginBatch();
        AtomicBoolean cancellation = batchCancellation.get();
        progressBar.setIndeterminate(true);
        setSummary(I18n.get("tool.fileencoding.status.scanning", 0), Tokens.mutedForeground());

        SwingWorker<EncodingBatchService.ScanResult, Integer> worker = new SwingWorker<>() {
            @Override
            protected EncodingBatchService.ScanResult doInBackground() throws IOException {
                return service.scan(options, cancellation::get, (done, total, current) -> publish(done));
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (ticket == batchGeneration.get() && !chunks.isEmpty()) {
                    setSummary(I18n.get("tool.fileencoding.status.scanning",
                            chunks.get(chunks.size() - 1)), Tokens.mutedForeground());
                }
            }

            @Override
            protected void done() {
                if (!finishBatch(ticket, this)) {
                    return;
                }
                try {
                    EncodingBatchService.ScanResult result = get();
                    scannedRoot = result.root();
                    tableModel.setRows(result.rows());
                    describeScan(result);
                } catch (ExecutionException error) {
                    setSummary(I18n.get("tool.fileencoding.error.scan", Errors.describeRoot(error)), Tokens.danger());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        batchWorker.set(worker);
        worker.execute();
    }

    private void describeScan(EncodingBatchService.ScanResult result) {
        int convert = 0;
        int unchanged = 0;
        int confirm = 0;
        int fail = 0;
        int skip = 0;
        for (EncodingBatchService.ScanRow row : result.rows()) {
            switch (row.action()) {
                case CONVERT:
                    convert++;
                    break;
                case UNCHANGED:
                    unchanged++;
                    break;
                case NEEDS_CONFIRMATION:
                    confirm++;
                    break;
                case WILL_FAIL:
                    fail++;
                    break;
                default:
                    skip++;
                    break;
            }
        }
        StringBuilder text = new StringBuilder(I18n.get("tool.fileencoding.summary.scan",
                result.rows().size(), convert, unchanged, confirm, fail, skip));
        if (result.cancelled()) {
            text.append(' ').append(I18n.get("tool.fileencoding.summary.cancelled"));
        }
        if (result.limitReached()) {
            text.append(' ').append(I18n.get("tool.fileencoding.summary.limit", EncodingBatchService.DEFAULT_MAX_FILES));
        }
        setSummary(text.toString(), confirm > 0 || fail > 0 || result.cancelled() || result.limitReached()
                ? Tokens.warning() : Tokens.mutedForeground());
        convertButton.setEnabled(!result.rows().isEmpty());
    }

    private void startConvert() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<BatchRow> chosen = new ArrayList<>();
        int confirmed = 0;
        int predictedFailures = 0;
        for (BatchRow row : tableModel.rows) {
            if (row.selected && row.selectable()) {
                chosen.add(row);
                if (row.scan.action() == EncodingBatchService.Action.NEEDS_CONFIRMATION) {
                    confirmed++;
                }
                if (row.scan.action() == EncodingBatchService.Action.WILL_FAIL && row.override == null) {
                    predictedFailures++;
                }
            }
        }
        if (chosen.isEmpty()) {
            UIUtils.info(getView(), I18n.get("tool.fileencoding.error.nothingSelected"));
            return;
        }
        EncodingBatchService.BackupMode backup = backupMode();
        Path backupRoot = null;
        if (backup == EncodingBatchService.BackupMode.MIRROR) {
            String text = backupDirField.getText().trim();
            if (text.isEmpty()) {
                UIUtils.warn(getView(), I18n.get("tool.fileencoding.error.noBackupDir"), null);
                return;
            }
            backupRoot = Path.of(text);
        }
        String backupText = backup == EncodingBatchService.BackupMode.NONE
                ? I18n.get("tool.fileencoding.confirm.noBackup")
                : backup == EncodingBatchService.BackupMode.SIBLING
                ? I18n.get("tool.fileencoding.confirm.siblingBackup")
                : I18n.get("tool.fileencoding.confirm.mirrorBackup", String.valueOf(backupRoot));
        String message = I18n.get("tool.fileencoding.confirm.convert", chosen.size(), confirmed,
                predictedFailures, describeTarget(), backupText);
        if (!UIUtils.confirm(getView(), message, I18n.get("tool.fileencoding.confirm.title"))) {
            return;
        }

        List<EncodingBatchService.ConvertRequest> requests = new ArrayList<>(chosen.size());
        for (BatchRow row : chosen) {
            requests.add(new EncodingBatchService.ConvertRequest(row.scan, row.override));
        }
        long maxBytes = ((Number) maxSizeSpinner.getValue()).longValue() * 1024L * 1024L;
        EncodingBatchService.ConvertOptions options = new EncodingBatchService.ConvertOptions(batchConversion(),
                backup, scannedRoot, backupRoot, preserveTimeCheck.isSelected(), maxBytes);

        long ticket = beginBatch();
        AtomicBoolean cancellation = batchCancellation.get();
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(requests.size());
        progressBar.setValue(0);
        setSummary(I18n.get("tool.fileencoding.status.converting", 0, requests.size()), Tokens.mutedForeground());

        SwingWorker<EncodingBatchService.ConvertSummary, Integer> worker = new SwingWorker<>() {
            @Override
            protected EncodingBatchService.ConvertSummary doInBackground() {
                return service.convert(requests, options, cancellation::get,
                        (done, total, current) -> publish(done));
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (ticket == batchGeneration.get() && !chunks.isEmpty()) {
                    int done = chunks.get(chunks.size() - 1);
                    progressBar.setValue(done);
                    setSummary(I18n.get("tool.fileencoding.status.converting", done, requests.size()),
                            Tokens.mutedForeground());
                }
            }

            @Override
            protected void done() {
                if (!finishBatch(ticket, this)) {
                    return;
                }
                try {
                    EncodingBatchService.ConvertSummary summary = get();
                    List<EncodingBatchService.FileResult> results = summary.results();
                    for (int i = 0; i < results.size(); i++) {
                        BatchRow row = chosen.get(i);
                        row.result = results.get(i);
                        // 已写入的文件大小和时间都变了，再转一次只会被判为「扫描后已改动」
                        row.selected = false;
                    }
                    tableModel.fireTableDataChanged();
                    String text = I18n.get("tool.fileencoding.summary.convert", summary.converted(),
                            summary.unchanged(), summary.skipped(), summary.failed());
                    if (summary.cancelled()) {
                        text += ' ' + I18n.get("tool.fileencoding.summary.cancelled");
                    }
                    setSummary(text, summary.failed() > 0 ? Tokens.danger()
                            : summary.cancelled() ? Tokens.warning() : Tokens.success());
                } catch (ExecutionException error) {
                    setSummary(I18n.get("tool.fileencoding.error.convert", Errors.describeRoot(error)),
                            Tokens.danger());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        batchWorker.set(worker);
        worker.execute();
    }

    private String describeTarget() {
        String target = String.valueOf(targetCombo.getSelectedItem());
        return target + " / " + bomCombo.getSelectedItem() + " / " + lineCombo.getSelectedItem();
    }

    private EncodingBatchService.BackupMode backupMode() {
        switch (backupCombo.getSelectedIndex()) {
            case 0:
                return EncodingBatchService.BackupMode.NONE;
            case 2:
                return EncodingBatchService.BackupMode.MIRROR;
            default:
                return EncodingBatchService.BackupMode.SIBLING;
        }
    }

    /** 开始一次批量任务：作废上一次的结果、换一个新的取消标志、锁住会改变任务含义的控件。 */
    private long beginBatch() {
        AtomicBoolean previous = batchCancellation.getAndSet(new AtomicBoolean(false));
        if (previous != null) {
            previous.set(true);
        }
        long ticket = batchGeneration.incrementAndGet();
        setBatchRunning(true);
        return ticket;
    }

    /** 任务结束回到 EDT：只有仍是当前任务时才更新界面。 */
    private boolean finishBatch(long ticket, SwingWorker<?, ?> worker) {
        if (ticket != batchGeneration.get()) {
            return false;
        }
        batchWorker.compareAndSet(worker, null);
        setBatchRunning(false);
        return true;
    }

    private void setBatchRunning(boolean running) {
        scanButton.setEnabled(!running);
        convertButton.setEnabled(!running && tableModel.getRowCount() > 0);
        cancelButton.setEnabled(running);
        table.setEnabled(!running);
        if (!running) {
            progressBar.setIndeterminate(false);
        }
    }

    /**
     * 取消只设置标志：转换线程可能正处在原子替换中间，中断它没有好处，由服务在文件之间停下。
     */
    private void cancelBatch() {
        AtomicBoolean cancellation = batchCancellation.get();
        if (cancellation != null) {
            cancellation.set(true);
        }
        SwingWorker<?, ?> worker = batchWorker.get();
        if (worker != null && !worker.isDone() && summaryLabel != null) {
            setSummary(I18n.get("tool.fileencoding.status.cancelling"), Tokens.warning());
        }
    }

    private void setSummary(String text, Color color) {
        summaryLabel.setText(text);
        summaryLabel.setForeground(color);
    }

    // ---- 表格 ----
    private static final class BatchRow {
        final EncodingBatchService.ScanRow scan;
        boolean selected;
        Charset override;
        EncodingBatchService.FileResult result;

        BatchRow(EncodingBatchService.ScanRow scan) {
            this.scan = scan;
            this.selected = scan.action() == EncodingBatchService.Action.CONVERT;
        }

        boolean selectable() {
            return scan.action() != EncodingBatchService.Action.SKIP && scan.detection() != null
                    && !scan.detection().binary();
        }

        boolean needsConfirmation() {
            return scan.action() == EncodingBatchService.Action.NEEDS_CONFIRMATION && override == null;
        }

        Charset effectiveCharset() {
            if (override != null) {
                return override;
            }
            return scan.detection() == null ? null : scan.detection().charset();
        }
    }

    private final class BatchTableModel extends AbstractTableModel {
        private final List<BatchRow> rows = new ArrayList<>();
        private final String[] columns = {
                "",
                I18n.get("tool.fileencoding.col.path"),
                I18n.get("tool.fileencoding.col.size"),
                I18n.get("tool.fileencoding.col.charset"),
                I18n.get("tool.fileencoding.col.confidence"),
                I18n.get("tool.fileencoding.col.bom"),
                I18n.get("tool.fileencoding.col.lines"),
                I18n.get("tool.fileencoding.col.status")};

        void setRows(List<EncodingBatchService.ScanRow> scanRows) {
            rows.clear();
            for (EncodingBatchService.ScanRow row : scanRows) {
                rows.add(new BatchRow(row));
            }
            fireTableDataChanged();
        }

        void selectAll(boolean selected) {
            for (BatchRow row : rows) {
                if (!selected) {
                    row.selected = false;
                } else if (row.selectable() && row.result == null) {
                    // 全选不代替用户确认：低置信度的行仍需逐行确认或指定编码
                    row.selected = !row.needsConfirmation()
                            && row.scan.action() != EncodingBatchService.Action.UNCHANGED;
                }
            }
            fireTableDataChanged();
        }

        BatchRow row(int modelIndex) {
            return rows.get(modelIndex);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == COL_SELECTED ? Boolean.class : column == COL_SIZE ? Long.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int column) {
            BatchRow row = rows.get(rowIndex);
            return (column == COL_SELECTED || column == COL_CHARSET) && row.selectable() && row.result == null;
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            BatchRow row = rows.get(rowIndex);
            EncodingDetector.Result detection = row.scan.detection();
            boolean text = detection != null && !detection.binary();
            switch (column) {
                case COL_SELECTED:
                    return row.selected;
                case COL_PATH:
                    return row.scan.relativePath();
                case COL_SIZE:
                    return row.scan.size();
                case COL_CHARSET:
                    Charset charset = row.effectiveCharset();
                    return charset == null ? "-" : charset.name();
                case COL_CONFIDENCE:
                    return text ? Math.round(detection.confidence() * 100) + "%" : "-";
                case COL_BOM:
                    return text ? I18n.get(detection.bom() ? "tool.fileencoding.value.yes" : "tool.fileencoding.value.no")
                            : "-";
                case COL_LINES:
                    return describeLines(row.scan.lineStats());
                default:
                    return describeStatus(row);
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int column) {
            BatchRow row = rows.get(rowIndex);
            if (column == COL_SELECTED) {
                boolean selected = Boolean.TRUE.equals(value);
                // 勾选一行低置信度的文件，即视为确认采用探测到的编码
                if (selected && row.needsConfirmation()) {
                    row.override = row.effectiveCharset();
                }
                row.selected = selected;
            } else if (column == COL_CHARSET && value != null) {
                Charset charset = EncodingDetector.lookup(String.valueOf(value));
                if (charset != null) {
                    row.override = charset;
                    row.selected = true;
                }
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }

    private String describeStatus(BatchRow row) {
        EncodingBatchService.FileResult result = row.result;
        if (result != null) {
            String outcome = I18n.get("tool.fileencoding.outcome." + result.outcome().name().toLowerCase(Locale.ROOT));
            if (result.code() == EncodingBatchService.Code.NONE) {
                if (result.replacements() > 0) {
                    return outcome + " - " + I18n.get("tool.fileencoding.status.replaced", result.replacements());
                }
                return outcome;
            }
            return outcome + " - " + describeCode(result.code(), result.errorOffset(), result.errorLine(),
                    result.errorCodePoint(), result.detail());
        }
        EncodingBatchService.ScanRow scan = row.scan;
        if (row.override != null && scan.action() != EncodingBatchService.Action.UNCHANGED) {
            return I18n.get("tool.fileencoding.status.override", row.override.name());
        }
        String action = I18n.get("tool.fileencoding.action." + scan.action().name().toLowerCase(Locale.ROOT));
        if (scan.code() == EncodingBatchService.Code.NONE) {
            return action;
        }
        return action + " - " + describeCode(scan.code(), scan.errorOffset(), scan.errorLine(),
                scan.errorCodePoint(), null);
    }

    private static String describeCode(EncodingBatchService.Code code, long offset, int line, int codePoint,
                                       String detail) {
        return I18n.get("tool.fileencoding.code." + code.name().toLowerCase(Locale.ROOT),
                String.valueOf(offset), String.valueOf(line), describeCodePoint(codePoint),
                detail == null ? "" : detail);
    }

    private static String describeCodePoint(int codePoint) {
        if (codePoint < 0) {
            return "";
        }
        return String.format(Locale.ROOT, "U+%04X (%s)", codePoint, new String(Character.toChars(codePoint)));
    }

    private static String describeLines(TextFileConverter.LineStats stats) {
        if (stats == null) {
            return "-";
        }
        switch (stats.style()) {
            case NONE:
                return I18n.get("tool.fileencoding.lines.none");
            case MIXED:
                return I18n.get("tool.fileencoding.lines.mixed", String.valueOf(stats.crlf()),
                        String.valueOf(stats.lf()), String.valueOf(stats.cr()));
            default:
                return stats.style().name() + " (" + stats.total() + ")";
        }
    }

    /** 低置信度待确认的行加底色；失败标红、完成标绿、跳过置灰。 */
    private final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable target, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Object shown = value;
            if (column == COL_SIZE && value instanceof Long) {
                shown = formatSize((Long) value);
            }
            Component component = super.getTableCellRendererComponent(target, shown, isSelected, hasFocus, row, column);
            setHorizontalAlignment(column == COL_SIZE || column == COL_CONFIDENCE ? RIGHT : LEFT);
            if (isSelected) {
                return component;
            }
            BatchRow batchRow = tableModel.row(target.convertRowIndexToModel(row));
            Color background = target.getBackground();
            Color foreground = target.getForeground();
            if (batchRow.needsConfirmation() && batchRow.result == null) {
                background = Tokens.blend(background, Tokens.warning(), 0.18f);
            }
            if (batchRow.result != null) {
                switch (batchRow.result.outcome()) {
                    case CONVERTED:
                        foreground = Tokens.success();
                        break;
                    case FAILED:
                        foreground = Tokens.danger();
                        break;
                    default:
                        foreground = Tokens.mutedForeground();
                        break;
                }
            } else if (batchRow.scan.action() == EncodingBatchService.Action.WILL_FAIL && batchRow.override == null) {
                foreground = Tokens.danger();
            } else if (batchRow.scan.action() == EncodingBatchService.Action.SKIP
                    || batchRow.scan.action() == EncodingBatchService.Action.UNCHANGED) {
                foreground = Tokens.mutedForeground();
            }
            component.setBackground(background);
            component.setForeground(foreground);
            return component;
        }
    }

    /** 源编码单元格：每行的下拉项 = 该文件的探测候选 + 常用编码。 */
    private final class CharsetCellEditor extends DefaultCellEditor {
        CharsetCellEditor() {
            super(new JComboBox<String>());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Component getTableCellEditorComponent(JTable target, Object value, boolean isSelected, int row,
                                                     int column) {
            JComboBox<String> combo = (JComboBox<String>) getComponent();
            BatchRow batchRow = tableModel.row(target.convertRowIndexToModel(row));
            Set<String> choices = new LinkedHashSet<>();
            if (batchRow.scan.detection() != null) {
                for (EncodingDetector.Candidate candidate : batchRow.scan.detection().candidates()) {
                    choices.add(candidate.charset().name());
                }
            }
            for (String name : SOURCE_CHARSETS) {
                if (EncodingDetector.lookup(name) != null) {
                    choices.add(name);
                }
            }
            combo.setModel(new DefaultComboBoxModel<>(choices.toArray(new String[0])));
            combo.setSelectedItem(value);
            return combo;
        }
    }

    // ==========================================
    // 单文件
    // ==========================================
    /** 按某个编码解读文件的预览。candidate 为 null 表示不在探测候选中、由用户手动挑选的编码。 */
    /** 只保留严格解码的结论与截断后的预览：完整文本按候选数量成倍占内存，不能长期持有。 */
    private record Preview(Charset charset, EncodingDetector.Candidate candidate, boolean strictOk, long errorOffset,
                           int errorLine, String text, boolean clipped, TextFileConverter.LineStats stats) {
    }

    private record Loaded(String name, byte[] bytes, EncodingDetector.Result detection, List<Preview> previews) {
    }

    private JComponent buildSingleTab() {
        JButton openButton = Buttons.primary(I18n.get("tool.fileencoding.btn.open"));
        hexField = Fields.mono("");
        hexField.putClientProperty("JTextField.placeholderText", I18n.get("tool.fileencoding.placeholder.hex"));
        JButton hexButton = Buttons.snug(I18n.get("tool.fileencoding.btn.detectHex"));
        singleStatus = Fields.caption(I18n.get("tool.fileencoding.single.ready"));

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.fileencoding.label.hex"), hexField, hexButton);
        Card sourceCard = Card.titled(I18n.get("tool.fileencoding.card.source"),
                I18n.get("tool.fileencoding.card.source.subtitle"));
        sourceCard.setContent(form);
        sourceCard.addHeaderAction(openButton);
        sourceCard.setFooter(singleStatus);

        previewListModel = new DefaultListModel<>();
        previewList = new JList<>(previewListModel);
        previewList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Card listCard = Card.flush(I18n.get("tool.fileencoding.card.candidates"));
        listCard.setContent(Fields.scroll(previewList));

        previewArea = Fields.output(16, 40);
        previewArea.setLineWrap(false);
        previewStatus = Fields.caption(" ");
        Card previewCard = Card.flush(I18n.get("tool.fileencoding.card.preview"));
        previewCard.setContent(Fields.scroll(previewArea));
        previewCard.setFooter(previewStatus);

        saveTargetCombo = Fields.combo(targetLabels());
        saveTargetCombo.setSelectedIndex(1);
        saveBomCombo = Fields.combo(bomLabels());
        saveBomCombo.setSelectedIndex(2);
        saveLineCombo = Fields.combo(lineLabels());
        saveReplaceCheck = Fields.check(I18n.get("tool.fileencoding.option.replace"), false);
        JButton saveButton = Buttons.primary(I18n.get("tool.fileencoding.btn.saveAs"));
        FormGrid saveForm = new FormGrid();
        saveForm.row(I18n.get("tool.fileencoding.label.target"), Layouts.wrapRow(saveTargetCombo,
                Fields.label(I18n.get("tool.fileencoding.label.bom")), saveBomCombo,
                Fields.label(I18n.get("tool.fileencoding.label.lineEnding")), saveLineCombo, saveReplaceCheck));
        Card saveCard = Card.titled(I18n.get("tool.fileencoding.card.save"));
        saveCard.setContent(saveForm);
        saveCard.addHeaderAction(saveButton);

        openButton.addActionListener(event -> openSingleFile());
        hexButton.addActionListener(event -> detectHex());
        hexField.addActionListener(event -> detectHex());
        previewList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showPreview(previewList.getSelectedIndex());
            }
        });
        saveButton.addActionListener(event -> saveAs());

        JPanel tab = Layouts.box(0, Tokens.SPACE_MD);
        tab.setBorder(new EmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        tab.add(sourceCard, BorderLayout.NORTH);
        tab.add(Layouts.splitHorizontal(listCard, previewCard, 0.3, 0.3), BorderLayout.CENTER);
        tab.add(saveCard, BorderLayout.SOUTH);
        return tab;
    }

    private void openSingleFile() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        if (chooser.showOpenDialog(getView()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        lastDirectory = file.getParentFile();
        loadSingle(file.getName(), () -> {
            // 多读一个字节即可判断是否超限，不必相信可能随时变化的 File.length()
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] bytes = in.readNBytes((int) SINGLE_MAX_BYTES + 1);
                if (bytes.length > SINGLE_MAX_BYTES) {
                    throw new FileTooLargeException();
                }
                return bytes;
            }
        });
    }

    private void detectHex() {
        byte[] bytes = parseHex(hexField.getText());
        if (bytes == null || bytes.length == 0) {
            setSingleStatus(I18n.get("tool.fileencoding.error.hex"), Tokens.danger());
            return;
        }
        loadSingle(I18n.get("tool.fileencoding.single.hexSource"), () -> bytes);
    }

    /** 接受 {@code E4 B8 AD}、{@code e4b8ad}、{@code 0xE4,0xB8}、{@code \xE4\xB8} 等写法。 */
    static byte[] parseHex(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("(?i)0x|\\\\x", " ").replaceAll("[\\s,;:\\-]+", "");
        if (cleaned.isEmpty() || (cleaned.length() & 1) != 0) {
            return null;
        }
        byte[] bytes = new byte[cleaned.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(cleaned.charAt(2 * i), 16);
            int low = Character.digit(cleaned.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    @FunctionalInterface
    private interface ByteSource {
        byte[] read() throws IOException;
    }

    private void loadSingle(String name, ByteSource source) {
        long ticket = singleGeneration.incrementAndGet();
        SwingWorker<?, ?> previous = singleWorker.getAndSet(null);
        if (previous != null) {
            previous.cancel(true);
        }
        setSingleStatus(I18n.get("tool.fileencoding.single.loading", name), Tokens.mutedForeground());

        SwingWorker<Loaded, Void> worker = new SwingWorker<>() {
            @Override
            protected Loaded doInBackground() throws IOException {
                byte[] bytes = source.read();
                return analyzeSingle(name, bytes);
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != singleGeneration.get()) {
                    return;
                }
                singleWorker.compareAndSet(this, null);
                try {
                    applyLoaded(get());
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof FileTooLargeException) {
                        setSingleStatus(I18n.get("tool.fileencoding.error.tooLarge",
                                String.valueOf(SINGLE_MAX_BYTES / (1024 * 1024))), Tokens.danger());
                    } else {
                        setSingleStatus(I18n.get("tool.fileencoding.error.read", Errors.describeRoot(error)),
                                Tokens.danger());
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        singleWorker.set(worker);
        worker.execute();
    }

    /** 后台线程：探测，并为每个候选与常用编码各做一次严格解码和预览。 */
    private Loaded analyzeSingle(String name, byte[] bytes) {
        EncodingDetector.Result detection = detector.detect(bytes);
        List<Preview> previews = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EncodingDetector.Candidate candidate : detection.candidates()) {
            if (seen.add(candidate.charset().name())) {
                previews.add(preview(bytes, candidate.charset(), candidate));
            }
        }
        for (String charsetName : SOURCE_CHARSETS) {
            Charset charset = EncodingDetector.lookup(charsetName);
            if (charset != null && seen.add(charset.name())) {
                previews.add(preview(bytes, charset, null));
            }
        }
        return new Loaded(name, bytes, detection, previews);
    }

    private static Preview preview(byte[] bytes, Charset charset, EncodingDetector.Candidate candidate) {
        TextFileConverter.Decoded strict = TextFileConverter.decode(bytes, charset);
        String text;
        if (strict.ok()) {
            text = strict.text();
        } else {
            // 严格解码失败时仍给出替换字符版本，便于用户肉眼判断
            int bom = TextFileConverter.bomLength(bytes, charset);
            text = new String(bytes, bom, bytes.length - bom, charset);
        }
        TextFileConverter.LineStats stats = TextFileConverter.LineStats.of(text);
        boolean clipped = text.length() > PREVIEW_CHARS;
        return new Preview(charset, candidate, strict.ok(), strict.errorOffset(), strict.errorLine(),
                clipped ? text.substring(0, PREVIEW_CHARS) : text, clipped, stats);
    }

    private void applyLoaded(Loaded result) {
        loaded = result;
        previewListModel.clear();
        for (Preview preview : result.previews()) {
            previewListModel.addElement(describePreview(preview));
        }
        EncodingDetector.Result detection = result.detection();
        if (detection.binary()) {
            setSingleStatus(I18n.get("tool.fileencoding.single.binary", result.name(),
                    formatSize(result.bytes().length)), Tokens.warning());
        } else {
            setSingleStatus(I18n.get("tool.fileencoding.single.loaded", result.name(),
                    formatSize(result.bytes().length), detection.charset().name(),
                    Math.round(detection.confidence() * 100),
                    I18n.get(detection.bom() ? "tool.fileencoding.value.yes" : "tool.fileencoding.value.no")),
                    detection.confidence() < EncodingBatchService.DEFAULT_CONFIDENCE_THRESHOLD
                            ? Tokens.warning() : Tokens.mutedForeground());
        }
        if (!previewListModel.isEmpty()) {
            previewList.setSelectedIndex(0);
        } else {
            showPreview(-1);
        }
    }

    private String describePreview(Preview preview) {
        String label;
        if (preview.candidate() != null) {
            label = I18n.get("tool.fileencoding.single.candidate", preview.charset().name(),
                    Math.round(preview.candidate().confidence() * 100),
                    describeReason(preview.candidate().reason()));
        } else {
            label = preview.charset().name();
        }
        return preview.strictOk() ? label : label + " " + I18n.get("tool.fileencoding.single.invalidSuffix");
    }

    private static String describeReason(EncodingDetector.Reason reason) {
        return I18n.get("tool.fileencoding.reason." + reason.name().toLowerCase(Locale.ROOT));
    }

    private void showPreview(int index) {
        if (loaded == null || index < 0 || index >= loaded.previews().size()) {
            previewArea.setText("");
            previewStatus.setText(" ");
            return;
        }
        Preview preview = loaded.previews().get(index);
        previewArea.setText(preview.text());
        previewArea.setCaretPosition(0);
        StringBuilder status = new StringBuilder();
        if (preview.strictOk()) {
            status.append(I18n.get("tool.fileencoding.single.strictOk"));
        } else {
            status.append(I18n.get("tool.fileencoding.single.strictFailed",
                    String.valueOf(preview.errorOffset()), String.valueOf(preview.errorLine())));
        }
        status.append("  |  ").append(I18n.get("tool.fileencoding.single.lines", describeLines(preview.stats()),
                String.valueOf(preview.stats().crlf()), String.valueOf(preview.stats().lf()),
                String.valueOf(preview.stats().cr())));
        if (preview.clipped()) {
            status.append("  |  ").append(I18n.get("tool.fileencoding.single.clipped", PREVIEW_CHARS));
        }
        previewStatus.setText(status.toString());
        previewStatus.setForeground(preview.strictOk() ? Tokens.mutedForeground() : Tokens.danger());
    }

    private void saveAs() {
        int index = previewList.getSelectedIndex();
        if (loaded == null || index < 0 || index >= loaded.previews().size()) {
            UIUtils.info(getView(), I18n.get("tool.fileencoding.error.nothingLoaded"));
            return;
        }
        Preview preview = loaded.previews().get(index);
        if (!preview.strictOk()) {
            UIUtils.warn(getView(), I18n.get("tool.fileencoding.error.sourceInvalid", preview.charset().name(),
                    String.valueOf(preview.errorOffset())), null);
            return;
        }
        TextFileConverter.Result result = TextFileConverter.convert(loaded.bytes(), preview.charset(),
                conversionOptions(saveTargetCombo, saveBomCombo, saveLineCombo, saveReplaceCheck));
        if (result.status() == TextFileConverter.Status.FAILED) {
            UIUtils.warn(getView(), I18n.get("tool.fileencoding.error.unmappable",
                    describeCodePoint(result.errorCodePoint()), String.valueOf(result.errorLine()),
                    result.target().name()), null);
            return;
        }
        JFileChooser chooser = new JFileChooser(lastDirectory);
        if (chooser.showSaveDialog(getView()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        lastDirectory = target.getParentFile();
        if (target.exists() && !UIUtils.confirm(getView(),
                I18n.get("tool.fileencoding.confirm.overwrite", target.getName()),
                I18n.get("tool.fileencoding.confirm.title"))) {
            return;
        }
        try {
            Files.write(target.toPath(), result.output());
            String message = I18n.get("tool.fileencoding.single.saved", target.getName(), result.target().name(),
                    formatSize(result.output().length));
            if (result.replacements() > 0) {
                message += ' ' + I18n.get("tool.fileencoding.status.replaced", result.replacements());
            }
            setSingleStatus(message, Tokens.success());
        } catch (IOException error) {
            Errors.report(getView(), I18n.get("tool.fileencoding.error.write"), error);
        }
    }

    private void setSingleStatus(String text, Color color) {
        singleStatus.setText(text);
        singleStatus.setForeground(color);
    }

    // ==========================================
    // 乱码修复
    // ==========================================
    private JComponent buildMojibakeTab() {
        mojibakeInput = Fields.area(6, 40);
        mojibakeInput.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.fileencoding.placeholder.mojibake"));
        JButton repairButton = Buttons.primary(I18n.get("tool.fileencoding.btn.repair"));
        mojibakeStatus = Fields.caption(I18n.get("tool.fileencoding.mojibake.ready"));
        Card inputCard = Card.titled(I18n.get("tool.fileencoding.card.mojibakeInput"),
                I18n.get("tool.fileencoding.card.mojibakeInput.subtitle"));
        inputCard.setContent(Fields.scrollBoxed(mojibakeInput));
        inputCard.addHeaderAction(repairButton);
        inputCard.setFooter(mojibakeStatus);

        mojibakeListModel = new DefaultListModel<>();
        mojibakeList = new JList<>(mojibakeListModel);
        mojibakeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Card listCard = Card.flush(I18n.get("tool.fileencoding.card.repairCandidates"));
        listCard.setContent(Fields.scroll(mojibakeList));

        mojibakeOutput = Fields.output(8, 40);
        JButton copyButton = Buttons.snug(I18n.get("tool.fileencoding.btn.copy"));
        Card outputCard = Card.flush(I18n.get("tool.fileencoding.card.repaired"));
        outputCard.setContent(Fields.scroll(mojibakeOutput));
        outputCard.addHeaderAction(copyButton);

        repairButton.addActionListener(event -> runRepair());
        mojibakeList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                int index = mojibakeList.getSelectedIndex();
                mojibakeOutput.setText(index >= 0 && index < mojibakeCandidates.size()
                        ? mojibakeCandidates.get(index).text() : "");
                mojibakeOutput.setCaretPosition(0);
            }
        });
        copyButton.addActionListener(event -> {
            String text = mojibakeOutput.getText();
            if (text != null && !text.isEmpty()) {
                UIUtils.copyToClipboard(text);
            }
        });

        JPanel tab = Layouts.box();
        tab.setBorder(new EmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        tab.add(Layouts.splitVertical(inputCard,
                Layouts.splitHorizontal(listCard, outputCard, 0.4, 0.4), 0.35, 0.35), BorderLayout.CENTER);
        return tab;
    }

    void runRepair() {
        String input = mojibakeInput.getText();
        if (input == null || input.isEmpty()) {
            setMojibakeStatus(I18n.get("tool.fileencoding.mojibake.empty"), Tokens.warning());
            return;
        }
        MojibakeRepair.Report report = MojibakeRepair.repair(input);
        mojibakeCandidates = report.candidates();
        mojibakeListModel.clear();
        for (MojibakeRepair.Candidate candidate : report.candidates()) {
            mojibakeListModel.addElement(I18n.get("tool.fileencoding.mojibake.candidate",
                    Math.round(candidate.score() * 100), describeChain(candidate.chain())));
        }
        mojibakeOutput.setText("");
        StringBuilder status = new StringBuilder();
        if (report.candidates().isEmpty()) {
            status.append(I18n.get("tool.fileencoding.mojibake.none"));
        } else {
            status.append(I18n.get("tool.fileencoding.mojibake.found", report.candidates().size()));
        }
        if (report.irreversible()) {
            status.append(' ').append(I18n.get("tool.fileencoding.mojibake.irreversible",
                    report.replacementChars(), report.kaoMarkers()));
        }
        setMojibakeStatus(status.toString(), report.irreversible() ? Tokens.danger()
                : report.candidates().isEmpty() ? Tokens.warning() : Tokens.mutedForeground());
        if (!report.candidates().isEmpty()) {
            mojibakeList.setSelectedIndex(0);
        }
    }

    private static String describeChain(List<MojibakeRepair.Step> chain) {
        StringBuilder text = new StringBuilder();
        for (MojibakeRepair.Step step : chain) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(I18n.get("tool.fileencoding.mojibake.step", step.encodeAs(), step.decodeAs()));
        }
        return text.toString();
    }

    private void setMojibakeStatus(String text, Color color) {
        mojibakeStatus.setText(text);
        mojibakeStatus.setForeground(color);
    }

    // ==========================================
    // 公共
    // ==========================================
    private static String[] targetLabels() {
        String[] labels = new String[TARGET_CHARSETS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = TARGET_CHARSETS[i] == null ? I18n.get("tool.fileencoding.target.keep") : TARGET_CHARSETS[i];
        }
        return labels;
    }

    private static String[] bomLabels() {
        return new String[]{
                I18n.get("tool.fileencoding.bom.keep"),
                I18n.get("tool.fileencoding.bom.add"),
                I18n.get("tool.fileencoding.bom.remove")};
    }

    private static String[] lineLabels() {
        return new String[]{
                I18n.get("tool.fileencoding.line.keep"),
                I18n.get("tool.fileencoding.line.lf"),
                I18n.get("tool.fileencoding.line.crlf"),
                I18n.get("tool.fileencoding.line.cr")};
    }

    private static TextFileConverter.Options conversionOptions(JComboBox<String> target, JComboBox<String> bom,
                                                               JComboBox<String> line, JCheckBox replace) {
        int targetIndex = Math.max(0, target.getSelectedIndex());
        String targetName = TARGET_CHARSETS[targetIndex];
        Charset charset = targetName == null ? null : EncodingDetector.lookup(targetName);
        if (targetName != null && charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        TextFileConverter.BomMode bomMode = TextFileConverter.BomMode.values()[Math.max(0, bom.getSelectedIndex())];
        TextFileConverter.LineEnding ending = TextFileConverter.LineEnding.values()[Math.max(0, line.getSelectedIndex())];
        return new TextFileConverter.Options(charset, bomMode, ending, replace.isSelected());
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

    @Override
    public void closeResources() {
        AtomicBoolean cancellation = batchCancellation.getAndSet(null);
        if (cancellation != null) {
            cancellation.set(true);
        }
        batchGeneration.incrementAndGet();
        SwingWorker<?, ?> batch = batchWorker.getAndSet(null);
        if (batch != null && !batch.isDone()) {
            // 不中断：让正在进行的原子替换自然完成，服务会在下一个文件前看到取消标志
            batch.cancel(false);
        }
        singleGeneration.incrementAndGet();
        SwingWorker<?, ?> single = singleWorker.getAndSet(null);
        if (single != null && !single.isDone()) {
            single.cancel(true);
        }
    }

    private static final class FileTooLargeException extends IOException {
        FileTooLargeException() {
            super("file too large");
        }
    }
}
