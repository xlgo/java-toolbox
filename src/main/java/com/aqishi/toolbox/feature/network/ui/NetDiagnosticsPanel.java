package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.network.domain.NetDiagnosticsService;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * DNS / TLS / HTTP 诊断面板。
 *
 * <p>三个页签共用一个「目标」输入：查域名、看证书、测耗时通常是同一个排查动作的三步，
 * 换页签时不必把主机名再敲一遍。</p>
 */
public class NetDiagnosticsPanel extends ToolPanel implements ManagedResourceOwner {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final NetDiagnosticsService service;

    // DNS
    private JTextField dnsHostField;
    private JComboBox<String> resolverCombo;
    private JCheckBox reverseCheck;
    private Map<String, JCheckBox> recordChecks = new LinkedHashMap<>();
    private DefaultTableModel dnsModel;
    private JLabel dnsStatusLabel;
    private JButton dnsQueryBtn;
    private final AtomicReference<SwingWorker<?, ?>> dnsWorker = new AtomicReference<>();

    // TLS
    private JTextField tlsHostField;
    private JSpinner tlsPortSpinner;
    private JTextField tlsSniField;
    private JTextArea tlsReportArea;
    private JLabel tlsStatusLabel;
    private JButton tlsInspectBtn;
    private final AtomicReference<SwingWorker<?, ?>> tlsWorker = new AtomicReference<>();

    // HTTP
    private JTextField httpUrlField;
    private JComboBox<String> httpMethodCombo;
    private JSpinner httpRedirectSpinner;
    private DefaultTableModel httpTimingModel;
    private JTextArea httpDetailArea;
    private JLabel httpStatusLabel;
    private JButton httpProbeBtn;
    private final AtomicReference<SwingWorker<?, ?>> httpWorker = new AtomicReference<>();

    private JSpinner timeoutSpinner;

    public NetDiagnosticsPanel() {
        this(ToolCatalog.NET_DIAGNOSTICS, new NetDiagnosticsService());
    }

    public NetDiagnosticsPanel(NetDiagnosticsService service) {
        this(ToolCatalog.NET_DIAGNOSTICS, service);
    }

    public NetDiagnosticsPanel(ToolDescriptor descriptor, NetDiagnosticsService service) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        timeoutSpinner = Fields.spinner(5000, 500, 60000, 500);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(I18n.get("tool.netdiag.tab.dns"), buildDnsTab());
        tabs.addTab(I18n.get("tool.netdiag.tab.tls"), buildTlsTab());
        tabs.addTab(I18n.get("tool.netdiag.tab.http"), buildHttpTab());

