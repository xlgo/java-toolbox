package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.security.domain.CertInspectorService;
import com.aqishi.toolbox.feature.security.domain.CertUtils;
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
import java.util.List;
import java.util.Objects;

/**
 * CSR / PKCS#12 / 证书链检查与诊断面板。
 */
public class CertInspectorPanel extends ToolPanel {

    private final CertInspectorService service;

    // Tab 1: CSR
    private JTextArea csrInputArea;
    private JTextArea csrResultArea;

    // Tab 2: PKCS#12
    private JTextField p12FileField;
    private JPasswordField p12PasswordField;
    private DefaultListModel<CertInspectorService.Pkcs12EntryInfo> p12ListModel;
    private JList<CertInspectorService.Pkcs12EntryInfo> p12List;
    private JTextArea p12DetailArea;
    private byte[] currentP12Bytes;

    // Tab 3: 证书链
    private JTextArea chainInputArea;
    private JTextArea chainLogArea;
    private JLabel chainStatusLabel;

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

        JButton parseBtn = Buttons.primary(I18n.get("tool.certinspector.btn.parseCsr", "解析 CSR"));
        JButton sampleBtn = Buttons.secondary("加载示例 CSR");
        JButton fileBtn = Buttons.ghost("从文件读取...");
        JButton clearBtn = Buttons.ghost("清空");

