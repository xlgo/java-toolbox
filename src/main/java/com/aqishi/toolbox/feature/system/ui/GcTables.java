package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.GcAdvice;
import com.aqishi.toolbox.feature.system.domain.GcEvent;
import com.aqishi.toolbox.feature.system.domain.GcLog;
import com.aqishi.toolbox.feature.system.domain.GcReport;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.List;
import java.util.Locale;

/**
 * 结果区的各个表格页：停顿、原因、阶段、安全点、建议、无法识别的行。
 */
final class GcTables {

    final ThreadDumpPanel.RowsModel<GcReport.TypeStats> typeModel = new ThreadDumpPanel.RowsModel<>();
    final ThreadDumpPanel.RowsModel<GcEvent> pauseModel = new ThreadDumpPanel.RowsModel<>();
    final ThreadDumpPanel.RowsModel<GcReport.CauseStats> causeModel = new ThreadDumpPanel.RowsModel<>();
    final ThreadDumpPanel.RowsModel<GcReport.PhaseStats> phaseModel = new ThreadDumpPanel.RowsModel<>();
    final ThreadDumpPanel.RowsModel<GcReport.OperationStats> safepointModel = new ThreadDumpPanel.RowsModel<>();
    final ThreadDumpPanel.RowsModel<GcAdvice> adviceModel = new ThreadDumpPanel.RowsModel<>();
    final ThreadDumpPanel.RowsModel<GcLog.Unparsed> unparsedModel = new ThreadDumpPanel.RowsModel<>();

    private JTable adviceTable;
    private JTextArea adviceDetail;
    private JLabel safepointSummary;
    private JLabel unparsedSummary;

    GcTables() {
        typeModel.column(I18n.get("tool.gclog.column.type"), String.class, GcReport.TypeStats::type)
                .column(I18n.get("tool.gclog.column.count"), Integer.class, stats -> stats.stats().count())
                .column(I18n.get("tool.gclog.column.total"), Double.class, stats -> stats.stats().totalMs())
                .column(I18n.get("tool.gclog.column.avg"), Double.class, stats -> stats.stats().avgMs())
                .column("p50", Double.class, stats -> stats.stats().p50())
                .column("p95", Double.class, stats -> stats.stats().p95())
                .column("p99", Double.class, stats -> stats.stats().p99())
                .column(I18n.get("tool.gclog.column.max"), Double.class, stats -> stats.stats().maxMs());
        pauseModel.column(I18n.get("tool.gclog.column.time"), String.class,
                        event -> event.date() != null ? event.date() : GcFormat.seconds(event.time()))
                .column(I18n.get("tool.gclog.column.uptime"), Double.class, GcEvent::uptime)
                .column(I18n.get("tool.gclog.column.type"), String.class, GcEvent::type)
                .column(I18n.get("tool.gclog.column.category"), String.class, event -> GcFormat.category(event.category()))
                .column(I18n.get("tool.gclog.column.cause"), String.class, event -> GcFormat.cause(event.cause()))
                .column(I18n.get("tool.gclog.column.durationMs"), Double.class, GcEvent::durationMs)
                .column(I18n.get("tool.gclog.column.heap"), String.class, GcFormat::heap)
                .column(I18n.get("tool.gclog.column.reclaimedMb"), Double.class, event -> event.hasHeap()
                        ? (event.heapBeforeKb() - event.heapAfterKb()) / 1024.0 : Double.NaN);
        causeModel.column(I18n.get("tool.gclog.column.cause"), String.class, stats -> GcFormat.cause(stats.cause()))
                .column(I18n.get("tool.gclog.column.collections"), Integer.class, GcReport.CauseStats::collections)
                .column(I18n.get("tool.gclog.column.fullGc"), Integer.class, GcReport.CauseStats::fullCount)
                .column(I18n.get("tool.gclog.column.total"), Double.class, GcReport.CauseStats::totalPauseMs)
                .column(I18n.get("tool.gclog.column.max"), Double.class, GcReport.CauseStats::maxPauseMs);
        phaseModel.column(I18n.get("tool.gclog.column.phase"), String.class, GcReport.PhaseStats::name)
                .column(I18n.get("tool.gclog.column.kind"), String.class, stats -> GcFormat.phaseKind(stats.kind()))
                .column(I18n.get("tool.gclog.column.count"), Integer.class, GcReport.PhaseStats::count)
                .column(I18n.get("tool.gclog.column.total"), Double.class, GcReport.PhaseStats::totalMs)
                .column(I18n.get("tool.gclog.column.avg"), Double.class, GcReport.PhaseStats::avgMs)
                .column(I18n.get("tool.gclog.column.max"), Double.class, GcReport.PhaseStats::maxMs);
        safepointModel.column(I18n.get("tool.gclog.column.operation"), String.class,
                        stats -> stats.operation() == null ? I18n.get("tool.gclog.value.unknownOperation")
                                : stats.operation())
                .column(I18n.get("tool.gclog.column.count"), Integer.class, GcReport.OperationStats::count)
                .column(I18n.get("tool.gclog.column.total"), Double.class, GcReport.OperationStats::totalMs)
                .column(I18n.get("tool.gclog.column.maxTts"), Double.class, GcReport.OperationStats::maxReachMs);
        adviceModel.column(I18n.get("tool.gclog.column.severity"), GcAdvice.Severity.class, GcAdvice::severity)
                .column(I18n.get("tool.gclog.column.advice"), String.class, GcReportText::adviceTitle);
        unparsedModel.column(I18n.get("tool.gclog.column.line"), Long.class, GcLog.Unparsed::lineNumber)
                .column(I18n.get("tool.gclog.column.text"), String.class, GcLog.Unparsed::text);
    }

