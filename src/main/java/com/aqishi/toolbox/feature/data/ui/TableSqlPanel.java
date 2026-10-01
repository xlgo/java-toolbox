package com.aqishi.toolbox.feature.data.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.data.application.*;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.*;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public final class TableSqlPanel extends ToolPanel implements ManagedResourceOwner {
    private final TableSqlService service = new TableSqlService();
    private TableSqlService.Table table;
    private SwingWorker<?, ?> worker;
    private JTextArea output;
    private DefaultTableModel mapping, preview;
    private final List<JButton> actions = new ArrayList<>();

    public TableSqlPanel() {
        super(ToolCatalog.TABLE_SQL);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        JTextField target = Fields.text("imported_data"), nullToken = Fields.text("\\N");
        JComboBox<QueryResultExporter.Dialect> dialect =
                new JComboBox<>(QueryResultExporter.Dialect.values());
        JCheckBox empty = Fields.check(I18n.get("tablesql.emptyNull"), false);
        JComboBox<String> separator = Fields.combo(new String[] {",", ";", "TAB"}, 90);
        JButton csv = Buttons.secondary(I18n.get("tablesql.paste")),
                file = Buttons.secondary(I18n.get("devtools.open")),
                generate = Buttons.primary(I18n.get("devtools.run"));
        actions.addAll(List.of(csv, file, generate));
        JPanel bar =
                Layouts.wrapRow(
                        file,
                        csv,
                        Fields.label(I18n.get("tablesql.separator")),
                        separator,
                        Fields.label(I18n.get("data.export.table")),
                        target,
                        dialect);
        root.add(
                Layouts.stack(
                        Tokens.SPACE_SM,
                        bar,
                        Layouts.wrapRow(
                                Fields.label(I18n.get("tablesql.nullToken")), nullToken, empty),
                        Fields.note(I18n.get("tablesql.hint"))),
                BorderLayout.NORTH);
        mapping =
                new DefaultTableModel(
                        new Object[] {
                            I18n.get("tablesql.include"),
                            I18n.get("tablesql.source"),
                            I18n.get("tablesql.target"),
                            I18n.get("tablesql.type")
                        },
                        0) {
                    public boolean isCellEditable(int r, int c) {
                        return c != 1;
                    }

                    public Class<?> getColumnClass(int c) {
                        return c == 0 ? Boolean.class : String.class;
                    }
                };
        JTable mapTable = new JTable(mapping);
        mapTable.setRowHeight(Tokens.CONTROL_HEIGHT);
        mapTable.putClientProperty("terminateEditOnFocusLost", true);
        mapTable.getColumnModel()
                .getColumn(3)
                .setCellEditor(
                        new DefaultCellEditor(new JComboBox<>(TableSqlService.Type.values())));
        preview =
                new DefaultTableModel() {
                    public boolean isCellEditable(int r, int c) {
                        return false;
                    }
                };
        JTable previewTable = new JTable(preview);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        output = Fields.output(12, 60);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(I18n.get("tablesql.mappingTitle"), Fields.scroll(mapTable));
        tabs.addTab(I18n.get("tablesql.preview"), Fields.scroll(previewTable));
        tabs.addTab("SQL", Fields.scroll(output));
        root.add(tabs);
        JLabel status = Fields.caption(I18n.get("tablesql.noData"));
        JButton copy = Buttons.secondary(I18n.get("devtools.copy")),
                save = Buttons.secondary(I18n.get("devtools.save")),
                cancel = Buttons.secondary(I18n.get("devtools.cancel"));
        cancel.addActionListener(
                e -> {
                    if (worker != null) worker.cancel(true);
                });
        JPanel footer = Layouts.box();
        footer.add(status);
        footer.add(Layouts.wrapRow(copy, save, cancel, generate), BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        csv.addActionListener(
                e -> {
                    JTextArea area = Fields.area(14, 60);
                    area.setText("id,name\n00123,Alice\n00456,Bob");
                    if (JOptionPane.showConfirmDialog(
                                    root,
                                    Fields.scroll(area),
                                    I18n.get("tablesql.paste"),
                                    JOptionPane.OK_CANCEL_OPTION)
                            != JOptionPane.OK_OPTION) return;
                    String text = area.getText();
                    char sep =
                            separator.getSelectedIndex() == 2
                                    ? '\t'
                                    : separator.getSelectedItem().toString().charAt(0);
                    job(
                            () -> service.csv(text, sep),
                            value -> {
                                install((TableSqlService.Table) value);
                                status.setText(I18n.get("tablesql.loaded", table.rows().size()));
                            },
                            status);
                });
        file.addActionListener(
                e -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setFileFilter(
                            new javax.swing.filechooser.FileNameExtensionFilter(
                                    "CSV / Excel", "csv", "xlsx", "xls"));
                    if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
                    Path path = chooser.getSelectedFile().toPath();
                    if (path.toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                        char sep =
                                separator.getSelectedIndex() == 2
                                        ? '\t'
                                        : separator.getSelectedItem().toString().charAt(0);
                        job(
                                () -> {
                                    if (Files.size(path) > 8_000_000)
                                        throw new IllegalArgumentException(
                                                I18n.get("tablesql.limit"));
                                    return service.csv(Files.readString(path), sep);
                                },
                                value -> {
                                    install((TableSqlService.Table) value);
                                    status.setText(
                                            I18n.get("tablesql.loaded", table.rows().size()));
                                },
                                status);
                    } else
                        job(
                                () -> service.sheets(path),
                                value -> {
                                    @SuppressWarnings("unchecked")
                                    List<String> sheets = (List<String>) value;
                                    String sheet =
                                            (String)
                                                    JOptionPane.showInputDialog(
                                                            root,
                                                            I18n.get("tablesql.sheet"),
                                                            I18n.get("tablesql.sheet"),
                                                            JOptionPane.PLAIN_MESSAGE,
                                                            null,
                                                            sheets.toArray(),
                                                            sheets.isEmpty()
                                                                    ? null
                                                                    : sheets.get(0));
                                    if (sheet != null)
                                        job(
                                                () -> service.excel(path, sheet),
                                                v -> {
                                                    install((TableSqlService.Table) v);
                                                    status.setText(
                                                            I18n.get(
                                                                    "tablesql.loaded",
                                                                    table.rows().size()));
                                                },
                                                status);
                                },
                                status);
                });
        generate.addActionListener(
                e -> {
                    if (table == null) return;
                    if (mapTable.isEditing() && !mapTable.getCellEditor().stopCellEditing()) return;
                    try {
                        List<TableSqlService.Mapping> list = new ArrayList<>();
                        for (int i = 0; i < mapping.getRowCount(); i++)
                            list.add(
                                    new TableSqlService.Mapping(
                                            i,
                                            String.valueOf(mapping.getValueAt(i, 2)),
                                            TableSqlService.Type.valueOf(
                                                    mapping.getValueAt(i, 3).toString()),
                                            Boolean.TRUE.equals(mapping.getValueAt(i, 0))));
                        var snapshot = table;
                        String marker = nullToken.getText(), name = target.getText();
                        boolean emptyNull = empty.isSelected();
                        var selected = (QueryResultExporter.Dialect) dialect.getSelectedItem();
                        job(
                                () ->
                                        service.generate(
                                                snapshot, list, marker, emptyNull, name, selected),
                                value -> {
                                    output.setText(value.toString());
                                    output.setCaretPosition(0);
                                    tabs.setSelectedIndex(2);
                                    status.setText(I18n.get("devtools.done"));
                                },
                                status);
                    } catch (Exception error) {
                        UIUtils.error(root, Errors.describeRoot(error));
                    }
                });
        copy.addActionListener(e -> UIUtils.copyToClipboard(output.getText()));
        save.addActionListener(
                e -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setSelectedFile(new java.io.File("import.sql"));
                    if (chooser.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return;
                    Path path = chooser.getSelectedFile().toPath();
                    if (Files.exists(path)
                            && !UIUtils.confirm(
                                    root,
                                    I18n.get("devtools.overwrite"),
                                    I18n.get("devtools.save"))) return;
                    String text = output.getText();
                    job(
                            () -> {
                                new com.aqishi.toolbox.vault.AtomicFiles()
                                        .write(
                                                path,
                                                text.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8));
                                return "";
                            },
                            v -> status.setText(I18n.get("devtools.done")),
                            status);
                });
        return root;
    }

    private void install(TableSqlService.Table value) {
        table = value;
        mapping.setRowCount(0);
        output.setText("");
        for (String name : value.columns()) mapping.addRow(new Object[] {true, name, name, "TEXT"});
        preview.setDataVector(
                value.rows().stream().limit(100).map(List::toArray).toArray(Object[][]::new),
                value.columns().toArray());
    }

    private void job(
            java.util.concurrent.Callable<?> task,
            java.util.function.Consumer<Object> done,
            JLabel status) {
        if (worker != null) return;
        actions.forEach(b -> b.setEnabled(false));
        status.setText(I18n.get("devtools.working"));
        worker =
                new SwingWorker<Object, Void>() {
                    protected Object doInBackground() throws Exception {
                        return task.call();
                    }

                    protected void done() {
                        worker = null;
                        actions.forEach(b -> b.setEnabled(true));
                        try {
                            if (isCancelled()) {
                                status.setText(I18n.get("devtools.cancelled"));
                                return;
                            }
                            done.accept(get());
                        } catch (Exception error) {
                            status.setText(Errors.describeRoot(error));
                            UIUtils.error(TableSqlPanel.this.getView(), Errors.describeRoot(error));
                        }
                    }
                };
        worker.execute();
    }

    @Override
    public void closeResources() {
        if (worker != null) worker.cancel(true);
    }
}
