package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.codec.domain.PageRangeParser;
import com.aqishi.toolbox.feature.codec.domain.PdfMergeItem;
import com.aqishi.toolbox.feature.codec.domain.PdfMerger;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 按照指定顺序合并面板。
 */
public final class PdfMergePanel extends ToolPanel implements ManagedResourceOwner {

    private static final String DEFAULT_OUTPUT_FILENAME = "合并.pdf";

    private JPanel root;
    private final List<PdfMergeItem> items = new ArrayList<>();
    private final PdfTableModel tableModel = new PdfTableModel();
    private JTable table;

    private JTextField outputPathField;
    private JButton browseOutputBtn;
    private JLabel summaryLabel;
    private JProgressBar progressBar;
    private JLabel statusLabel;

    private JButton selectAllBtn;
    private JButton selectInverseBtn;
    private JButton moveUpBtn;
    private JButton moveDownBtn;
    private JButton moveTopBtn;
    private JButton moveBottomBtn;
    private JButton sortAscBtn;
    private JButton sortDescBtn;
    private JButton removeBtn;
    private JButton clearBtn;

    private JButton startBtn;
    private JButton cancelBtn;
    private JButton openFileBtn;
    private JButton openDirBtn;

    private File lastDirectory;
    private File lastGeneratedFile;

    private SwingWorker<?, ?> currentWorker;
    private final AtomicBoolean cancellationFlag = new AtomicBoolean(false);

    public PdfMergePanel() {
        super(ToolCatalog.PDF_MERGE);
    }

