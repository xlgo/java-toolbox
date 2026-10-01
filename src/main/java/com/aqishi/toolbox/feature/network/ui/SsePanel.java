package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.network.application.SseSession;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.*;

import java.awt.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Bounded UI delivery: callbacks queue data and a Swing timer batches table updates. */
public final class SsePanel extends ToolPanel implements ManagedResourceOwner {
    private SseSession session;
    private javax.swing.Timer timer;
    private volatile int generation;
    private final ArrayBlockingQueue<SseSession.Received> queue = new ArrayBlockingQueue<>(128);
    private final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(100);
    private final java.util.concurrent.atomic.AtomicLong dropped =
            new java.util.concurrent.atomic.AtomicLong();

    public SsePanel() {
        super(ToolCatalog.SSE_CLIENT);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        JTextField url = Fields.text("http://127.0.0.1:8080/events");
        JComboBox<String> method = Fields.combo(new String[] {"GET", "POST"}, 85);
        JCheckBox reconnect = Fields.check(I18n.get("sse.reconnect"), false);
        JTextArea headers = Fields.area(2, 45), body = Fields.area(2, 45);
        JTabbedPane request = new JTabbedPane();
        request.addTab(I18n.get("ui.http.requestHeaders"), Fields.scroll(headers));
        request.addTab(I18n.get("ui.http.requestBody"), Fields.scroll(body));
        JButton start = Buttons.primary(I18n.get("sse.start")),
                stop = Buttons.secondary(I18n.get("sse.stop")),
                clear = Buttons.secondary(I18n.get("devtools.clear"));
        stop.setEnabled(false);
        JPanel address = Layouts.box(Tokens.SPACE_SM, 0);
        address.add(method, BorderLayout.WEST);
        address.add(url);
        address.add(Layouts.wrapRow(reconnect, start, stop), BorderLayout.EAST);
        root.add(
                Layouts.stack(Tokens.SPACE_SM, address, request, Fields.note(I18n.get("sse.hint"))),
                BorderLayout.NORTH);
        var model =
                new DefaultTableModel(
                        new Object[] {
                            I18n.get("sse.time"), I18n.get("sse.elapsed"), "ID", "Event", "Data"
                        },
                        0) {
                    public boolean isCellEditable(int r, int c) {
                        return false;
                    }
                };
        JTable table = new JTable(model);
        table.setRowHeight(Tokens.CONTROL_HEIGHT);
        table.setAutoCreateRowSorter(true);
        JTextArea detail = Fields.output(5, 60);
        table.getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            int row = table.getSelectedRow();
                            if (row >= 0)
                                detail.setText(
                                        String.valueOf(
                                                model.getValueAt(
                                                        table.convertRowIndexToModel(row), 4)));
                        });
        root.add(Layouts.splitVertical(Fields.scroll(table), Fields.scroll(detail), 0.7, 0.7));
        JLabel status = Fields.caption(I18n.get("sse.stopped"));
        JButton copy = Buttons.secondary(I18n.get("devtools.copy"));
        copy.addActionListener(e -> UIUtils.copyToClipboard(detail.getText()));
        JPanel footer = Layouts.box();
        footer.add(status);
        footer.add(Layouts.wrapRow(copy, clear), BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        method.addActionListener(
                e -> {
                    if (method.getSelectedItem().equals("POST")) {
                        reconnect.setSelected(false);
                        reconnect.setEnabled(false);
                    } else reconnect.setEnabled(true);
                });
        start.addActionListener(
                e -> {
                    try {
                        Map<String, String> hs = new LinkedHashMap<>();
                        for (String line : headers.getText().split("\\R")) {
                            if (line.isBlank()) continue;
                            int colon = line.indexOf(':');
                            if (colon <= 0)
                                throw new IllegalArgumentException(
                                        I18n.get("http.workspace.headerLine"));
                            hs.put(
                                    line.substring(0, colon).trim(),
                                    line.substring(colon + 1).trim());
                        }
                        queue.clear();
                        messages.clear();
                        int gen = ++generation;
                        session =
                                new SseSession(
                                        url.getText().trim(),
                                        (String) method.getSelectedItem(),
                                        hs,
                                        body.getText(),
                                        reconnect.isSelected(),
                                        event -> {
                                            if (gen == generation && !queue.offer(event))
                                                dropped.incrementAndGet();
                                        },
                                        text -> {
                                            if (gen == generation) messages.offer(text);
                                        });
                        session.start();
                        start.setEnabled(false);
                        stop.setEnabled(true);
                        url.setEnabled(false);
                        method.setEnabled(false);
                    } catch (Exception error) {
                        UIUtils.error(root, Errors.describeRoot(error));
                    }
                });
        stop.addActionListener(
                e -> {
                    if (session != null) session.close();
                });
        clear.addActionListener(
                e -> {
                    queue.clear();
                    model.setRowCount(0);
                    detail.setText("");
                    dropped.set(0);
                });
        timer =
                new javax.swing.Timer(
                        150,
                        e -> {
                            for (int i = 0; i < 100; i++) {
                                var event = queue.poll();
                                if (event == null) break;
                                if (model.getRowCount() >= 500) model.removeRow(0);
                                model.addRow(
                                        new Object[] {
                                            event.time().toString(),
                                            event.elapsedMillis(),
                                            event.event().id(),
                                            event.event().type(),
                                            event.event().data()
                                        });
                            }
                            String message;
                            while ((message = messages.poll()) != null) {
                                status.setText(message);
                                status.setToolTipText(message);
                            }
                            if (dropped.get() > 0) {
                                status.setText(I18n.get("sse.dropped", dropped.get()));
                                status.setToolTipText(status.getText());
                            }
                            if (session != null && session.isClosed()) {
                                start.setEnabled(true);
                                stop.setEnabled(false);
                                url.setEnabled(true);
                                method.setEnabled(true);
                            }
                        });
        timer.start();
        return root;
    }

    @Override
    public void closeResources() {
        generation++;
        if (session != null) session.close();
        if (timer != null) timer.stop();
        queue.clear();
        messages.clear();
    }
}
