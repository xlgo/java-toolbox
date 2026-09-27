package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.BenchPlan;
import com.aqishi.toolbox.feature.network.domain.BenchRequest;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 压测面板的"负载"卡片：并发、停止条件、限速、预热、超时、协议与断言。
 *
 * <p>所有上限都与 {@link BenchPlan} 的硬上限一致，微调框本身就拦住了越界输入；
 * 最终仍以 {@link BenchPlan.Builder#build()} 的校验为准。</p>
 */
final class BenchLoadForm {

    private final JSpinner concurrencySpinner = Fields.spinner(10, 1, BenchPlan.MAX_CONCURRENCY, 1);
    private final JRadioButton byRequests = Fields.radio(I18n.get("tool.httpbench.mode.requests"), true);
    private final JRadioButton byDuration = Fields.radio(I18n.get("tool.httpbench.mode.duration"), false);
    private final JSpinner requestsSpinner = Fields.spinner(1000, 1, (int) BenchPlan.MAX_TOTAL_REQUESTS, 100);
    private final JSpinner secondsSpinner = Fields.spinner(10, 1, (int) BenchPlan.MAX_DURATION.getSeconds(), 5);
    private final JSpinner rpsSpinner = Fields.spinner(0, 0, (int) BenchPlan.MAX_TARGET_RPS, 10);
    private final JSpinner warmupSpinner = Fields.spinner(0, 0, (int) BenchPlan.MAX_TOTAL_REQUESTS, 10);
    private final JLabel warmupUnit = Fields.caption(I18n.get("tool.httpbench.unit.requests"));
    private final JSpinner connectTimeoutSpinner = Fields.spinner(5000, 1, 600_000, 500);
    private final JSpinner requestTimeoutSpinner = Fields.spinner(30_000, 1, 600_000, 1000);
    private final JComboBox<String> versionBox = Fields.combo(new String[]{"HTTP/1.1", "HTTP/2"}, 110);
    private final JCheckBox redirectsCheck = Fields.check(I18n.get("tool.httpbench.check.redirects"), false);
    private final JCheckBox insecureCheck = Fields.check(I18n.get("tool.httpbench.check.insecure"), false);
    private final JLabel insecureWarning = new JLabel(I18n.get("tool.httpbench.caption.insecure"));
    private final JTextField statusField = Fields.mono("200-299");
    private final JTextField containsField = Fields.mono("");
    private final Card card;

    BenchLoadForm() {
        ButtonGroup modes = new ButtonGroup();
        modes.add(byRequests);
        modes.add(byDuration);
        byRequests.addActionListener(event -> syncMode());
        byDuration.addActionListener(event -> syncMode());
        insecureCheck.addActionListener(event -> syncInsecure());
        containsField.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.httpbench.placeholder.contains"));
        rpsSpinner.setToolTipText(I18n.get("tool.httpbench.tip.rps"));

        FormGrid form = new FormGrid();
        form.rowCompact(I18n.get("tool.httpbench.label.concurrency"), concurrencySpinner);
        form.rowCompact(I18n.get("tool.httpbench.label.stop"),
                row(byRequests, requestsSpinner, byDuration, secondsSpinner,
                        Fields.caption(I18n.get("tool.httpbench.unit.seconds"))));
        form.rowCompact(I18n.get("tool.httpbench.label.rps"), row(rpsSpinner,
                Fields.caption(I18n.get("tool.httpbench.caption.rps"))));
        form.rowCompact(I18n.get("tool.httpbench.label.warmup"), row(warmupSpinner, warmupUnit));
        form.rowCompact(I18n.get("tool.httpbench.label.timeouts"), row(
                Fields.caption(I18n.get("tool.httpbench.label.connectTimeout")), connectTimeoutSpinner,
                Fields.caption(I18n.get("tool.httpbench.label.requestTimeout")), requestTimeoutSpinner,
                Fields.caption(I18n.get("tool.httpbench.unit.ms"))));
        form.rowCompact(I18n.get("tool.httpbench.label.protocol"), row(versionBox, redirectsCheck, insecureCheck));
        form.fullRow(insecureWarning);
        form.row(I18n.get("tool.httpbench.label.expectStatus"), statusField);
        form.row(I18n.get("tool.httpbench.label.bodyContains"), containsField);
        form.caption(I18n.get("tool.httpbench.caption.connections"));

        card = Card.titled(I18n.get("tool.httpbench.card.load"));
        card.setContent(form);
        syncMode();
        syncInsecure();
    }

    private static JPanel row(Component... children) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        panel.setOpaque(false);
        for (Component child : children) {
            panel.add(child);
        }
        return panel;
    }

    private void syncMode() {
        boolean requests = byRequests.isSelected();
        requestsSpinner.setEnabled(requests);
        secondsSpinner.setEnabled(!requests);
        warmupUnit.setText(I18n.get(requests ? "tool.httpbench.unit.requests" : "tool.httpbench.unit.seconds"));
    }

    private void syncInsecure() {
        insecureWarning.setVisible(insecureCheck.isSelected());
        insecureWarning.setFont(Tokens.fontCaption());
        insecureWarning.setForeground(Tokens.danger());
    }

    Card card() {
        return card;
    }

    /**
     * 组合成压测计划。
     *
     * @throws IllegalArgumentException 参数不合法（英文消息，来自领域层校验）
     */
    BenchPlan toPlan(BenchRequest request) {
        int[] status = BenchPlan.parseStatusRange(statusField.getText());
        BenchPlan.Builder builder = BenchPlan.builder(request)
                .concurrency(intValue(concurrencySpinner))
                .targetRps(intValue(rpsSpinner))
                .connectTimeout(Duration.ofMillis(intValue(connectTimeoutSpinner)))
                .requestTimeout(Duration.ofMillis(intValue(requestTimeoutSpinner)))
                .httpVersion(versionBox.getSelectedIndex() == 1 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .followRedirects(redirectsCheck.isSelected())
                .insecureTls(insecureCheck.isSelected())
                .expectedStatus(status[0], status[1])
                .bodyContains(containsField.getText());
        if (byRequests.isSelected()) {
            builder.totalRequests(intValue(requestsSpinner)).warmupRequests(intValue(warmupSpinner));
        } else {
            builder.duration(Duration.ofSeconds(intValue(secondsSpinner)))
                    .warmupDuration(Duration.ofSeconds(intValue(warmupSpinner)));
        }
        return builder.build();
    }

    boolean isInsecureTls() {
        return insecureCheck.isSelected();
    }

    void setEditable(boolean editable) {
        for (JComponent component : new JComponent[]{concurrencySpinner, byRequests, byDuration, rpsSpinner,
                warmupSpinner, connectTimeoutSpinner, requestTimeoutSpinner, versionBox, redirectsCheck,
                insecureCheck, statusField, containsField}) {
            component.setEnabled(editable);
        }
        if (editable) {
            syncMode();
        } else {
            requestsSpinner.setEnabled(false);
            secondsSpinner.setEnabled(false);
        }
    }

    /** 测试与脚本用：直接设定并发与请求数。 */
    void configureRequests(int concurrency, int requests) {
        concurrencySpinner.setValue(concurrency);
        byRequests.setSelected(true);
        requestsSpinner.setValue(requests);
        syncMode();
    }

    private static int intValue(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }
}
