package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.crypto.CertUtils;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 证书管理面板：根证书创建、证书签发、证书解析。
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

    public CertPanel() {
        super("dev", "cert.management",
                "证书", "CA", "根证书", "自签证书", "X.509",
                "SSL", "TLS", "PKI", "证书解析", "certificate");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("创建根证书 (Root CA)", buildRootCaTab());
        tabs.addTab("签发证书 (Sign)", buildSignTab());
        tabs.addTab("证书解析 (Parse)", buildParseTab());

        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    // ================================================================
    //  Tab 1: 创建根证书
    // ================================================================
    private JComponent buildRootCaTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        // ---- 配置区 ----
        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("根证书配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 6, 4, 6);

        int row = 0;

        // 密钥算法
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("密钥算法："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootAlgCombo = new JComboBox<>(KEY_ALG_ITEMS);
        rootAlgCombo.setFont(UIUtils.plainFont());
        configPanel.add(rootAlgCombo, gbc);

        // 主题字段
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        configPanel.add(new JLabel("CN (通用名称)"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootCnField = new JTextField("My Root CA");
        configPanel.add(rootCnField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("O (组织)"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootOField = new JTextField("My Org");
        configPanel.add(rootOField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("OU (部门)"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootOuField = new JTextField("Security");
        configPanel.add(rootOuField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("L (城市)"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootLField = new JTextField();
        configPanel.add(rootLField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("ST (省份)"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootStField = new JTextField();
        configPanel.add(rootStField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("C (国家/地区 2字母代码)"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootCField = new JTextField("CN");
        configPanel.add(rootCField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        configPanel.add(new JLabel("有效期 (年)"), gbc);
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

        // ---- 输出区 ----
        JPanel outPanel = new JPanel(new GridLayout(2, 1, 0, 6));
        rootCertOut = new JTextArea();
        rootCertOut.setFont(UIUtils.monoFont());
        rootCertOut.setEditable(false);
        outPanel.add(UIUtils.scrollText(rootCertOut, "证书 (Certificate PEM)"));

        rootKeyOut = new JTextArea();
        rootKeyOut.setFont(UIUtils.monoFont());
        rootKeyOut.setEditable(false);
        outPanel.add(UIUtils.scrollText(rootKeyOut, "私钥 (Private Key PEM)  ⚠️ 请妥善保管"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(configPanel), outPanel);
        split.setResizeWeight(0.4);
        p.add(split, BorderLayout.CENTER);

        // 事件
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

        // ---- 左侧：CA 密钥配置 ----
        JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
        leftPanel.setBorder(BorderFactory.createTitledBorder("CA 凭证"));

        signCaCertArea = new JTextArea(8, 30);
        signCaCertArea.setFont(UIUtils.monoFont());
        signCaCertArea.setLineWrap(true);
        leftPanel.add(UIUtils.scrollText(signCaCertArea, "CA 证书 (PEM)"), BorderLayout.NORTH);

        signCaKeyArea = new JTextArea(8, 30);
        signCaKeyArea.setFont(UIUtils.monoFont());
        signCaKeyArea.setLineWrap(true);
        leftPanel.add(UIUtils.scrollText(signCaKeyArea, "CA 私钥 (PEM)"), BorderLayout.CENTER);

        // ---- 右侧：新证书配置 ----
        JPanel rightPanel = new JPanel(new GridBagLayout());
        rightPanel.setBorder(BorderFactory.createTitledBorder("新证书配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 6, 3, 6);

        int row = 0;

        // 密钥算法：label + combo on same row (同创建根证书页签)
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        rightPanel.add(new JLabel("密钥算法："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signAlgCombo = new JComboBox<>(KEY_ALG_ITEMS);
        signAlgCombo.setFont(UIUtils.plainFont());
        rightPanel.add(signAlgCombo, gbc);

        // 主题字段
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        rightPanel.add(new JLabel("CN"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signCnField = new JTextField("myserver.example.com");
        rightPanel.add(signCnField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("O"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signOField = new JTextField("My Org");
        rightPanel.add(signOField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("OU"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signOuField = new JTextField("IT");
        rightPanel.add(signOuField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("L"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signLField = new JTextField();
        rightPanel.add(signLField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("ST"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signStField = new JTextField();
        rightPanel.add(signStField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rightPanel.add(new JLabel("C"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        signCField = new JTextField("CN");
        rightPanel.add(signCField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 2;
        rightPanel.add(new JLabel("SAN (域名, 多个用逗号分隔)："), gbc);
        row++; gbc.gridy = row; gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.weightx = 0;
        gbc.gridx = 1; gbc.weightx = 1.0;
        signSanField = new JTextField("example.com, www.example.com");
        rightPanel.add(signSanField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 2;
        rightPanel.add(new JLabel("有效期 (年)："), gbc);
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

        // ---- 输出区 ----
        signCertOut = new JTextArea();
        signCertOut.setFont(UIUtils.monoFont());
        signCertOut.setEditable(false);
        signKeyOut = new JTextArea();
        signKeyOut.setFont(UIUtils.monoFont());
        signKeyOut.setEditable(false);

        JPanel outPanel = new JPanel(new GridLayout(2, 1, 0, 4));
        outPanel.add(UIUtils.scrollText(signCertOut, "签发证书 (Certificate PEM)"));
        outPanel.add(UIUtils.scrollText(signKeyOut, "新证书私钥 (Private Key PEM)  ⚠️ 请妥善保管"));

        // ---- 布局 ----
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

        // 输入
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

        inputPanel.add(UIUtils.scrollText(parseInputArea, "粘贴 PEM 格式证书内容"), BorderLayout.CENTER);
        inputPanel.add(btnRow, BorderLayout.SOUTH);

        // 输出
        parseOutputArea = new JTextArea();
        parseOutputArea.setFont(UIUtils.monoFont());
        parseOutputArea.setEditable(false);
        JScrollPane outputScroll = UIUtils.scrollText(parseOutputArea, "解析结果");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inputPanel, outputScroll);
        split.setResizeWeight(0.35);
        p.add(split, BorderLayout.CENTER);

        // 事件
        parseBtn.addActionListener(e -> doParse());
        loadExampleBtn.addActionListener(e -> loadExampleCert());
        clearBtn2.addActionListener(e -> {
            parseInputArea.setText("");
            parseOutputArea.setText("");
        });

        // 启动时自动载入示例
        loadExampleCert();

        return p;
    }

    // ================================================================
    //  操作实现
    // ================================================================

    /** 创建根证书 */
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
                UIUtils.error(rootCertOut, "CN (通用名称) 不能为空");
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

    /** 签发证书 */
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
                UIUtils.error(signCertOut, "CN (通用名称) 不能为空");
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

    /** 解析证书 */
    private void doParse() {
        String pem = parseInputArea.getText().trim();
        if (pem.isEmpty()) {
            parseOutputArea.setText("请粘贴 PEM 格式的证书内容。");
            return;
        }

        try {
            CertUtils.CertInfo info = CertUtils.parseCertificate(pem);
            parseOutputArea.setText(info.toReport());
        } catch (Exception ex) {
            parseOutputArea.setText("解析失败：" + ex.getMessage());
        }
    }

    /** 载入一个自生成的示例证书用于演示解析 */
    private void loadExampleCert() {
        try {
            // 生成一个示例根证书用于解析演示
            CertUtils.CertResult result = CertUtils.createRootCA(0, "Example CA",
                    "Example Inc", "Security", "", "", "CN", 10);
            String pem = result.getCertificatePem();
            parseInputArea.setText(pem);
            doParse();
        } catch (Exception ignored) {
            parseInputArea.setText("// 无法自动生成示例");
        }
    }
}