    // ---------------------------------------------------------------- 页签

    JComponent pausesTab() {
        JTable types = table(typeModel);
        JTable pauses = table(pauseModel);
        pauses.getColumnModel().getColumn(0).setPreferredWidth(190);
        pauses.getColumnModel().getColumn(2).setPreferredWidth(200);
        pauses.getColumnModel().getColumn(4).setPreferredWidth(170);
        pauses.getColumnModel().getColumn(6).setPreferredWidth(220);
        types.getColumnModel().getColumn(0).setPreferredWidth(220);
        JPanel panel = shell();
        panel.add(Layouts.splitVertical(Fields.scrollBoxed(types), Fields.scrollBoxed(pauses), 0.3, 0.3),
                BorderLayout.CENTER);
        return panel;
    }

    JComponent causesTab() {
        JTable causes = table(causeModel);
        causes.getColumnModel().getColumn(0).setPreferredWidth(320);
        JPanel panel = shell();
        panel.add(Fields.scrollBoxed(causes), BorderLayout.CENTER);
        return panel;
    }

    JComponent phasesTab() {
        JTable phases = table(phaseModel);
        phases.getColumnModel().getColumn(0).setPreferredWidth(320);
        JPanel panel = shell();
        panel.add(Fields.scrollBoxed(phases), BorderLayout.CENTER);
        return panel;
    }

