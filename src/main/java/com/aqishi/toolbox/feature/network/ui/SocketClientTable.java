package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.TcpServerSession;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 服务端模式的客户端列表：编号、地址、接入时间、收发字节；多选后可定向发送或断开。
 */
final class SocketClientTable extends JPanel {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JRadioButton toSelected;
    private final JRadioButton toAll;
    private final JLabel countLabel = Fields.caption("");

    SocketClientTable(IntConsumer disconnect) {
        super(new BorderLayout(0, Tokens.SPACE_XS));
        setOpaque(false);
        table.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);

        toSelected = Fields.radio(I18n.get("tool.socketdebug.server.toSelected"), false);
        toAll = Fields.radio(I18n.get("tool.socketdebug.server.toAll"), true);
        ButtonGroup group = new ButtonGroup();
        group.add(toSelected);
        group.add(toAll);
        JButton kickBtn = Buttons.snug(I18n.get("tool.socketdebug.server.disconnect"));

        add(Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS, toAll, toSelected, kickBtn, countLabel),
                BorderLayout.NORTH);
        JScrollPane scroll = Fields.scroll(table);
        scroll.setPreferredSize(new Dimension(360, 120));
        add(scroll, BorderLayout.CENTER);

        kickBtn.addActionListener(e -> {
            for (int id : selectedIds()) {
                disconnect.accept(id);
            }
        });
        // 选中客户端时自动切到"发给选中"，符合直觉
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRowCount() > 0) {
                toSelected.setSelected(true);
            }
        });
    }

    /** 用最新快照刷新表格，尽量保留选中的客户端。 */
    void update(List<TcpServerSession.ClientInfo> clients) {
        List<Integer> selected = selectedIds();
        model.setRows(clients);
        table.clearSelection();
        for (int row = 0; row < clients.size(); row++) {
            if (selected.contains(clients.get(row).getId())) {
                table.addRowSelectionInterval(row, row);
            }
        }
        countLabel.setText(I18n.get("tool.socketdebug.server.count", String.valueOf(clients.size())));
    }

    boolean isSendToSelected() {
        return toSelected.isSelected();
    }

    List<Integer> selectedIds() {
        List<Integer> ids = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            if (row < model.rows.size()) {
                ids.add(model.rows.get(row).getId());
            }
        }
        return ids;
    }

    void selectAllForTest() {
        table.selectAll();
    }

    int rowCountForTest() {
        return model.getRowCount();
    }

    private static final class Model extends AbstractTableModel {
        private List<TcpServerSession.ClientInfo> rows = Collections.emptyList();

        void setRows(List<TcpServerSession.ClientInfo> rows) {
            this.rows = new ArrayList<>(rows);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case 0: return I18n.get("tool.socketdebug.server.col.id");
                case 1: return I18n.get("tool.socketdebug.server.col.address");
                case 2: return I18n.get("tool.socketdebug.server.col.connectedAt");
                case 3: return I18n.get("tool.socketdebug.server.col.rx");
                default: return I18n.get("tool.socketdebug.server.col.tx");
            }
        }

        @Override
        public Object getValueAt(int row, int column) {
            TcpServerSession.ClientInfo info = rows.get(row);
            switch (column) {
                case 0: return "#" + info.getId();
                case 1: return SocketEvent.format(info.getAddress());
                case 2: return TIME.format(Instant.ofEpochMilli(info.getConnectedAt()));
                case 3: return SocketUiText.bytes(info.getBytesIn());
                default: return SocketUiText.bytes(info.getBytesOut());
            }
        }
    }
}
