package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.ClassFileInfo;
import com.aqishi.toolbox.feature.system.domain.JarInspector;
import com.aqishi.toolbox.feature.system.domain.JarReport;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * “JAR”页签：分析 JAR/WAR/EAR 的 MANIFEST、坐标、版本分布、包、嵌套库、SPI 与条目清单。
 */
final class JarTab {

    static final int SUB_OVERVIEW = 0;
    static final int SUB_LIBRARIES = 3;
    static final int SUB_ENTRIES = 5;

    private final JarInspector inspector;
    private final Card card;
    private final JLabel statusLabel;
    private final JPanel summaryRow = Layouts.wrapRow();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea overviewArea = JarUi.detailArea();
    private final JTextArea libraryDetail = JarUi.detailArea();
    private final ClassInfoView entryView = new ClassInfoView();

    private final JarUi.RowsModel<Map.Entry<String, String>> manifestModel = new JarUi.RowsModel<>();
    private final JarUi.RowsModel<Map.Entry<Integer, Integer>> histogramModel = new JarUi.RowsModel<>();
    private final JarUi.RowsModel<JarReport.VersionStats> versionedModel = new JarUi.RowsModel<>();
    private final JarUi.RowsModel<JarReport.PackageStats> packageModel = new JarUi.RowsModel<>();
    private final JarUi.RowsModel<JarReport.NestedLibrary> libraryModel = new JarUi.RowsModel<>();
    private final JarUi.RowsModel<JarReport.ServiceFile> serviceModel = new JarUi.RowsModel<>();
    private final JarUi.RowsModel<JarReport.EntryInfo> entryModel = new JarUi.RowsModel<>();
    private JTable entryTable;
    private TableRowSorter<JarUi.RowsModel<JarReport.PackageStats>> packageSorter;
    private TableRowSorter<JarUi.RowsModel<JarReport.EntryInfo>> entrySorter;
    private final JTextField packageFilter = Fields.text("", I18n.get("tool.jarinspector.placeholder.filter"));
    private final JTextField entryFilter = Fields.text("", I18n.get("tool.jarinspector.placeholder.filter"));

    private JarReport report;
    private Path archive;
    private File lastDirectory;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong entryGeneration = new AtomicLong();
    private final AtomicReference<SwingWorker<?, ?>> worker = new AtomicReference<>();
    private final AtomicReference<SwingWorker<?, ?>> entryWorker = new AtomicReference<>();
    private final AtomicReference<AtomicBoolean> cancelFlag = new AtomicReference<>();

