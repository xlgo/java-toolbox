package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.BoundedLogBuffer;
import com.aqishi.toolbox.feature.network.application.MqttLogEntry;
import com.aqishi.toolbox.util.I18n;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Table model mirroring the retained window of a {@link BoundedLogBuffer}. Rows
 * are appended and trimmed in whole batches so a busy topic costs one insert
 * and at most one delete event per flush. EDT only.
 */
final class MqttMessageTableModel extends AbstractTableModel {

    static final int PAYLOAD_COLUMN = 5;

    private final List<MqttLogEntry> rows = new ArrayList<>();
    private final String[] columns = {
            I18n.get("tool.mqtt.col.direction"),
            I18n.get("tool.mqtt.col.time"),
            "Topic", "QoS", "Retain", "Payload"
    };

    /** Applies one drained batch: append new rows, then evict from the head. */
    void apply(BoundedLogBuffer.Flush<MqttLogEntry> flush) {
        List<MqttLogEntry> added = flush.added();
        if (!added.isEmpty()) {
            int first = rows.size();
            rows.addAll(added);
            fireTableRowsInserted(first, rows.size() - 1);
        }
        int evicted = Math.min(flush.evictedCount(), rows.size());
        if (evicted > 0) {
            rows.subList(0, evicted).clear();
            fireTableRowsDeleted(0, evicted - 1);
        }
    }

    void clear() {
        if (!rows.isEmpty()) {
            int last = rows.size() - 1;
            rows.clear();
            fireTableRowsDeleted(0, last);
        }
    }

    MqttLogEntry entryAt(int row) {
        return rows.get(row);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        MqttLogEntry entry = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> directionLabel(entry.direction());
            case 1 -> entry.time();
            case 2 -> entry.topic();
            case 3 -> entry.qos();
            case 4 -> I18n.get(entry.retain() ? "tool.mqtt.yes" : "tool.mqtt.no");
            default -> entry.payload();
        };
    }

    private static String directionLabel(MqttLogEntry.Direction direction) {
        return switch (direction) {
            case RECEIVED -> I18n.get("tool.mqtt.dir.received");
            case SENT -> I18n.get("tool.mqtt.dir.sent");
            case SYSTEM -> "System";
        };
    }
}
