package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.BenchErrorKind;
import com.aqishi.toolbox.feature.network.domain.BenchResult;
import com.aqishi.toolbox.feature.network.domain.BenchSecond;
import com.aqishi.toolbox.feature.network.domain.BenchSnapshot;
import com.aqishi.toolbox.feature.network.domain.LatencyHistogram;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.FormatUtils;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

/**
 * 压测的实时指标条、趋势图与结束后的明细表。
 *
 * <p>指标条与趋势图随每次快照刷新；四张明细表只在结束时填一次——
 * 每 500 ms 重建表格会把用户正在看的行和滚动位置一起冲掉。</p>
 */
final class BenchResultView {

    private final JLabel elapsedValue = kpiValue();
    private final JLabel completedValue = kpiValue();
    private final JLabel qpsValue = kpiValue();
    private final JLabel p50Value = kpiValue();
    private final JLabel p99Value = kpiValue();
    private final JLabel errorValue = kpiValue();
    private final BenchChart chart = new BenchChart();
    private final DefaultTableModel summaryModel = readOnlyModel(
            "tool.httpbench.col.metric", "tool.httpbench.col.value");
    private final DefaultTableModel statusModel = readOnlyModel(
            "tool.httpbench.col.status", "tool.httpbench.col.count", "tool.httpbench.col.share");
    private final DefaultTableModel errorModel = readOnlyModel(
            "tool.httpbench.col.errorKind", "tool.httpbench.col.count", "tool.httpbench.col.share");
    private final DefaultTableModel secondModel = readOnlyModel(
            "tool.httpbench.col.second", "tool.httpbench.col.requests", "tool.httpbench.col.errors",
            "tool.httpbench.col.p50", "tool.httpbench.col.p99", "tool.httpbench.col.bytes");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel root = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));

    BenchResultView() {
        root.setOpaque(false);
        JPanel kpis = new JPanel(new GridLayout(1, 6, Tokens.SPACE_SM, 0));
        kpis.setOpaque(false);
        kpis.add(kpi("tool.httpbench.kpi.elapsed", elapsedValue));
        kpis.add(kpi("tool.httpbench.kpi.completed", completedValue));
        kpis.add(kpi("tool.httpbench.kpi.qps", qpsValue));
        kpis.add(kpi("tool.httpbench.kpi.p50", p50Value));
        kpis.add(kpi("tool.httpbench.kpi.p99", p99Value));
        kpis.add(kpi("tool.httpbench.kpi.errorRate", errorValue));

        tabs.setBorder(null);
        tabs.addTab(I18n.get("tool.httpbench.tab.chart"), chart);
        tabs.addTab(I18n.get("tool.httpbench.tab.summary"), table(summaryModel));
        tabs.addTab(I18n.get("tool.httpbench.tab.status"), table(statusModel));
        tabs.addTab(I18n.get("tool.httpbench.tab.errors"), table(errorModel));
        tabs.addTab(I18n.get("tool.httpbench.tab.seconds"), table(secondModel));

        root.add(kpis, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        showSnapshot(BenchSnapshot.idle());
    }

    JComponent component() {
        return root;
    }

    /** 刷新指标条与趋势图，只能在 EDT 调用。 */
    void showSnapshot(BenchSnapshot snapshot) {
        LatencyHistogram latency = snapshot.latency();
        elapsedValue.setText(BenchFormat.seconds(snapshot.elapsedSeconds()));
        completedValue.setText(String.valueOf(snapshot.completed()));
        qpsValue.setText(BenchFormat.rate(snapshot.requestsPerSecond()));
        p50Value.setText(latency.isEmpty() ? "-" : BenchFormat.latency(latency.valueAtPercentile(50)));
        p99Value.setText(latency.isEmpty() ? "-" : BenchFormat.latency(latency.valueAtPercentile(99)));
        errorValue.setText(BenchFormat.percent(snapshot.errorRate()));
        errorValue.setForeground(snapshot.errors() > 0 ? Tokens.danger() : Tokens.foreground());
        chart.setSeconds(snapshot.timeline());
    }

    /** 结束后填明细表，只能在 EDT 调用。 */
    void showResult(BenchResult result) {
        BenchSnapshot snapshot = result.snapshot();
        showSnapshot(snapshot);
        LatencyHistogram latency = snapshot.latency();

        summaryModel.setRowCount(0);
        summaryRow("tool.httpbench.metric.outcome", BenchFormat.outcomeLabel(result.outcome()));
        summaryRow("tool.httpbench.metric.completed", String.valueOf(snapshot.completed()));
        summaryRow("tool.httpbench.metric.errors", snapshot.errors() + " (" + BenchFormat.percent(snapshot.errorRate()) + ")");
        summaryRow("tool.httpbench.metric.duration", BenchFormat.seconds(snapshot.elapsedSeconds()));
        summaryRow("tool.httpbench.metric.qps", BenchFormat.rate(snapshot.requestsPerSecond()));
        summaryRow("tool.httpbench.metric.transfer", FormatUtils.bytes(snapshot.bytesReceived())
                + " (" + FormatUtils.bytes((long) snapshot.bytesPerSecond()) + "/s)");
        summaryRow("tool.httpbench.metric.maxInFlight", String.valueOf(snapshot.maxInFlight()));
        summaryRow("tool.httpbench.metric.min", BenchFormat.latency(latency.min()));
        summaryRow("tool.httpbench.metric.mean", BenchFormat.latency(latency.mean()));
        summaryRow("tool.httpbench.metric.stddev", BenchFormat.latency(latency.stddev()));
        for (double p : BenchFormat.PERCENTILES) {
            summaryModel.addRow(new Object[]{BenchFormat.percentileLabel(p),
                    BenchFormat.latency(latency.valueAtPercentile(p))});
        }
        summaryRow("tool.httpbench.metric.max", BenchFormat.latency(latency.max()));

        long total = Math.max(1, snapshot.completed());
        statusModel.setRowCount(0);
        for (Map.Entry<Integer, Long> entry : snapshot.statusCounts().entrySet()) {
            statusModel.addRow(new Object[]{String.valueOf(entry.getKey()), entry.getValue(),
                    BenchFormat.percent(entry.getValue() / (double) total)});
        }
        errorModel.setRowCount(0);
        for (Map.Entry<BenchErrorKind, Long> entry : snapshot.errorCounts().entrySet()) {
            errorModel.addRow(new Object[]{BenchFormat.errorLabel(entry.getKey()), entry.getValue(),
                    BenchFormat.percent(entry.getValue() / (double) total)});
        }
        secondModel.setRowCount(0);
        for (BenchSecond second : snapshot.timeline()) {
            secondModel.addRow(new Object[]{second.second(), second.requests(), second.errors(),
                    second.p50Micros() > 0 ? BenchFormat.latency(second.p50Micros()) : "-",
                    second.p99Micros() > 0 ? BenchFormat.latency(second.p99Micros()) : "-",
                    FormatUtils.bytes(second.bytes())});
        }
    }

    /** 新一轮开始前清空明细。 */
    void reset() {
        summaryModel.setRowCount(0);
        statusModel.setRowCount(0);
        errorModel.setRowCount(0);
        secondModel.setRowCount(0);
        showSnapshot(BenchSnapshot.idle());
        tabs.setSelectedIndex(0);
    }

    private void summaryRow(String key, String value) {
        summaryModel.addRow(new Object[]{I18n.get(key), value});
    }

    // ------------------------------------------------------------------ 测试可见

    int summaryRows() {
        return summaryModel.getRowCount();
    }

    int statusRows() {
        return statusModel.getRowCount();
    }

    int secondRows() {
        return secondModel.getRowCount();
    }

    String completedText() {
        return completedValue.getText();
    }

    BenchChart chart() {
        return chart;
    }

    // ------------------------------------------------------------------ 组件

    private static JLabel kpiValue() {
        JLabel label = new JLabel("-");
        label.setFont(Tokens.fontTitle());
        label.setForeground(Tokens.foreground());
        return label;
    }

    private static JPanel kpi(String captionKey, JLabel value) {
        JPanel tile = new JPanel(new BorderLayout(0, 2));
        tile.setOpaque(false);
        tile.setBorder(BorderFactory.createCompoundBorder(KitBorders.lineSubtle(1, 1, 1, 1),
                KitBorders.padding(Tokens.SPACE_SM)));
        tile.add(Fields.caption(I18n.get(captionKey)), BorderLayout.NORTH);
        tile.add(value, BorderLayout.CENTER);
        return tile;
    }

    private static DefaultTableModel readOnlyModel(String... columnKeys) {
        Object[] columns = new Object[columnKeys.length];
        for (int i = 0; i < columnKeys.length; i++) {
            columns[i] = I18n.get(columnKeys[i]);
        }
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JScrollPane table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        return Fields.scroll(table);
    }
}
