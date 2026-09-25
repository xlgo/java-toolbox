package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.security.domain.JoseAlgorithm;
import com.aqishi.toolbox.feature.security.domain.JwkException;
import com.aqishi.toolbox.feature.security.domain.JwkKey;
import com.aqishi.toolbox.feature.security.domain.JwkService;
import com.aqishi.toolbox.feature.security.domain.JwkSet;
import com.aqishi.toolbox.feature.security.domain.JwtVerification;
import com.aqishi.toolbox.feature.security.infra.JwksFetcher;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JWK / JWKS 查看、PEM 互转与 JWT 验签面板。
 *
 * <p>三个页签共用同一份密钥列表：在「密钥」页签里贴入或拉取的 JWKS，就是「验签」页签用的钥，
 * 「PEM → JWK」转出来的结果也可以一键并入。排查时通常是「拉 JWKS → 看 kid → 验令牌」一气呵成，
 * 不该让用户在页签之间复制粘贴。</p>
 */
public class JwkPanel extends ToolPanel implements ManagedResourceOwner {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx").withZone(ZoneId.systemDefault());

    private final JwkService service;
    private final JwksFetcher fetcher;

    // 密钥页签
    private JTextField urlField;
    private JButton fetchBtn;
    private JLabel fetchStatusLabel;
    private JTextArea jwksArea;
    private DefaultTableModel keyModel;
    private JTable keyTable;
    private JLabel keySummaryLabel;
    private JTextArea detailArea;
    private JButton copyPemBtn;
    private JButton copyCertBtn;
    private JButton copyJwkBtn;
    /** 表格行对应的对象：{@link JwkKey} 或 {@link JwkSet.Failure}。 */
    private final List<Object> rowItems = new ArrayList<>();
    private JwkSet currentSet = JwkSet.empty();

    // PEM 页签
    private JTextArea pemArea;
    private JTextField pemKidField;
    private JComboBox<String> pemUseCombo;
    private JComboBox<String> pemAlgCombo;
    private JTextArea pemOutputArea;
    private JLabel pemStatusLabel;

    // 验签页签
    private JTextArea tokenArea;
    private JSpinner leewaySpinner;
    private JTextField issuerField;
    private JTextField audienceField;
    private JTextArea reportArea;
    private JTextArea decodedArea;
    private JLabel verdictLabel;

    private JTabbedPane tabs;

    /** 每次拉取递增；回调时代次对不上就说明结果已经过期（用户又点了一次或面板已关闭）。 */
    private final AtomicLong fetchGeneration = new AtomicLong();
    private volatile Future<JwksFetcher.Result> pendingFetch;

    public JwkPanel() {
        this(ToolCatalog.JWK_TOOL, new JwkService(), new JwksFetcher());
    }

    public JwkPanel(JwkService service, JwksFetcher fetcher) {
        this(ToolCatalog.JWK_TOOL, service, fetcher);
    }

    public JwkPanel(ToolDescriptor descriptor, JwkService service, JwksFetcher fetcher) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.service = Objects.requireNonNull(service, "service");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        tabs = new JTabbedPane();
        tabs.addTab(I18n.get("tool.jwk.tab.keys"), buildKeysTab());
        tabs.addTab(I18n.get("tool.jwk.tab.verify"), buildVerifyTab());
        tabs.addTab(I18n.get("tool.jwk.tab.pem"), buildPemTab());
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
    // 密钥页签
    // ==========================================
    private JComponent buildKeysTab() {
        JPanel panel = tabShell();
        panel.add(Layouts.splitHorizontal(buildJwksInputCard(), buildKeyResultPane(), 0.4, 0.42),
                BorderLayout.CENTER);
        return panel;
    }

    private Card buildJwksInputCard() {
        urlField = Fields.mono("");
        urlField.putClientProperty("JTextField.placeholderText", I18n.get("tool.jwk.placeholder.url"));
        urlField.setToolTipText(I18n.get("tool.jwk.tip.url"));
        fetchBtn = Buttons.secondary(I18n.get("tool.jwk.btn.fetch"));
        fetchStatusLabel = Fields.caption(I18n.get("tool.jwk.status.fetchReady"));

        JPanel urlRow = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        urlRow.setOpaque(false);
        urlRow.add(urlField, BorderLayout.CENTER);
        urlRow.add(fetchBtn, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, Tokens.SPACE_XS));
        top.setOpaque(false);
        top.add(urlRow, BorderLayout.NORTH);
        top.add(fetchStatusLabel, BorderLayout.CENTER);

