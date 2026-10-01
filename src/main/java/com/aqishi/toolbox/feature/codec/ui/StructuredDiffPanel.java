package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.codec.domain.StructuredDiff;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.*;

import java.awt.*;
import java.util.*;

import javax.swing.*;

public final class StructuredDiffPanel extends ToolPanel implements ManagedResourceOwner {
    private SwingWorker<?, ?> worker;

    public StructuredDiffPanel() {
        super(ToolCatalog.STRUCTURED_DIFF);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        JTextArea left = Fields.area(10, 35), right = Fields.area(10, 35);
        left.setText("{\"users\":[{\"id\":1,\"name\":\"Alice\"}],\"time\":1}");
        right.setText("{\"time\":2,\"users\":[{\"id\":1,\"name\":\"Bob\"}]}");
        JComboBox<String> format = Fields.combo(new String[] {"JSON", "YAML"}, 100);
        JTextField ignored = Fields.text("$.time"), key = Fields.text("id");
        FormGrid options = new FormGrid();
        options.row(I18n.get("structured.format"), format);
        options.row(I18n.get("structured.ignore"), ignored);
        options.row(I18n.get("structured.key"), key);
        options.fullRow(Fields.note(I18n.get("structured.hint")));
        Card a = Card.flush(I18n.get("structured.before"));
        a.setContent(Fields.scroll(left));
        Card b = Card.flush(I18n.get("structured.after"));
        b.setContent(Fields.scroll(right));
        var model =
                new javax.swing.table.DefaultTableModel(
                        new Object[] {
                            I18n.get("structured.path"),
                            I18n.get("structured.kind"),
                            I18n.get("structured.before"),
                            I18n.get("structured.after")
                        },
                        0) {
                    @Override
                    public boolean isCellEditable(int r, int c) {
                        return false;
                    }
                };
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(Tokens.CONTROL_HEIGHT);
        JTextArea detail = Fields.output(3, 30);
        table.getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            int r = table.getSelectedRow();
                            if (r >= 0) {
                                r = table.convertRowIndexToModel(r);
                                detail.setText(
                                        model.getValueAt(r, 0)
                                                + "\n- "
                                                + model.getValueAt(r, 2)
                                                + "\n+ "
                                                + model.getValueAt(r, 3));
                            }
                        });
        JLabel status = Fields.caption(" ");
        JButton run = Buttons.primary(I18n.get("devtools.run")),
                cancel = Buttons.secondary(I18n.get("devtools.cancel"));
        cancel.setEnabled(false);
        run.addActionListener(
                e -> {
                    String aa = left.getText(), bb = right.getText(), id = key.getText();
                    boolean yaml = format.getSelectedIndex() == 1;
                    Set<String> skip =
                            new HashSet<>(
                                    Arrays.asList(ignored.getText().split("\\s*[,\\r\\n]\\s*")));
                    run.setEnabled(false);
                    cancel.setEnabled(true);
                    SwingWorker<java.util.List<StructuredDiff.Change>, Void> job =
                            new SwingWorker<>() {
                                protected java.util.List<StructuredDiff.Change> doInBackground()
                                        throws Exception {
                                    return new StructuredDiff().compare(aa, bb, yaml, skip, id);
                                }

                                protected void done() {
                                    try {
                                        if (!isCancelled()) {
                                            var changes = get();
                                            model.setRowCount(0);
                                            detail.setText("");
                                            for (var c : changes)
                                                model.addRow(
                                                        new Object[] {
                                                            c.path(),
                                                            I18n.get("structured." + c.kind()),
                                                            c.before(),
                                                            c.after()
                                                        });
                                            status.setText(
                                                    I18n.get("structured.count", changes.size()));
                                        }
                                    } catch (Exception error) {
                                        UIUtils.error(root, Errors.describeRoot(error));
                                    } finally {
                                        run.setEnabled(true);
                                        cancel.setEnabled(false);
                                        worker = null;
                                    }
                                }
                            };
                    worker = job;
                    job.execute();
                });
        cancel.addActionListener(
                e -> {
                    if (worker != null) worker.cancel(true);
                });
        JButton copy = Buttons.snug(I18n.get("devtools.copy"));
        copy.addActionListener(
                e -> {
                    StringBuilder text = new StringBuilder();
                    for (int r = 0; r < model.getRowCount(); r++)
                        text.append(model.getValueAt(r, 0))
                                .append("\t")
                                .append(model.getValueAt(r, 1))
                                .append("\t")
                                .append(model.getValueAt(r, 2))
                                .append("\t")
                                .append(model.getValueAt(r, 3))
                                .append('\n');
                    UIUtils.copyToClipboard(text.toString());
                });
        root.add(options, BorderLayout.NORTH);
        root.add(
                Layouts.splitVertical(
                        Layouts.splitHorizontal(a, b, 0.5, 0.5),
                        Layouts.splitVertical(
                                Fields.scroll(table), Fields.scroll(detail), 0.7, 0.7),
                        0.5,
                        0.5));
        JPanel actions = Layouts.box();
        actions.add(status);
        actions.add(Layouts.wrapRow(copy, cancel, run), BorderLayout.EAST);
        root.add(actions, BorderLayout.SOUTH);
        return root;
    }

    @Override
    public void closeResources() {
        if (worker != null) worker.cancel(true);
    }
}
