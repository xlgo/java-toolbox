package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.HttpBody;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;

import java.awt.*;
import java.util.*;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

final class HttpBodyEditor extends JPanel {
    private final JComboBox<HttpBody.Mode> mode = new JComboBox<>(HttpBody.Mode.values());
    private final DefaultTableModel model =
            new DefaultTableModel(
                    new Object[] {
                        I18n.get("upload.name"), I18n.get("upload.value"), I18n.get("upload.file")
                    },
                    0) {
                public Class<?> getColumnClass(int c) {
                    return c == 2 ? Boolean.class : String.class;
                }
            };
    private final JTable table = new JTable(model);
    private final JPanel cards = new JPanel(new CardLayout());

    HttpBodyEditor(JTextArea raw) {
        super(new BorderLayout(0, Tokens.SPACE_XS));
        setOpaque(false);
        table.setRowHeight(Tokens.CONTROL_HEIGHT);
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.getTableHeader().setReorderingAllowed(false);
        JButton add = Buttons.snug(I18n.get("upload.add")),
                remove = Buttons.snug(I18n.get("upload.remove")),
                choose = Buttons.snug(I18n.get("upload.choose"));
        add.addActionListener(e -> model.addRow(new Object[] {"", "", false}));
        remove.addActionListener(
                e -> {
                    if (table.isEditing()) table.getCellEditor().cancelCellEditing();
                    int r = table.getSelectedRow();
                    if (r >= 0) model.removeRow(r);
                });
        choose.addActionListener(
                e -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setMultiSelectionEnabled(true);
                    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
                    for (var file : chooser.getSelectedFiles())
                        model.addRow(new Object[] {"file", file.getAbsolutePath(), true});
                    mode.setSelectedItem(HttpBody.Mode.MULTIPART);
                });
        add(Layouts.wrapRow(mode, add, remove, choose), BorderLayout.NORTH);
        cards.setOpaque(false);
        cards.add(Fields.scroll(raw), "raw");
        cards.add(Fields.scroll(table), "form");
        add(cards);
        mode.addActionListener(
                e -> {
                    boolean isRaw = mode.getSelectedItem() == HttpBody.Mode.RAW;
                    ((CardLayout) cards.getLayout()).show(cards, isRaw ? "raw" : "form");
                    add.setEnabled(!isRaw);
                    remove.setEnabled(!isRaw);
                });
        add.setEnabled(false);
        remove.setEnabled(false);
        setPreferredSize(new Dimension(0, 165));
        setToolTipText(I18n.get("upload.hint"));
    }

    HttpBody snapshot() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing())
            throw new IllegalStateException(I18n.get("upload.headerInvalid"));
        java.util.List<HttpBody.Part> parts = new ArrayList<>();
        for (int r = 0; r < model.getRowCount(); r++)
            parts.add(
                    new HttpBody.Part(
                            Objects.toString(model.getValueAt(r, 0), ""),
                            Objects.toString(model.getValueAt(r, 1), ""),
                            Boolean.TRUE.equals(model.getValueAt(r, 2))));
        return new HttpBody((HttpBody.Mode) mode.getSelectedItem(), parts);
    }

    void apply(HttpBody body) {
        HttpBody spec = body == null ? HttpBody.raw() : body;
        mode.setSelectedItem(spec.mode());
        model.setRowCount(0);
        for (var p : spec.parts()) model.addRow(new Object[] {p.name(), p.value(), p.file()});
    }
}
