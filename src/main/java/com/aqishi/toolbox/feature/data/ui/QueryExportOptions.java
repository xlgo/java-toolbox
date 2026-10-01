package com.aqishi.toolbox.feature.data.ui;

import com.aqishi.toolbox.feature.data.application.QueryResultExporter.*;
import com.aqishi.toolbox.feature.data.domain.QueryResult;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;
import javax.swing.*;
import java.awt.*;

/** Shows only the settings relevant to the selected export format. */
final class QueryExportOptions extends JPanel {
    private final JComboBox<Format> format = new JComboBox<>(Format.values());
    private final JComboBox<Dialect> dialect = new JComboBox<>(Dialect.values());
    private final JTextField table = Fields.text("exported_data");
    private final JCheckBox guard = Fields.check(I18n.get("data.export.csvGuard"), true);
    private final JPanel details = Layouts.box();
    private final FormGrid sql = new FormGrid();

    QueryExportOptions(QueryResult result) {
        super(new BorderLayout(0, Tokens.SPACE_MD));
        setOpaque(false);
        format.setRenderer(renderer("ui.export.format."));
        dialect.setRenderer(renderer("ui.export.dialect."));
        FormGrid form = new FormGrid();
        form.row(I18n.get("data.export.format"), format);
        sql.row(I18n.get("ui.export.table"), table);
        sql.row(I18n.get("data.export.dialect"), dialect);
        table.setToolTipText(I18n.get("data.export.table"));
        form.fullRow(sql); form.fullRow(guard);
        form.fullRow(details);
        Card settings = Card.titled(I18n.get("ui.export.options")); settings.setContent(form);
        Card scope = Card.titled(I18n.get("ui.export.scope"));
        scope.setContent(Fields.note(I18n.get("data.export.scope", result.getRows().size())));
        if (result.getWarning() != null) {
            JLabel warning = Fields.note(result.getWarning()); warning.setForeground(Tokens.warning());
            scope.setFooter(warning);
        }
        add(Layouts.stack(Tokens.SPACE_MD, scope, settings), BorderLayout.CENTER);
        setPreferredSize(new Dimension(540, 370));
        format.addActionListener(e -> refresh()); refresh();
    }

    private static DefaultListCellRenderer renderer(String prefix) {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value == null ? "" : I18n.get(prefix + value), index, selected, focus);
            }
        };
    }

    private void refresh() {
        Format current = (Format) format.getSelectedItem();
        sql.setVisible(current == Format.SQL); guard.setVisible(current == Format.CSV);
        details.removeAll(); details.add(Fields.note(I18n.get("ui.export.hint." + current)));
        revalidate(); repaint();
    }

    Options options() {
        if (format.getSelectedItem() == Format.SQL && table.getText().isBlank()) {
            table.requestFocusInWindow();
            throw new IllegalArgumentException(I18n.get("data.export.tableRequired"));
        }
        return new Options((Format) format.getSelectedItem(), table.getText(), (Dialect) dialect.getSelectedItem(), guard.isSelected());
    }
}