    @Override
    protected JComponent build() {
        this.root = Layouts.page();

        // 1. 顶部控制栏
        Card topCard = Card.titled(I18n.get("tool.pdfmerge.toolbar.title", "文件列表与排序操作"));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, Tokens.SPACE_XS));
        toolbar.setOpaque(false);

        JButton addFilesBtn = Buttons.primary(I18n.get("tool.pdfmerge.btn.addFiles", "添加 PDF 文件..."));
        JButton addDirBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.addDir", "添加文件夹..."));
        selectAllBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.selectAll", "全选"));
        selectInverseBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.selectInverse", "反选"));
        moveUpBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.moveUp", "↑ 上移"));
        moveDownBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.moveDown", "↓ 下移"));
        moveTopBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.moveTop", "⇈ 置顶"));
        moveBottomBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.moveBottom", "⇊ 置底"));
        sortAscBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.sortAsc", "名称升序 A-Z"));
        sortDescBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.sortDesc", "名称降序 Z-A"));
        removeBtn = Buttons.ghost(I18n.get("tool.pdfmerge.btn.remove", "移除选中"));
        clearBtn = Buttons.ghost(I18n.get("tool.pdfmerge.btn.clear", "清空全部"));

        addFilesBtn.addActionListener(e -> chooseFiles());
        addDirBtn.addActionListener(e -> chooseDirectory());
        selectAllBtn.addActionListener(e -> selectAll(true));
        selectInverseBtn.addActionListener(e -> selectInverse());
        moveUpBtn.addActionListener(e -> moveSelected(-1));
        moveDownBtn.addActionListener(e -> moveSelected(1));
        moveTopBtn.addActionListener(e -> moveSelectedToEdge(true));
        moveBottomBtn.addActionListener(e -> moveSelectedToEdge(false));
        sortAscBtn.addActionListener(e -> sortItems(true));
        sortDescBtn.addActionListener(e -> sortItems(false));
        removeBtn.addActionListener(e -> removeSelected());
        clearBtn.addActionListener(e -> clearAll());

        toolbar.add(addFilesBtn);
        toolbar.add(addDirBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(selectAllBtn);
        toolbar.add(selectInverseBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(moveUpBtn);
        toolbar.add(moveDownBtn);
        toolbar.add(moveTopBtn);
        toolbar.add(moveBottomBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(sortAscBtn);
        toolbar.add(sortDescBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(removeBtn);
        toolbar.add(clearBtn);

        topCard.setContent(toolbar);
        root.add(topCard, BorderLayout.NORTH);

        // 2. 中间表格
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);

        // 设置列宽与对齐（0:选择, 1:#, 2:文件名, 3:页数, 4:提取范围, 5:大小, 6:文件路径）
        table.getColumnModel().getColumn(0).setPreferredWidth(45);
        table.getColumnModel().getColumn(0).setMaxWidth(55);
        table.getColumnModel().getColumn(1).setPreferredWidth(45);
        table.getColumnModel().getColumn(1).setMaxWidth(55);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setPreferredWidth(75);
        table.getColumnModel().getColumn(4).setPreferredWidth(120);
        table.getColumnModel().getColumn(5).setPreferredWidth(85);
        table.getColumnModel().getColumn(6).setPreferredWidth(280);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        table.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(4).setCellRenderer(centerRenderer);

        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(5).setCellRenderer(rightRenderer);

        table.getSelectionModel().addListSelectionListener(e -> updateButtonStates());

        // 支持拖拽文件进入
        enableDragAndDrop(table);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(800, 320));

        Card tableCard = Card.flush(I18n.get("tool.pdfmerge.table.title", "待合并 PDF 文件列表（勾选参与合并的文件，拖拽调序，双击“提取范围”可指定页码）"));
        tableCard.setContent(scrollPane);
        root.add(tableCard, BorderLayout.CENTER);

        // 3. 底部输出与操作
        Card bottomCard = Card.titled(I18n.get("tool.pdfmerge.output.title", "合并输出与执行"));
        FormGrid form = new FormGrid();

        summaryLabel = new JLabel(I18n.get("tool.pdfmerge.summary.empty", "尚未添加 PDF 文件"));
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD));
        form.fullRow(summaryLabel);

        outputPathField = Fields.text("");
        browseOutputBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.browse", "浏览..."));
        browseOutputBtn.addActionListener(e -> chooseOutputFile());

        JPanel outputPanel = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        outputPanel.setOpaque(false);
        outputPanel.add(outputPathField, BorderLayout.CENTER);
        outputPanel.add(browseOutputBtn, BorderLayout.EAST);
        form.row(I18n.get("tool.pdfmerge.output.label", "输出路径:"), outputPanel);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(Tokens.mutedForeground());

        JPanel progressPanel = new JPanel(new BorderLayout(0, 4));
        progressPanel.setOpaque(false);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(statusLabel, BorderLayout.SOUTH);
        form.fullRow(progressPanel);

        // 按钮栏
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        actionPanel.setOpaque(false);

        startBtn = Buttons.primary(I18n.get("tool.pdfmerge.btn.start", "开始合并"));
        cancelBtn = Buttons.ghost(I18n.get("tool.pdfmerge.btn.cancel", "取消"));
        cancelBtn.setEnabled(false);
        openFileBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.openFile", "打开合并文件"));
        openFileBtn.setEnabled(false);
        openDirBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.openDir", "打开所在文件夹"));
        openDirBtn.setEnabled(false);

        startBtn.addActionListener(e -> startMerge());
        cancelBtn.addActionListener(e -> cancelMerge());
        openFileBtn.addActionListener(e -> openGeneratedFile());
        openDirBtn.addActionListener(e -> openGeneratedDir());

        actionPanel.add(startBtn);
        actionPanel.add(cancelBtn);
        actionPanel.add(openFileBtn);
        actionPanel.add(openDirBtn);
        form.fullRow(actionPanel);

        bottomCard.setContent(form);
        root.add(bottomCard, BorderLayout.SOUTH);

        updateButtonStates();
        updateSummary();

        return root;
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(I18n.get("tool.pdfmerge.choose.title", "选择 PDF 文件"));
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF 文件 (*.pdf)", "pdf"));

        if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            File[] files = chooser.getSelectedFiles();
            List<File> toAdd = new ArrayList<>();
            if (files != null && files.length > 0) {
                toAdd.addAll(List.of(files));
            } else if (chooser.getSelectedFile() != null) {
                toAdd.add(chooser.getSelectedFile());
            }
            if (!toAdd.isEmpty()) {
                lastDirectory = toAdd.get(0).getParentFile();
                addFilesAsync(toAdd);
            }
        }
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(I18n.get("tool.pdfmerge.chooseDir.title", "选择包含 PDF 的文件夹"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            File dir = chooser.getSelectedFile();
            if (dir != null && dir.isDirectory()) {
                lastDirectory = dir;
                File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".pdf"));
                if (files != null && files.length > 0) {
                    addFilesAsync(List.of(files));
                } else {
                    JOptionPane.showMessageDialog(root,
                            I18n.get("tool.pdfmerge.msg.noPdfInDir", "该目录下未找到任何 .pdf 文件"),
                            I18n.get("tool.pdfmerge.title", "PDF 合并"),
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }
    }

    private void chooseOutputFile() {
        File defaultFile = new File(suggestDefaultOutputPath());
        File parentDir = defaultFile.getParentFile();
        if (parentDir == null || !parentDir.exists()) {
            parentDir = lastDirectory != null && lastDirectory.exists() ? lastDirectory : new File(System.getProperty("user.home"));
        }

        JFileChooser chooser = new JFileChooser(parentDir);
        chooser.setDialogTitle(I18n.get("tool.pdfmerge.chooseOutput.title", "选择合并后的保存路径"));
        chooser.setSelectedFile(new File(parentDir, DEFAULT_OUTPUT_FILENAME));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF 文件 (*.pdf)", "pdf"));

        if (chooser.showSaveDialog(root) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                file = new File(file.getParentFile(), file.getName() + ".pdf");
            }
            outputPathField.setText(file.getAbsolutePath());
            lastDirectory = file.getParentFile();
        }
    }

    private void addFilesAsync(List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        // 过滤非 pdf
        List<File> pdfFiles = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                pdfFiles.add(f);
            }
        }
        if (pdfFiles.isEmpty()) {
            return;
        }

        statusLabel.setText(I18n.get("tool.pdfmerge.status.reading", "正在读取 PDF 文件信息..."));

        new SwingWorker<List<PdfMergeItem>, Void>() {
            @Override
            protected List<PdfMergeItem> doInBackground() {
                List<PdfMergeItem> newItems = new ArrayList<>();
                for (File file : pdfFiles) {
                    int pages = 0;
                    try {
                        pages = PdfMerger.readPageCount(file);
                    } catch (Exception ignored) {
                    }
                    newItems.add(new PdfMergeItem(file, file.length(), pages, "全部"));
                }
                return newItems;
            }

            @Override
            protected void done() {
                try {
                    List<PdfMergeItem> result = get();
                    items.addAll(result);
                    tableModel.fireTableDataChanged();
                    if (!items.isEmpty()) {
                        table.setRowSelectionInterval(items.size() - 1, items.size() - 1);
                    }
                    updateDefaultOutputPath(false);
                    statusLabel.setText(" ");
                    updateButtonStates();
                    updateSummary();
                } catch (Exception e) {
                    statusLabel.setText(I18n.get("tool.pdfmerge.status.readError", "读取文件失败: ") + e.getMessage());
                }
            }
        }.execute();
    }

    private void moveSelected(int delta) {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return;
        }

        if (delta < 0) {
            // 上移，从前往后处理
            if (rows[0] == 0) {
                return;
            }
            for (int r : rows) {
                Collections.swap(items, r, r - 1);
            }
            tableModel.fireTableDataChanged();
            table.clearSelection();
            for (int r : rows) {
                table.addRowSelectionInterval(r - 1, r - 1);
            }
        } else if (delta > 0) {
            // 下移，从后往前处理
            if (rows[rows.length - 1] == items.size() - 1) {
                return;
            }
            for (int i = rows.length - 1; i >= 0; i--) {
                int r = rows[i];
                Collections.swap(items, r, r + 1);
            }
            tableModel.fireTableDataChanged();
            table.clearSelection();
            for (int r : rows) {
                table.addRowSelectionInterval(r + 1, r + 1);
            }
        }
        updateDefaultOutputPath(false);
        updateButtonStates();
    }

    private void moveSelectedToEdge(boolean toTop) {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0 || rows.length == items.size()) {
            return;
        }

        List<PdfMergeItem> selected = new ArrayList<>();
        List<PdfMergeItem> others = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (isSelectedRow(rows, i)) {
                selected.add(items.get(i));
            } else {
                others.add(items.get(i));
            }
        }

        items.clear();
        if (toTop) {
            items.addAll(selected);
            items.addAll(others);
            tableModel.fireTableDataChanged();
            table.setRowSelectionInterval(0, selected.size() - 1);
        } else {
            items.addAll(others);
            items.addAll(selected);
            tableModel.fireTableDataChanged();
            int start = items.size() - selected.size();
            table.setRowSelectionInterval(start, items.size() - 1);
        }
        updateDefaultOutputPath(false);
        updateButtonStates();
    }

    private boolean isSelectedRow(int[] rows, int r) {
        for (int row : rows) {
            if (row == r) return true;
        }
        return false;
    }

    private void sortItems(boolean ascending) {
        if (items.size() <= 1) {
            return;
        }
        Comparator<String> naturalComp = createNaturalComparator();
        items.sort((o1, o2) -> {
            int cmp = naturalComp.compare(o1.getFileName(), o2.getFileName());
            return ascending ? cmp : -cmp;
        });
        tableModel.fireTableDataChanged();
        updateDefaultOutputPath(false);
        updateButtonStates();
    }

    private void removeSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        for (int i = rows.length - 1; i >= 0; i--) {
            items.remove(rows[i]);
        }
        tableModel.fireTableDataChanged();
        updateDefaultOutputPath(false);
        updateButtonStates();
        updateSummary();
    }

    private void clearAll() {
        items.clear();
        tableModel.fireTableDataChanged();
        updateDefaultOutputPath(true);
        updateButtonStates();
        updateSummary();
    }

    private void selectAll(boolean select) {
        for (PdfMergeItem item : items) {
            item.setSelected(select);
        }
        tableModel.fireTableDataChanged();
        updateSummary();
        updateButtonStates();
    }

    private void selectInverse() {
        for (PdfMergeItem item : items) {
            item.setSelected(!item.isSelected());
        }
        tableModel.fireTableDataChanged();
        updateSummary();
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean hasItems = !items.isEmpty();
        int[] selectedRows = table.getSelectedRows();
        boolean hasSelection = selectedRows.length > 0;
        boolean isRunning = currentWorker != null && !currentWorker.isDone();
        boolean hasChecked = items.stream().anyMatch(PdfMergeItem::isSelected);

        selectAllBtn.setEnabled(!isRunning && hasItems);
        selectInverseBtn.setEnabled(!isRunning && hasItems);
        moveUpBtn.setEnabled(!isRunning && hasSelection && selectedRows[0] > 0);
        moveDownBtn.setEnabled(!isRunning && hasSelection && selectedRows[selectedRows.length - 1] < items.size() - 1);
        moveTopBtn.setEnabled(!isRunning && hasSelection && selectedRows[0] > 0);
        moveBottomBtn.setEnabled(!isRunning && hasSelection && selectedRows[selectedRows.length - 1] < items.size() - 1);
        sortAscBtn.setEnabled(!isRunning && items.size() > 1);
        sortDescBtn.setEnabled(!isRunning && items.size() > 1);
        removeBtn.setEnabled(!isRunning && hasSelection);
        clearBtn.setEnabled(!isRunning && hasItems);

        startBtn.setEnabled(!isRunning && hasChecked);
        cancelBtn.setEnabled(isRunning);
    }

    private void updateSummary() {
        if (items.isEmpty()) {
            summaryLabel.setText(I18n.get("tool.pdfmerge.summary.empty", "尚未添加 PDF 文件"));
            return;
        }
        int totalExpectedPages = 0;
        long totalBytes = 0;
        int selectedCount = 0;
        for (PdfMergeItem item : items) {
            if (item.isSelected()) {
                selectedCount++;
                totalExpectedPages += item.getEffectivePageCount();
                totalBytes += item.getFileSize();
            }
        }
        if (selectedCount == items.size()) {
            summaryLabel.setText(String.format(
                    I18n.get("tool.pdfmerge.summary.format", "已添加 %d 个 PDF 文件，预计合并 %d 页 (总源文件大小: %s)"),
                    items.size(), totalExpectedPages, formatFileSize(totalBytes)));
        } else {
            summaryLabel.setText(String.format(
                    I18n.get("tool.pdfmerge.summary.partialFormat", "已添加 %d 个 PDF 文件 (已勾选 %d 个)，预计合并 %d 页 (勾选源文件大小: %s)"),
                    items.size(), selectedCount, totalExpectedPages, formatFileSize(totalBytes)));
        }
    }

    private String suggestDefaultOutputPath() {
        File baseDir;
        if (!items.isEmpty()) {
            File firstFile = items.get(0).getFile();
            File parent = firstFile.getParentFile();
            baseDir = parent != null ? parent : firstFile.getParentFile();
        } else if (lastDirectory != null && lastDirectory.exists()) {
            baseDir = lastDirectory;
        } else {
            baseDir = new File(System.getProperty("user.home"));
        }
        return new File(baseDir, DEFAULT_OUTPUT_FILENAME).getAbsolutePath();
    }

    private void updateDefaultOutputPath(boolean force) {
        if (items.isEmpty()) {
            if (force) {
                outputPathField.setText("");
            }
            return;
        }
        String currentText = outputPathField.getText().trim();
        if (force || currentText.isEmpty() || currentText.endsWith(DEFAULT_OUTPUT_FILENAME)) {
            outputPathField.setText(suggestDefaultOutputPath());
        }
    }

    private void startMerge() {
        if (items.isEmpty()) {
            JOptionPane.showMessageDialog(root,
                    I18n.get("tool.pdfmerge.msg.noFiles", "请先添加待合并的 PDF 文件"),
                    I18n.get("tool.pdfmerge.title", "PDF 合并"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<PdfMergeItem> activeItems = new ArrayList<>();
        for (PdfMergeItem item : items) {
            if (item.isSelected()) {
                activeItems.add(item);
            }
        }
        if (activeItems.isEmpty()) {
            JOptionPane.showMessageDialog(root,
                    I18n.get("tool.pdfmerge.msg.noSelectedFiles", "请至少勾选一个待合并的 PDF 文件"),
                    I18n.get("tool.pdfmerge.title", "PDF 合并"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String outputPath = outputPathField.getText().trim();
        if (outputPath.isEmpty()) {
            outputPath = suggestDefaultOutputPath();
            outputPathField.setText(outputPath);
        }

        File outputFile = new File(outputPath);
        if (outputFile.exists()) {
            int ret = JOptionPane.showConfirmDialog(root,
                    I18n.get("tool.pdfmerge.msg.overwriteConfirm", "目标文件已存在，是否覆盖？\n" + outputFile.getAbsolutePath()),
                    I18n.get("tool.pdfmerge.msg.overwriteTitle", "确认覆盖"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (ret != JOptionPane.YES_OPTION) {
                return;
            }
        }

        cancellationFlag.set(false);
        progressBar.setVisible(true);
        progressBar.setValue(0);
        progressBar.setMaximum(activeItems.size());
        statusLabel.setText(I18n.get("tool.pdfmerge.status.starting", "正在准备合并..."));

        openFileBtn.setEnabled(false);
        openDirBtn.setEnabled(false);
        updateButtonStates();

        List<PdfMergeItem> snapshot = new ArrayList<>(activeItems);
        final File finalOutputFile = outputFile;

        currentWorker = new SwingWorker<Void, MergeProgress>() {
            @Override
            protected Void doInBackground() throws Exception {
                PdfMerger.merge(
                        snapshot,
                        finalOutputFile,
                        (cur, total, name) -> publish(new MergeProgress(cur, total, name)),
                        cancellationFlag::get
                );
                return null;
            }

            @Override
            protected void process(List<MergeProgress> chunks) {
                if (!chunks.isEmpty()) {
                    MergeProgress latest = chunks.get(chunks.size() - 1);
                    progressBar.setValue(latest.current);
                    statusLabel.setText(String.format(
                            I18n.get("tool.pdfmerge.status.merging", "正在合并 (%d/%d): %s"),
                            latest.current, latest.total, latest.fileName));
                }
            }

            @Override
            protected void done() {
                progressBar.setVisible(false);
                updateButtonStates();
                try {
                    get();
                    lastGeneratedFile = finalOutputFile;
                    openFileBtn.setEnabled(true);
                    openDirBtn.setEnabled(true);
                    int totalPages = 0;
                    try {
                        totalPages = PdfMerger.readPageCount(finalOutputFile);
                    } catch (Exception ignored) {
                    }
                    statusLabel.setText(String.format(
                            I18n.get("tool.pdfmerge.status.success", "合并完成！已生成 %s (共 %d 页, %s)"),
                            finalOutputFile.getName(), totalPages, formatFileSize(finalOutputFile.length())));
                    JOptionPane.showMessageDialog(root,
                            I18n.get("tool.pdfmerge.msg.success", "PDF 文件合并成功！\n保存至: ") + finalOutputFile.getAbsolutePath(),
                            I18n.get("tool.pdfmerge.title", "PDF 合并"),
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InterruptedException || cancellationFlag.get()) {
                        statusLabel.setText(I18n.get("tool.pdfmerge.status.cancelled", "合并已取消"));
                    } else {
                        statusLabel.setText(I18n.get("tool.pdfmerge.status.failed", "合并失败: ") + (cause != null ? cause.getMessage() : e.getMessage()));
                        JOptionPane.showMessageDialog(root,
                                I18n.get("tool.pdfmerge.msg.failed", "合并过程中发生错误: ") + (cause != null ? cause.getMessage() : e.getMessage()),
                                I18n.get("tool.pdfmerge.title", "PDF 合并"),
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        };

        currentWorker.execute();
        updateButtonStates();
    }

    private void cancelMerge() {
        cancellationFlag.set(true);
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
        }
        statusLabel.setText(I18n.get("tool.pdfmerge.status.cancelling", "正在取消..."));
    }

    private void openGeneratedFile() {
        if (lastGeneratedFile != null && lastGeneratedFile.exists()) {
            try {
                Desktop.getDesktop().open(lastGeneratedFile);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(root,
                        I18n.get("tool.pdfmerge.msg.openError", "无法打开文件: ") + e.getMessage(),
                        I18n.get("tool.pdfmerge.title", "PDF 合并"),
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void openGeneratedDir() {
        if (lastGeneratedFile != null) {
            File dir = lastGeneratedFile.getParentFile();
            if (dir != null && dir.exists()) {
                try {
                    Desktop.getDesktop().open(dir);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(root,
                            I18n.get("tool.pdfmerge.msg.openError", "无法打开目录: ") + e.getMessage(),
                            I18n.get("tool.pdfmerge.title", "PDF 合并"),
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void enableDragAndDrop(Component comp) {
        new DropTarget(comp, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = dtde.getTransferable();
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        List<File> droppedFiles = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                        if (droppedFiles != null && !droppedFiles.isEmpty()) {
                            List<File> toAdd = new ArrayList<>();
                            for (File f : droppedFiles) {
                                if (f.isDirectory()) {
                                    File[] sub = f.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".pdf"));
                                    if (sub != null) {
                                        Collections.addAll(toAdd, sub);
                                    }
                                } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                                    toAdd.add(f);
                                }
                            }
                            if (!toAdd.isEmpty()) {
                                lastDirectory = toAdd.get(0).getParentFile();
                                addFilesAsync(toAdd);
                            }
                        }
                    }
                    dtde.dropComplete(true);
                } catch (Exception ex) {
                    dtde.dropComplete(false);
                }
            }
        });
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static Comparator<String> createNaturalComparator() {
        Pattern pattern = Pattern.compile("(\\d+)|(\\D+)");
        return (s1, s2) -> {
            Matcher m1 = pattern.matcher(s1);
            Matcher m2 = pattern.matcher(s2);
            while (m1.find() && m2.find()) {
                String token1 = m1.group();
                String token2 = m2.group();
                int cmp;
                if (Character.isDigit(token1.charAt(0)) && Character.isDigit(token2.charAt(0))) {
                    try {
                        long n1 = Long.parseLong(token1);
                        long n2 = Long.parseLong(token2);
                        cmp = Long.compare(n1, n2);
                    } catch (NumberFormatException e) {
                        cmp = token1.compareTo(token2);
                    }
                } else {
                    cmp = token1.compareToIgnoreCase(token2);
                }
                if (cmp != 0) return cmp;
            }
            return Integer.compare(s1.length(), s2.length());
        };
    }

    @Override
    public void closeResources() {
        cancelMerge();
    }

    private static class MergeProgress {
        final int current;
        final int total;
        final String fileName;

        MergeProgress(int current, int total, String fileName) {
            this.current = current;
            this.total = total;
            this.fileName = fileName;
        }
    }

    private class PdfTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {
                I18n.get("tool.pdfmerge.col.select", "选择"),
                "#",
                I18n.get("tool.pdfmerge.col.fileName", "文件名"),
                I18n.get("tool.pdfmerge.col.pages", "页数"),
                I18n.get("tool.pdfmerge.col.range", "提取范围"),
                I18n.get("tool.pdfmerge.col.size", "大小"),
                I18n.get("tool.pdfmerge.col.path", "文件路径")
        };

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 0) {
                return Boolean.class;
            }
            return super.getColumnClass(columnIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            // 第 0 列选择框，第 4 列提取范围允许编辑
            return columnIndex == 0 || columnIndex == 4;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PdfMergeItem item = items.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return item.isSelected();
                case 1:
                    return rowIndex + 1;
                case 2:
                    return item.getFileName();
                case 3:
                    return item.getPageCount() > 0 ? String.valueOf(item.getPageCount()) : "-";
                case 4:
                    return item.getPageRange().isEmpty() ? "全部" : item.getPageRange();
                case 5:
                    return formatFileSize(item.getFileSize());
                case 6:
                    return item.getAbsolutePath();
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            PdfMergeItem item = items.get(rowIndex);
            if (columnIndex == 0 && aValue instanceof Boolean) {
                item.setSelected((Boolean) aValue);
                fireTableCellUpdated(rowIndex, columnIndex);
                updateSummary();
                updateButtonStates();
            } else if (columnIndex == 4 && aValue != null) {
                String val = aValue.toString().trim();
                if (val.isEmpty() || "全部".equalsIgnoreCase(val) || "all".equalsIgnoreCase(val)) {
                    item.setPageRange("全部");
                } else {
                    try {
                        PageRangeParser.parse(val, item.getPageCount());
                        item.setPageRange(val);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(root,
                                I18n.get("tool.pdfmerge.msg.rangeError", "页码范围格式无效: ") + ex.getMessage(),
                                I18n.get("tool.pdfmerge.title", "PDF 合并"),
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                }
                fireTableCellUpdated(rowIndex, columnIndex);
                updateSummary();
            }
        }
    }
}
