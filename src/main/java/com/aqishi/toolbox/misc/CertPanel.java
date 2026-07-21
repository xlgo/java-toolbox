package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.crypto.CertUtils;
import com.aqishi.toolbox.crypto.acme.AcmeChallengeHelper;
import com.aqishi.toolbox.crypto.acme.AcmeClient;
import com.aqishi.toolbox.crypto.acme.CloudflareDnsProvider;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 证书管理面板：根证书创建、证书签发、证书解析、免费证书自动申请。
 */
public class CertPanel extends ToolPanel {

    // ==================== 通用组件 ====================
    private static final String[] KEY_ALG_ITEMS = CertUtils.KEY_ALGORITHMS;

    // ==================== Tab 1: 创建根证书 ====================
    private JComboBox<String> rootAlgCombo;
    private JTextField rootCnField, rootOField, rootOuField, rootLField, rootStField, rootCField;
    private JSpinner rootYearsSpinner;
    private JTextArea rootCertOut, rootKeyOut;

    // ==================== Tab 2: 签发证书 ====================
    private JTextArea signCaCertArea, signCaKeyArea;
    private JComboBox<String> signAlgCombo;
    private JTextField signCnField, signOField, signOuField, signLField, signStField, signCField;
    private JTextField signSanField;
    private JSpinner signYearsSpinner;
    private JTextArea signCertOut, signKeyOut;

    // ==================== Tab 3: 证书解析 ====================
    private JTextArea parseInputArea;
    private JTextArea parseOutputArea;

    // ==================== Tab 4: 免费证书申请 ====================
    private JComboBox<String> acmeCaCombo;
    private JTextField acmeCustomCaUrlField;
    private JTextField acmeEmailField;
    private JTextField acmeDomainsField;
    private JComboBox<String> acmeChallengeCombo;
    private JTextField acmeCfTokenField;
    private JTextField acmeHttpPortField;
    private JTextField acmeWebDirField;
    private JComboBox<String> acmeKeyAlgCombo;

    private JButton step1Btn;
    private JButton step2Btn;
    private javax.swing.Timer step2Timer;
    private int step2CountdownSeconds;

    private JTextArea acmeLogArea;
    private JTextArea acmeCertOut, acmeKeyOut;

    // ACME 运行过程中的状态数据
    private KeyPair currentAccountKeyPair;
    private KeyPair currentDomainKeyPair;
    private AcmeClient currentAcmeClient;
    private AcmeClient.AcmeOrder currentOrder;
    private List<AcmeClient.AcmeChallenge> currentChallenges;
    private List<String> currentDomainList;
    private Map<AcmeClient.AcmeChallenge, String> cfRecordIds = new ConcurrentHashMap<>();

