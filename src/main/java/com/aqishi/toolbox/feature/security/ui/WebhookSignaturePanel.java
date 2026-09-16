package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.security.domain.WebhookSignatureService;
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
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Webhook 回调签名的计算与验签面板。
 */
public class WebhookSignaturePanel extends ToolPanel {

    private final WebhookSignatureService service;

    private JComboBox<String> presetCombo;
    private List<String> presetIds = new ArrayList<>();

    private JTextField templateField;
    private JComboBox<String> schemeCombo;
    private JComboBox<String> algorithmCombo;
    private JComboBox<String> encodingCombo;
    private JTextField prefixField;
    private JTextField headerNameField;

    private JPasswordField secretField;
    private JTextField timestampField;
    private JTextField nonceField;
    private JSpinner windowSpinner;
    private JTextField receivedField;

    private JTextArea bodyArea;
    private JTextArea publicKeyArea;
    private JTextArea reportArea;
    private JLabel verdictLabel;

    /** 预设切换时抑制联动回调，避免把用户刚选的算法又覆盖回去。 */
    private boolean applyingPreset;

    public WebhookSignaturePanel() {
        this(ToolCatalog.WEBHOOK_SIGNATURE, new WebhookSignatureService());
    }

    public WebhookSignaturePanel(WebhookSignatureService service) {
        this(ToolCatalog.WEBHOOK_SIGNATURE, service);
    }