        parseBtn.addActionListener(e -> parseCsr());
        sampleBtn.addActionListener(e -> loadSampleCsr());
        fileBtn.addActionListener(e -> loadCsrFile());
        clearBtn.addActionListener(e -> {
            csrInputArea.setText("");
            csrResultArea.setText("");
        });

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SPACE_SM, 0));
        btnBar.setOpaque(false);
        btnBar.add(parseBtn);
        btnBar.add(sampleBtn);
        btnBar.add(fileBtn);
        btnBar.add(clearBtn);

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
        try {
            CertInspectorService.CsrInfo info = service.parseCsr(pem);
            StringBuilder sb = new StringBuilder();
            sb.append("【CSR 解析成功】\n");
            sb.append("--------------------------------------------------\n");
            sb.append("• 主题名称 (Subject)        : ").append(info.getSubject()).append("\n");
            sb.append("• 签名算法 (Signature Alg)  : ").append(info.getSignatureAlgorithm()).append("\n");
            sb.append("• 公钥算法 (Public Key Alg) : ").append(info.getPublicKeyAlgorithm()).append("\n");
            sb.append("• 公钥长度 (Key Size)       : ").append(info.getKeySize()).append(" bits\n");
            sb.append("• 自签名校验 (Proof-of-Poss): ").append(info.isSignatureValid() ? "✓ PASS (自签名有效，持有人拥有该私钥)" : "✗ FAIL (签名无效)")
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
            csrResultArea.setText(sb.toString());
        } catch (Exception ex) {
            UIUtils.error(getView(), "解析 CSR 失败: " + ex.getMessage());
        }
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
            try {
                byte[] bytes = Files.readAllBytes(chooser.getSelectedFile().toPath());
                csrInputArea.setText(new String(bytes, StandardCharsets.UTF_8));
                parseCsr();
            } catch (Exception ex) {
                UIUtils.error(getView(), "读取文件失败: " + ex.getMessage());
            }
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
        JButton chooseBtn = Buttons.secondary("选择文件...");
        chooseBtn.addActionListener(e -> choosePkcs12File());

        p12PasswordField = Fields.password();
        JButton unlockBtn = Buttons.primary("解锁并检查");
        unlockBtn.addActionListener(e -> inspectPkcs12());

        JPanel fileRow = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        fileRow.setOpaque(false);
        fileRow.add(p12FileField, BorderLayout.CENTER);
        fileRow.add(chooseBtn, BorderLayout.EAST);

        JPanel pwdRow = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        pwdRow.setOpaque(false);
        pwdRow.add(p12PasswordField, BorderLayout.CENTER);
        pwdRow.add(unlockBtn, BorderLayout.EAST);

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

        JButton exportCertBtn = Buttons.snug("导出证书 (PEM)");
        JButton exportKeyBtn = Buttons.snug("导出私钥 (PEM)");
        exportCertBtn.addActionListener(e -> exportSelectedCert());
        exportKeyBtn.addActionListener(e -> exportSelectedKey());

        detailCard.addHeaderAction(exportCertBtn);
        detailCard.addHeaderAction(exportKeyBtn);
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
            try {
                File f = chooser.getSelectedFile();
                p12FileField.setText(f.getAbsolutePath());
                currentP12Bytes = Files.readAllBytes(f.toPath());
            } catch (Exception ex) {
                UIUtils.error(getView(), "读取失败: " + ex.getMessage());
            }
        }
    }

    private void inspectPkcs12() {
        if (currentP12Bytes == null || currentP12Bytes.length == 0) {
            UIUtils.info(getView(), "请先选择 PKCS#12 文件！");
            return;
        }
        char[] password = p12PasswordField.getPassword();
        try {
            List<CertInspectorService.Pkcs12EntryInfo> entries = service.inspectPkcs12(currentP12Bytes, password);
            p12ListModel.clear();
            for (var entry : entries) {
                p12ListModel.addElement(entry);
            }
            if (!entries.isEmpty()) {
                p12List.setSelectedIndex(0);
            }
            UIUtils.info(getView(), "成功读取到 " + entries.size() + " 个别名条目！");
        } catch (Exception ex) {
            UIUtils.error(getView(), "解锁失败（密码错误或文件损坏）: " + ex.getMessage());
        }
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
        try {
            String pem = CertUtils.toPem(entry.getCertificate());
            UIUtils.copyToClipboard(pem);
            UIUtils.info(getView(), "证书 PEM 已复制到剪贴板！");
        } catch (Exception ex) {
            UIUtils.error(getView(), "导出失败: " + ex.getMessage());
        }
    }

    private void exportSelectedKey() {
        CertInspectorService.Pkcs12EntryInfo entry = p12List.getSelectedValue();
        if (entry == null || entry.getPrivateKey() == null) {
            UIUtils.info(getView(), "所选条目不包含私钥！");
            return;
        }
        try {
            String pem = CertUtils.toPemPrivateKey(entry.getPrivateKey());
            UIUtils.copyToClipboard(pem);
            UIUtils.info(getView(), "私钥 PEM 已复制到剪贴板！");
        } catch (Exception ex) {
            UIUtils.error(getView(), "导出私钥失败: " + ex.getMessage());
        }
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

        JButton validateBtn = Buttons.primary("执行证书链诊断");
        JButton sampleChainBtn = Buttons.secondary("生成测试自签链");
        JButton fileBtn = Buttons.ghost("从文件加载...");
        JButton clearBtn = Buttons.ghost("清空");

        validateBtn.addActionListener(e -> validateChain());
        sampleChainBtn.addActionListener(e -> generateSampleChain());
        fileBtn.addActionListener(e -> loadChainFile());
        clearBtn.addActionListener(e -> {
            chainInputArea.setText("");
            chainLogArea.setText("");
            chainStatusLabel.setText("状态：就绪");
        });

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SPACE_SM, 0));
        btnBar.setOpaque(false);
        btnBar.add(validateBtn);
        btnBar.add(sampleChainBtn);
        btnBar.add(fileBtn);
        btnBar.add(clearBtn);

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
        try {
            CertInspectorService.ChainValidationResult res = service.validateCertificateChainFromPem(pem);
            if (res.isValid()) {
                chainStatusLabel.setText("校验结果: ✓ PASS (证书链完整且逐级签名校验通过)");
                chainStatusLabel.setForeground(Tokens.accent());
            } else {
                chainStatusLabel.setText("校验结果: ✗ FAIL (存在签名验证失败或证书链断裂)");
                chainStatusLabel.setForeground(Tokens.danger());
            }
            chainLogArea.setText(String.join("\n", res.getLogs()));
        } catch (Exception ex) {
            chainStatusLabel.setText("解析失败");
            chainStatusLabel.setForeground(Tokens.danger());
            chainLogArea.setText("解析异常: " + ex.getMessage());
        }
    }

    private void generateSampleChain() {
        try {
            // 生成 Root CA
            var rootRes = CertUtils.createRootCA(0, "Demo Root CA", "JavaToolbox", "Security", "Beijing", "Beijing", "CN", 10);
            // 签发 Leaf Cert
            var leafRes = CertUtils.signCertificate(
                    rootRes.getCertificatePem(), rootRes.getPrivateKeyPem(),
                    "RSA 2048", "demo.local", "JavaToolbox", "App", "Beijing", "Beijing", "CN",
                    "demo.local,localhost,127.0.0.1", 2
            );
            chainInputArea.setText(leafRes.getCertificatePem() + "\n" + rootRes.getCertificatePem());
            validateChain();
            UIUtils.info(getView(), "已生成自签证书链测试样本！");
        } catch (Exception ex) {
            UIUtils.error(getView(), "生成失败: " + ex.getMessage());
        }
    }

    private void loadChainFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择证书链文件 (.crt / .pem / .ca-bundle)");
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            try {
                byte[] bytes = Files.readAllBytes(chooser.getSelectedFile().toPath());
                chainInputArea.setText(new String(bytes, StandardCharsets.UTF_8));
                validateChain();
            } catch (Exception ex) {
                UIUtils.error(getView(), "读取失败: " + ex.getMessage());
            }
        }
    }
}