        // 切页签时把目标带过去，省掉重复输入。
        tabs.addChangeListener(event -> syncTarget(tabs.getSelectedIndex()));

        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    private JPanel tabShell() {
        JPanel panel = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(
                Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM));
        return panel;
    }

    // ==========================================
    // DNS
    // ==========================================
    private JComponent buildDnsTab() {
        JPanel panel = tabShell();

        dnsHostField = Fields.mono("example.com");
        resolverCombo = Fields.combo(NetDiagnosticsService.commonResolvers().toArray(new String[0]));
        resolverCombo.setEditable(true);
        resolverCombo.setToolTipText(I18n.get("tool.netdiag.dns.resolver.tip"));
        reverseCheck = Fields.check(I18n.get("tool.netdiag.dns.reverse"), false);
        reverseCheck.setToolTipText(I18n.get("tool.netdiag.dns.reverse.tip"));

        JPanel typesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        typesRow.setOpaque(false);
        recordChecks = new LinkedHashMap<>();
        for (String type : NetDiagnosticsService.RECORD_TYPES) {
            if ("PTR".equals(type)) {
                continue;
            }
            JCheckBox check = Fields.check(type,
                    NetDiagnosticsService.DEFAULT_RECORD_TYPES.contains(type));
            recordChecks.put(type, check);
            typesRow.add(check);
        }

        dnsQueryBtn = Buttons.primary(I18n.get("tool.netdiag.btn.query"));
        JPanel resolverRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        resolverRow.setOpaque(false);
        resolverRow.add(resolverCombo);
        resolverRow.add(reverseCheck);
        resolverRow.add(Fields.label(I18n.get("tool.netdiag.label.timeout")));
        resolverRow.add(timeoutSpinner);
        resolverRow.add(dnsQueryBtn);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.netdiag.label.host"), dnsHostField);
        form.row(I18n.get("tool.netdiag.label.resolver"), resolverRow);
        form.row(I18n.get("tool.netdiag.label.recordTypes"), typesRow);

        Card configCard = Card.titled(I18n.get("tool.netdiag.card.dnsConfig"));
        configCard.setContent(form);

        dnsModel = new DefaultTableModel(new Object[]{
                I18n.get("tool.netdiag.column.type"),
                I18n.get("tool.netdiag.column.value"),
                I18n.get("tool.netdiag.column.source")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(dnsModel);
        table.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        table.getColumnModel().getColumn(0).setMaxWidth(90);
        table.getColumnModel().getColumn(2).setMaxWidth(160);

        Card resultCard = Card.flush(I18n.get("tool.netdiag.card.dnsResult"));
        resultCard.setContent(Fields.scroll(table));
        dnsStatusLabel = Fields.caption(I18n.get("tool.netdiag.status.ready"));
        resultCard.addHeaderAction(dnsStatusLabel);

        panel.add(configCard, BorderLayout.NORTH);
        panel.add(resultCard, BorderLayout.CENTER);

        dnsQueryBtn.addActionListener(event -> runDnsQuery());
        dnsHostField.addActionListener(event -> runDnsQuery());
        return panel;
    }

    private void runDnsQuery() {
        String host = dnsHostField.getText().trim();
        if (host.isEmpty()) {
            UIUtils.warn(null, I18n.get("tool.netdiag.error.emptyHost"), getLabel());
            return;
        }
        List<String> types = new ArrayList<>();
        for (Map.Entry<String, JCheckBox> entry : recordChecks.entrySet()) {
            if (entry.getValue().isSelected()) {
                types.add(entry.getKey());
            }
        }
        String resolver = String.valueOf(resolverCombo.getEditor().getItem()).trim();
        int timeout = timeoutValue();
        boolean reverse = reverseCheck.isSelected();

        dnsModel.setRowCount(0);
        runAsync(dnsWorker, dnsQueryBtn, dnsStatusLabel,
                () -> reverse
                        ? service.reverseLookup(host, resolver, timeout)
                        : service.lookup(host, types, resolver, timeout),
                result -> {
                    for (NetDiagnosticsService.DnsRecord record : result.records()) {
                        dnsModel.addRow(new Object[]{record.type(), record.value(), record.source()});
                    }
                    if (result.isSuccess()) {
                        setStatus(dnsStatusLabel, I18n.get("tool.netdiag.status.dnsDone",
                                result.records().size(), result.elapsedMs()), Tokens.mutedForeground());
                    } else {
                        setStatus(dnsStatusLabel, localizeError(result.error()), Tokens.danger());
                    }
                });
    }

    // ==========================================
    // TLS
    // ==========================================
    private JComponent buildTlsTab() {
        JPanel panel = tabShell();

        tlsHostField = Fields.mono("example.com");
        tlsPortSpinner = Fields.spinner(443, 1, 65535, 1);
        tlsSniField = Fields.mono("");
        tlsSniField.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.netdiag.placeholder.sni"));
        tlsInspectBtn = Buttons.primary(I18n.get("tool.netdiag.btn.inspect"));

        JPanel hostRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        hostRow.setOpaque(false);
        hostRow.add(tlsPortSpinner);
        hostRow.add(Fields.label(I18n.get("tool.netdiag.label.sni")));
        hostRow.add(tlsSniField);
        hostRow.add(tlsInspectBtn);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.netdiag.label.host"), tlsHostField);
        form.row(I18n.get("tool.netdiag.label.port"), hostRow);
        form.caption(I18n.get("tool.netdiag.caption.tls"));

        Card configCard = Card.titled(I18n.get("tool.netdiag.card.tlsConfig"));
        configCard.setContent(form);

        tlsReportArea = Fields.output(16, 30);
        Card resultCard = Card.flush(I18n.get("tool.netdiag.card.tlsResult"));
        resultCard.setContent(Fields.scroll(tlsReportArea));
        tlsStatusLabel = Fields.caption(I18n.get("tool.netdiag.status.ready"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.netdiag.btn.copy"));
        resultCard.addHeaderAction(tlsStatusLabel);
        resultCard.addHeaderAction(copyBtn);
        copyBtn.addActionListener(event -> copy(tlsReportArea));

        panel.add(configCard, BorderLayout.NORTH);
        panel.add(resultCard, BorderLayout.CENTER);

        tlsInspectBtn.addActionListener(event -> runTlsInspect());
        tlsHostField.addActionListener(event -> runTlsInspect());
        return panel;
    }

    private void runTlsInspect() {
        String host = tlsHostField.getText().trim();
        if (host.isEmpty()) {
            UIUtils.warn(null, I18n.get("tool.netdiag.error.emptyHost"), getLabel());
            return;
        }
        int port = ((Number) tlsPortSpinner.getValue()).intValue();
        String sni = tlsSniField.getText().trim();
        int timeout = timeoutValue();

        tlsReportArea.setText("");
        runAsync(tlsWorker, tlsInspectBtn, tlsStatusLabel,
                () -> service.inspectTls(host, port, sni, timeout),
                result -> {
                    tlsReportArea.setText(renderTls(result));
                    tlsReportArea.setCaretPosition(0);
                    if (!result.isSuccess()) {
                        setStatus(tlsStatusLabel, localizeError(result.error()), Tokens.danger());
                    } else if (!result.trusted()) {
                        setStatus(tlsStatusLabel, I18n.get("tool.netdiag.status.untrusted"),
                                Tokens.warning());
                    } else {
                        setStatus(tlsStatusLabel, I18n.get("tool.netdiag.status.tlsDone",
                                result.handshakeMs()), Tokens.success());
                    }
                });
    }

    private String renderTls(NetDiagnosticsService.TlsResult result) {
        StringBuilder text = new StringBuilder();
        if (!result.isSuccess()) {
            return I18n.get("tool.netdiag.tls.failed") + "\n" + localizeError(result.error());
        }
        text.append(I18n.get("tool.netdiag.tls.summary")).append('\n');
        text.append(line("tool.netdiag.tls.endpoint", result.host() + ":" + result.port()));
        text.append(line("tool.netdiag.tls.protocol", result.protocol()));
        text.append(line("tool.netdiag.tls.cipher", result.cipherSuite()));
        text.append(line("tool.netdiag.tls.sni", result.sniServerName()));
        text.append(line("tool.netdiag.tls.handshake", result.handshakeMs() + " ms"));
        text.append(line("tool.netdiag.tls.trusted", result.trusted()
                ? I18n.get("tool.netdiag.value.yes")
                : I18n.get("tool.netdiag.value.no") + " - " + localizeError(result.trustError())));
        text.append(line("tool.netdiag.tls.hostname", result.hostnameMatched()
                ? I18n.get("tool.netdiag.value.yes") : I18n.get("tool.netdiag.value.no")));

        for (NetDiagnosticsService.CertificateInfo certificate : result.chain()) {
            text.append('\n')
                    .append(I18n.get("tool.netdiag.tls.certificate", certificate.index()))
                    .append('\n');
            text.append(line("tool.netdiag.tls.subject", certificate.subject()));
            text.append(line("tool.netdiag.tls.issuer", certificate.issuer()));
            text.append(line("tool.netdiag.tls.serial", certificate.serialNumber()));
            text.append(line("tool.netdiag.tls.validity",
                    TIMESTAMP_FORMAT.format(certificate.notBefore()) + "  ~  "
                            + TIMESTAMP_FORMAT.format(certificate.notAfter())));
            text.append(line("tool.netdiag.tls.remaining", certificate.isExpired()
                    ? I18n.get("tool.netdiag.tls.expired")
                    : I18n.get("tool.netdiag.tls.daysLeft", certificate.daysRemaining())));
            text.append(line("tool.netdiag.tls.signature", certificate.signatureAlgorithm()));
            text.append(line("tool.netdiag.tls.publicKey", certificate.publicKeyAlgorithm()
                    + (certificate.publicKeyBits() > 0 ? " " + certificate.publicKeyBits() : "")));
            if (!certificate.subjectAlternativeNames().isEmpty()) {
                text.append(line("tool.netdiag.tls.san",
                        String.join(", ", certificate.subjectAlternativeNames())));
            }
            text.append(line("tool.netdiag.tls.fingerprint", certificate.sha256Fingerprint()));
            if (certificate.selfSigned()) {
                text.append(line("tool.netdiag.tls.selfSigned", I18n.get("tool.netdiag.value.yes")));
            }
        }
        return text.toString();
    }

    // ==========================================
    // HTTP
    // ==========================================
    private JComponent buildHttpTab() {
        JPanel panel = tabShell();

        httpUrlField = Fields.mono("https://example.com");
        httpMethodCombo = Fields.combo(new String[]{"GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"});
        httpRedirectSpinner = Fields.spinner(5, 0, 20, 1);
        httpProbeBtn = Buttons.primary(I18n.get("tool.netdiag.btn.probe"));

        JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        optionsRow.setOpaque(false);
        optionsRow.add(httpMethodCombo);
        optionsRow.add(Fields.label(I18n.get("tool.netdiag.label.maxRedirects")));
        optionsRow.add(httpRedirectSpinner);
        optionsRow.add(httpProbeBtn);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.netdiag.label.url"), httpUrlField);
        form.row(I18n.get("tool.netdiag.label.options"), optionsRow);
        form.caption(I18n.get("tool.netdiag.caption.http"));

        Card configCard = Card.titled(I18n.get("tool.netdiag.card.httpConfig"));
        configCard.setContent(form);

        httpTimingModel = new DefaultTableModel(new Object[]{
                I18n.get("tool.netdiag.column.phase"),
                I18n.get("tool.netdiag.column.elapsed")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable timingTable = new JTable(httpTimingModel);
        timingTable.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        timingTable.getColumnModel().getColumn(1).setMaxWidth(140);

        Card timingCard = Card.flush(I18n.get("tool.netdiag.card.timing"));
        timingCard.setContent(Fields.scroll(timingTable));
        httpStatusLabel = Fields.caption(I18n.get("tool.netdiag.status.ready"));
        timingCard.addHeaderAction(httpStatusLabel);

        httpDetailArea = Fields.output(10, 30);
        Card detailCard = Card.flush(I18n.get("tool.netdiag.card.httpDetail"));
        detailCard.setContent(Fields.scroll(httpDetailArea));
        JButton copyBtn = Buttons.snug(I18n.get("tool.netdiag.btn.copy"));
        detailCard.addHeaderAction(copyBtn);
        copyBtn.addActionListener(event -> copy(httpDetailArea));

        panel.add(configCard, BorderLayout.NORTH);
        panel.add(Layouts.splitHorizontal(timingCard, detailCard, 0.4, 0.4), BorderLayout.CENTER);

        httpProbeBtn.addActionListener(event -> runHttpProbe());
        httpUrlField.addActionListener(event -> runHttpProbe());
        return panel;
    }

    private void runHttpProbe() {
        String url = httpUrlField.getText().trim();
        if (url.isEmpty()) {
            UIUtils.warn(null, I18n.get("tool.netdiag.error.emptyUrl"), getLabel());
            return;
        }
        String method = String.valueOf(httpMethodCombo.getSelectedItem());
        int redirects = ((Number) httpRedirectSpinner.getValue()).intValue();
        int timeout = timeoutValue();

        httpTimingModel.setRowCount(0);
        httpDetailArea.setText("");
        runAsync(httpWorker, httpProbeBtn, httpStatusLabel,
                () -> service.probeHttp(url, method, timeout, redirects),
                result -> {
                    addTiming("tool.netdiag.phase.dns", result.dnsMs());
                    addTiming("tool.netdiag.phase.tcp", result.tcpMs());
                    addTiming("tool.netdiag.phase.tls", result.tlsMs());
                    addTiming("tool.netdiag.phase.ttfb", result.ttfbMs());
                    addTiming("tool.netdiag.phase.transfer", result.transferMs());
                    addTiming("tool.netdiag.phase.total", result.totalMs());
                    httpDetailArea.setText(renderHttp(result));
                    httpDetailArea.setCaretPosition(0);
                    if (result.isSuccess()) {
                        setStatus(httpStatusLabel, I18n.get("tool.netdiag.status.httpDone",
                                        result.statusCode(), result.totalMs()),
                                result.statusCode() < 400 ? Tokens.success() : Tokens.warning());
                    } else {
                        setStatus(httpStatusLabel, localizeError(result.error()), Tokens.danger());
                    }
                });
    }

    private void addTiming(String labelKey, long value) {
        httpTimingModel.addRow(new Object[]{I18n.get(labelKey),
                value < 0 ? "-" : value + " ms"});
    }

    private String renderHttp(NetDiagnosticsService.HttpResult result) {
        StringBuilder text = new StringBuilder();
        if (!result.isSuccess()) {
            text.append(I18n.get("tool.netdiag.http.failed")).append('\n')
                    .append(localizeError(result.error())).append("\n\n");
        }
        text.append(line("tool.netdiag.http.finalUrl", result.url()));
        if (result.statusCode() > 0) {
            text.append(line("tool.netdiag.http.status",
                    result.statusCode() + " (" + result.httpVersion() + ")"));
        }
        if (!result.remoteAddress().isEmpty()) {
            text.append(line("tool.netdiag.http.remote", result.remoteAddress()));
        }
        if (result.contentLength() >= 0) {
            text.append(line("tool.netdiag.http.bytes", String.valueOf(result.contentLength())));
        }
        if (!result.redirects().isEmpty()) {
            text.append('\n').append(I18n.get("tool.netdiag.http.redirects")).append('\n');
            for (NetDiagnosticsService.HttpHop hop : result.redirects()) {
                text.append("  ").append(hop.statusCode()).append("  ").append(hop.url())
                        .append("  ->  ").append(hop.location())
                        .append("  (").append(hop.elapsedMs()).append(" ms)\n");
            }
        }
        if (!result.headers().isEmpty()) {
            text.append('\n').append(I18n.get("tool.netdiag.http.headers")).append('\n');
            for (Map.Entry<String, List<String>> header : result.headers().entrySet()) {
                text.append("  ").append(header.getKey()).append(": ")
                        .append(String.join(", ", header.getValue())).append('\n');
            }
        }
        return text.toString();
    }

    // ==========================================
    // 公共
    // ==========================================

    /** 把上一次还没跑完的查询取消掉，再起新的；按钮在执行期间禁用，避免叠加请求。 */
    private <T> void runAsync(AtomicReference<SwingWorker<?, ?>> slot, JButton trigger,
                              JLabel status, Supplier<T> task, java.util.function.Consumer<T> onDone) {
        cancel(slot);
        trigger.setEnabled(false);
        setStatus(status, I18n.get("tool.netdiag.status.running"), Tokens.mutedForeground());
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override
            protected T doInBackground() {
                return task.get();
            }

            @Override
            protected void done() {
                trigger.setEnabled(true);
                if (isCancelled()) {
                    return;
                }
                try {
                    onDone.accept(get());
                } catch (Exception error) {
                    setStatus(status, describe(error), Tokens.danger());
                }
            }
        };
        slot.set(worker);
        worker.execute();
    }

    private void cancel(AtomicReference<SwingWorker<?, ?>> slot) {
        SwingWorker<?, ?> previous = slot.getAndSet(null);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
    }

    /** 切换页签时把上一个页签填过的主机名带过来。 */
    private void syncTarget(int selectedIndex) {
        switch (selectedIndex) {
            case 1: {
                String host = hostOf(dnsHostField.getText());
                if (!host.isEmpty() && isPlaceholderHost(tlsHostField.getText())) {
                    tlsHostField.setText(host);
                }
                break;
            }
            case 2: {
                String host = hostOf(tlsHostField.getText());
                if (!host.isEmpty() && isPlaceholderHost(hostOf(httpUrlField.getText()))) {
                    httpUrlField.setText("https://" + host);
                }
                break;
            }
            default: {
                String host = hostOf(httpUrlField.getText());
                if (!host.isEmpty() && isPlaceholderHost(dnsHostField.getText())) {
                    dnsHostField.setText(host);
                }
                break;
            }
        }
    }

    /** 只覆盖示例值，不动用户自己敲进去的目标。 */
    private boolean isPlaceholderHost(String value) {
        String host = value == null ? "" : value.trim();
        return host.isEmpty() || "example.com".equalsIgnoreCase(host);
    }

    private String hostOf(String value) {
        String text = value == null ? "" : value.trim();
        int scheme = text.indexOf("://");
        if (scheme >= 0) {
            text = text.substring(scheme + 3);
        }
        int slash = text.indexOf('/');
        if (slash >= 0) {
            text = text.substring(0, slash);
        }
        int colon = text.lastIndexOf(':');
        if (colon > 0 && text.indexOf(':') == colon) {
            text = text.substring(0, colon);
        }
        return text;
    }

    private int timeoutValue() {
        return ((Number) timeoutSpinner.getValue()).intValue();
    }

    private String line(String labelKey, String value) {
        return "  " + I18n.get(labelKey) + ": " + (value == null ? "" : value) + "\n";
    }

    private void copy(JTextArea area) {
        String text = area.getText();
        if (text != null && !text.isEmpty()) {
            UIUtils.copyToClipboard(text);
        }
    }

    private void setStatus(JLabel label, String text, Color color) {
        label.setText(text);
        label.setForeground(color);
    }

    /** 领域层用稳定错误码表示输入缺失与空结果，其余原样透出协议栈的描述。 */
    private String localizeError(String raw) {
        if (raw == null) {
            return "";
        }
        switch (raw) {
            case "empty.host":
                return I18n.get("tool.netdiag.error.emptyHost");
            case "empty.url":
                return I18n.get("tool.netdiag.error.emptyUrl");
            case "invalid.url":
                return I18n.get("tool.netdiag.error.invalidUrl");
            case "no.records":
                return I18n.get("tool.netdiag.error.noRecords");
            default:
                return raw;
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void closeResources() {
        cancel(dnsWorker);
        cancel(tlsWorker);
        cancel(httpWorker);
    }
}
