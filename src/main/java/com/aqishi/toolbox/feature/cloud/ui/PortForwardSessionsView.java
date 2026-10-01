package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.feature.cloud.application.KubectlPortForward;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.util.List;

/** Refreshes changed cells only, preserving the selected session and any log text being inspected. */
final class PortForwardSessionsView extends JPanel {
    record Row(String resource, String local, String remote, KubectlPortForward.State state, String log) { }
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{
            I18n.get("ui.forward.target"), I18n.get("ui.forward.local"), I18n.get("ui.forward.remote"), I18n.get("ui.forward.state")}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JTextArea log = Fields.output(6, 30);
    private final JScrollPane logScroll = Fields.scroll(log);
    private final JCheckBox follow = Fields.check(I18n.get("ui.forward.follow"), true);
    private final JButton stop = Buttons.snug(I18n.get("k8s.forward.stop"));
    private final JButton copy = Buttons.snug(I18n.get("k8s.forward.copy"));
    private final JPanel sessionBody = new JPanel(new CardLayout());
    private final JLabel logLabel = Fields.caption(I18n.get("ui.forward.selectLog"));
    private List<Row> rows = List.of();
    private int displayedRow = -1;
    private boolean updating;

    PortForwardSessionsView(Runnable stopSelected, Runnable copySelected) {
        super(new BorderLayout()); setOpaque(false);
        table.setRowHeight(Tokens.CONTROL_HEIGHT); table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(290);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        ((DefaultCaret) log.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        log.setLineWrap(false);
        sessionBody.setOpaque(false);
        JPanel empty = Layouts.box();
        JLabel emptyLabel = Fields.note(I18n.get("ui.forward.empty"));
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(20, 16, 20, 16)); empty.add(emptyLabel, BorderLayout.NORTH);
        sessionBody.add(empty, "empty"); sessionBody.add(Fields.scroll(table), "table");
        Card sessions = Card.flush(I18n.get("ui.forward.sessions")); sessions.setContent(sessionBody);
        sessions.addHeaderAction(copy); sessions.addHeaderAction(stop);
        sessions.setMinimumSize(new Dimension(0, 135));
        Card logs = Card.flush(I18n.get("ui.forward.logs")); logs.setContent(logScroll);
        logs.addHeaderAction(follow); logs.setFooter(logLabel); logs.setMinimumSize(new Dimension(0, 110));
        add(Layouts.splitVertical(sessions, logs, 0.45, 0.46), BorderLayout.CENTER);
        stop.addActionListener(e -> stopSelected.run()); copy.addActionListener(e -> copySelected.run());
        table.getSelectionModel().addListSelectionListener(e -> { if (!updating && !e.getValueIsAdjusting()) refreshLog(); });
        follow.addActionListener(e -> { if (follow.isSelected()) log.setCaretPosition(log.getDocument().getLength()); });
        refreshLog();
    }

    int selectedIndex() { return table.getSelectedRow(); }
    void select(int index) { if (index >= 0 && index < rows.size()) table.setRowSelectionInterval(index, index); }

    void update(List<Row> values) {
        rows = List.copyOf(values); updating = true;
        try {
            while (model.getRowCount() > rows.size()) model.removeRow(model.getRowCount()-1);
            while (model.getRowCount() < rows.size()) model.addRow(new Object[4]);
            for (int r = 0; r < rows.size(); r++) {
                Row row = rows.get(r);
                Object[] cells = {row.resource(), row.local(), row.remote(), I18n.get("k8s.forward.state." + row.state())};
                for (int c = 0; c < cells.length; c++) if (!java.util.Objects.equals(cells[c], model.getValueAt(r,c))) model.setValueAt(cells[c], r,c);
            }
            ((CardLayout) sessionBody.getLayout()).show(sessionBody, rows.isEmpty() ? "empty" : "table");
        } finally { updating = false; }
        refreshLog();
    }

    private void refreshLog() {
        int index = selectedIndex(); Row row = index >= 0 && index < rows.size() ? rows.get(index) : null;
        copy.setEnabled(row != null && row.state() == KubectlPortForward.State.RUNNING);
        stop.setEnabled(row != null && (row.state() == KubectlPortForward.State.RUNNING || row.state() == KubectlPortForward.State.STARTING));
        String text = row == null ? "" : row.log();
        boolean changedSession = displayedRow != index;
        if (changedSession || !text.equals(log.getText())) {
            Point scroll = logScroll.getViewport().getViewPosition();
            int dot = log.getCaret().getDot(), mark = log.getCaret().getMark();
            if (!changedSession && text.startsWith(log.getText())) log.append(text.substring(log.getDocument().getLength()));
            else log.setText(text);
            if (follow.isSelected()) log.setCaretPosition(log.getDocument().getLength());
            else {
                int length = log.getDocument().getLength();
                log.setCaretPosition(Math.min(mark, length)); log.moveCaretPosition(Math.min(dot, length));
                logScroll.getViewport().setViewPosition(changedSession ? new Point() : scroll);
            }
        }
        displayedRow = index;
        String label = row == null ? I18n.get("ui.forward.selectLog") : row.resource();
        logLabel.setText(label); logLabel.setToolTipText(label);
    }
}