    JarTab(JarInspector inspector) {
        this.inspector = inspector;
        buildModels();
        tabs.addTab(I18n.get("tool.jarinspector.tab.overview"), Layouts.splitHorizontal(
                Fields.scrollBoxed(overviewArea), Fields.scrollBoxed(JarUi.table(manifestModel)), 0.55, 0.55));
        tabs.addTab(I18n.get("tool.jarinspector.tab.versions"), Layouts.splitHorizontal(
                Fields.scrollBoxed(JarUi.table(histogramModel)), Fields.scrollBoxed(JarUi.table(versionedModel)),
                0.5, 0.5));
        tabs.addTab(I18n.get("tool.jarinspector.tab.packages"), filtered(packageFilter, packageTable()));
        tabs.addTab(I18n.get("tool.jarinspector.tab.libraries"), librariesPanel());
        tabs.addTab(I18n.get("tool.jarinspector.tab.services"), Fields.scrollBoxed(JarUi.table(serviceModel)));
        tabs.addTab(I18n.get("tool.jarinspector.tab.entries"), Layouts.splitHorizontal(
                filtered(entryFilter, buildEntryTable()), entryView.component(), 0.45, 0.45));

        JPanel body = Layouts.box(0, Tokens.SPACE_SM);
        body.add(summaryRow, BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);

        card = Card.titled(I18n.get("tool.jarinspector.card.jar"), I18n.get("tool.jarinspector.card.jar.subtitle"));
        card.setContent(body);
        JButton openBtn = Buttons.primary(I18n.get("tool.jarinspector.btn.openJar"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.jarinspector.btn.copyReport"));
        card.addHeaderAction(openBtn);
        card.addHeaderAction(copyBtn);
        statusLabel = Fields.caption(I18n.get("tool.jarinspector.status.dropJar"));
        card.setFooter(statusLabel);

        openBtn.addActionListener(event -> choose());
        copyBtn.addActionListener(event -> {
            if (report != null) {
                UIUtils.copyToClipboard(JarText.report(report));
                setStatus(I18n.get("tool.jarinspector.status.copied"), Tokens.success());
            }
        });
        onFilter(packageFilter, () -> applyFilter(packageSorter, packageFilter, 0));
        onFilter(entryFilter, () -> applyFilter(entrySorter, entryFilter, 0));
        JarUi.installDrop(card, files -> open(files.get(0)));
        applyReport(null, null);
    }

    JComponent component() {
        return card;
    }

    JTabbedPane tabs() {
        return tabs;
    }

    JarReport report() {
        return report;
    }

    ClassInfoView entryView() {
        return entryView;
    }

    JTable entryTable() {
        return entryTable;
    }

    private void buildModels() {
        manifestModel.column(I18n.get("tool.jarinspector.column.key"), String.class, Map.Entry::getKey)
                .column(I18n.get("tool.jarinspector.column.value"), String.class, Map.Entry::getValue);
        histogramModel.column(I18n.get("tool.jarinspector.column.major"), Integer.class, Map.Entry::getKey)
                .column(I18n.get("tool.jarinspector.column.release"), String.class,
                        entry -> ClassFileInfo.releaseName(entry.getKey()))
                .column(I18n.get("tool.jarinspector.column.classes"), Integer.class, Map.Entry::getValue);
        versionedModel.column(I18n.get("tool.jarinspector.column.versionDir"), Integer.class,
                        JarReport.VersionStats::release)
                .column(I18n.get("tool.jarinspector.column.classes"), Integer.class, JarReport.VersionStats::classCount)
                .column(I18n.get("tool.jarinspector.column.maxRelease"), String.class,
                        stats -> JarUi.releaseLabel(stats.maxMajor()));
        packageModel.column(I18n.get("tool.jarinspector.column.package"), String.class,
                        stats -> stats.name().isEmpty() ? I18n.get("tool.jarinspector.value.defaultPackage") : stats.name())
                .column(I18n.get("tool.jarinspector.column.classes"), Integer.class, JarReport.PackageStats::classCount);
        libraryModel.column(I18n.get("tool.jarinspector.column.path"), String.class, JarReport.NestedLibrary::path)
                .column(I18n.get("tool.jarinspector.column.coordinates"), String.class,
                        library -> JarText.coordinatesText(library.bestCoordinates()))
                .column(I18n.get("tool.jarinspector.column.maxRelease"), String.class,
                        library -> library.report() == null ? "-" : JarUi.releaseLabel(library.report().maxMajor()))
                .column(I18n.get("tool.jarinspector.column.classes"), Integer.class,
                        library -> library.report() == null ? 0 : library.report().classCount())
                .column(I18n.get("tool.jarinspector.column.size"), String.class,
                        library -> JarUi.formatSize(library.size()));
        serviceModel.column(I18n.get("tool.jarinspector.column.service"), String.class, JarReport.ServiceFile::service)
                .column(I18n.get("tool.jarinspector.column.providers"), String.class,
                        service -> String.join(", ", service.providers()));
        entryModel.column(I18n.get("tool.jarinspector.column.entry"), String.class, JarReport.EntryInfo::name)
                .column(I18n.get("tool.jarinspector.column.size"), Long.class, JarReport.EntryInfo::size)
                .column(I18n.get("tool.jarinspector.column.compressed"), Long.class, JarReport.EntryInfo::compressedSize);
    }

    private JTable packageTable() {
        JTable table = JarUi.table(packageModel);
        packageSorter = new TableRowSorter<>(packageModel);
        table.setRowSorter(packageSorter);
        return table;
    }

    private JTable buildEntryTable() {
        entryTable = JarUi.table(entryModel);
        entrySorter = new TableRowSorter<>(entryModel);
        entryTable.setRowSorter(entrySorter);
        entryTable.getColumnModel().getColumn(0).setPreferredWidth(360);
        JarUi.onSelect(entryTable, entryModel, this::showEntry);
        return entryTable;
    }

    private JComponent librariesPanel() {
        JTable table = JarUi.table(libraryModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        JarUi.onSelect(table, libraryModel, library -> {
            libraryDetail.setText(library.report() == null ? JarText.coordinatesText(library.bestCoordinates())
                    : JarText.overview(library.report()));
            libraryDetail.setCaretPosition(0);
        });
        return Layouts.splitVertical(Fields.scrollBoxed(table), Fields.scrollBoxed(libraryDetail), 0.6, 0.6);
    }

    private static JComponent filtered(JTextField filter, JTable table) {
        JPanel panel = Layouts.box(0, Tokens.SPACE_SM);
        panel.add(filter, BorderLayout.NORTH);
        panel.add(Fields.scrollBoxed(table), BorderLayout.CENTER);
        return panel;
    }

    private static void onFilter(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                action.run();
            }
        });
    }

