package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.security.domain.CertInspectorService;
import com.aqishi.toolbox.feature.security.domain.CertUtils;
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
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * CSR / PKCS#12 / 证书链检查与诊断面板。
 */
public class CertInspectorPanel extends ToolPanel implements ManagedResourceOwner {

    private final CertInspectorService service;

    // Tab 1: CSR
    private JTextArea csrInputArea;
    private JTextArea csrResultArea;
    private JButton csrParseBtn;
    private JButton csrSampleBtn;
    private JButton csrFileBtn;
    private JButton csrClearBtn;
    private final AtomicReference<SwingWorker<?, ?>> csrWorker = new AtomicReference<>();

    // Tab 2: PKCS#12
    private JTextField p12FileField;
    private JPasswordField p12PasswordField;
    private DefaultListModel<CertInspectorService.Pkcs12EntryInfo> p12ListModel;
    private JList<CertInspectorService.Pkcs12EntryInfo> p12List;
    private JTextArea p12DetailArea;
    private byte[] currentP12Bytes;
    private JButton p12ChooseBtn;
    private JButton p12UnlockBtn;
    private JButton p12ExportCertBtn;
    private JButton p12ExportKeyBtn;
    private final AtomicReference<SwingWorker<?, ?>> p12Worker = new AtomicReference<>();

    // Tab 3: 证书链
    private JTextArea chainInputArea;
    private JTextArea chainLogArea;
    private JLabel chainStatusLabel;
    private JButton chainValidateBtn;
    private JButton chainSampleBtn;
    private JButton chainFileBtn;
    private JButton chainClearBtn;
    private final AtomicReference<SwingWorker<?, ?>> chainWorker = new AtomicReference<>();

    public CertInspectorPanel() {
        this(ToolCatalog.CERT_INSPECTOR, new CertInspectorService());
    }

    public CertInspectorPanel(CertInspectorService service) {
        this(ToolCatalog.CERT_INSPECTOR, service);
    }

    public CertInspectorPanel(ToolDescriptor descriptor, CertInspectorService service) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        JTabbedPane mainTabs = new JTabbedPane();

        mainTabs.addTab(I18n.get("tool.certinspector.tab.csr", "CSR 签名请求解析"), buildCsrTab());
        mainTabs.addTab(I18n.get("tool.certinspector.tab.pkcs12", "PKCS#12 密钥库 (.p12/.pfx)"), buildPkcs12Tab());
        mainTabs.addTab(I18n.get("tool.certinspector.tab.chain", "证书链完整性诊断"), buildChainTab());

