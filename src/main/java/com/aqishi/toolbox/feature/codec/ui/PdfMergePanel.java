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

import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 页面整理与合并面板。
 *
 * <p>能力：按系统导入顺序合并多个 PDF、删除列表中的文件、在页面整理对话框里删除任意页、
 * 行内拖拽调序、外部文件拖入到指定位置、带打开口令的源文件（输出恒不加密）。</p>
 *
 * <p>所有"删除"只影响合并结果，不修改磁盘上的源文件。</p>
 */
public final class PdfMergePanel extends ToolPanel implements ManagedResourceOwner {

    private static final String ROW_PREFIX = "java-toolbox-pdf-row:";
    private static final DataFlavor ROW_FLAVOR = createRowFlavor();
    private static final int MAX_DIRECTORY_DEPTH = 8;

    private JPanel root;
    private final List<PdfMergeItem> items = new ArrayList<>();
    private final PdfTableModel tableModel = new PdfTableModel();
    private final List<SwingWorker<?, ?>> readWorkers = new CopyOnWriteArrayList<>();
    private JTable table;

    private javax.swing.JTextField outputPathField;
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
    private JButton pageEditBtn;
    private JButton passwordBtn;
    private JButton removeBtn;
    private JButton clearBtn;

    private JButton startBtn;
    private JButton cancelBtn;
    private JButton openFileBtn;
    private JButton openDirBtn;

    private File lastDirectory;
    private File lastGeneratedFile;
    private String autoOutputPath = "";

    private SwingWorker<?, ?> currentWorker;
    private final AtomicBoolean cancellationFlag = new AtomicBoolean(false);

    public PdfMergePanel() {
        super(ToolCatalog.PDF_MERGE);
    }

