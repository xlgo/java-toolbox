package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.application.ClassSearchService;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * “找类与冲突”页签：在一组目录 / 归档里查找类或资源，检测重复类并区分内容是否相同。
 */
final class ClassSearchTab {

    static final int SUB_RESULTS = 0;
    static final int SUB_DUPLICATES = 1;
    static final int SUB_PAIRS = 2;
    static final int SUB_WARNINGS = 3;

    private final ClassSearchService service;
    private final JPanel root;
    private final DefaultListModel<Path> rootsModel = new DefaultListModel<>();
    private final JList<Path> rootsList = new JList<>(rootsModel);
    private final JTextField queryField = Fields.text("", I18n.get("tool.jarinspector.placeholder.query"));
    private final JLabel hintLabel = Fields.caption(I18n.get("tool.jarinspector.search.hint"));
    private final JButton searchBtn = Buttons.primary(I18n.get("tool.jarinspector.btn.search"));
    private final JButton duplicatesBtn = Buttons.secondary(I18n.get("tool.jarinspector.btn.duplicates"));
    private final JButton cancelBtn = Buttons.danger(I18n.get("tool.jarinspector.btn.cancel"));
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = Fields.caption(I18n.get("tool.jarinspector.status.addRoots"));
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea duplicateDetail = JarUi.detailArea();
    private final JTextArea warningsArea = JarUi.detailArea();

    private final JarUi.RowsModel<ClassSearchService.Hit> hitModel = new JarUi.RowsModel<>();
    private final JarUi.RowsModel<ClassSearchService.DuplicateClass> duplicateModel = new JarUi.RowsModel<>();
    private final JarUi.RowsModel<ClassSearchService.JarPair> pairModel = new JarUi.RowsModel<>();

    private ClassSearchService.SearchResult searchResult;
    private ClassSearchService.ConflictReport conflictReport;
    private File lastDirectory;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<SwingWorker<?, ?>> worker = new AtomicReference<>();
    private final AtomicReference<AtomicBoolean> cancelFlag = new AtomicReference<>();

    ClassSearchTab(ClassSearchService service) {
        this.service = service;
        buildModels();
        root = Layouts.box(0, Tokens.SPACE_SM);
        root.add(buildInputCard(), BorderLayout.NORTH);
        root.add(buildResultCard(), BorderLayout.CENTER);
        JarUi.installDrop(root, this::addRoots);
    }

    JComponent component() {
        return root;
    }

    JTabbedPane tabs() {
        return tabs;
    }

    JTextField queryField() {
        return queryField;
    }

    JLabel hintLabel() {
        return hintLabel;
    }

    DefaultListModel<Path> rootsModel() {
        return rootsModel;
    }

    JarUi.RowsModel<ClassSearchService.Hit> hitModel() {
        return hitModel;
    }

    JarUi.RowsModel<ClassSearchService.DuplicateClass> duplicateModel() {
        return duplicateModel;
    }

    JarUi.RowsModel<ClassSearchService.JarPair> pairModel() {
        return pairModel;
    }

    private void buildModels() {
        hitModel.column(I18n.get("tool.jarinspector.column.name"), String.class, ClassSearchService.Hit::name)
                .column(I18n.get("tool.jarinspector.column.location"), String.class,
                        hit -> SearchText.location(hit.location()))
                .column(I18n.get("tool.jarinspector.column.entry"), String.class, ClassSearchService.Hit::entryPath)
                .column(I18n.get("tool.jarinspector.column.version"), String.class,
                        hit -> JarUi.orDash(hit.location().version()));
        duplicateModel.column(I18n.get("tool.jarinspector.column.className"), String.class,
                        ClassSearchService.DuplicateClass::className)
                .column(I18n.get("tool.jarinspector.column.copies"), Integer.class,
                        duplicate -> duplicate.occurrences().size())
                .column(I18n.get("tool.jarinspector.column.content"), Boolean.class,
                        ClassSearchService.DuplicateClass::contentDiffers)
                .column(I18n.get("tool.jarinspector.column.locations"), String.class, ClassSearchTab::locationsText);
        pairModel.column(I18n.get("tool.jarinspector.column.first"), String.class,
                        pair -> SearchText.location(pair.first()))
                .column(I18n.get("tool.jarinspector.column.second"), String.class,
                        pair -> SearchText.location(pair.second()))
                .column(I18n.get("tool.jarinspector.column.shared"), Integer.class,
                        ClassSearchService.JarPair::sharedClasses)
                .column(I18n.get("tool.jarinspector.column.differing"), Integer.class,
                        ClassSearchService.JarPair::differingClasses);
    }

