package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.system.domain.CronConflictDetector;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.time.Duration;
import java.time.ZoneId;

/**
 * 多 Cron 任务冲突检测与时间窗预测面板。
 *
 * <p>支持批量输入定时任务与 Cron 表达式，预测未来时间窗内的全部执行时间序列，
 * 自动识别并发撞车点与时间线甘特明细。</p>
 */
public final class CronConflictPanel extends ToolPanel implements ManagedResourceOwner {

    private static final String SAMPLE =
            "# Nightly batch & maintenance schedules\n"
                    + "db_backup         0 0 2 * * ?\n"
                    + "log_archive       0 0 2 * * ?\n"
                    + "temp_cleanup      0 0 2 * * ?\n"
                    + "report_daily      0 30 2 * * ?\n"
                    + "token_refresh     0 */15 * * * ?\n"
                    + "health_check      0 */5 * * * ?\n";

    private TextWorkbench workbench;

    public CronConflictPanel() {
        super(ToolCatalog.CRON_CONFLICT);
    }

    @Override
    protected JComponent build() {
        String[] windowKeys = {"24h", "1h", "6h", "7d", "30d"};
        JComboBox<String> windowCombo = Fields.combo(windowKeys);
        windowCombo.setSelectedItem("24h");

        FormGrid options = new FormGrid();
        options.row(I18n.get("cron.conflict.window"), windowCombo);
        options.fullRow(Fields.note(I18n.get("cron.conflict.hint")));

        workbench = new TextWorkbench(
                options,
                () -> {
                    String input = workbench.input.getText();
                    String selected = (String) windowCombo.getSelectedItem();
                    Duration duration = switch (selected != null ? selected : "24h") {
                        case "1h" -> Duration.ofHours(1);
                        case "6h" -> Duration.ofHours(6);
                        case "7d" -> Duration.ofDays(7);
                        case "30d" -> Duration.ofDays(30);
                        default -> Duration.ofHours(24);
                    };
                    return () -> new CronConflictDetector(duration, ZoneId.systemDefault()).analyze(input);
                },
                SAMPLE
        );

        JPanel root = Layouts.page();
        root.add(workbench);
        return root;
    }

    @Override
    public void closeResources() {
        if (workbench != null) {
            workbench.closeResources();
        }
    }
}