    @Override
    protected JComponent build() {
        this.root = Layouts.page();

        Card topCard = Card.titled(I18n.get("tool.pdfmerge.toolbar.title"));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, Tokens.SPACE_XS));
        toolbar.setOpaque(false);

        JButton addFilesBtn = Buttons.primary(I18n.get("tool.pdfmerge.btn.addFiles"));
        JButton addDirBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.addDir"));
        selectAllBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.selectAll"));
        selectInverseBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.selectInverse"));
        moveUpBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.moveUp"));
        moveDownBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.moveDown"));
        moveTopBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.moveTop"));
        moveBottomBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.moveBottom"));
        sortAscBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.sortAsc"));
        sortDescBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.sortDesc"));
        pageEditBtn = Buttons.primary(I18n.get("tool.pdfmerge.btn.pageEdit"));
        passwordBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.password"));
        removeBtn = Buttons.ghost(I18n.get("tool.pdfmerge.btn.remove"));
        clearBtn = Buttons.ghost(I18n.get("tool.pdfmerge.btn.clear"));

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
        pageEditBtn.addActionListener(e -> openPageEditor(table.getSelectedRow()));
        passwordBtn.addActionListener(e -> promptPassword(table.getSelectedRow()));
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
        toolbar.add(pageEditBtn);
        toolbar.add(passwordBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(removeBtn);
        toolbar.add(clearBtn);

        topCard.setContent(toolbar);
        root.add(topCard, BorderLayout.NORTH);

        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);
        installTransferHandler();
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)
                        && table.columnAtPoint(e.getPoint()) != 0) {
                    openPageEditor(table.rowAtPoint(e.getPoint()));
                }
            }
        });

        table.getColumnModel().getColumn(0).setPreferredWidth(45);
        table.getColumnModel().getColumn(0).setMaxWidth(55);
        table.getColumnModel().getColumn(1).setPreferredWidth(45);
        table.getColumnModel().getColumn(1).setMaxWidth(55);
        table.getColumnModel().getColumn(2).setPreferredWidth(240);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(140);
        table.getColumnModel().getColumn(5).setPreferredWidth(85);
        table.getColumnModel().getColumn(6).setPreferredWidth(260);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        table.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(4).setCellRenderer(centerRenderer);
        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(5).setCellRenderer(rightRenderer);

        table.getSelectionModel().addListSelectionListener(e -> updateButtonStates());

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(800, 300));

        Card tableCard = Card.flush(I18n.get("tool.pdfmerge.table.title"));
        tableCard.setContent(scrollPane);
        root.add(tableCard, BorderLayout.CENTER);

        Card bottomCard = Card.titled(I18n.get("tool.pdfmerge.output.title"));
        FormGrid form = new FormGrid();

        summaryLabel = new JLabel(I18n.get("tool.pdfmerge.summary.empty"));
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD));
        form.fullRow(summaryLabel);

        outputPathField = Fields.text("");
        JButton browseOutputBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.browse"));
        browseOutputBtn.addActionListener(e -> chooseOutputFile());
        JPanel outputPanel = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        outputPanel.setOpaque(false);
        outputPanel.add(outputPathField, BorderLayout.CENTER);
        outputPanel.add(browseOutputBtn, BorderLayout.EAST);
        form.row(I18n.get("tool.pdfmerge.output.label"), outputPanel);

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

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        actionPanel.setOpaque(false);
        startBtn = Buttons.primary(I18n.get("tool.pdfmerge.btn.start"));
        cancelBtn = Buttons.ghost(I18n.get("tool.pdfmerge.btn.cancel"));
        cancelBtn.setEnabled(false);
        openFileBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.openFile"));
        openFileBtn.setEnabled(false);
        openDirBtn = Buttons.secondary(I18n.get("tool.pdfmerge.btn.openDir"));
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

    /** 统一处理"外部文件拖入"与"表格行内拖拽调序"，避免两套 DnD 机制争抢事件。 */
    private void installTransferHandler() {
        table.setDragEnabled(true);
        table.setDropMode(DropMode.INSERT_ROWS);
        table.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return MOVE;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                int row = table.getSelectedRow();
                return row < 0 ? null : new RowTransferable(ROW_PREFIX + row);
            }

            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || support.isDataFlavorSupported(ROW_FLAVOR);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                int insertRow = -1;
                if (support.getDropLocation() instanceof JTable.DropLocation) {
                    insertRow = ((JTable.DropLocation) support.getDropLocation()).getRow();
                }
                try {
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        List<File> dropped = (List<File>) support.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        List<File> pdfs = collectPdfs(dropped);
                        if (!pdfs.isEmpty()) {
                            addFilesAsync(pdfs, insertRow);
                        } else {
                            statusLabel.setText(I18n.get("tool.pdfmerge.status.noNewPdf"));
                        }
                        return true;
                    }
                    Object payload = support.getTransferable().getTransferData(ROW_FLAVOR);
                    if (payload instanceof String && ((String) payload).startsWith(ROW_PREFIX)) {
                        int from = Integer.parseInt(((String) payload).substring(ROW_PREFIX.length()));
                        moveRow(from, insertRow);
                        return true;
                    }
                } catch (Exception ex) {
                    statusLabel.setText(I18n.get("tool.pdfmerge.status.dropError") + ex.getMessage());
                }
                return false;
            }
        });
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(I18n.get("tool.pdfmerge.choose.title"));
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF files (*.pdf)", "pdf"));
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
                addFilesAsync(collectPdfs(toAdd), -1);
            }
        }
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(I18n.get("tool.pdfmerge.chooseDir.title"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            File dir = chooser.getSelectedFile();
            if (dir != null && dir.isDirectory()) {
                lastDirectory = dir;
                List<File> files = collectPdfs(List.of(dir));
                if (!files.isEmpty()) {
                    addFilesAsync(files, -1);
                } else {
                    JOptionPane.showMessageDialog(root,
                            I18n.get("tool.pdfmerge.msg.noPdfInDir"),
                            I18n.get("tool.pdfmerge.title"),
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }
    }

    /** 保持系统返回的顺序，不排序；过滤非 PDF、递归目录、与已有项去重。 */
    private List<File> collectPdfs(List<File> inputs) {
        List<File> collected = new ArrayList<>();
        if (inputs != null) {
            for (File file : inputs) {
                if (file == null) {
                    continue;
                }
                if (file.isDirectory()) {
                    collectDirectory(file, collected, 0);
                } else if (isPdf(file)) {
                    collected.add(file);
                }
            }
        }
        Set<String> seen = new HashSet<>();
        for (PdfMergeItem item : items) {
            seen.add(normalizeKey(item.getFile()));
        }
        List<File> unique = new ArrayList<>();
        for (File file : collected) {
            if (seen.add(normalizeKey(file))) {
                unique.add(file);
            }
        }
        return unique;
    }

    private void collectDirectory(File dir, List<File> out, int depth) {
        if (depth > MAX_DIRECTORY_DEPTH) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectDirectory(child, out, depth + 1);
            } else if (isPdf(child)) {
                out.add(child);
            }
        }
    }

    private void addFilesAsync(List<File> files, int insertRow) {
        if (files == null || files.isEmpty()) {
            statusLabel.setText(I18n.get("tool.pdfmerge.status.noNewPdf"));
            return;
        }
        statusLabel.setText(I18n.get("tool.pdfmerge.status.reading"));

        SwingWorker<List<PdfMergeItem>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<PdfMergeItem> doInBackground() {
                List<PdfMergeItem> result = new ArrayList<>();
                for (File file : files) {
                    PdfMerger.ProbeResult probe = PdfMerger.probe(file, null);
                    PdfMergeItem item = new PdfMergeItem(file, file.length(), probe.getPageCount(), "");
                    item.setEncrypted(probe.isEncrypted());
                    if (probe.isNeedsPassword()) {
                        item.setLoadError(I18n.get("tool.pdfmerge.state.needPassword"));
                    } else if (probe.getError() != null) {
                        item.setLoadError(probe.getError());
                    }
                    result.add(item);
                }
                return result;
            }

            @Override
            protected void done() {
                readWorkers.remove(this);
                try {
                    List<PdfMergeItem> loaded = get();
                    int at = insertRow < 0 ? items.size() : Math.min(insertRow, items.size());
                    items.addAll(at, loaded);
                    tableModel.fireTableDataChanged();
                    if (!loaded.isEmpty()) {
                        table.setRowSelectionInterval(at, Math.min(items.size() - 1, at + loaded.size() - 1));
                    }
                    int needPassword = (int) loaded.stream().filter(PdfMergeItem::isPasswordRequired).count();
                    int failed = (int) loaded.stream()
                            .filter(it -> !it.isReadable() && !it.isPasswordRequired()).count();
                    if (needPassword > 0 || failed > 0) {
                        statusLabel.setText(String.format(
                                I18n.get("tool.pdfmerge.status.addedWithIssues"),
                                loaded.size(), needPassword, failed));
                    } else {
                        statusLabel.setText(String.format(
                                I18n.get("tool.pdfmerge.status.added"),
                                loaded.size()));
                    }
                    updateDefaultOutputPath(false);
                    updateButtonStates();
                    updateSummary();
                } catch (Exception e) {
                    statusLabel.setText(I18n.get("tool.pdfmerge.status.readError") + e.getMessage());
                }
            }
        };
        readWorkers.add(worker);
        worker.execute();
    }

    private void promptPassword(int row) {
        if (row < 0 || row >= items.size()) {
            return;
        }
        PdfMergeItem item = items.get(row);
        JPanel panel = new JPanel(new BorderLayout(Tokens.SPACE_SM, Tokens.SPACE_SM));
        panel.add(new JLabel(String.format(
                I18n.get("tool.pdfmerge.msg.passwordPrompt"),
                item.getFileName())), BorderLayout.NORTH);
        JPasswordField field = new JPasswordField(20);
        panel.add(field, BorderLayout.CENTER);
        int ret = JOptionPane.showConfirmDialog(root, panel,
                I18n.get("tool.pdfmerge.msg.passwordTitle"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ret != JOptionPane.OK_OPTION) {
            return;
        }
        String password = new String(field.getPassword());
        field.setText("");
        item.setPassword(password.isEmpty() ? null : password);

        SwingWorker<PdfMerger.ProbeResult, Void> worker = new SwingWorker<>() {
            @Override
            protected PdfMerger.ProbeResult doInBackground() {
                return PdfMerger.probe(item.getFile(), item.getPassword());
            }

            @Override
            protected void done() {
                readWorkers.remove(this);
                try {
                    PdfMerger.ProbeResult probe = get();
                    if (probe.isNeedsPassword()) {
                        item.setLoadError(I18n.get("tool.pdfmerge.state.needPassword"));
                        JOptionPane.showMessageDialog(root,
                                I18n.get("tool.pdfmerge.msg.badPassword"),
                                I18n.get("tool.pdfmerge.title"), JOptionPane.WARNING_MESSAGE);
                    } else if (probe.getError() != null) {
                        item.setLoadError(probe.getError());
                        statusLabel.setText(I18n.get("tool.pdfmerge.status.readError") + probe.getError());
                    } else {
                        item.applyLoadedPageCount(probe.getPageCount());
                        item.setEncrypted(probe.isEncrypted());
                        statusLabel.setText(String.format(
                                I18n.get("tool.pdfmerge.status.passwordOk"),
                                item.getFileName(), probe.getPageCount()));
                    }
                    tableModel.fireTableDataChanged();
                    updateButtonStates();
                    updateSummary();
                } catch (Exception e) {
                    statusLabel.setText(I18n.get("tool.pdfmerge.status.readError") + e.getMessage());
                }
            }
        };
        readWorkers.add(worker);
        worker.execute();
    }

    private void openPageEditor(int row) {
        if (row < 0 || row >= items.size()) {
            return;
        }
        PdfMergeItem item = items.get(row);
        if (!item.isReadable()) {
            promptPassword(row);
            return;
        }
        PdfPageEditorDialog dialog = new PdfPageEditorDialog(
                SwingUtilities.getWindowAncestor(root), item);
        if (dialog.showDialog()) {
            tableModel.fireTableDataChanged();
            updateSummary();
            statusLabel.setText(String.format(
                    I18n.get("tool.pdfmerge.status.pagesUpdated"),
                    item.getFileName(), item.getEffectivePageCount()));
        }
    }

    private void moveRow(int from, int insertBefore) {
        if (from < 0 || from >= items.size()) {
            return;
        }
        int target = insertBefore < 0 ? items.size() : insertBefore;
        PdfMergeItem moved = items.remove(from);
        if (target > from) {
            target--;
        }
        target = Math.max(0, Math.min(items.size(), target));
        items.add(target, moved);
        tableModel.fireTableDataChanged();
        table.setRowSelectionInterval(target, target);
        updateDefaultOutputPath(false);
        updateButtonStates();
    }

    private void chooseOutputFile() {
        File defaultFile = nextAvailableOutput();
        JFileChooser chooser = new JFileChooser(defaultFile.getParentFile());
        chooser.setDialogTitle(I18n.get("tool.pdfmerge.chooseOutput.title"));
        chooser.setSelectedFile(defaultFile);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF files (*.pdf)", "pdf"));
        if (chooser.showSaveDialog(root) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                file = new File(file.getParentFile(), file.getName() + ".pdf");
            }
            autoOutputPath = "";
            outputPathField.setText(file.getAbsolutePath());
            lastDirectory = file.getParentFile();
        }
    }

    private File suggestOutputDirectory() {
        if (!items.isEmpty()) {
            File parent = items.get(0).getFile().getParentFile();
            if (parent != null) {
                return parent;
            }
        }
        if (lastDirectory != null && lastDirectory.exists()) {
            return lastDirectory;
        }
        return new File(System.getProperty("user.home"));
    }

    /** 默认输出名走本地化键，且永不覆盖：冲突时自动加序号。 */
    private File nextAvailableOutput() {
        File dir = suggestOutputDirectory();
        String base = I18n.get("tool.pdfmerge.output.defaultName");
        File candidate = new File(dir, base + ".pdf");
        if (!candidate.exists()) {
            return candidate;
        }
        for (int i = 1; i < 10000; i++) {
            candidate = new File(dir, base + "_" + i + ".pdf");
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ".pdf");
    }

    private void updateDefaultOutputPath(boolean force) {
        if (items.isEmpty()) {
            if (force) {
                outputPathField.setText("");
                autoOutputPath = "";
            }
            return;
        }
        String current = outputPathField.getText().trim();
        if (force || current.isEmpty() || current.equals(autoOutputPath)) {
            autoOutputPath = nextAvailableOutput().getAbsolutePath();
            outputPathField.setText(autoOutputPath);
        }
    }

    private void moveSelected(int delta) {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        if (delta < 0) {
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
        } else {
            if (rows[rows.length - 1] == items.size() - 1) {
                return;
            }
            for (int i = rows.length - 1; i >= 0; i--) {
                Collections.swap(items, rows[i], rows[i] + 1);
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
            (isSelectedRow(rows, i) ? selected : others).add(items.get(i));
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
            if (row == r) {
                return true;
            }
        }
        return false;
    }

    private void sortItems(boolean ascending) {
        if (items.size() <= 1) {
            return;
        }
        Comparator<String> natural = createNaturalComparator();
        items.sort((o1, o2) -> {
            int cmp = natural.compare(o1.getFileName(), o2.getFileName());
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
        boolean singleSelection = selectedRows.length == 1
                && selectedRows[0] >= 0 && selectedRows[0] < items.size();

        selectAllBtn.setEnabled(!isRunning && hasItems);
        selectInverseBtn.setEnabled(!isRunning && hasItems);
        moveUpBtn.setEnabled(!isRunning && hasSelection && selectedRows[0] > 0);
        moveDownBtn.setEnabled(!isRunning && hasSelection
                && selectedRows[selectedRows.length - 1] < items.size() - 1);
        moveTopBtn.setEnabled(!isRunning && hasSelection && selectedRows[0] > 0);
        moveBottomBtn.setEnabled(!isRunning && hasSelection
                && selectedRows[selectedRows.length - 1] < items.size() - 1);
        sortAscBtn.setEnabled(!isRunning && items.size() > 1);
        sortDescBtn.setEnabled(!isRunning && items.size() > 1);
        pageEditBtn.setEnabled(!isRunning && singleSelection && items.get(selectedRows[0]).isReadable());
        passwordBtn.setEnabled(!isRunning && singleSelection && items.get(selectedRows[0]).isEncrypted());
        removeBtn.setEnabled(!isRunning && hasSelection);
        clearBtn.setEnabled(!isRunning && hasItems);
        startBtn.setEnabled(!isRunning && hasChecked);
        cancelBtn.setEnabled(isRunning);
    }

    private void updateSummary() {
        if (items.isEmpty()) {
            summaryLabel.setText(I18n.get("tool.pdfmerge.summary.empty"));
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
                    I18n.get("tool.pdfmerge.summary.format"),
                    items.size(), totalExpectedPages, formatFileSize(totalBytes)));
        } else {
            summaryLabel.setText(String.format(
                    I18n.get("tool.pdfmerge.summary.partialFormat"),
                    items.size(), selectedCount, totalExpectedPages, formatFileSize(totalBytes)));
        }
    }

    private void startMerge() {
        List<PdfMergeItem> activeItems = new ArrayList<>();
        for (PdfMergeItem item : items) {
            if (item.isSelected()) {
                activeItems.add(item);
            }
        }
        if (activeItems.isEmpty()) {
            JOptionPane.showMessageDialog(root,
                    I18n.get("tool.pdfmerge.msg.noSelectedFiles"),
                    I18n.get("tool.pdfmerge.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        int unreadable = (int) activeItems.stream().filter(it -> !it.isReadable()).count();
        if (unreadable > 0) {
            JOptionPane.showMessageDialog(root,
                    String.format(I18n.get("tool.pdfmerge.msg.needPassword"), unreadable),
                    I18n.get("tool.pdfmerge.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        String outputPath = outputPathField.getText().trim();
        if (outputPath.isEmpty() || outputPath.equals(autoOutputPath)) {
            outputPath = nextAvailableOutput().getAbsolutePath();
            autoOutputPath = outputPath;
            outputPathField.setText(outputPath);
        }
        File outputFile = new File(outputPath);
        if (outputFile.exists()) {
            String confirmTemplate = I18n.get("tool.pdfmerge.msg.overwriteConfirm");
            String confirmMessage = confirmTemplate.contains("%s")
                    ? String.format(confirmTemplate, outputPath)
                    : confirmTemplate + outputPath;
            int ret = JOptionPane.showConfirmDialog(root,
                    confirmMessage,
                    I18n.get("tool.pdfmerge.msg.overwriteTitle"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (ret != JOptionPane.YES_OPTION) {
                return;
            }
        }

        int totalPages = activeItems.stream().mapToInt(PdfMergeItem::getEffectivePageCount).sum();
        cancellationFlag.set(false);
        progressBar.setVisible(true);
        progressBar.setValue(0);
        progressBar.setMaximum(Math.max(1, totalPages));
        statusLabel.setText(I18n.get("tool.pdfmerge.status.starting"));
        openFileBtn.setEnabled(false);
        openDirBtn.setEnabled(false);
        updateButtonStates();

        List<PdfMergeItem> snapshot = new ArrayList<>(activeItems);
        final File finalOutputFile = outputFile;
        currentWorker = new SwingWorker<Void, MergeProgress>() {
            @Override
            protected Void doInBackground() throws Exception {
                PdfMerger.merge(snapshot, finalOutputFile,
                        (cur, total, name) -> publish(new MergeProgress(cur, total, name)),
                        cancellationFlag::get);
                return null;
            }

            @Override
            protected void process(List<MergeProgress> chunks) {
                if (!chunks.isEmpty()) {
                    MergeProgress latest = chunks.get(chunks.size() - 1);
                    progressBar.setValue(latest.current);
                    statusLabel.setText(String.format(
                            I18n.get("tool.pdfmerge.status.mergingPages"),
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
                    int totalPagesWritten = 0;
                    try {
                        totalPagesWritten = PdfMerger.readPageCount(finalOutputFile);
                    } catch (Exception ignored) {
                    }
                    statusLabel.setText(String.format(
                            I18n.get("tool.pdfmerge.status.success"),
                            finalOutputFile.getName(), totalPagesWritten, formatFileSize(finalOutputFile.length())));
                    updateDefaultOutputPath(false);
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InterruptedException || cancellationFlag.get()) {
                        statusLabel.setText(I18n.get("tool.pdfmerge.status.cancelled"));
                    } else {
                        String message = cause != null ? cause.getMessage() : e.getMessage();
                        statusLabel.setText(I18n.get("tool.pdfmerge.status.failed") + message);
                        JOptionPane.showMessageDialog(root,
                                I18n.get("tool.pdfmerge.msg.failed") + message,
                                I18n.get("tool.pdfmerge.title"), JOptionPane.ERROR_MESSAGE);
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
        // 面板可能只被实例化而未 build（例如目录自检），此时组件尚未创建
        if (statusLabel != null) {
            statusLabel.setText(I18n.get("tool.pdfmerge.status.cancelling"));
        }
    }

    private void openGeneratedFile() {
        if (lastGeneratedFile != null && lastGeneratedFile.exists()) {
            openWithDesktop(lastGeneratedFile);
        }
    }

    private void openGeneratedDir() {
        if (lastGeneratedFile != null) {
            File dir = lastGeneratedFile.getParentFile();
            if (dir != null && dir.exists()) {
                openWithDesktop(dir);
            }
        }
    }

    /** 桌面打开能力不可用时降级为复制路径，Linux 无 xdg-open / headless 环境不会抛栈。 */
    private void openWithDesktop(File target) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(target);
                return;
            }
        } catch (Exception ignored) {
            // 落到下面的统一降级
        }
        UIUtils.copyToClipboard(target.getAbsolutePath());
        statusLabel.setText(I18n.get("tool.pdfmerge.msg.pathCopied") + target.getAbsolutePath());
    }

    @Override
    public void closeResources() {
        cancelMerge();
        for (SwingWorker<?, ?> worker : readWorkers) {
            if (!worker.isDone()) {
                worker.cancel(true);
            }
        }
        readWorkers.clear();
        for (PdfMergeItem item : items) {
            item.clearPassword();
        }
    }

    private static DataFlavor createRowFlavor() {
        try {
            return new DataFlavor("application/x-java-toolbox-pdf-row;class=java.lang.String");
        } catch (ClassNotFoundException e) {
            return DataFlavor.stringFlavor;
        }
    }

    private static boolean isPdf(File file) {
        return file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static String normalizeKey(File file) {
        try {
            return file.toPath().toRealPath().toString();
        } catch (Exception e) {
            return file.toPath().toAbsolutePath().normalize().toString();
        }
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
                        cmp = Long.compare(Long.parseLong(token1), Long.parseLong(token2));
                    } catch (NumberFormatException e) {
                        cmp = token1.compareTo(token2);
                    }
                } else {
                    cmp = token1.compareToIgnoreCase(token2);
                }
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(s1.length(), s2.length());
        };
    }

    private static final class RowTransferable implements Transferable {
        private final String value;

        RowTransferable(String value) {
            this.value = value;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{ROW_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return ROW_FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return value;
        }
    }

    private static final class MergeProgress {
        final int current;
        final int total;
        final String fileName;

        MergeProgress(int current, int total, String fileName) {
            this.current = current;
            this.total = total;
            this.fileName = fileName;
        }
    }

    private final class PdfTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {
                I18n.get("tool.pdfmerge.col.select"),
                "#",
                I18n.get("tool.pdfmerge.col.fileName"),
                I18n.get("tool.pdfmerge.col.pages"),
                I18n.get("tool.pdfmerge.col.range"),
                I18n.get("tool.pdfmerge.col.size"),
                I18n.get("tool.pdfmerge.col.path")
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
            return columnIndex == 0 ? Boolean.class : super.getColumnClass(columnIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
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
                    return item.getFileName() + (item.isEncrypted()
                            ? I18n.get("tool.pdfmerge.state.encryptedSuffix") : "");
                case 3:
                    return item.isReadable() ? String.valueOf(item.getPageCount()) : "-";
                case 4:
                    if (!item.isReadable()) {
                        return item.isPasswordRequired()
                                ? I18n.get("tool.pdfmerge.state.needPassword")
                                : I18n.get("tool.pdfmerge.state.unreadable");
                    }
                    return item.isAllPages()
                            ? I18n.get("tool.pdfmerge.state.allPages")
                            : String.format(I18n.get("tool.pdfmerge.state.keptPages"),
                            item.getEffectivePageCount(), item.getPageCount());
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
            if (columnIndex == 0 && aValue instanceof Boolean) {
                items.get(rowIndex).setSelected((Boolean) aValue);
                fireTableCellUpdated(rowIndex, columnIndex);
                updateSummary();
                updateButtonStates();
            }
        }
    }
}