    public CertPanel() {
        super("crypto", "cert.management",
                "证书", "CA", "根证书", "自签证书", "X.509",
                "SSL", "TLS", "PKI", "证书解析", "certificate", "ACME", "Let's Encrypt", "免费证书", "Cloudflare");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("创建根证书", buildRootCaTab());
        tabs.addTab("签发证书", buildSignTab());
        tabs.addTab("证书解析", buildParseTab());
        tabs.addTab("免费证书申请", buildAcmeTab());

        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    // ================================================================
    //  Tab 1: 创建根证书
    // ================================================================
    private JComponent buildRootCaTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("根证书配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 6, 4, 6);

        int row = 0;

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("密钥算法："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootAlgCombo = new JComboBox<>(KEY_ALG_ITEMS);
        rootAlgCombo.setFont(UIUtils.plainFont());
        configPanel.add(rootAlgCombo, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        configPanel.add(new JLabel("通用名称"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootCnField = new JTextField("My Root CA");
        configPanel.add(rootCnField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("组织"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootOField = new JTextField("My Org");
        configPanel.add(rootOField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("部门"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootOuField = new JTextField("Security");
        configPanel.add(rootOuField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("城市"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootLField = new JTextField();
        configPanel.add(rootLField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("省份"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootStField = new JTextField();
        configPanel.add(rootStField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("国家代码"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootCField = new JTextField("CN");
        configPanel.add(rootCField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("有效期"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootYearsSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
        configPanel.add(rootYearsSpinner, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton createBtn = UIUtils.button("生成根证书", 110);
        JButton clearBtn = UIUtils.button("清空", 70);
        btnPanel.add(createBtn);
        btnPanel.add(clearBtn);
        configPanel.add(btnPanel, gbc);

        rootCertOut = new JTextArea();
        rootCertOut.setFont(UIUtils.monoFont());
        rootCertOut.setEditable(false);
        JButton rootCertDlBtn = UIUtils.button("下载证书", 110);
        rootCertDlBtn.addActionListener(e -> downloadPem(rootCertOut.getText(), "root-ca.crt"));

        rootKeyOut = new JTextArea();
        rootKeyOut.setFont(UIUtils.monoFont());
        rootKeyOut.setEditable(false);
        JButton rootKeyDlBtn = UIUtils.button("下载私钥", 110);
        rootKeyDlBtn.addActionListener(e -> downloadPem(rootKeyOut.getText(), "root-ca.key"));

        JPanel outPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        outPanel.add(wrapWithButtons(
                UIUtils.scrollText(rootCertOut, "证书"), rootCertDlBtn));
        outPanel.add(wrapWithButtons(
                UIUtils.scrollText(rootKeyOut, "私钥"), rootKeyDlBtn));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(configPanel), outPanel);
        split.setResizeWeight(0.4);
        p.add(split, BorderLayout.CENTER);

        createBtn.addActionListener(e -> doCreateRootCa());
        clearBtn.addActionListener(e -> {
            rootCertOut.setText("");
            rootKeyOut.setText("");
        });

        return p;
    }

    // ================================================================
    //  Tab 2: 签发证书
    // ================================================================
    private JComponent buildSignTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
        leftPanel.setBorder(BorderFactory.createTitledBorder("CA 凭证"));

        signCaCertArea = new JTextArea(6, 30);
        signCaCertArea.setFont(UIUtils.monoFont());
        signCaCertArea.setLineWrap(true);
        leftPanel.add(UIUtils.scrollText(signCaCertArea, "CA 证书"), BorderLayout.NORTH);

        signCaKeyArea = new JTextArea(6, 30);
        signCaKeyArea.setFont(UIUtils.monoFont());
        signCaKeyArea.setLineWrap(true);
        leftPanel.add(UIUtils.scrollText(signCaKeyArea, "CA 私钥"), BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new GridBagLayout());
        rightPanel.setBorder(BorderFactory.createTitledBorder("新证书配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 6, 3, 6);

        int row = 0;

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        rightPanel.add(new JLabel("密钥算法："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signAlgCombo = new JComboBox<>(KEY_ALG_ITEMS);
        signAlgCombo.setFont(UIUtils.plainFont());
        rightPanel.add(signAlgCombo, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        rightPanel.add(new JLabel("通用名称："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signCnField = new JTextField("myserver.example.com");
        rightPanel.add(signCnField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("组织："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signOField = new JTextField("My Org");
        rightPanel.add(signOField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("部门："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signOuField = new JTextField("IT");
        rightPanel.add(signOuField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("城市："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signLField = new JTextField();
        rightPanel.add(signLField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("省份："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signStField = new JTextField();
        rightPanel.add(signStField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("国家代码："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signCField = new JTextField("CN");
        rightPanel.add(signCField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 2;
        rightPanel.add(new JLabel("主题备用名："), gbc);
        row++; gbc.gridy = row; gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.weightx = 0;
        gbc.gridx = 1; gbc.weightx = 1.0;
        signSanField = new JTextField("DNS:example.com, DNS:*.example.com, IP:192.168.1.1");
        rightPanel.add(signSanField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 2;
        rightPanel.add(new JLabel("有效期："), gbc);
        row++; gbc.gridy = row; gbc.gridwidth = 1;
        gbc.gridx = 1; gbc.weightx = 1.0;
        signYearsSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 50, 1));
        rightPanel.add(signYearsSpinner, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton signBtn = UIUtils.button("签发证书", 100);
        btnPanel.add(signBtn);
        rightPanel.add(btnPanel, gbc);

        signCertOut = new JTextArea();
        signCertOut.setFont(UIUtils.monoFont());
        signCertOut.setEditable(false);
        JButton signCertDlBtn = UIUtils.button("下载证书", 110);
        signCertDlBtn.addActionListener(e -> downloadPem(signCertOut.getText(), "server.crt"));

        signKeyOut = new JTextArea();
        signKeyOut.setFont(UIUtils.monoFont());
        signKeyOut.setEditable(false);
        JButton signKeyDlBtn = UIUtils.button("下载私钥", 110);
        signKeyDlBtn.addActionListener(e -> downloadPem(signKeyOut.getText(), "server.key"));

        JPanel outPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        outPanel.add(wrapWithButtons(
                UIUtils.scrollText(signCertOut, "证书"), signCertDlBtn));
        outPanel.add(wrapWithButtons(
                UIUtils.scrollText(signKeyOut, "私钥"), signKeyDlBtn));

        JSplitPane topSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(leftPanel), new JScrollPane(rightPanel));
        topSplit.setResizeWeight(0.4);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, outPanel);
        mainSplit.setResizeWeight(0.6);

        p.add(mainSplit, BorderLayout.CENTER);

        signBtn.addActionListener(e -> doSignCertificate());

        return p;
    }

    // ================================================================
    //  Tab 3: 证书解析
    // ================================================================
    private JComponent buildParseTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        JPanel inputPanel = new JPanel(new BorderLayout(4, 4));
        parseInputArea = new JTextArea(10, 50);
        parseInputArea.setFont(UIUtils.monoFont());
        parseInputArea.setLineWrap(true);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton parseBtn = UIUtils.button("解析证书", 100);
        JButton loadExampleBtn = UIUtils.button("载入示例", 90);
        JButton clearBtn2 = UIUtils.button("清空", 70);
        btnRow.add(parseBtn);
        btnRow.add(loadExampleBtn);
        btnRow.add(clearBtn2);

        inputPanel.add(UIUtils.scrollText(parseInputArea, "粘贴证书内容"), BorderLayout.CENTER);
        inputPanel.add(btnRow, BorderLayout.SOUTH);

        parseOutputArea = new JTextArea();
        parseOutputArea.setFont(UIUtils.monoFont());
        parseOutputArea.setEditable(false);

        JButton parseDlBtn = UIUtils.button("下载报告", 110);
        parseDlBtn.addActionListener(e -> downloadText(parseOutputArea.getText(), "cert-report.txt"));

        JPanel outputPanel = wrapWithButtons(
                UIUtils.scrollText(parseOutputArea, "解析结果"), parseDlBtn);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inputPanel, outputPanel);
        split.setResizeWeight(0.35);
        p.add(split, BorderLayout.CENTER);

        parseBtn.addActionListener(e -> doParse());
        loadExampleBtn.addActionListener(e -> loadExampleCert());
        clearBtn2.addActionListener(e -> {
            parseInputArea.setText("");
            parseOutputArea.setText("");
        });

        loadExampleCert();

        return p;
    }

    // ================================================================
    //  Tab 4: 免费证书申请
    // ================================================================
    private JComponent buildAcmeTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("自动化申请配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 6, 4, 6);

        // 两列布局构建
        // 行 0：CA 机构服务 | 自定义 Directory
        gbc.gridy = 0;
        gbc.gridx = 0; gbc.weightx = 0;
        configPanel.add(new JLabel("CA 机构服务："), gbc);
        gbc.gridx = 1; gbc.weightx = 0.5;
        String[] caOptions = {"Let's Encrypt 生产环境", "Let's Encrypt 测试环境", "ZeroSSL", "自定义 Directory URL"};
        acmeCaCombo = new JComboBox<>(caOptions);
        acmeCaCombo.setFont(UIUtils.plainFont());
        configPanel.add(acmeCaCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        configPanel.add(new JLabel("自定义 Directory："), gbc);
        gbc.gridx = 3; gbc.weightx = 0.5;
        acmeCustomCaUrlField = new JTextField("https://acme-v02.api.letsencrypt.org/directory");
        acmeCustomCaUrlField.setEnabled(false);
        configPanel.add(acmeCustomCaUrlField, gbc);

        // 行 1：联系 Email | 申请域名
        gbc.gridy = 1;
        gbc.gridx = 0; gbc.weightx = 0;
        configPanel.add(new JLabel("联系 Email："), gbc);
        gbc.gridx = 1; gbc.weightx = 0.5;
        acmeEmailField = new JTextField("admin@example.com");
        configPanel.add(acmeEmailField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        configPanel.add(new JLabel("申请域名："), gbc);
        gbc.gridx = 3; gbc.weightx = 0.5;
        acmeDomainsField = new JTextField("example.com, *.example.com");
        configPanel.add(acmeDomainsField, gbc);

        // 行 2：域名验证方式 | Cloudflare Token
        gbc.gridy = 2;
        gbc.gridx = 0; gbc.weightx = 0;
        configPanel.add(new JLabel("域名验证方式："), gbc);
        gbc.gridx = 1; gbc.weightx = 0.5;
        String[] challengeOptions = {
                "DNS-01 自动验证",
                "DNS-01 手动验证",
                "HTTP-01 内置服务",
                "HTTP-01 目录写入"
        };
        acmeChallengeCombo = new JComboBox<>(challengeOptions);
        acmeChallengeCombo.setFont(UIUtils.plainFont());
        configPanel.add(acmeChallengeCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        configPanel.add(new JLabel("Cloudflare Token："), gbc);
        gbc.gridx = 3; gbc.weightx = 0.5;
        acmeCfTokenField = new JTextField();
        acmeCfTokenField.setToolTipText("请提供具有 Zone.DNS 权限的 Cloudflare API Token");
        configPanel.add(acmeCfTokenField, gbc);

        // 行 3：HTTP-01 参数 (独占整行不动)
        gbc.gridy = 3;
        gbc.gridx = 0; gbc.weightx = 0; gbc.gridwidth = 1;
        configPanel.add(new JLabel("HTTP-01 参数："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 3;
        JPanel httpOptPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        httpOptPanel.add(new JLabel("内置端口:"));
        acmeHttpPortField = new JTextField("80", 5);
        acmeHttpPortField.setEnabled(false);
        httpOptPanel.add(acmeHttpPortField);
        httpOptPanel.add(new JLabel(" Web根目录:"));
        acmeWebDirField = new JTextField("/var/www/html", 15);
        acmeWebDirField.setEnabled(false);
        httpOptPanel.add(acmeWebDirField);
        configPanel.add(httpOptPanel, gbc);

        // 行 4：域名密钥算法
        gbc.gridy = 4;
        gbc.gridx = 0; gbc.weightx = 0; gbc.gridwidth = 1;
        configPanel.add(new JLabel("域名密钥算法："), gbc);
        gbc.gridx = 1; gbc.weightx = 0.5; gbc.gridwidth = 1;
        acmeKeyAlgCombo = new JComboBox<>(new String[]{"RSA 2048", "RSA 4096", "EC P-256"});
        acmeKeyAlgCombo.setFont(UIUtils.plainFont());
        configPanel.add(acmeKeyAlgCombo, gbc);

        // 行 5：按钮操作栏 (单独占一行)
        gbc.gridy = 5;
        gbc.gridx = 0; gbc.weightx = 1.0; gbc.gridwidth = 4;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        step1Btn = UIUtils.button("1. 初始化 & 提交申请", 160);
        step2Btn = UIUtils.button("2. 确认部署并开始验证", 160);
        JButton clearAcmeBtn = UIUtils.button("清空", 70);
        step2Btn.setEnabled(false);

        btnPanel.add(step1Btn);
        btnPanel.add(step2Btn);
        btnPanel.add(clearAcmeBtn);
        configPanel.add(btnPanel, gbc);

        // 行 6：垂直方向填补，保证整个配置面板顶部对齐
        gbc.gridy = 6;
        gbc.gridx = 0; gbc.gridwidth = 4; gbc.weighty = 1.0;
        configPanel.add(new JLabel(), gbc);

        // 中间与下方输出 (两列布局)
        acmeLogArea = new JTextArea();
        acmeLogArea.setFont(UIUtils.monoFont());
        acmeLogArea.setEditable(false);

        acmeCertOut = new JTextArea();
        acmeCertOut.setFont(UIUtils.monoFont());
        acmeCertOut.setEditable(false);

        acmeKeyOut = new JTextArea();
        acmeKeyOut.setFont(UIUtils.monoFont());
        acmeKeyOut.setEditable(false);

        JButton acmeCertDlBtn = UIUtils.button("下载证书", 110);
        acmeCertDlBtn.addActionListener(e -> {
            String prefix = buildDefaultCertFileNamePrefix();
            downloadPem(acmeCertOut.getText(), prefix + ".pem");
        });

        JButton acmeKeyDlBtn = UIUtils.button("下载私钥", 110);
        acmeKeyDlBtn.addActionListener(e -> {
            String prefix = buildDefaultCertFileNamePrefix();
            downloadPem(acmeKeyOut.getText(), prefix + ".key");
        });

        JButton acmeZipDlBtn = UIUtils.button("一键打包下载", 120);
        acmeZipDlBtn.addActionListener(e -> {
            String prefix = buildDefaultCertFileNamePrefix();
            downloadCertZip(acmeCertOut.getText(), acmeKeyOut.getText(), prefix + ".zip");
        });

        JPanel certWrap = wrapWithButtons(UIUtils.scrollText(acmeCertOut, "证书"), acmeCertDlBtn, acmeZipDlBtn);
        JPanel keyWrap = wrapWithButtons(UIUtils.scrollText(acmeKeyOut, "私钥"), acmeKeyDlBtn);

        JPanel outResultPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        outResultPanel.add(certWrap);
        outResultPanel.add(keyWrap);

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                UIUtils.scrollText(acmeLogArea, "申请流程与日志控制台"), outResultPanel);
        centerSplit.setResizeWeight(0.5);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(configPanel), centerSplit);
        mainSplit.setResizeWeight(0.35);

        p.add(mainSplit, BorderLayout.CENTER);

        // 事件监听
        acmeCaCombo.addActionListener(e -> {
            boolean isCustom = acmeCaCombo.getSelectedIndex() == 3;
            acmeCustomCaUrlField.setEnabled(isCustom);
        });

        acmeChallengeCombo.addActionListener(e -> {
            int idx = acmeChallengeCombo.getSelectedIndex();
            acmeCfTokenField.setEnabled(idx == 0);
            acmeHttpPortField.setEnabled(idx == 2);
            acmeWebDirField.setEnabled(idx == 3);
        });

        step1Btn.addActionListener(e -> {
            step1Btn.setEnabled(false);
            new Thread(() -> {
                try {
                    boolean ok = doAcmeStep1();
                    SwingUtilities.invokeLater(() -> {
                        step1Btn.setEnabled(true);
                        if (ok) {
                            startStep2Countdown();
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> step1Btn.setEnabled(true));
                }
            }).start();
        });

        step2Btn.addActionListener(e -> {
            if (step2Timer != null && step2Timer.isRunning()) {
                step2Timer.stop();
            }
            step2Btn.setEnabled(false);
            step2Btn.setText("2. 确认部署并开始验证");
            new Thread(() -> {
                try {
                    doAcmeStep2();
                } finally {
                    SwingUtilities.invokeLater(() -> step2Btn.setEnabled(true));
                }
            }).start();
        });

        clearAcmeBtn.addActionListener(e -> {
            if (step2Timer != null && step2Timer.isRunning()) {
                step2Timer.stop();
            }
            acmeLogArea.setText("");
            acmeCertOut.setText("");
            acmeKeyOut.setText("");
            step2Btn.setEnabled(false);
            step2Btn.setText("2. 确认部署并开始验证");
        });

        return p;
    }

    private void startStep2Countdown() {
        if (step2Timer != null && step2Timer.isRunning()) {
            step2Timer.stop();
        }
        step2CountdownSeconds = 15;
        step2Btn.setEnabled(false);
        step2Btn.setText("2. 确认部署并开始验证 (15s)");

        step2Timer = new javax.swing.Timer(1000, e -> {
            step2CountdownSeconds--;
            if (step2CountdownSeconds > 0) {
                step2Btn.setText("2. 确认部署并开始验证 (" + step2CountdownSeconds + "s)");
            } else {
                step2Timer.stop();
                step2Btn.setEnabled(true);
                step2Btn.setText("2. 确认部署并开始验证");
            }
        });
        step2Timer.start();
    }

    // ================================================================
    //  ACME 操作步骤实现
    // ================================================================

    private void appendAcmeLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            acmeLogArea.append(msg + "\n");
            acmeLogArea.setCaretPosition(acmeLogArea.getDocument().getLength());
        });
    }

    private boolean doAcmeStep1() {
        try {
            acmeLogArea.setText("");
            acmeCertOut.setText("");
            acmeKeyOut.setText("");
            cfRecordIds.clear();

            String directoryUrl;
            int caIdx = acmeCaCombo.getSelectedIndex();
            if (caIdx == 0) directoryUrl = AcmeClient.LETSENCRYPT_PROD;
            else if (caIdx == 1) directoryUrl = AcmeClient.LETSENCRYPT_STAGE;
            else if (caIdx == 2) directoryUrl = AcmeClient.ZEROSSL_PROD;
            else directoryUrl = acmeCustomCaUrlField.getText().trim();

            String email = acmeEmailField.getText().trim();
            String rawDomains = acmeDomainsField.getText().trim();
            if (rawDomains.isEmpty()) {
                appendAcmeLog("❌ 错误：申请域名不能为空！");
                return false;
            }

            String[] domainArr = rawDomains.split("[,\\s]+");
            currentDomainList = new ArrayList<>(Arrays.asList(domainArr));

            appendAcmeLog("=== 步骤 1: 开始准备申请免费证书 ===");
            appendAcmeLog("CA 接口地址: " + directoryUrl);
            appendAcmeLog("目标域名列表: " + currentDomainList);

            appendAcmeLog("生成 RSA 2048 账户密钥与域名私钥...");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            currentAccountKeyPair = kpg.generateKeyPair();

            String domainKeyAlg = (String) acmeKeyAlgCombo.getSelectedItem();
            int idxAlg = CertUtils.indexOfKeyAlg(domainKeyAlg);
            currentDomainKeyPair = CertUtils.generateKeyPair(idxAlg < 0 ? 0 : idxAlg);

            currentAcmeClient = new AcmeClient(directoryUrl);
            currentAcmeClient.setLogger(this::appendAcmeLog);

            currentAcmeClient.init();
            currentAcmeClient.registerAccount(currentAccountKeyPair, email);

            currentOrder = currentAcmeClient.createOrder(currentAccountKeyPair, currentDomainList);

            int challengeTypeIdx = acmeChallengeCombo.getSelectedIndex();
            String preferType = (challengeTypeIdx == 0 || challengeTypeIdx == 1) ? "dns-01" : "http-01";

            currentChallenges = currentAcmeClient.getChallenges(currentAccountKeyPair, currentOrder, preferType);

            appendAcmeLog("\n=======================================================");
            appendAcmeLog("📋 域名验证部署指引:");
            appendAcmeLog("=======================================================");

            if (challengeTypeIdx == 0) { // DNS-01 自动验证
                String cfToken = acmeCfTokenField.getText().trim();
                if (cfToken.isEmpty()) {
                    appendAcmeLog("❌ 错误：未填写 Cloudflare API Token！");
                    return false;
                }

                appendAcmeLog("正在通过 Cloudflare API 自动添加 DNS TXT 解析记录...");
                for (AcmeClient.AcmeChallenge ch : currentChallenges) {
                    appendAcmeLog("正在为域名 [" + ch.domain + "] 添加记录: " + ch.dnsTxtRecordName);
                    String recordId = CloudflareDnsProvider.addTxtRecord(cfToken, ch.domain, ch.dnsTxtRecordName, ch.dnsTxtRecordValue);
                    cfRecordIds.put(ch, recordId);
                    appendAcmeLog("  ✅ 成功解析添加！Record ID: " + recordId);
                }
                appendAcmeLog("\n💡 Cloudflare API 解析记录已添加完毕！倒计时 15s 待全球 DNS 生效后即可点击第 2 步。");

            } else if (challengeTypeIdx == 1) { // DNS-01 手动验证
                for (AcmeClient.AcmeChallenge ch : currentChallenges) {
                    appendAcmeLog("【域名】: " + ch.domain);
                    appendAcmeLog("  请前往您的 DNS 域名服务商后台，添加一条 TXT 解析记录：");
                    appendAcmeLog("  主机记录 : " + ch.dnsTxtRecordName);
                    appendAcmeLog("  记录值   : " + ch.dnsTxtRecordValue);
                    appendAcmeLog("-------------------------------------------------------");
                }
                appendAcmeLog("💡 提示：添加 TXT 记录后请等待倒计时生效，然后点击第 2 步。");

            } else if (challengeTypeIdx == 2) { // HTTP-01 内置服务
                int port = Integer.parseInt(acmeHttpPortField.getText().trim());
                appendAcmeLog("启动本地内置 HTTP-01 验证服务 (Port: " + port + ")...");
                AcmeChallengeHelper.startHttpServer(port);
                for (AcmeClient.AcmeChallenge ch : currentChallenges) {
                    AcmeChallengeHelper.registerToken(ch.token, ch.keyAuthorization);
                    appendAcmeLog("已挂载路径: http://" + ch.domain + "/.well-known/acme-challenge/" + ch.token);
                }
                appendAcmeLog("💡 内置 HTTP 服务已启动，待倒计时结束后点击第 2 步。");

            } else if (challengeTypeIdx == 3) { // HTTP-01 目录写入
                String webDir = acmeWebDirField.getText().trim();
                for (AcmeClient.AcmeChallenge ch : currentChallenges) {
                    File file = AcmeChallengeHelper.writeChallengeToFile(webDir, ch.token, ch.keyAuthorization);
                    appendAcmeLog("已向本地 Web 目录写入 Challenge 文件: " + file.getAbsolutePath());
                }
                appendAcmeLog("💡 文件写入完成，待倒计时结束后点击第 2 步。");
            }

            return true;
        } catch (Exception ex) {
            appendAcmeLog("\n❌ 初始化申请失败: " + ex.getMessage());
            return false;
        }
    }

    private void doAcmeStep2() {
        try {
            appendAcmeLog("\n=== 步骤 2: 开始向 CA 发起验证并签发证书 ===");
            for (AcmeClient.AcmeChallenge ch : currentChallenges) {
                currentAcmeClient.triggerChallenge(currentAccountKeyPair, ch);
            }

            String certPemChain = currentAcmeClient.finalizeOrder(currentAccountKeyPair, currentDomainKeyPair, currentDomainList, currentOrder);

            String domainKeyPem = CertUtils.toPemPrivateKey(currentDomainKeyPair.getPrivate());

            SwingUtilities.invokeLater(() -> {
                acmeCertOut.setText(certPemChain);
                acmeKeyOut.setText(domainKeyPem);
            });

            appendAcmeLog("\n🎉🎉 恭喜！免费 SSL 证书已成功签发！旁边的证书与私钥可以保存使用。");
        } catch (Exception ex) {
            appendAcmeLog("\n❌ 验证或签发失败: " + ex.getMessage());
        } finally {
            AcmeChallengeHelper.stopHttpServer();
            AcmeChallengeHelper.clearTokens();

            if (!cfRecordIds.isEmpty()) {
                String cfToken = acmeCfTokenField.getText().trim();
                appendAcmeLog("\n🧹 正在通过 Cloudflare API 清理临时生成的 TXT 验证记录...");
                for (Map.Entry<AcmeClient.AcmeChallenge, String> entry : cfRecordIds.entrySet()) {
                    try {
                        CloudflareDnsProvider.deleteTxtRecord(cfToken, entry.getKey().domain, entry.getValue());
                        appendAcmeLog("  ✓ 已清理域名 [" + entry.getKey().domain + "] 的 TXT 记录 (" + entry.getValue() + ")");
                    } catch (Exception ex) {
                        appendAcmeLog("  ⚠️ 清理 TXT 记录失败: " + ex.getMessage());
                    }
                }
                cfRecordIds.clear();
            }
        }
    }

    // ================================================================
    //  其它辅助操作实现
    // ================================================================

    private void doCreateRootCa() {
        try {
            String alg = (String) rootAlgCombo.getSelectedItem();
            String cn = rootCnField.getText().trim();
            String o = rootOField.getText().trim();
            String ou = rootOuField.getText().trim();
            String l = rootLField.getText().trim();
            String st = rootStField.getText().trim();
            String c = rootCField.getText().trim();
            int years = (Integer) rootYearsSpinner.getValue();

            if (cn.isEmpty()) {
                UIUtils.error(rootCertOut, "通用名称不能为空");
                return;
            }

            CertUtils.CertResult result = CertUtils.createRootCA(alg, cn, o, ou, l, st, c, years);

            rootCertOut.setText(result.getCertificatePem());
            rootKeyOut.setText(result.getPrivateKeyPem());

            UIUtils.info(rootCertOut, "✅ 根证书创建成功！");
        } catch (Exception ex) {
            rootCertOut.setText("");
            rootKeyOut.setText("");
            UIUtils.error(rootCertOut, "创建根证书失败：" + ex.getMessage());
        }
    }

    private void doSignCertificate() {
        try {
            String caCertPem = signCaCertArea.getText().trim();
            String caKeyPem = signCaKeyArea.getText().trim();

            if (caCertPem.isEmpty() || caKeyPem.isEmpty()) {
                UIUtils.error(signCertOut, "请先填入 CA 证书和 CA 私钥");
                return;
            }

            String alg = (String) signAlgCombo.getSelectedItem();
            String cn = signCnField.getText().trim();
            String o = signOField.getText().trim();
            String ou = signOuField.getText().trim();
            String l = signLField.getText().trim();
            String st = signStField.getText().trim();
            String c = signCField.getText().trim();
            String san = signSanField.getText().trim();
            int years = (Integer) signYearsSpinner.getValue();

            if (cn.isEmpty()) {
                UIUtils.error(signCertOut, "通用名称不能为空");
                return;
            }

            CertUtils.CertResult result = CertUtils.signCertificate(
                    caCertPem, caKeyPem, alg, cn, o, ou, l, st, c, san, years);

            signCertOut.setText(result.getCertificatePem());
            signKeyOut.setText(result.getPrivateKeyPem());

            UIUtils.info(signCertOut, "✅ 证书签发成功！");
        } catch (Exception ex) {
            signCertOut.setText("");
            signKeyOut.setText("");
            UIUtils.error(signCertOut, "签发证书失败：" + ex.getMessage());
        }
    }

    private void doParse() {
        String pem = parseInputArea.getText().trim();
        if (pem.isEmpty()) {
            parseOutputArea.setText("请粘贴证书内容。");
            return;
        }

        try {
            CertUtils.CertInfo info = CertUtils.parseCertificate(pem);
            parseOutputArea.setText(info.toReport());
        } catch (Exception ex) {
            parseOutputArea.setText("解析失败：" + ex.getMessage());
        }
    }

    private void loadExampleCert() {
        try {
            CertUtils.CertResult result = CertUtils.createRootCA(0, "Example CA",
                    "Example Inc", "Security", "", "", "CN", 10);
            String pem = result.getCertificatePem();
            parseInputArea.setText(pem);
            doParse();
        } catch (Exception ignored) {
            parseInputArea.setText("// 无法自动生成示例");
        }
    }

    private static JPanel wrapWithButtons(Component scrollPane, JButton... buttons) {
        JPanel p = new JPanel(new BorderLayout(4, 2));
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        for (JButton btn : buttons) {
            btnRow.add(btn);
        }
        p.add(scrollPane, BorderLayout.CENTER);
        p.add(btnRow, BorderLayout.SOUTH);
        return p;
    }

    private String buildDefaultCertFileNamePrefix() {
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.of(1900, 1, 1),
                java.time.LocalDate.now());
        String domain = "domain";
        if (currentDomainList != null && !currentDomainList.isEmpty()) {
            domain = currentDomainList.get(0).replaceAll("^\\*\\.", "").replaceAll("[^a-zA-Z0-9.-]", "_");
        } else if (acmeDomainsField != null) {
            String raw = acmeDomainsField.getText().trim();
            if (!raw.isEmpty()) {
                domain = raw.split("[,\\s]+")[0].replaceAll("^\\*\\.", "").replaceAll("[^a-zA-Z0-9.-]", "_");
            }
        }
        return days + "_" + domain;
    }

    private void downloadCertZip(String certPem, String keyPem, String defaultName) {
        if (certPem == null || certPem.trim().isEmpty() || keyPem == null || keyPem.trim().isEmpty()) {
            UIUtils.error(getView(), "没有可打包下载的内容，请先完成证书签发");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(defaultName));
        chooser.setDialogTitle("保存证书压缩包");
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP 压缩文件 (*.zip)", "zip"));
        if (chooser.showSaveDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File zipFile = chooser.getSelectedFile();
            String prefix = buildDefaultCertFileNamePrefix();
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipFile.toPath()))) {
                zos.putNextEntry(new java.util.zip.ZipEntry(prefix + ".pem"));
                zos.write(certPem.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new java.util.zip.ZipEntry(prefix + ".crt"));
                zos.write(certPem.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new java.util.zip.ZipEntry(prefix + ".key"));
                zos.write(keyPem.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                UIUtils.info(getView(), "打包下载成功：" + zipFile.getName());
            } catch (Exception ex) {
                UIUtils.error(getView(), "打包下载失败：" + ex.getMessage());
            }
        }
    }

    private void downloadPem(String content, String defaultName) {
        if (content == null || content.trim().isEmpty()) {
            UIUtils.error(getView(), "没有可下载的内容，请先生成证书");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(defaultName));
        chooser.setDialogTitle("下载证书文件");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "PEM 文件 (*.pem, *.crt, *.key)", "pem", "crt", "key"));
        if (chooser.showSaveDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            saveToFile(chooser.getSelectedFile(), content);
        }
    }

    private void downloadText(String content, String defaultName) {
        if (content == null || content.trim().isEmpty()) {
            UIUtils.error(getView(), "没有可下载的内容");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(defaultName));
        chooser.setDialogTitle("下载报告");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "文本文件 (*.txt)", "txt"));
        if (chooser.showSaveDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            saveToFile(chooser.getSelectedFile(), content);
        }
    }

    private void saveToFile(File file, String content) {
        try {
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
            UIUtils.info(getView(), "下载成功：" + file.getName());
        } catch (Exception ex) {
            UIUtils.error(getView(), "下载失败：" + ex.getMessage());
        }
    }
}