        root.add(mainTabs, BorderLayout.CENTER);
        return root;
    }

    // ==========================================
    // Tab 1: CSR 证书签名请求解析
    // ==========================================
    private JComponent buildCsrTab() {
        JPanel panel = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM));

        csrInputArea = Fields.area(8, 30);
        csrInputArea.putClientProperty("JTextField.placeholderText", "粘贴 -----BEGIN CERTIFICATE REQUEST----- 格式的 CSR 文本");

        csrParseBtn = Buttons.primary(I18n.get("tool.certinspector.btn.parseCsr", "解析 CSR"));
        csrSampleBtn = Buttons.secondary("加载示例 CSR");
        csrFileBtn = Buttons.ghost("从文件读取...");
        csrClearBtn = Buttons.ghost("清空");

        csrParseBtn.addActionListener(e -> parseCsr());
        csrSampleBtn.addActionListener(e -> loadSampleCsr());
        csrFileBtn.addActionListener(e -> loadCsrFile());
        csrClearBtn.addActionListener(e -> {
            cancelWorker(csrWorker);
            setButtonsEnabled(true, csrButtons());
            setInputControlsEnabled(true, new JComponent[]{csrInputArea});
            csrInputArea.setText("");
            csrResultArea.setText("");
        });

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SPACE_SM, 0));
        btnBar.setOpaque(false);
        btnBar.add(csrParseBtn);
        btnBar.add(csrSampleBtn);
        btnBar.add(csrFileBtn);
        btnBar.add(csrClearBtn);

        Card inputCard = Card.titled(I18n.get("tool.certinspector.card.csrInput", "CSR 文本内容"));
        JPanel inputBody = Layouts.box(0, Tokens.SPACE_SM);
        inputBody.add(Fields.scroll(csrInputArea), BorderLayout.CENTER);
        inputBody.add(btnBar, BorderLayout.SOUTH);
        inputCard.setContent(inputBody);

        csrResultArea = Fields.output(10, 30);
        Card resultCard = Card.flush(I18n.get("tool.certinspector.card.csrResult", "CSR 解析详情与自签名校验"));
        resultCard.setContent(Fields.scroll(csrResultArea));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.45);
        split.setTopComponent(inputCard);
        split.setBottomComponent(resultCard);
        split.setBorder(null);

        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private void parseCsr() {
        String pem = csrInputArea.getText().trim();
        if (pem.isEmpty()) {
            UIUtils.info(getView(), "请输入 CSR 文本！");
            return;
        }
        runBackgroundWithInputs(csrWorker, csrButtons(), new JComponent[]{csrInputArea},
                () -> service.parseCsr(pem),
                info -> csrResultArea.setText(formatCsrInfo(info)),
                error -> UIUtils.error(getView(), "解析 CSR 失败: " + errorMessage(error)));
    }

    private static String formatCsrInfo(CertInspectorService.CsrInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("【CSR 解析成功】\n");
        sb.append("--------------------------------------------------\n");
        sb.append("• 主题名称 (Subject)        : ").append(info.getSubject()).append("\n");
        sb.append("• 签名算法 (Signature Alg)  : ").append(info.getSignatureAlgorithm()).append("\n");
        sb.append("• 公钥算法 (Public Key Alg) : ").append(info.getPublicKeyAlgorithm()).append("\n");
        sb.append("• 公钥长度 (Key Size)       : ").append(info.getKeySize()).append(" bits\n");
        sb.append("• 自签名校验 (Proof-of-Poss): ")
                .append(info.isSignatureValid()
                        ? "✓ PASS (自签名有效，持有人拥有该私钥)"
                        : "✗ FAIL (签名无效)")
                .append("\n");

        if (!info.getSanList().isEmpty()) {
            sb.append("• 备用名称 (SAN)            :\n");
            for (String san : info.getSanList()) {
                sb.append("    - ").append(san).append("\n");
            }
        }
        if (!info.getAttributes().isEmpty()) {
            sb.append("• 请求扩展属性 (Attributes) :\n");
            for (var entry : info.getAttributes().entrySet()) {
                sb.append("    - OID ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        return sb.toString();
    }

    private void loadSampleCsr() {
        // 生成一个简易自签 CSR 示例
        csrInputArea.setText("-----BEGIN CERTIFICATE REQUEST-----\n" +
                "MIICvDCCAaQCAQAwdzELMAkGA1UEBhMCQ04xEDAOBgNVBAgMB0JlaWppbmcxEDAOBgNV\n" +
                "BAcMB0JlaWppbmcxDzANBgNVBAoMBkFxaXNoaTEOMAwGA1UECwwFVG9vbHMxDjAMBgNV\n" +
                "BAMMBWxvY2FsMRgwFgYJKoZIhvcNAQkBFgl0ZXN0QGEuY20wggEiMA0GCSqGSIb3DQEB\n" +
                "AQUAA4IBDwAwggEKAoIBAQC6jY5u9kQyWlG4kS2jNl9qD4M2g3q7zR/3yK1m0q4c4tqE\n" +
                "mE5f9hK8j7f4y7xZ6u3m8v0c1m4b9q2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c\n" +
                "5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m\n" +
                "9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s\n" +
                "1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5wIDAQAB\n" +
                "oAAwDQYJKoZIhvcNAQELBQADggEBAJVm4a6e8m1k5t2b7s1q9v3c4w8k7p2e6s1t4m9v\n" +
                "2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t\n" +
                "4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e\n" +
                "6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k\n" +
                "7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v2c5w8k7p3e6s1t4m9v0=\n" +
                "-----END CERTIFICATE REQUEST-----");
        parseCsr();
    }

    private void loadCsrFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 CSR 文件 (.csr / .pem / .txt)");
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            runBackgroundWithInputs(csrWorker, csrButtons(), new JComponent[]{csrInputArea}, () -> {
                        String pem = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                        return new CsrLoadResult(pem, service.parseCsr(pem.trim()));
                    },
                    result -> {
                        csrInputArea.setText(result.pem);
                        csrResultArea.setText(formatCsrInfo(result.info));
                    },
                    error -> UIUtils.error(getView(), "读取或解析 CSR 失败: " + errorMessage(error)));
        }
    }

    // ==========================================
    // Tab 2: PKCS#12 密钥库检查与导出
    // ==========================================
    private JComponent buildPkcs12Tab() {
        JPanel panel = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM));

        p12FileField = Fields.mono("");
        p12FileField.setEditable(false);
        p12ChooseBtn = Buttons.secondary("选择文件...");
        p12ChooseBtn.addActionListener(e -> choosePkcs12File());

        p12PasswordField = Fields.password();
        p12UnlockBtn = Buttons.primary("解锁并检查");
        p12UnlockBtn.addActionListener(e -> inspectPkcs12());

        JPanel fileRow = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        fileRow.setOpaque(false);
        fileRow.add(p12FileField, BorderLayout.CENTER);
        fileRow.add(p12ChooseBtn, BorderLayout.EAST);

        JPanel pwdRow = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        pwdRow.setOpaque(false);
        pwdRow.add(p12PasswordField, BorderLayout.CENTER);
        pwdRow.add(p12UnlockBtn, BorderLayout.EAST);

        FormGrid configGrid = new FormGrid();
        configGrid.row("PKCS#12 文件", fileRow);
        configGrid.row("保护密码 (Password)", pwdRow);

        Card topCard = Card.titled("密钥库文件选择");
        topCard.setContent(configGrid);

        // 下方：左侧别名列表，右侧条目详情
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setDividerLocation(260);
        split.setResizeWeight(0.3);
        split.setBorder(null);

        p12ListModel = new DefaultListModel<>();
        p12List = new JList<>(p12ListModel);
        p12List.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        p12List.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onPkcs12EntrySelected(p12List.getSelectedValue());
            }
        });

        Card listCard = Card.flush("条目别名列表 (Aliases)");
        listCard.setContent(Fields.scroll(p12List));

        Card detailCard = Card.flush("证书与密钥详情");
        p12DetailArea = Fields.output(10, 30);

        p12ExportCertBtn = Buttons.snug("导出证书 (PEM)");
        p12ExportKeyBtn = Buttons.snug("导出私钥 (PEM)");
        p12ExportCertBtn.addActionListener(e -> exportSelectedCert());
        p12ExportKeyBtn.addActionListener(e -> exportSelectedKey());

        detailCard.addHeaderAction(p12ExportCertBtn);
        detailCard.addHeaderAction(p12ExportKeyBtn);
        detailCard.setContent(Fields.scroll(p12DetailArea));

        split.setLeftComponent(listCard);
        split.setRightComponent(detailCard);

        panel.add(topCard, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private void choosePkcs12File() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 PKCS#12 文件 (.p12 / .pfx)");
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            runBackgroundWithInputs(p12Worker, p12Buttons(), new JComponent[]{p12PasswordField},
                    () -> Files.readAllBytes(file.toPath()),
                    bytes -> {
                        p12FileField.setText(file.getAbsolutePath());
                        currentP12Bytes = bytes;
                        p12ListModel.clear();
                        p12DetailArea.setText("");
                    },
                    error -> UIUtils.error(getView(), "读取失败: " + errorMessage(error)));
        }
    }

    private void inspectPkcs12() {
        if (currentP12Bytes == null || currentP12Bytes.length == 0) {
            UIUtils.info(getView(), "请先选择 PKCS#12 文件！");
            return;
        }
        char[] password = p12PasswordField.getPassword();
        byte[] p12Bytes = currentP12Bytes;
        runBackgroundWithInputs(p12Worker, p12Buttons(), new JComponent[]{p12PasswordField}, () -> {
                    return service.inspectPkcs12(p12Bytes, password);
                },
                entries -> {
                    p12ListModel.clear();
                    for (CertInspectorService.Pkcs12EntryInfo entry : entries) {
                        p12ListModel.addElement(entry);
                    }
                    if (!entries.isEmpty()) {
                        p12List.setSelectedIndex(0);
                    }
                    UIUtils.info(getView(), "成功读取到 " + entries.size() + " 个别名条目！");
                },
                error -> UIUtils.error(getView(), "解锁失败（密码错误或文件损坏）: " + errorMessage(error)),
                () -> Arrays.fill(password, '\0'));
    }

    private void onPkcs12EntrySelected(CertInspectorService.Pkcs12EntryInfo entry) {
        if (entry == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("别名名称: ").append(entry.getAlias()).append("\n");
        sb.append("包含私钥: ").append(entry.isHasPrivateKey() ? "是 (PrivateKey Present)" : "否 (纯证书条目)").append("\n");
        sb.append("证书链长: ").append(entry.getChainLength()).append("\n");
        sb.append("公钥算法: ").append(entry.getKeyAlg()).append("\n");
        sb.append("主体 Subject: ").append(entry.getSubject()).append("\n");
        sb.append("颁发 Issuer : ").append(entry.getIssuer()).append("\n");
        sb.append("生效时间: ").append(entry.getNotBefore()).append("\n");
        sb.append("失效时间: ").append(entry.getNotAfter()).append("\n");

        if (entry.getChainLength() > 1) {
            sb.append("\n--- 完整证书链 (共 ").append(entry.getChainLength()).append(" 级) ---\n");
            for (int i = 0; i < entry.getChain().size(); i++) {
                X509Certificate c = entry.getChain().get(i);
                sb.append(String.format("Level %d: %s (由 %s 颁发)\n", i, c.getSubjectX500Principal().getName(), c.getIssuerX500Principal().getName()));
            }
        }
        p12DetailArea.setText(sb.toString());
    }

    private void exportSelectedCert() {
        CertInspectorService.Pkcs12EntryInfo entry = p12List.getSelectedValue();
        if (entry == null || entry.getCertificate() == null) {
            UIUtils.info(getView(), "请选择一个包含证书的条目！");
            return;
        }
        runBackgroundWithInputs(p12Worker, p12Buttons(), new JComponent[]{p12PasswordField},
                () -> CertUtils.toPem(entry.getCertificate()),
                pem -> {
                    UIUtils.copyToClipboard(pem);
                    UIUtils.info(getView(), "证书 PEM 已复制到剪贴板！");
                },
                error -> UIUtils.error(getView(), "导出失败: " + errorMessage(error)));
    }

    private void exportSelectedKey() {
        CertInspectorService.Pkcs12EntryInfo entry = p12List.getSelectedValue();
        if (entry == null || entry.getPrivateKey() == null) {
            UIUtils.info(getView(), "所选条目不包含私钥！");
            return;
        }
        runBackgroundWithInputs(p12Worker, p12Buttons(), new JComponent[]{p12PasswordField},
                () -> CertUtils.toPemPrivateKey(entry.getPrivateKey()),
                pem -> {
                    UIUtils.copyToClipboard(pem);
                    UIUtils.info(getView(), "私钥 PEM 已复制到剪贴板！");
                },
                error -> UIUtils.error(getView(), "导出私钥失败: " + errorMessage(error)));
    }

    // ==========================================
    // Tab 3: 证书链完整性诊断
    // ==========================================
    private JComponent buildChainTab() {
        JPanel panel = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM));

        chainInputArea = Fields.area(8, 30);
        chainInputArea.putClientProperty("JTextField.placeholderText", "粘贴包含多张证书的 PEM 文本（顺序任意，支持 Leaf + Intermediate + Root CA）");

        chainValidateBtn = Buttons.primary("执行证书链诊断");
        chainSampleBtn = Buttons.secondary("生成测试自签链");
        chainFileBtn = Buttons.ghost("从文件加载...");
        chainClearBtn = Buttons.ghost("清空");

        chainValidateBtn.addActionListener(e -> validateChain());
        chainSampleBtn.addActionListener(e -> generateSampleChain());
        chainFileBtn.addActionListener(e -> loadChainFile());
        chainClearBtn.addActionListener(e -> {
            cancelWorker(chainWorker);
            setButtonsEnabled(true, chainButtons());
            setInputControlsEnabled(true, new JComponent[]{chainInputArea});
            chainInputArea.setText("");
            chainLogArea.setText("");
            chainStatusLabel.setText("状态：就绪");
        });

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SPACE_SM, 0));
        btnBar.setOpaque(false);
        btnBar.add(chainValidateBtn);
        btnBar.add(chainSampleBtn);
        btnBar.add(chainFileBtn);
        btnBar.add(chainClearBtn);

        Card inputCard = Card.titled("输入多级证书 (PEM 格式)");
        JPanel inputBody = Layouts.box(0, Tokens.SPACE_SM);
        inputBody.add(Fields.scroll(chainInputArea), BorderLayout.CENTER);
        inputBody.add(btnBar, BorderLayout.SOUTH);
        inputCard.setContent(inputBody);

        chainStatusLabel = Fields.caption("状态：待校验");
        chainLogArea = Fields.output(10, 30);
        Card resultCard = Card.flush("证书链拓扑诊断报告");
        JPanel resultBody = new JPanel(new BorderLayout(0, Tokens.SPACE_XS));
        resultBody.setOpaque(false);
        resultBody.add(chainStatusLabel, BorderLayout.NORTH);
        resultBody.add(Fields.scroll(chainLogArea), BorderLayout.CENTER);
        resultCard.setContent(resultBody);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.45);
        split.setTopComponent(inputCard);
        split.setBottomComponent(resultCard);
        split.setBorder(null);

        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private void validateChain() {
        String pem = chainInputArea.getText().trim();
        if (pem.isEmpty()) {
            UIUtils.info(getView(), "请输入证书链 PEM 内容！");
            return;
        }
        runBackgroundWithInputs(chainWorker, chainButtons(), new JComponent[]{chainInputArea},
                () -> service.validateCertificateChainFromPem(pem),
                this::showChainResult,
                error -> showChainError(errorMessage(error)));
    }

    private void generateSampleChain() {
        runBackgroundWithInputs(chainWorker, chainButtons(), new JComponent[]{chainInputArea}, () -> {
                    // Key generation, certificate signing and validation are all
                    // deliberately kept off the EDT.
                    CertUtils.CertResult rootRes = CertUtils.createRootCA(
                            0, "Demo Root CA", "JavaToolbox", "Security", "Beijing", "Beijing", "CN", 10);
                    CertUtils.CertResult leafRes = CertUtils.signCertificate(
                            rootRes.getCertificatePem(), rootRes.getPrivateKeyPem(),
                            "RSA 2048", "demo.local", "JavaToolbox", "App", "Beijing", "Beijing", "CN",
                            "demo.local,localhost,127.0.0.1", 2);
                    String pem = leafRes.getCertificatePem() + "\n" + rootRes.getCertificatePem();
                    return new ChainLoadResult(pem, service.validateCertificateChainFromPem(pem));
                },
                result -> {
                    chainInputArea.setText(result.pem);
                    showChainResult(result.validation);
                    UIUtils.info(getView(), "已生成自签证书链测试样本！");
                },
                error -> UIUtils.error(getView(), "生成失败: " + errorMessage(error)));
    }

    private void loadChainFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择证书链文件 (.crt / .pem / .ca-bundle)");
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            runBackgroundWithInputs(chainWorker, chainButtons(), new JComponent[]{chainInputArea}, () -> {
                        String pem = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                        return new ChainLoadResult(pem, service.validateCertificateChainFromPem(pem.trim()));
                    },
                    result -> {
                        chainInputArea.setText(result.pem);
                        showChainResult(result.validation);
                    },
                    error -> UIUtils.error(getView(), "读取或校验证书链失败: " + errorMessage(error)));
        }
    }

    private void showChainResult(CertInspectorService.ChainValidationResult result) {
        if (result.isValid()) {
            chainStatusLabel.setText("校验结果: ✓ PASS (证书链完整且逐级签名校验通过)");
            chainStatusLabel.setForeground(Tokens.accent());
        } else {
            chainStatusLabel.setText("校验结果: ✗ FAIL (存在签名验证失败或证书链断裂)");
            chainStatusLabel.setForeground(Tokens.danger());
        }
        chainLogArea.setText(String.join("\n", result.getLogs()));
    }

    private void showChainError(String message) {
        chainStatusLabel.setText("解析失败");
        chainStatusLabel.setForeground(Tokens.danger());
        chainLogArea.setText("解析异常: " + message);
    }

    private JButton[] csrButtons() {
        return new JButton[]{csrParseBtn, csrSampleBtn, csrFileBtn};
    }

    private JButton[] p12Buttons() {
        return new JButton[]{p12ChooseBtn, p12UnlockBtn, p12ExportCertBtn, p12ExportKeyBtn};
    }

    private JButton[] chainButtons() {
        return new JButton[]{chainValidateBtn, chainSampleBtn, chainFileBtn};
    }

    private <T> void runBackground(AtomicReference<SwingWorker<?, ?>> currentWorker,
                                   JButton[] buttons,
                                   BackgroundTask<T> task,
                                   Consumer<T> onSuccess,
                                   Consumer<Throwable> onFailure) {
        runBackground(currentWorker, buttons, new JComponent[0], task, onSuccess, onFailure, () -> { });
    }

    private <T> void runBackground(AtomicReference<SwingWorker<?, ?>> currentWorker,
                                   JButton[] buttons,
                                   BackgroundTask<T> task,
                                   Consumer<T> onSuccess,
                                   Consumer<Throwable> onFailure,
                                   Runnable onFinished) {
        runBackground(currentWorker, buttons, new JComponent[0], task, onSuccess, onFailure, onFinished);
    }

    private <T> void runBackgroundWithInputs(AtomicReference<SwingWorker<?, ?>> currentWorker,
                                              JButton[] buttons,
                                              JComponent[] inputs,
                                              BackgroundTask<T> task,
                                              Consumer<T> onSuccess,
                                              Consumer<Throwable> onFailure) {
        runBackground(currentWorker, buttons, inputs, task, onSuccess, onFailure, () -> { });
    }

    private <T> void runBackgroundWithInputs(AtomicReference<SwingWorker<?, ?>> currentWorker,
                                              JButton[] buttons,
                                              JComponent[] inputs,
                                              BackgroundTask<T> task,
                                              Consumer<T> onSuccess,
                                              Consumer<Throwable> onFailure,
                                              Runnable onFinished) {
        runBackground(currentWorker, buttons, inputs, task, onSuccess, onFailure, onFinished);
    }

    private <T> void runBackground(AtomicReference<SwingWorker<?, ?>> currentWorker,
                                   JButton[] buttons,
                                   JComponent[] inputs,
                                   BackgroundTask<T> task,
                                   Consumer<T> onSuccess,
                                   Consumer<Throwable> onFailure,
                                   Runnable onFinished) {
        cancelWorker(currentWorker);
        setButtonsEnabled(false, buttons);
        setInputControlsEnabled(false, inputs);
        Object lifecycleLock = new Object();
        AtomicBoolean taskStarted = new AtomicBoolean(false);
        AtomicBoolean cleanupDone = new AtomicBoolean(false);
        AtomicBoolean skippedBeforeStart = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (cleanupDone.compareAndSet(false, true)) {
                onFinished.run();
            }
        };
        Runnable cancelBeforeStart = () -> {
            boolean shouldCleanup;
            synchronized (lifecycleLock) {
                shouldCleanup = !taskStarted.get()
                        && cleanupDone.compareAndSet(false, true);
                if (shouldCleanup) {
                    skippedBeforeStart.set(true);
                }
            }
            if (shouldCleanup) {
                onFinished.run();
            }
        };

        SwingWorker<T, Void> worker = new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                synchronized (lifecycleLock) {
                    // Cancellation can complete done() before the executor gets
                    // to this method. Do not let the task observe inputs that
                    // have already been cleared in that case.
                    if (cleanupDone.get() || isCancelled()) {
                        skippedBeforeStart.set(true);
                        return null;
                    }
                    taskStarted.set(true);
                }
                try {
                    return task.run();
                } finally {
                    // Run cleanup on the worker after the task has stopped using
                    // its inputs. SwingWorker.done() may run before an
                    // interrupted task has actually returned.
                    cleanup.run();
                }
            }

            @Override
            protected void done() {
                boolean isCurrent = currentWorker.compareAndSet(this, null);
                if (isCurrent) {
                    setButtonsEnabled(true, buttons);
                    setInputControlsEnabled(true, inputs);
                }
                if (isCancelled() && !taskStarted.get()) {
                    cancelBeforeStart.run();
                }
                T result = null;
                Throwable failure = null;
                boolean deliver = false;
                if (isCancelled() || !isCurrent || skippedBeforeStart.get()) {
                    return;
                }
                try {
                    result = get();
                    deliver = true;
                } catch (CancellationException ignored) {
                    // Cancellation intentionally leaves the panel unchanged.
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException error) {
                    failure = error.getCause() == null ? error : error.getCause();
                }
                if (failure != null) {
                    onFailure.accept(failure);
                } else if (deliver) {
                    onSuccess.accept(result);
                }
            }
        };
        currentWorker.set(worker);
        worker.execute();
    }

    private static void cancelWorker(AtomicReference<SwingWorker<?, ?>> currentWorker) {
        SwingWorker<?, ?> worker = currentWorker.getAndSet(null);
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
    }

    private static void setButtonsEnabled(boolean enabled, JButton[] buttons) {
        for (JButton button : buttons) {
            if (button != null) {
                button.setEnabled(enabled);
            }
        }
    }

    private static void setInputControlsEnabled(boolean enabled, JComponent[] inputs) {
        for (JComponent input : inputs) {
            if (input != null) {
                input.setEnabled(enabled);
            }
        }
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    @FunctionalInterface
    private interface BackgroundTask<T> {
        T run() throws Exception;
    }

    private static final class ChainLoadResult {
        private final String pem;
        private final CertInspectorService.ChainValidationResult validation;

        private ChainLoadResult(String pem, CertInspectorService.ChainValidationResult validation) {
            this.pem = pem;
            this.validation = validation;
        }
    }

    private static final class CsrLoadResult {
        private final String pem;
        private final CertInspectorService.CsrInfo info;

        private CsrLoadResult(String pem, CertInspectorService.CsrInfo info) {
            this.pem = pem;
            this.info = info;
        }
    }

    @Override
    public void closeResources() {
        cancelWorker(csrWorker);
        cancelWorker(p12Worker);
        cancelWorker(chainWorker);
    }
}