    public WebhookSignaturePanel(ToolDescriptor descriptor, WebhookSignatureService service) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        root.add(buildConfigCard(), BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(buildInputCard(), buildResultCard(), 0.5, 0.5),
                BorderLayout.CENTER);
        applyPreset("github");
        return root;
    }

    private Card buildConfigCard() {
        Map<String, WebhookSignatureService.Preset> presets = WebhookSignatureService.presets();
        String[] presetLabels = new String[presets.size()];
        presetIds = new ArrayList<>();
        int index = 0;
        for (String id : presets.keySet()) {
            presetLabels[index++] = I18n.get("tool.webhook.preset." + id);
            presetIds.add(id);
        }
        presetCombo = Fields.combo(presetLabels);

        schemeCombo = Fields.combo(new String[]{
                I18n.get("tool.webhook.scheme.hmac"),
                I18n.get("tool.webhook.scheme.digest"),
                I18n.get("tool.webhook.scheme.sortedDigest"),
                I18n.get("tool.webhook.scheme.rsa"),
                I18n.get("tool.webhook.scheme.plainToken")});

        algorithmCombo = Fields.combo(
                WebhookSignatureService.hmacAlgorithms().toArray(new String[0]));
        algorithmCombo.setEditable(true);

        encodingCombo = Fields.combo(new String[]{
                I18n.get("tool.webhook.encoding.hexLower"),
                I18n.get("tool.webhook.encoding.hexUpper"),
                I18n.get("tool.webhook.encoding.base64")});

        templateField = Fields.mono(WebhookSignatureService.PLACEHOLDER_BODY);
        templateField.setToolTipText(I18n.get("tool.webhook.label.template.tip"));

        prefixField = Fields.mono("");
        headerNameField = Fields.mono("");

        JPanel algorithmRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        algorithmRow.setOpaque(false);
        algorithmRow.add(schemeCombo);
        algorithmRow.add(algorithmCombo);
        algorithmRow.add(encodingCombo);

        JPanel headerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        headerRow.setOpaque(false);
        headerRow.add(headerNameField);
        headerRow.add(Fields.label(I18n.get("tool.webhook.label.prefix")));
        headerRow.add(prefixField);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.webhook.label.preset"), presetCombo);
        form.row(I18n.get("tool.webhook.label.algorithm"), algorithmRow);
        form.row(I18n.get("tool.webhook.label.template"), templateField);
        form.row(I18n.get("tool.webhook.label.header"), headerRow);
        form.caption(I18n.get("tool.webhook.caption.placeholders"));

        Card card = Card.titled(I18n.get("tool.webhook.card.config"));
        card.setContent(form);

        presetCombo.addActionListener(event -> {
            int selected = presetCombo.getSelectedIndex();
            if (selected >= 0 && selected < presetIds.size()) {
                applyPreset(presetIds.get(selected));
            }
        });
        schemeCombo.addActionListener(event -> {
            if (!applyingPreset) {
                syncAlgorithmChoices();
            }
        });
        return card;
    }

    private Card buildInputCard() {
        secretField = Fields.password();
        timestampField = Fields.mono("");
        nonceField = Fields.mono("");
        receivedField = Fields.mono("");
        receivedField.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.webhook.placeholder.received"));
        windowSpinner = Fields.spinner(300, 0, 86400, 30);

        JButton nowBtn = Buttons.snug(I18n.get("tool.webhook.btn.now"));
        JButton parseBtn = Buttons.snug(I18n.get("tool.webhook.btn.parseHeader"));

        JPanel timestampRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        timestampRow.setOpaque(false);
        timestampRow.add(timestampField);
        timestampRow.add(nowBtn);
        timestampRow.add(Fields.label(I18n.get("tool.webhook.label.window")));
        timestampRow.add(windowSpinner);

        JPanel receivedRow = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        receivedRow.setOpaque(false);
        receivedRow.add(receivedField, BorderLayout.CENTER);
        receivedRow.add(parseBtn, BorderLayout.EAST);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.webhook.label.secret"), secretField);
        form.row(I18n.get("tool.webhook.label.timestamp"), timestampRow);
        form.row(I18n.get("tool.webhook.label.nonce"), nonceField);
        form.row(I18n.get("tool.webhook.label.received"), receivedRow);

        bodyArea = Fields.area(8, 28);
        bodyArea.setText("{\"event\":\"order.paid\",\"id\":\"1001\"}");
        publicKeyArea = Fields.area(4, 28);
        publicKeyArea.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.webhook.placeholder.publicKey"));

        JPanel stack = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        stack.setOpaque(false);
        stack.add(form, BorderLayout.NORTH);

        Card bodyCard = Card.flush(I18n.get("tool.webhook.card.body"));
        bodyCard.setContent(Fields.scroll(bodyArea));
        Card keyCard = Card.flush(I18n.get("tool.webhook.card.publicKey"));
        keyCard.setContent(Fields.scroll(publicKeyArea));
        stack.add(Layouts.splitVertical(bodyCard, keyCard, 0.7, 0.7), BorderLayout.CENTER);

        Card card = Card.titled(I18n.get("tool.webhook.card.request"));
        card.setContent(stack);

        JButton verifyBtn = Buttons.primary(I18n.get("tool.webhook.btn.verify"));
        card.addHeaderAction(verifyBtn);

        verifyBtn.addActionListener(event -> evaluate());
        nowBtn.addActionListener(event ->
                timestampField.setText(String.valueOf(System.currentTimeMillis() / 1000L)));
        parseBtn.addActionListener(event -> {
            String[] extracted = service.extractSignature(receivedField.getText());
            receivedField.setText(extracted[0]);
            if (!extracted[1].isEmpty()) {
                timestampField.setText(extracted[1]);
            }
            evaluate();
        });
        return card;
    }

    private Card buildResultCard() {
        reportArea = Fields.output(16, 30);
        Card card = Card.flush(I18n.get("tool.webhook.card.result"));
        card.setContent(Fields.scroll(reportArea));

        verdictLabel = Fields.caption(I18n.get("tool.webhook.status.ready"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.webhook.btn.copy"));
        card.addHeaderAction(verdictLabel);
        card.addHeaderAction(copyBtn);

        copyBtn.addActionListener(event -> {
            String text = reportArea.getText();
            if (text != null && !text.isEmpty()) {
                UIUtils.copyToClipboard(text);
            }
        });
        return card;
    }

    // ==========================================
    // 行为
    // ==========================================
    private void applyPreset(String id) {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset(id);
        applyingPreset = true;
        try {
            presetCombo.setSelectedIndex(Math.max(0, presetIds.indexOf(id)));
            schemeCombo.setSelectedIndex(schemeIndex(preset.getScheme()));
            syncAlgorithmChoices();
            algorithmCombo.setSelectedItem(preset.getAlgorithm());
            encodingCombo.setSelectedIndex(encodingIndex(preset.getEncoding()));
            templateField.setText(preset.getTemplate());
            prefixField.setText(preset.getSignaturePrefix());
            headerNameField.setText(preset.getHeaderName());
        } finally {
            applyingPreset = false;
        }
    }

    /** HMAC 用 HmacXxx 名称，散列用 SHA-256 这类名称，非对称用 Signature 算法名——三套不能混选。 */
    private void syncAlgorithmChoices() {
        WebhookSignatureService.Scheme scheme = selectedScheme();
        Object previous = algorithmCombo.getSelectedItem();
        List<String> choices;
        switch (scheme) {
            case DIGEST:
            case SORTED_DIGEST:
                choices = WebhookSignatureService.digestAlgorithms();
                break;
            case RSA_VERIFY:
                choices = List.of("SHA256withRSA", "SHA1withRSA", "SHA512withRSA");
                break;
            case PLAIN_TOKEN:
                choices = List.of("");
                break;
            default:
                choices = WebhookSignatureService.hmacAlgorithms();
                break;
        }
        algorithmCombo.setModel(new DefaultComboBoxModel<>(choices.toArray(new String[0])));
        if (previous != null && choices.contains(String.valueOf(previous))) {
            algorithmCombo.setSelectedItem(previous);
        }
        boolean needsKey = scheme == WebhookSignatureService.Scheme.RSA_VERIFY;
        boolean needsSecret = scheme != WebhookSignatureService.Scheme.RSA_VERIFY;
        if (publicKeyArea != null) {
            publicKeyArea.setEnabled(needsKey);
        }
        if (secretField != null) {
            secretField.setEnabled(needsSecret);
        }
        encodingCombo.setEnabled(scheme != WebhookSignatureService.Scheme.PLAIN_TOKEN
                && scheme != WebhookSignatureService.Scheme.RSA_VERIFY);
    }

    private void evaluate() {
        WebhookSignatureService.Request request = new WebhookSignatureService.Request()
                .scheme(selectedScheme())
                .algorithm(String.valueOf(algorithmCombo.getSelectedItem()))
                .encoding(selectedEncoding())
                .template(templateField.getText())
                .signaturePrefix(prefixField.getText())
                .secret(new String(secretField.getPassword()))
                .body(bodyArea.getText())
                .timestamp(timestampField.getText())
                .nonce(nonceField.getText())
                .receivedSignature(receivedField.getText())
                .publicKeyPem(publicKeyArea.getText())
                .replayWindowSeconds(((Number) windowSpinner.getValue()).longValue());

        WebhookSignatureService.Result result = service.evaluate(request);
        StringBuilder report = new StringBuilder();

        if (!result.isSuccess()) {
            report.append(I18n.get("tool.webhook.report.failed")).append('\n')
                    .append(localizeError(result.getErrorCode()));
            if (result.getErrorDetail() != null) {
                report.append('\n').append(result.getErrorDetail());
            }
            reportArea.setText(report.toString());
            setVerdict(I18n.get("tool.webhook.status.error"), Tokens.danger());
            return;
        }

        report.append(I18n.get("tool.webhook.report.signedPayload")).append('\n')
                .append(escape(result.getSignedPayload())).append("\n\n");
        if (!result.getComputedSignature().isEmpty()) {
            report.append(I18n.get("tool.webhook.report.computed")).append('\n')
                    .append(prefixField.getText()).append(result.getComputedSignature())
                    .append("\n\n");
        }
        report.append(I18n.get("tool.webhook.report.timestamp")).append('\n')
                .append(describeFreshness(result)).append("\n\n");
        report.append(I18n.get("tool.webhook.report.comparison")).append('\n');

        if (!result.isCompared()) {
            report.append(I18n.get("tool.webhook.report.noSignature"));
            setVerdict(I18n.get("tool.webhook.status.computed"), Tokens.mutedForeground());
        } else if (result.isMatched()) {
            report.append(I18n.get("tool.webhook.report.match"));
            setVerdict(I18n.get("tool.webhook.status.match"), Tokens.success());
        } else {
            report.append(I18n.get("tool.webhook.report.mismatch"));
            setVerdict(I18n.get("tool.webhook.status.mismatch"), Tokens.danger());
        }

        if (result.getFreshness() == WebhookSignatureService.Freshness.EXPIRED) {
            report.append('\n').append(I18n.get("tool.webhook.report.replayWarning"));
        }
        reportArea.setText(report.toString());
        reportArea.setCaretPosition(0);
    }

    private String describeFreshness(WebhookSignatureService.Result result) {
        switch (result.getFreshness()) {
            case FRESH:
                return I18n.get("tool.webhook.freshness.fresh", result.getSkewSeconds());
            case EXPIRED:
                return I18n.get("tool.webhook.freshness.expired", result.getSkewSeconds());
            case UNPARSEABLE:
                return I18n.get("tool.webhook.freshness.unparseable");
            default:
                return I18n.get("tool.webhook.freshness.notApplicable");
        }
    }

    /** 待签串里的换行必须看得见，否则「少一个 \n」这种错永远查不出来。 */
    private String escape(String payload) {
        return payload.replace("\n", "\\n\n");
    }

    private WebhookSignatureService.Scheme selectedScheme() {
        switch (schemeCombo.getSelectedIndex()) {
            case 1:
                return WebhookSignatureService.Scheme.DIGEST;
            case 2:
                return WebhookSignatureService.Scheme.SORTED_DIGEST;
            case 3:
                return WebhookSignatureService.Scheme.RSA_VERIFY;
            case 4:
                return WebhookSignatureService.Scheme.PLAIN_TOKEN;
            default:
                return WebhookSignatureService.Scheme.HMAC;
        }
    }

    private WebhookSignatureService.Encoding selectedEncoding() {
        switch (encodingCombo.getSelectedIndex()) {
            case 1:
                return WebhookSignatureService.Encoding.HEX_UPPER;
            case 2:
                return WebhookSignatureService.Encoding.BASE64;
            default:
                return WebhookSignatureService.Encoding.HEX_LOWER;
        }
    }

    private static int schemeIndex(WebhookSignatureService.Scheme scheme) {
        switch (scheme) {
            case DIGEST:
                return 1;
            case SORTED_DIGEST:
                return 2;
            case RSA_VERIFY:
                return 3;
            case PLAIN_TOKEN:
                return 4;
            default:
                return 0;
        }
    }

    private static int encodingIndex(WebhookSignatureService.Encoding encoding) {
        switch (encoding) {
            case HEX_UPPER:
                return 1;
            case BASE64:
                return 2;
            default:
                return 0;
        }
    }

    private void setVerdict(String text, Color color) {
        verdictLabel.setText(text);
        verdictLabel.setForeground(color);
    }

    private String localizeError(String code) {
        if (code == null) {
            return "";
        }
        switch (code) {
            case "error.missingPublicKey":
                return I18n.get("tool.webhook.error.missingPublicKey");
            case "error.badSignatureEncoding":
                return I18n.get("tool.webhook.error.badSignatureEncoding");
            case "error.computeFailed":
                return I18n.get("tool.webhook.error.computeFailed");
            case "error.verifyFailed":
                return I18n.get("tool.webhook.error.verifyFailed");
            default:
                return code;
        }
    }
}