    private static <M extends javax.swing.table.TableModel> void applyFilter(TableRowSorter<M> sorter,
                                                                             JTextField field, int column) {
        String needle = field.getText().trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            sorter.setRowFilter(null);
            return;
        }
        sorter.setRowFilter(new RowFilter<M, Integer>() {
            @Override
            public boolean include(Entry<? extends M, ? extends Integer> entry) {
                return String.valueOf(entry.getValue(column)).toLowerCase(Locale.ROOT).contains(needle);
            }
        });
    }

    // ==========================================
    // 行为
    // ==========================================
    private void choose() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(I18n.get("tool.jarinspector.btn.openJar"));
        if (chooser.showOpenDialog(SwingUtilities.getWindowAncestor(card)) == JFileChooser.APPROVE_OPTION) {
            open(chooser.getSelectedFile());
        }
    }

    /** 后台分析；新的一次分析会作废并取消上一次。 */
    void open(File file) {
        lastDirectory = file.getParentFile();
        long ticket = generation.incrementAndGet();
        cancel();
        AtomicBoolean cancelled = new AtomicBoolean();
        cancelFlag.set(cancelled);
        Path path = file.toPath();
        setStatus(I18n.get("tool.jarinspector.status.inspecting", file.getName()), Tokens.mutedForeground());
        SwingWorker<JarReport, Void> task = new SwingWorker<>() {
            private long elapsed;

            @Override
            protected JarReport doInBackground() throws Exception {
                long start = System.nanoTime();
                JarReport result = inspector.inspect(path, cancelled::get);
                elapsed = (System.nanoTime() - start) / 1_000_000L;
                return result;
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != generation.get()) {
                    return;
                }
                try {
                    applyReport(get(), path);
                    setStatus(I18n.get("tool.jarinspector.status.inspected", file.getName(),
                            String.valueOf(elapsed)), Tokens.mutedForeground());
                } catch (ExecutionException error) {
                    applyReport(null, null);
                    setStatus(ClassText.describeError(error), Tokens.danger());
                } catch (java.util.concurrent.CancellationException | InterruptedException stopped) {
                    // 已被新任务取代或面板关闭
                }
            }
        };
        worker.set(task);
        task.execute();
    }

    /** 包内可见：测试直接灌入分析结果。 */
    void applyReport(JarReport newReport, Path source) {
        report = newReport;
        archive = source;
        entryGeneration.incrementAndGet();
        manifestModel.setRows(newReport == null ? List.of() : new ArrayList<>(newReport.manifest().entrySet()));
        histogramModel.setRows(newReport == null ? List.of()
                : new ArrayList<>(newReport.majorHistogram().entrySet()));
        versionedModel.setRows(newReport == null ? List.of()
                : new ArrayList<>(newReport.versionedClasses().values()));
        packageModel.setRows(newReport == null ? List.of() : newReport.packages());
        libraryModel.setRows(newReport == null ? List.of() : newReport.libraries());
        serviceModel.setRows(newReport == null ? List.of() : newReport.services());
        entryModel.setRows(newReport == null ? List.of() : newReport.entries());
        overviewArea.setText(newReport == null ? "" : JarText.overview(newReport));
        overviewArea.setCaretPosition(0);
        libraryDetail.setText("");
        entryView.showMessage(I18n.get("tool.jarinspector.class.selectEntry"), false);
        tabs.setTitleAt(SUB_LIBRARIES, newReport == null || newReport.libraries().isEmpty()
                ? I18n.get("tool.jarinspector.tab.libraries")
                : I18n.get("tool.jarinspector.tab.libraries") + " (" + newReport.libraries().size() + ")");
        renderSummary(newReport);
    }

    private void renderSummary(JarReport source) {
        summaryRow.removeAll();
        if (source == null) {
            summaryRow.add(Fields.caption(I18n.get("tool.jarinspector.summary.noJar")));
        } else {
            JarReport.SpringBootInfo boot = source.springBoot();
            String main = source.manifestValue("Main-Class");
            summaryRow.add(chip("tool.jarinspector.chip.coordinates",
                    JarText.coordinatesText(source.primaryCoordinates(source.name())), null));
            summaryRow.add(chip("tool.jarinspector.chip.requiredJava", JarUi.releaseLabel(source.maxMajor()),
                    Tokens.accent()));
            if (!source.libraries().isEmpty()) {
                summaryRow.add(chip("tool.jarinspector.chip.withLibraries",
                        JarUi.releaseLabel(source.maxMajorIncludingLibraries()), null));
            }
            summaryRow.add(chip("tool.jarinspector.chip.layout", JarText.layoutLabel(source.layout()), null));
            summaryRow.add(chip("tool.jarinspector.chip.mainClass", JarUi.orDash(main), null));
            if (boot != null) {
                summaryRow.add(chip("tool.jarinspector.chip.startClass", JarUi.orDash(boot.startClass()), null));
            }
            summaryRow.add(chip("tool.jarinspector.chip.signed", I18n.get(source.signed()
                    ? "tool.jarinspector.value.yes" : "tool.jarinspector.value.no"), null));
            summaryRow.add(chip("tool.jarinspector.chip.multiRelease", I18n.get(source.multiRelease()
                    ? "tool.jarinspector.value.yes" : "tool.jarinspector.value.no"), null));
            if (source.maxMajor() > 0) {
                JLabel explain = Fields.caption(I18n.get("tool.jarinspector.explain.jar",
                        ClassFileInfo.releaseName(source.maxMajor()), String.valueOf(source.maxMajor())));
                summaryRow.add(explain);
            }
        }
        summaryRow.revalidate();
        summaryRow.repaint();
    }

    private static JComponent chip(String titleKey, String value, Color accent) {
        JLabel title = Fields.caption(I18n.get(titleKey));
        JLabel text = new JLabel(value);
        text.setFont(Tokens.fontBodyStrong());
        if (accent != null) {
            text.setForeground(accent);
        }
        JPanel box = Layouts.stack(0, title, text);
        box.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, Tokens.SPACE_MD));
        return box;
    }

    /** 选中 .class 条目时从 zip 里读出并解析，结果过期（又选了别的）即丢弃。 */
    private void showEntry(JarReport.EntryInfo entry) {
        Path source = archive;
        if (source == null || entry.directory() || !entry.name().endsWith(".class")) {
            entryView.showMessage(I18n.get("tool.jarinspector.class.selectEntry"), false);
            return;
        }
        long ticket = entryGeneration.incrementAndGet();
        SwingWorker<?, ?> previous = entryWorker.getAndSet(null);
        if (previous != null) {
            previous.cancel(true);
        }
        SwingWorker<ClassFileInfo, Void> task = new SwingWorker<>() {
            @Override
            protected ClassFileInfo doInBackground() throws Exception {
                return inspector.readClass(source, entry.name());
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != entryGeneration.get()) {
                    return;
                }
                try {
                    entryView.show(get());
                } catch (ExecutionException error) {
                    entryView.showMessage(ClassText.describeError(error), true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        entryWorker.set(task);
        task.execute();
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void cancel() {
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
        entryGeneration.incrementAndGet();
        cancel();
        SwingWorker<?, ?> previous = entryWorker.getAndSet(null);
        if (previous != null) {
            previous.cancel(true);
        }
    }
}