    JComponent safepointsTab() {
        JTable table = table(safepointModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        safepointSummary = Fields.caption(I18n.get("tool.gclog.safepoint.none"));
        JPanel panel = shell();
        panel.add(safepointSummary, BorderLayout.NORTH);
        panel.add(Fields.scrollBoxed(table), BorderLayout.CENTER);
        return panel;
    }

    JComponent adviceTab() {
        adviceTable = table(adviceModel);
        adviceTable.setAutoCreateRowSorter(false);
        adviceTable.getColumnModel().getColumn(0).setMaxWidth(110);
        adviceTable.getColumnModel().getColumn(0).setCellRenderer(new SeverityRenderer());
        adviceDetail = Fields.output(6, 40);
        adviceDetail.setLineWrap(true);
        adviceDetail.setWrapStyleWord(true);
        adviceTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showAdvice();
            }
        });
        JPanel panel = shell();
        panel.add(Layouts.splitVertical(Fields.scrollBoxed(adviceTable), Fields.scrollBoxed(adviceDetail), 0.45, 0.45),
                BorderLayout.CENTER);
        return panel;
    }

    JComponent unparsedTab() {
        JTable table = table(unparsedModel);
        table.getColumnModel().getColumn(0).setMaxWidth(100);
        table.getColumnModel().getColumn(1).setPreferredWidth(700);
        unparsedSummary = Fields.caption("");
        JPanel panel = shell();
        panel.add(unparsedSummary, BorderLayout.NORTH);
        panel.add(Fields.scrollBoxed(table), BorderLayout.CENTER);
        return panel;
    }

    private void showAdvice() {
        int view = adviceTable.getSelectedRow();
        if (view < 0 || view >= adviceModel.getRowCount()) {
            adviceDetail.setText(adviceModel.getRowCount() == 0 ? I18n.get("tool.gclog.advice.none") : "");
            return;
        }
        GcAdvice advice = adviceModel.rowAt(adviceTable.convertRowIndexToModel(view));
        adviceDetail.setText(GcReportText.adviceTitle(advice) + "\n\n" + GcReportText.adviceDetail(advice));
        adviceDetail.setCaretPosition(0);
    }

    // ---------------------------------------------------------------- 填充

    void fill(GcReport report) {
        typeModel.setRows(report == null ? List.of() : report.byType());
        pauseModel.setRows(report == null ? List.of() : GcPauseChart.pauseEvents(report));
        causeModel.setRows(report == null ? List.of() : report.causes());
        phaseModel.setRows(report == null ? List.of() : report.phases());
        GcReport.SafepointStats safepoints = report == null ? null : report.safepoints();
        safepointModel.setRows(safepoints == null ? List.of() : safepoints.operations());
        safepointSummary.setText(safepoints == null ? I18n.get("tool.gclog.safepoint.none")
                : I18n.get("tool.gclog.safepoint.summary", String.valueOf(safepoints.count()),
                GcFormat.ms(safepoints.totalMs()), GcFormat.ms(safepoints.reach().p99()),
                GcFormat.ms(safepoints.reach().maxMs())));
        adviceModel.setRows(report == null ? List.of() : report.advice());
        if (adviceModel.getRowCount() > 0) {
            adviceTable.setRowSelectionInterval(0, 0);
        }
        showAdvice();
        GcLog log = report == null ? null : report.log();
        unparsedModel.setRows(log == null ? List.of() : log.unparsedSample());
        unparsedSummary.setText(log == null ? "" : I18n.get("tool.gclog.unparsed.summary",
                String.valueOf(log.unparsedLines()), String.valueOf(log.totalLines()),
                String.valueOf(log.ignoredLines()), String.valueOf(log.unparsedSample().size())));
    }

    // ---------------------------------------------------------------- 支撑

    private static JPanel shell() {
        JPanel panel = Layouts.box(0, Tokens.SPACE_SM);
        panel.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        return panel;
    }

    private static JTable table(ThreadDumpPanel.RowsModel<?> model) {
        JTable table = new JTable(model);
        table.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(Double.class, new NumberRenderer());
        return table;
    }

    /** 数值列：按量级保留小数，NaN 显示为横线；右对齐便于比较。 */
    private static final class NumberRenderer extends DefaultTableCellRenderer {
        NumberRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            if (!(value instanceof Double) || !Double.isFinite((Double) value)) {
                setText("-");
                return;
            }
            double number = (Double) value;
            setText(String.format(Locale.ROOT, Math.abs(number) >= 100 ? "%.1f" : "%.3f", number));
        }
    }

    private static final class SeverityRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            GcAdvice.Severity severity = value instanceof GcAdvice.Severity ? (GcAdvice.Severity) value
                    : GcAdvice.Severity.INFO;
            Component component = super.getTableCellRendererComponent(table, GcFormat.severity(severity), isSelected,
                    hasFocus, row, column);
            if (!isSelected) {
                component.setForeground(GcFormat.severityColor(severity));
            }
            return component;
        }
    }
}