        jwksArea = Fields.area(14, 30);
        jwksArea.putClientProperty("JTextField.placeholderText", I18n.get("tool.jwk.placeholder.jwks"));

        JPanel content = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        content.setOpaque(false);
        content.add(top, BorderLayout.NORTH);
        content.add(Fields.scrollBoxed(jwksArea), BorderLayout.CENTER);

        Card card = Card.titled(I18n.get("tool.jwk.card.input"));
        card.setContent(content);
        JButton parseBtn = Buttons.primary(I18n.get("tool.jwk.btn.parse"));
        card.addHeaderAction(parseBtn);

        parseBtn.addActionListener(event -> parseKeys(true));
        fetchBtn.addActionListener(event -> startFetch());
        urlField.addActionListener(event -> startFetch());
        return card;
    }

    private JComponent buildKeyResultPane() {
        keyModel = new DefaultTableModel(new Object[]{
                I18n.get("tool.jwk.column.kid"),
                I18n.get("tool.jwk.column.kty"),
                I18n.get("tool.jwk.column.alg"),
                I18n.get("tool.jwk.column.use"),
                I18n.get("tool.jwk.column.size"),
                I18n.get("tool.jwk.column.thumbprint"),
                I18n.get("tool.jwk.column.notes")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        keyTable = new JTable(keyModel);
        keyTable.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        keyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        keyTable.getColumnModel().getColumn(1).setMaxWidth(60);
        keyTable.getColumnModel().getColumn(2).setMaxWidth(80);
        keyTable.getColumnModel().getColumn(3).setMaxWidth(50);
        keyTable.getColumnModel().getColumn(6).setCellRenderer(new NotesRenderer());
        keyTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showSelected();
            }
        });

        Card tableCard = Card.flush(I18n.get("tool.jwk.card.keys"));
        tableCard.setContent(Fields.scroll(keyTable));
        keySummaryLabel = Fields.caption(I18n.get("tool.jwk.status.noKeys"));
        tableCard.addHeaderAction(keySummaryLabel);

        detailArea = Fields.output(12, 30);
        detailArea.setLineWrap(false);
        Card detailCard = Card.flush(I18n.get("tool.jwk.card.detail"));
        detailCard.setContent(Fields.scroll(detailArea));
        copyJwkBtn = Buttons.snug(I18n.get("tool.jwk.btn.copyJwk"));
        copyCertBtn = Buttons.snug(I18n.get("tool.jwk.btn.copyCert"));
        copyPemBtn = Buttons.snug(I18n.get("tool.jwk.btn.copyPem"));
        detailCard.addHeaderAction(copyJwkBtn);
        detailCard.addHeaderAction(copyCertBtn);
        detailCard.addHeaderAction(copyPemBtn);
        updateCopyButtons(null);

        copyPemBtn.addActionListener(event -> copyFromSelected(service::publicKeyPem));
        copyCertBtn.addActionListener(event -> copyFromSelected(service::certificatePem));
        copyJwkBtn.addActionListener(event -> copyFromSelected(service::publicJwkPretty));

        return Layouts.splitVertical(tableCard, detailCard, 0.45, 0.45);
    }

    /** 解析 JWKS 文本框的内容并刷新表格。返回是否成功（空输入也算成功）。 */
    private boolean parseKeys(boolean reportErrors) {
        String text = jwksArea.getText();
        rowItems.clear();
        keyModel.setRowCount(0);
        detailArea.setText("");
        updateCopyButtons(null);
        if (text == null || text.isBlank()) {
            currentSet = JwkSet.empty();
            setStatus(keySummaryLabel, I18n.get("tool.jwk.status.noKeys"), Tokens.mutedForeground());
            return true;
        }
        try {
            currentSet = service.parse(text);
        } catch (JwkException error) {
            currentSet = JwkSet.empty();
            String message = localizeJwkError(error);
            setStatus(keySummaryLabel, I18n.get("tool.jwk.status.parseFailed"), Tokens.danger());
            detailArea.setText(I18n.get("tool.jwk.detail.parseFailed") + "\n" + message);
            if (reportErrors) {
                detailArea.setCaretPosition(0);
            }
            return false;
        }

        for (JwkKey key : currentSet.keys()) {
            rowItems.add(key);
            keyModel.addRow(new Object[]{
                    key.kid() == null ? I18n.get("tool.jwk.value.none") : key.kid(),
                    key.kty(),
                    orDash(key.alg()),
                    orDash(key.use()),
                    sizeText(key),
                    shortThumbprint(key.thumbprint()),
                    notesText(key)});
        }
        for (JwkSet.Failure failure : currentSet.failures()) {
            rowItems.add(failure);
            keyModel.addRow(new Object[]{
                    failure.kid() == null ? "#" + failure.index() : failure.kid(),
                    "-", "-", "-", "-", "-",
                    I18n.get("tool.jwk.table.failed")});
        }

        String summary = I18n.get("tool.jwk.status.keys",
                String.valueOf(currentSet.keys().size()), String.valueOf(currentSet.failures().size()));
        Color color = currentSet.failures().isEmpty() ? Tokens.mutedForeground() : Tokens.warning();
        if (!currentSet.duplicateKids().isEmpty()) {
            summary += " " + I18n.get("tool.jwk.status.duplicateKids", String.join(", ", currentSet.duplicateKids()));
            color = Tokens.warning();
        }
        setStatus(keySummaryLabel, summary, color);
        if (!rowItems.isEmpty()) {
            keyTable.setRowSelectionInterval(0, 0);
        }
        return true;
    }

    private void showSelected() {
        Object item = selectedItem();
        if (item instanceof JwkKey) {
            JwkKey key = (JwkKey) item;
            detailArea.setText(describeKey(key));
            updateCopyButtons(key);
        } else if (item instanceof JwkSet.Failure) {
            JwkSet.Failure failure = (JwkSet.Failure) item;
            detailArea.setText(I18n.get("tool.jwk.detail.keyFailed", String.valueOf(failure.index()))
                    + "\n" + localizeJwkError(failure.error()));
            updateCopyButtons(null);
        } else {
            updateCopyButtons(null);
            return;
        }
        detailArea.setCaretPosition(0);
    }

    private Object selectedItem() {
        int row = keyTable.getSelectedRow();
        if (row < 0 || row >= rowItems.size()) {
            return null;
        }
        return rowItems.get(keyTable.convertRowIndexToModel(row));
    }

    private String describeKey(JwkKey key) {
        StringBuilder text = new StringBuilder();
        line(text, "tool.jwk.detail.kid", key.kid() == null ? I18n.get("tool.jwk.value.none") : key.kid());
        line(text, "tool.jwk.detail.kty", key.kty());
        if (key.curve() != null) {
            line(text, "tool.jwk.detail.curve", key.curve());
        }
        line(text, "tool.jwk.detail.size", I18n.get("tool.jwk.size.bits", String.valueOf(key.sizeBits())));
        line(text, "tool.jwk.detail.alg", orDash(key.alg()));
        line(text, "tool.jwk.detail.use", orDash(key.use()));
        if (key.keyOps() != null) {
            line(text, "tool.jwk.detail.keyOps", String.join(", ", key.keyOps()));
        }
        line(text, "tool.jwk.detail.thumbprint", key.thumbprint());
        line(text, "tool.jwk.detail.thumbprintUri", key.thumbprintUri());
        if (key.x5cPresent()) {
            line(text, "tool.jwk.detail.x5c", I18n.get("tool.jwk.detail.x5cCount",
                    String.valueOf(key.certificates().size())));
            if (!key.certificates().isEmpty()) {
                X509Certificate leaf = key.certificates().get(0);
                line(text, "tool.jwk.detail.certSubject", leaf.getSubjectX500Principal().getName());
                line(text, "tool.jwk.detail.certIssuer", leaf.getIssuerX500Principal().getName());
                line(text, "tool.jwk.detail.certValidity", TIME_FORMAT.format(leaf.getNotBefore().toInstant())
                        + "  ~  " + TIME_FORMAT.format(leaf.getNotAfter().toInstant()));
            }
        }

        if (!key.warnings().isEmpty()) {
            text.append('\n').append(I18n.get("tool.jwk.detail.warnings")).append('\n');
            for (JwkKey.Warning warning : key.warnings()) {
                text.append("  ! ").append(localizeWarning(warning)).append('\n');
            }
        }

        text.append('\n');
        if (key.isSymmetric()) {
            text.append(I18n.get("tool.jwk.detail.symmetric")).append('\n');
        } else {
            try {
                text.append(service.publicKeyPem(key));
            } catch (JwkException error) {
                text.append(localizeJwkError(error)).append('\n');
            }
        }
        return text.toString();
    }

    private void updateCopyButtons(JwkKey key) {
        copyPemBtn.setEnabled(key != null && key.publicKey() != null);
        copyCertBtn.setEnabled(key != null && !key.certificates().isEmpty());
        copyJwkBtn.setEnabled(key != null && !key.isSymmetric());
    }

    private void copyFromSelected(java.util.function.Function<JwkKey, String> exporter) {
        Object item = selectedItem();
        if (!(item instanceof JwkKey)) {
            return;
        }
        try {
            UIUtils.copyToClipboard(exporter.apply((JwkKey) item));
        } catch (JwkException error) {
            UIUtils.warn(null, localizeJwkError(error), getLabel());
        }
    }

    // ==========================================
    // 拉取
    // ==========================================
    private void startFetch() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            setStatus(fetchStatusLabel, I18n.get("tool.jwk.fetch.error.emptyUrl"), Tokens.danger());
            return;
        }
        cancelFetch();
        long generation = fetchGeneration.incrementAndGet();
        fetchBtn.setEnabled(false);
        setStatus(fetchStatusLabel, I18n.get("tool.jwk.status.fetching"), Tokens.mutedForeground());
        pendingFetch = fetcher.fetchAsync(url,
                result -> SwingUtilities.invokeLater(() -> onFetched(generation, result)),
                error -> SwingUtilities.invokeLater(() -> onFetchFailed(generation, error)));
    }

    private void onFetched(long generation, JwksFetcher.Result result) {
        if (generation != fetchGeneration.get()) {
            return;
        }
        fetchBtn.setEnabled(true);
        pendingFetch = null;
        jwksArea.setText(prettyOrRaw(result.body()));
        jwksArea.setCaretPosition(0);
        parseKeys(false);

        StringBuilder status = new StringBuilder(I18n.get("tool.jwk.status.fetched",
                String.valueOf(currentSet.keys().size()), String.valueOf(result.elapsedMs())));
        StringBuilder tooltip = new StringBuilder("<html>" + escapeHtml(result.jwksUrl()));
        if (result.issuer() != null) {
            tooltip.append("<br>").append(escapeHtml(I18n.get("tool.jwk.status.issuer", result.issuer())));
        }
        for (JwksFetcher.Warning warning : result.warnings()) {
            String text = I18n.get("tool.jwk.fetch.warning." + warning.name());
            status.append("  ").append(text);
            tooltip.append("<br>").append(escapeHtml(text));
        }
        setStatus(fetchStatusLabel, status.toString(),
                result.warnings().isEmpty() ? Tokens.success() : Tokens.warning());
        fetchStatusLabel.setToolTipText(tooltip.append("</html>").toString());
    }

    private void onFetchFailed(long generation, Throwable error) {
        if (generation != fetchGeneration.get()) {
            return;
        }
        fetchBtn.setEnabled(true);
        pendingFetch = null;
        String message;
        if (error instanceof JwksFetcher.FetchException) {
            JwksFetcher.FetchException fetchError = (JwksFetcher.FetchException) error;
            message = I18n.get("tool.jwk.fetch.error." + fetchError.getCode(), args(fetchError.getParams()));
        } else if (error instanceof InterruptedException) {
            return;
        } else {
            message = Errors.describeRoot(error);
        }
        setStatus(fetchStatusLabel, message, Tokens.danger());
        fetchStatusLabel.setToolTipText(message);
    }

    private void cancelFetch() {
        fetchGeneration.incrementAndGet();
        Future<JwksFetcher.Result> previous = pendingFetch;
        pendingFetch = null;
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
        if (fetchBtn != null) {
            fetchBtn.setEnabled(true);
        }
    }

    // ==========================================
    // 验签页签
    // ==========================================
    private JComponent buildVerifyTab() {
        JPanel panel = tabShell();

        tokenArea = Fields.area(8, 30);
        tokenArea.putClientProperty("JTextField.placeholderText", I18n.get("tool.jwk.placeholder.token"));
        leewaySpinner = Fields.spinner(60, 0, 86400, 30);
        issuerField = Fields.mono("");
        issuerField.putClientProperty("JTextField.placeholderText", I18n.get("tool.jwk.placeholder.optional"));
        audienceField = Fields.mono("");
        audienceField.putClientProperty("JTextField.placeholderText", I18n.get("tool.jwk.placeholder.optional"));

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.jwk.label.leeway"), leewaySpinner);
        form.row(I18n.get("tool.jwk.label.issuer"), issuerField);
        form.row(I18n.get("tool.jwk.label.audience"), audienceField);
        form.caption(I18n.get("tool.jwk.caption.verify"));

        JPanel content = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        content.setOpaque(false);
        content.add(Fields.scrollBoxed(tokenArea), BorderLayout.CENTER);
        content.add(form, BorderLayout.SOUTH);

        Card tokenCard = Card.titled(I18n.get("tool.jwk.card.token"));
        tokenCard.setContent(content);
        JButton verifyBtn = Buttons.primary(I18n.get("tool.jwk.btn.verify"));
        tokenCard.addHeaderAction(verifyBtn);

        reportArea = Fields.output(12, 30);
        Card reportCard = Card.flush(I18n.get("tool.jwk.card.report"));
        reportCard.setContent(Fields.scroll(reportArea));
        verdictLabel = Fields.caption(I18n.get("tool.jwk.verdict.ready"));
        JButton copyReportBtn = Buttons.snug(I18n.get("tool.jwk.btn.copy"));
        reportCard.addHeaderAction(verdictLabel);
        reportCard.addHeaderAction(copyReportBtn);

        decodedArea = Fields.output(10, 30);
        Card decodedCard = Card.flush(I18n.get("tool.jwk.card.decoded"));
        decodedCard.setContent(Fields.scroll(decodedArea));
        JButton copyDecodedBtn = Buttons.snug(I18n.get("tool.jwk.btn.copy"));
        decodedCard.addHeaderAction(copyDecodedBtn);

        panel.add(Layouts.splitHorizontal(tokenCard,
                Layouts.splitVertical(reportCard, decodedCard, 0.55, 0.55), 0.45, 0.45), BorderLayout.CENTER);

        verifyBtn.addActionListener(event -> verify());
        copyReportBtn.addActionListener(event -> copy(reportArea));
        copyDecodedBtn.addActionListener(event -> copy(decodedArea));
        return panel;
    }

    private void verify() {
        // 以文本框当前内容为准：用户可能刚改过 JWKS 还没点「解析」。
        boolean keysOk = parseKeys(false);
        long leeway = ((Number) leewaySpinner.getValue()).longValue();
        JwtVerification result = service.verify(tokenArea.getText(), currentSet.keys(),
                new JwkService.VerifyOptions(leeway, issuerField.getText(), audienceField.getText()));

        StringBuilder report = new StringBuilder();
        if (!keysOk) {
            report.append(I18n.get("tool.jwk.report.jwksInvalid")).append("\n\n");
        }
        report.append(I18n.get("tool.jwk.report.algorithm", orDash(result.algorithm()))).append('\n');
        report.append(I18n.get("tool.jwk.report.headerKid",
                result.headerKid() == null ? I18n.get("tool.jwk.value.none") : result.headerKid())).append('\n');
        if (result.key() != null) {
            report.append(I18n.get("tool.jwk.report.key", result.key().displayId(), result.key().kty(),
                    result.key().thumbprint())).append('\n');
        }
        report.append(I18n.get("tool.jwk.report.checkedAt", TIME_FORMAT.format(result.now()),
                String.valueOf(leeway))).append("\n\n");

        for (JwtVerification.Check check : result.checks()) {
            report.append('[').append(I18n.get("tool.jwk.checkStatus." + check.status().name())).append("] ")
                    .append(I18n.get("tool.jwk.checkId." + check.id().name())).append(": ")
                    .append(I18n.get("tool.jwk.check." + check.code(), args(check.params())))
                    .append('\n');
        }
        reportArea.setText(report.toString());
        reportArea.setCaretPosition(0);

        StringBuilder decoded = new StringBuilder();
        if (result.headerJson() != null) {
            decoded.append(I18n.get("tool.jwk.decoded.header")).append('\n').append(result.headerJson()).append("\n\n");
        }
        if (result.payloadJson() != null) {
            decoded.append(I18n.get("tool.jwk.decoded.payload")).append('\n').append(result.payloadJson()).append('\n');
        }
        decodedArea.setText(decoded.toString());
        decodedArea.setCaretPosition(0);

        switch (result.verdict()) {
            case VALID:
                setStatus(verdictLabel, I18n.get("tool.jwk.verdict.VALID"), Tokens.success());
                break;
            case UNVERIFIABLE:
                setStatus(verdictLabel, I18n.get("tool.jwk.verdict.UNVERIFIABLE"), Tokens.warning());
                break;
            case MALFORMED:
                setStatus(verdictLabel, I18n.get("tool.jwk.verdict.MALFORMED"), Tokens.danger());
                break;
            default:
                setStatus(verdictLabel, I18n.get("tool.jwk.verdict.INVALID"), Tokens.danger());
                break;
        }
    }

    // ==========================================
    // PEM → JWK 页签
    // ==========================================
    private JComponent buildPemTab() {
        JPanel panel = tabShell();

        pemArea = Fields.area(12, 30);
        pemArea.putClientProperty("JTextField.placeholderText", I18n.get("tool.jwk.placeholder.pem"));
        pemKidField = Fields.mono("");
        pemKidField.putClientProperty("JTextField.placeholderText", I18n.get("tool.jwk.placeholder.kid"));
        pemUseCombo = Fields.combo(new String[]{"", "sig", "enc"});
        pemUseCombo.setEditable(true);
        List<String> algorithms = new ArrayList<>();
        algorithms.add("");
        for (JoseAlgorithm algorithm : JoseAlgorithm.values()) {
            if (algorithm.family() != JoseAlgorithm.Family.HMAC) {
                algorithms.add(algorithm.joseName());
            }
        }
        pemAlgCombo = Fields.combo(algorithms.toArray(new String[0]));
        pemAlgCombo.setEditable(true);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.jwk.label.kid"), pemKidField);
        form.row(I18n.get("tool.jwk.label.use"), pemUseCombo);
        form.row(I18n.get("tool.jwk.label.alg"), pemAlgCombo);
        form.caption(I18n.get("tool.jwk.caption.pem"));

        JPanel content = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        content.setOpaque(false);
        content.add(Fields.scrollBoxed(pemArea), BorderLayout.CENTER);
        content.add(form, BorderLayout.SOUTH);

        Card inputCard = Card.titled(I18n.get("tool.jwk.card.pemInput"));
        inputCard.setContent(content);
        JButton convertBtn = Buttons.primary(I18n.get("tool.jwk.btn.convert"));
        inputCard.addHeaderAction(convertBtn);

        pemOutputArea = Fields.output(12, 30);
        pemOutputArea.setLineWrap(false);
        Card outputCard = Card.flush(I18n.get("tool.jwk.card.pemOutput"));
        outputCard.setContent(Fields.scroll(pemOutputArea));
        pemStatusLabel = Fields.caption("");
        JButton addBtn = Buttons.snug(I18n.get("tool.jwk.btn.addToKeys"));
        addBtn.setToolTipText(I18n.get("tool.jwk.tip.addToKeys"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.jwk.btn.copy"));
        outputCard.addHeaderAction(pemStatusLabel);
        outputCard.addHeaderAction(addBtn);
        outputCard.addHeaderAction(copyBtn);

        panel.add(Layouts.splitHorizontal(inputCard, outputCard, 0.5, 0.5), BorderLayout.CENTER);

        convertBtn.addActionListener(event -> convertPem());
        copyBtn.addActionListener(event -> copy(pemOutputArea));
        addBtn.addActionListener(event -> addConvertedToKeys());
        return panel;
    }

    private void convertPem() {
        try {
            String json = service.pemToJwk(pemArea.getText(), pemKidField.getText(),
                    comboText(pemUseCombo), comboText(pemAlgCombo));
            pemOutputArea.setText(json);
            pemOutputArea.setCaretPosition(0);
            setStatus(pemStatusLabel, I18n.get("tool.jwk.status.converted"), Tokens.success());
        } catch (JwkException error) {
            pemOutputArea.setText("");
            setStatus(pemStatusLabel, localizeJwkError(error), Tokens.danger());
        }
    }

    /** 把转换结果并入密钥页签的 JWKS：空则新建，单个 JWK 则升级为 {"keys":[...]}。 */
    private void addConvertedToKeys() {
        String converted = pemOutputArea.getText();
        if (converted == null || converted.isBlank()) {
            return;
        }
        try {
            JsonNode jwk = Json.mapper().readTree(converted);
            ObjectNode set;
            String existing = jwksArea.getText();
            JsonNode current = existing == null || existing.isBlank() ? null : Json.mapper().readTree(existing);
            if (current != null && current.isObject() && current.path("keys").isArray()) {
                set = (ObjectNode) current;
            } else {
                set = Json.mapper().createObjectNode();
                ArrayNode keys = set.putArray("keys");
                if (current != null && current.isObject() && current.has("kty")) {
                    keys.add(current);
                }
            }
            ((ArrayNode) set.get("keys")).add(jwk);
            jwksArea.setText(Json.prettyMapper().writeValueAsString(set));
            parseKeys(false);
            setStatus(pemStatusLabel, I18n.get("tool.jwk.status.added"), Tokens.success());
            tabs.setSelectedIndex(0);
        } catch (Exception error) {
            setStatus(pemStatusLabel, I18n.get("tool.jwk.status.addFailed"), Tokens.danger());
        }
    }

    // ==========================================
    // 本地化与格式化
    // ==========================================
    private String localizeJwkError(JwkException error) {
        return I18n.get("tool.jwk.error." + error.getCode(), args(error.getParams()));
    }

    private String localizeWarning(JwkKey.Warning warning) {
        return I18n.get("tool.jwk.warning." + warning.code().name(), args(warning.params()));
    }

    /** 参数统一转成字符串：MessageFormat 会给数字加千分位，把 2048 显示成 2,048。 */
    private static Object[] args(List<Object> params) {
        Object[] result = new Object[params.size()];
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Instant) {
                result[i] = TIME_FORMAT.format((Instant) value);
            } else {
                result[i] = String.valueOf(value);
            }
        }
        return result;
    }

    private String notesText(JwkKey key) {
        if (key.hasPrivateMembers()) {
            return key.isSymmetric() ? I18n.get("tool.jwk.table.secret") : I18n.get("tool.jwk.table.private");
        }
        if (!key.warnings().isEmpty()) {
            return I18n.get("tool.jwk.table.warnings", String.valueOf(key.warnings().size()));
        }
        return "";
    }

    private static String sizeText(JwkKey key) {
        if (key.curve() != null) {
            return key.curve();
        }
        return I18n.get("tool.jwk.size.bits", String.valueOf(key.sizeBits()));
    }

    private static String shortThumbprint(String thumbprint) {
        return thumbprint.length() <= 12 ? thumbprint : thumbprint.substring(0, 12) + "...";
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static void line(StringBuilder text, String labelKey, String value) {
        text.append(I18n.get(labelKey)).append(": ").append(value).append('\n');
    }

    private static String comboText(JComboBox<String> combo) {
        Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return item == null ? "" : item.toString().trim();
    }

    private static String prettyOrRaw(String body) {
        try {
            return Json.prettyMapper().writeValueAsString(Json.mapper().readTree(body));
        } catch (Exception error) {
            return body;
        }
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void copy(JTextArea area) {
        String text = area.getText();
        if (text != null && !text.isEmpty()) {
            UIUtils.copyToClipboard(text);
        }
    }

    private static void setStatus(JLabel label, String text, Color color) {
        label.setText(text);
        label.setForeground(color);
    }

    /** 「备注」列：私钥 / 解析失败用危险色，其余提示用警告色。 */
    private final class NotesRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                int modelRow = table.convertRowIndexToModel(row);
                Object item = modelRow < rowItems.size() ? rowItems.get(modelRow) : null;
                if (item instanceof JwkSet.Failure
                        || (item instanceof JwkKey && ((JwkKey) item).hasPrivateMembers())) {
                    component.setForeground(Tokens.danger());
                } else if (item instanceof JwkKey && !((JwkKey) item).warnings().isEmpty()) {
                    component.setForeground(Tokens.warning());
                } else {
                    component.setForeground(table.getForeground());
                }
            }
            return component;
        }
    }

    @Override
    public void closeResources() {
        cancelFetch();
        fetcher.close();
    }
}
