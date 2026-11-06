package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.system.domain.JvmOptionAnalyzer;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;

/**
 * JVM 启动参数分析与解释面板。
 *
 * <p>支持解析单行 shell 命令、多行参数与环境变量，识别冲突、重复、JDK 版本废弃/移除项，并给出优化建议。</p>
 */
public final class JvmOptionsPanel extends ToolPanel implements ManagedResourceOwner {

    private static final String SAMPLE =
            "# Typical Java service startup command example\n"
                    + "java -server \\\n"
                    + "  -Xms2048m -Xmx4096m -Xss512k \\\n"
                    + "  -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m \\\n"
                    + "  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \\\n"
                    + "  -XX:+HeapDumpOnOutOfMemoryError \\\n"
                    + "  -XX:+UseConcMarkSweepGC \\\n"
                    + "  -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai \\\n"
                    + "  -javaagent:/opt/agents/skywalking-agent.jar \\\n"
                    + "  -jar app.jar --spring.profiles.active=prod\n";

    private TextWorkbench workbench;

    public JvmOptionsPanel() {
        super(ToolCatalog.JVM_OPTIONS);
    }

    @Override
    protected JComponent build() {
        Integer[] jdkVersions = {8, 11, 17, 21};
        JComboBox<Integer> jdkCombo = Fields.combo(jdkVersions);
        jdkCombo.setSelectedItem(17);

        FormGrid options = new FormGrid();
        options.row(I18n.get("jvm.jdkSelect"), jdkCombo);
        options.fullRow(Fields.note(I18n.get("jvm.hint")));

        workbench = new TextWorkbench(
                options,
                () -> {
                    String input = workbench.input.getText();
                    Integer selectedJdk = (Integer) jdkCombo.getSelectedItem();
                    int jdk = selectedJdk != null ? selectedJdk : 17;
                    return () -> new JvmOptionAnalyzer(jdk).analyze(input);
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