    private static String locationsText(ClassSearchService.DuplicateClass duplicate) {
        List<String> names = new ArrayList<>();
        for (ClassSearchService.Occurrence occurrence : duplicate.occurrences()) {
            String version = occurrence.location().version();
            names.add(occurrence.location().displayName() + (version == null ? "" : " (" + version + ")"));
        }
        return String.join(", ", names);
    }

    private Card buildInputCard() {
        rootsList.setVisibleRowCount(3);
        JButton addDirBtn = Buttons.snug(I18n.get("tool.jarinspector.btn.addFolder"));
        JButton addJarBtn = Buttons.snug(I18n.get("tool.jarinspector.btn.addJar"));
        JButton addM2Btn = Buttons.snug(I18n.get("tool.jarinspector.btn.addMaven"));
        JButton removeBtn = Buttons.ghost(I18n.get("tool.jarinspector.btn.remove"));
        JButton clearBtn = Buttons.ghost(I18n.get("tool.jarinspector.btn.clearRoots"));
        JPanel rootsPanel = Layouts.box(Tokens.SPACE_SM, 0);
        rootsPanel.add(Fields.scrollBoxed(rootsList), BorderLayout.CENTER);
        rootsPanel.add(Layouts.wrapRow(addDirBtn, addJarBtn, addM2Btn, removeBtn, clearBtn), BorderLayout.SOUTH);

        cancelBtn.setEnabled(false);
        progressBar.setStringPainted(true);
        JPanel queryRow = Layouts.box(Tokens.SPACE_SM, 0);
        queryRow.add(queryField, BorderLayout.CENTER);
        queryRow.add(Layouts.wrapRow(searchBtn, duplicatesBtn, cancelBtn), BorderLayout.EAST);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.jarinspector.label.roots"), rootsPanel);
        form.row(I18n.get("tool.jarinspector.label.query"), queryRow);
        form.row("", hintLabel);
        form.row(I18n.get("tool.jarinspector.label.progress"), progressBar);

        Card card = Card.titled(I18n.get("tool.jarinspector.card.search"),
                I18n.get("tool.jarinspector.card.search.subtitle"));
        card.setContent(form);
        card.setFooter(statusLabel);

        addDirBtn.addActionListener(event -> chooseRoots(JFileChooser.DIRECTORIES_ONLY));
        addJarBtn.addActionListener(event -> chooseRoots(JFileChooser.FILES_ONLY));
        addM2Btn.addActionListener(event -> {
            Path m2 = Paths.get(System.getProperty("user.home"), ".m2", "repository");
            if (Files.isDirectory(m2)) {
                addRoots(List.of(m2.toFile()));
            } else {
                setStatus(I18n.get("tool.jarinspector.status.noMaven", m2.toString()), Tokens.warning());
            }
        });
        removeBtn.addActionListener(event -> {
            for (Path selected : rootsList.getSelectedValuesList()) {
                rootsModel.removeElement(selected);
            }
        });
        clearBtn.addActionListener(event -> rootsModel.clear());
        searchBtn.addActionListener(event -> runSearch());
        queryField.addActionListener(event -> runSearch());
        duplicatesBtn.addActionListener(event -> runDuplicates());
        cancelBtn.addActionListener(event -> cancel());
        queryField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updateHint();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updateHint();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updateHint();
            }
        });
        return card;
    }

    private Card buildResultCard() {
        JTable hitTable = JarUi.table(hitModel);
        hitTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        hitTable.getColumnModel().getColumn(1).setPreferredWidth(320);
        JTable duplicateTable = JarUi.table(duplicateModel);
        duplicateTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        duplicateTable.getColumnModel().getColumn(1).setMaxWidth(80);
        duplicateTable.getColumnModel().getColumn(2).setCellRenderer(new ContentRenderer());
        JarUi.onSelect(duplicateTable, duplicateModel, this::showDuplicate);
        JTable pairTable = JarUi.table(pairModel);

        tabs.addTab(I18n.get("tool.jarinspector.tab.results"), Fields.scrollBoxed(hitTable));
        tabs.addTab(I18n.get("tool.jarinspector.tab.duplicates"), Layouts.splitVertical(
                Fields.scrollBoxed(duplicateTable), Fields.scrollBoxed(duplicateDetail), 0.65, 0.65));
        tabs.addTab(I18n.get("tool.jarinspector.tab.pairs"), Fields.scrollBoxed(pairTable));
        tabs.addTab(I18n.get("tool.jarinspector.tab.warnings"), Fields.scrollBoxed(warningsArea));

        Card card = Card.titled(I18n.get("tool.jarinspector.card.searchResult"));
        card.setContent(tabs);
        JButton copyBtn = Buttons.snug(I18n.get("tool.jarinspector.btn.copyReport"));
        card.addHeaderAction(copyBtn);
        copyBtn.addActionListener(event -> copyReport());
        return card;
    }

    /** 内容列：不同 = 真正的冲突（警示色），相同 = 仅重复打包。 */
    private static final class ContentRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            boolean differs = Boolean.TRUE.equals(value);
            Component component = super.getTableCellRendererComponent(table,
                    I18n.get(differs ? "tool.jarinspector.value.differs" : "tool.jarinspector.value.identical"),
                    isSelected, hasFocus, row, column);
            if (!isSelected) {
                component.setForeground(differs ? Tokens.danger() : Tokens.mutedForeground());
            }
            return component;
        }
    }

    // ==========================================
    // 行为
    // ==========================================
    void addRoots(List<File> files) {
        for (File file : files) {
            Path path = file.toPath().toAbsolutePath().normalize();
            if (!rootsModel.contains(path)) {
                rootsModel.addElement(path);
            }
        }
        setStatus(I18n.get("tool.jarinspector.status.roots", String.valueOf(rootsModel.size())),
                Tokens.mutedForeground());
    }

    private void chooseRoots(int mode) {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setFileSelectionMode(mode);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(SwingUtilities.getWindowAncestor(root)) == JFileChooser.APPROVE_OPTION) {
            File[] selected = chooser.getSelectedFiles();
            if (selected.length > 0) {
                lastDirectory = selected[0].getParentFile();
                addRoots(List.of(selected));
            }
        }
    }

    private void updateHint() {
        hintLabel.setText(SearchText.hint(queryField.getText()));
    }

    private List<Path> roots() {
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < rootsModel.size(); i++) {
            roots.add(rootsModel.get(i));
        }
        return roots;
    }

    void runSearch() {
        String query = queryField.getText();
        if (query == null || query.isBlank()) {
            setStatus(I18n.get("tool.jarinspector.status.emptyQuery"), Tokens.warning());
            return;
        }
        run((listener, cancelled) -> service.search(roots(), query, listener, cancelled), result -> {
            applySearch(result);
            tabs.setSelectedIndex(SUB_RESULTS);
            setStatus(I18n.get(result.truncated() ? "tool.jarinspector.status.searchTruncated"
                            : "tool.jarinspector.status.searchDone", String.valueOf(result.hits().size()),
                    String.valueOf(result.archivesScanned()), String.valueOf(result.elapsedMillis())),
                    result.hits().isEmpty() ? Tokens.warning() : Tokens.mutedForeground());
        });
    }

    void runDuplicates() {
        run((listener, cancelled) -> service.findDuplicates(roots(), listener, cancelled), report -> {
            applyConflicts(report);
            tabs.setSelectedIndex(SUB_DUPLICATES);
            setStatus(I18n.get("tool.jarinspector.status.duplicatesDone", String.valueOf(report.duplicates().size()),
                    String.valueOf(report.conflictCount()), String.valueOf(report.archivesScanned()),
                    String.valueOf(report.elapsedMillis())),
                    report.conflictCount() > 0 ? Tokens.danger() : Tokens.success());
        });
    }

    @FunctionalInterface
    private interface Job<T> {
        T run(ClassSearchService.ProgressListener listener, java.util.function.BooleanSupplier cancelled);
    }

    /** 后台执行一次扫描：带进度、可取消，结果按代次过滤。 */
    private <T> void run(Job<T> job, java.util.function.Consumer<T> onDone) {
        if (rootsModel.isEmpty()) {
            setStatus(I18n.get("tool.jarinspector.status.addRoots"), Tokens.warning());
            return;
        }
        long ticket = generation.incrementAndGet();
        cancel();
        AtomicBoolean cancelled = new AtomicBoolean();
        cancelFlag.set(cancelled);
        setRunning(true);
        progressBar.setValue(0);
        progressBar.setIndeterminate(true);
        setStatus(I18n.get("tool.jarinspector.status.scanning"), Tokens.mutedForeground());
        SwingWorker<T, int[]> task = new SwingWorker<>() {
            @Override
            protected T doInBackground() {
                return job.run((done, total, current) -> publish(new int[]{done, total}), cancelled::get);
            }

            @Override
            protected void process(List<int[]> chunks) {
                if (ticket != generation.get() || chunks.isEmpty()) {
                    return;
                }
                int[] last = chunks.get(chunks.size() - 1);
                progressBar.setIndeterminate(false);
                progressBar.setMaximum(Math.max(1, last[1]));
                progressBar.setValue(last[0]);
                progressBar.setString(last[0] + " / " + last[1]);
            }

            @Override
            protected void done() {
                if (ticket != generation.get()) {
                    return;
                }
                setRunning(false);
                progressBar.setIndeterminate(false);
                if (isCancelled()) {
                    setStatus(I18n.get("tool.jarinspector.status.cancelled"), Tokens.warning());
                    return;
                }
                try {
                    onDone.accept(get());
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof CancellationException) {
                        setStatus(I18n.get("tool.jarinspector.status.cancelled"), Tokens.warning());
                    } else {
                        setStatus(ClassText.describeError(error), Tokens.danger());
                    }
                } catch (CancellationException stopped) {
                    setStatus(I18n.get("tool.jarinspector.status.cancelled"), Tokens.warning());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        worker.set(task);
        task.execute();
    }

    /** 包内可见：测试直接灌入结果。 */
    void applySearch(ClassSearchService.SearchResult result) {
        searchResult = result;
        hitModel.setRows(result == null ? List.of() : result.hits());
        tabs.setTitleAt(SUB_RESULTS, I18n.get("tool.jarinspector.tab.results")
                + (result == null ? "" : " (" + result.hits().size() + ")"));
        showWarnings(result == null ? List.of() : result.warnings());
    }

    void applyConflicts(ClassSearchService.ConflictReport report) {
        conflictReport = report;
        duplicateModel.setRows(report == null ? List.of() : report.duplicates());
        pairModel.setRows(report == null ? List.of() : report.pairs());
        duplicateDetail.setText("");
        int conflicts = report == null ? 0 : report.conflictCount();
        tabs.setTitleAt(SUB_DUPLICATES, I18n.get("tool.jarinspector.tab.duplicates")
                + (report == null ? "" : " (" + report.duplicates().size() + ")"));
        tabs.setForegroundAt(SUB_DUPLICATES, conflicts > 0 ? Tokens.danger() : null);
        showWarnings(report == null ? List.of() : report.warnings());
    }

    private void showWarnings(List<ClassSearchService.ScanWarning> warnings) {
        warningsArea.setText(SearchText.warnings(warnings));
        tabs.setTitleAt(SUB_WARNINGS, I18n.get("tool.jarinspector.tab.warnings")
                + (warnings.isEmpty() ? "" : " (" + warnings.size() + ")"));
    }

    private void showDuplicate(ClassSearchService.DuplicateClass duplicate) {
        StringBuilder text = new StringBuilder(duplicate.className()).append("\n");
        text.append(I18n.get(duplicate.contentDiffers() ? "tool.jarinspector.detail.differs"
                : "tool.jarinspector.detail.identical", String.valueOf(duplicate.variants()))).append("\n\n");
        for (ClassSearchService.Occurrence occurrence : duplicate.occurrences()) {
            text.append(SearchText.location(occurrence.location())).append('\n')
                    .append("    ").append(occurrence.entryPath()).append('\n')
                    .append("    ").append(JarText.coordinatesText(occurrence.location().coordinates()))
                    .append("  ").append(JarUi.orDash(occurrence.location().version())).append('\n')
                    .append("    SHA-256 ").append(occurrence.sha256()).append('\n');
        }
        duplicateDetail.setText(text.toString());
        duplicateDetail.setCaretPosition(0);
    }

    private void copyReport() {
        int selected = tabs.getSelectedIndex();
        boolean conflicts = selected == SUB_DUPLICATES || selected == SUB_PAIRS;
        String text = conflicts && conflictReport != null ? SearchText.conflictReport(conflictReport)
                : searchResult != null ? SearchText.searchReport(searchResult)
                : conflictReport != null ? SearchText.conflictReport(conflictReport) : null;
        if (text != null) {
            UIUtils.copyToClipboard(text);
            setStatus(I18n.get("tool.jarinspector.status.copied"), Tokens.success());
        }
    }

    private void setRunning(boolean running) {
        searchBtn.setEnabled(!running);
        duplicatesBtn.setEnabled(!running);
        cancelBtn.setEnabled(running);
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    /** 取消当前扫描：扫描线程每个条目检查一次标志，SwingWorker 本身也被中断。 */
    void cancel() {
        AtomicBoolean flag = cancelFlag.getAndSet(null);
        if (flag != null) {
            flag.set(true);
        }
        SwingWorker<?, ?> previous = worker.getAndSet(null);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
    }

    void close() {
        generation.incrementAndGet();
        cancel();
    }
}
