package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.system.domain.StackTraceReport;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;

/** 异常堆栈分析：离线解析异常链、定位根因与首个业务帧，并折叠框架帧。 */
public final class StackTracePanel extends ToolPanel implements ManagedResourceOwner {
    private static final String SAMPLE =
            "2026-11-05 10:00:00.123 ERROR [http-nio-8080-exec-1] c.e.OrderController - failed\n"
                    + "org.springframework.dao.DataAccessResourceFailureException: query failed;"
                    + " nested exception is java.sql.SQLTransientConnectionException\n"
                    + "\tat org.springframework.jdbc.support.SQLStateSQLExceptionTranslator"
                    + ".doTranslate(SQLStateSQLExceptionTranslator.java:105)\n"
                    + "\tat org.springframework.jdbc.core.JdbcTemplate.query(JdbcTemplate.java:732)\n"
                    + "\tat com.example.order.OrderDao.findById(OrderDao.java:42)\n"
                    + "\tat com.example.order.OrderService.detail(OrderService.java:27)\n"
                    + "\tat com.example.order.OrderService$$SpringCGLIB$$0.detail(<generated>)\n"
                    + "\tat com.example.order.OrderController.detail(OrderController.java:19)\n"
                    + "\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor"
                    + ".invoke(DirectMethodHandleAccessor.java:103)\n"
                    + "\tat org.springframework.web.servlet.FrameworkServlet"
                    + ".service(FrameworkServlet.java:885)\n"
                    + "Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 -"
                    + " Connection is not available, request timed out after 30000ms.\n"
                    + "\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException"
                    + "(HikariPool.java:696)\n"
                    + "\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)\n"
                    + "\t... 6 common frames omitted\n";

    private TextWorkbench workbench;

    public StackTracePanel() {
        super(ToolCatalog.STACKTRACE_ANALYZER);
    }

    @Override
    protected JComponent build() {
        JTextField packages = Fields.text("", "com.example, org.demo");
        JCheckBox fold = Fields.check(I18n.get("stack.fold"), true);
        FormGrid options = new FormGrid();
        options.row(I18n.get("stack.packages"), packages);
        options.row("", fold);
        options.fullRow(Fields.note(I18n.get("stack.hint")));
        workbench =
                new TextWorkbench(
                        options,
                        () -> {
                            String input = workbench.input.getText();
                            var report = new StackTraceReport(packages.getText(), fold.isSelected());
                            return () -> report.analyze(input);
                        },
                        SAMPLE);
        JPanel root = Layouts.page();
        root.add(workbench);
        return root;
    }

    @Override
    public void closeResources() {
        if (workbench != null) workbench.closeResources();
    }
}
