package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.crypto.CertUtils;
import com.aqishi.toolbox.crypto.acme.AcmeChallengeHelper;
import com.aqishi.toolbox.crypto.acme.AcmeClient;
import com.aqishi.toolbox.crypto.acme.CloudflareDnsProvider;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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
        JPanel root = Layouts.page();

        // 四类工作差别很大，保留标签页；标签外观交给全局主题，这里只去掉自带描边
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(null);
        tabs.addTab("创建根证书", buildRootCaTab());
        tabs.addTab("签发证书", buildSignTab());
        tabs.addTab("证书解析", buildParseTab());
        tabs.addTab("免费证书申请", buildAcmeTab());

        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    /** 标签页内容容器：补一层比 page() 更薄的内边距，纵向留出卡片间距 */
    private static JPanel tabBody() {
        JPanel body = Layouts.box(0, Tokens.SPACE_LG);
        body.setBorder(KitBorders.padding(Tokens.SPACE_MD));
        return body;
    }

    // ================================================================
    //  Tab 1: 创建根证书
    // ================================================================
    private JComponent buildRootCaTab() {
        JPanel p = tabBody();

        rootAlgCombo = Fields.combo(KEY_ALG_ITEMS, 160);
        rootCnField = Fields.text("My Root CA");
        rootOField = Fields.text("My Org");
        rootOuField = Fields.text("Security");
        rootLField = Fields.text("");
        rootStField = Fields.text("");
        rootCField = Fields.text("CN");
        rootYearsSpinner = Fields.spinner(10, 1, 100, 1);

        // 主体信息共 8 项，排成两列：单列 8 行会把下面的证书 / 私钥输出挤到看不见，
        // 而卡片横向是富余的。下拉框与微调器用 rowCompact，不拉满整列。
        FormGrid left = new FormGrid();
        left.rowCompact("密钥算法：", rootAlgCombo);
        left.row("通用名称", rootCnField);
        left.row("组织", rootOField);
        left.row("部门", rootOuField);
        left.glue();

        FormGrid right = new FormGrid();
        right.row("城市", rootLField);
        right.row("省份", rootStField);
        right.row("国家代码", rootCField);
        right.rowCompact("有效期", Layouts.wrapRow(Tokens.SPACE_SM, 0, rootYearsSpinner, Fields.caption("年")));
        right.glue();

        JButton createBtn = Buttons.primary("生成根证书");
        JButton clearBtn = Buttons.ghost("清空");

        Card config = Card.titled("根证书配置");
        config.setContent(Layouts.columns(Tokens.SPACE_XL, left, right));
        config.addHeaderAction(clearBtn);
        config.addHeaderAction(createBtn);

        // ===== 输出：证书与私钥等宽并排，下载动作放各自卡片标题右侧 =====
        rootCertOut = Fields.output(8, 30);
        rootKeyOut = Fields.output(8, 30);

        JButton rootCertDlBtn = Buttons.secondary("下载证书");
        rootCertDlBtn.addActionListener(e -> downloadPem(rootCertOut.getText(), "root-ca.crt"));

        JButton rootKeyDlBtn = Buttons.secondary("下载私钥");
        rootKeyDlBtn.addActionListener(e -> downloadPem(rootKeyOut.getText(), "root-ca.key"));

        Card certCard = Card.flush("证书");
        certCard.setContent(Fields.scroll(rootCertOut));
        certCard.addHeaderAction(rootCertDlBtn);

        Card keyCard = Card.flush("私钥");
        keyCard.setContent(Fields.scroll(rootKeyOut));
        keyCard.addHeaderAction(rootKeyDlBtn);

        p.add(config, BorderLayout.NORTH);
        p.add(Layouts.columns(Tokens.SPACE_LG, certCard, keyCard), BorderLayout.CENTER);

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
        JPanel p = tabBody();

        signAlgCombo = Fields.combo(KEY_ALG_ITEMS, 160);
        signCnField = Fields.text("myserver.example.com");
        signOField = Fields.text("My Org");
        signOuField = Fields.text("IT");
        signLField = Fields.text("");
        signStField = Fields.text("");
        signCField = Fields.text("CN");
        signSanField = Fields.text("DNS:example.com, DNS:*.example.com, IP:192.168.1.1");
        signYearsSpinner = Fields.spinner(2, 1, 50, 1);

        FormGrid left = new FormGrid();
        left.rowCompact("密钥算法：", signAlgCombo);
        left.row("通用名称：", signCnField);
        left.row("组织：", signOField);
        left.row("部门：", signOuField);
        left.glue();

        FormGrid right = new FormGrid();
        right.row("城市：", signLField);
        right.row("省份：", signStField);
        right.row("国家代码：", signCField);
        right.rowCompact("有效期：", Layouts.wrapRow(Tokens.SPACE_SM, 0, signYearsSpinner, Fields.caption("年")));
        right.glue();

        // 主题备用名往往是一长串，横跨整行而不是挤在半列里
        FormGrid sanForm = new FormGrid();
        sanForm.row("主题备用名：", signSanField);

        JPanel configBody = Layouts.box(0, Tokens.SPACE_SM);
        configBody.add(Layouts.columns(Tokens.SPACE_XL, left, right), BorderLayout.NORTH);
        configBody.add(sanForm, BorderLayout.SOUTH);

        JButton signBtn = Buttons.primary("签发证书");

        Card config = Card.titled("新证书配置");
        config.setContent(configBody);
        config.addHeaderAction(signBtn);

        // ===== CA 凭证（输入）与签发结果（输出）：四块 PEM 排成 2×2，等宽等高 =====
        signCaCertArea = Fields.area(6, 30);
        signCaKeyArea = Fields.area(6, 30);

        Card caCertCard = Card.flush("CA 证书");
        caCertCard.setContent(Fields.scroll(signCaCertArea));

        Card caKeyCard = Card.flush("CA 私钥");
        caKeyCard.setContent(Fields.scroll(signCaKeyArea));

        signCertOut = Fields.output(6, 30);
        signKeyOut = Fields.output(6, 30);

        JButton signCertDlBtn = Buttons.secondary("下载证书");
        signCertDlBtn.addActionListener(e -> downloadPem(signCertOut.getText(), "server.crt"));

        JButton signKeyDlBtn = Buttons.secondary("下载私钥");
        signKeyDlBtn.addActionListener(e -> downloadPem(signKeyOut.getText(), "server.key"));

        Card outCertCard = Card.flush("证书");
        outCertCard.setContent(Fields.scroll(signCertOut));
        outCertCard.addHeaderAction(signCertDlBtn);

        Card outKeyCard = Card.flush("私钥");
        outKeyCard.setContent(Fields.scroll(signKeyOut));
        outKeyCard.addHeaderAction(signKeyDlBtn);

        p.add(config, BorderLayout.NORTH);
        p.add(Layouts.rows(Tokens.SPACE_LG,
                Layouts.columns(Tokens.SPACE_LG, caCertCard, caKeyCard),
                Layouts.columns(Tokens.SPACE_LG, outCertCard, outKeyCard)), BorderLayout.CENTER);

        signBtn.addActionListener(e -> doSignCertificate());

        return p;
    }

    // ================================================================
    //  Tab 3: 证书解析
    // ================================================================
    private JComponent buildParseTab() {
        JPanel p = tabBody();

        parseInputArea = Fields.area(10, 50);

        JButton parseBtn = Buttons.primary("解析证书");
        JButton loadExampleBtn = Buttons.secondary("载入示例");
        JButton clearBtn2 = Buttons.ghost("清空");

        Card inputCard = Card.flush("粘贴证书内容");
        inputCard.setContent(Fields.scroll(parseInputArea));
        inputCard.addHeaderAction(clearBtn2);
        inputCard.addHeaderAction(loadExampleBtn);
        inputCard.addHeaderAction(parseBtn);

        parseOutputArea = Fields.output(12, 50);

        JButton parseDlBtn = Buttons.secondary("下载报告");
        parseDlBtn.addActionListener(e -> downloadText(parseOutputArea.getText(), "cert-report.txt"));

        Card outputCard = Card.flush("解析结果");
        outputCard.setContent(Fields.scroll(parseOutputArea));
        outputCard.addHeaderAction(parseDlBtn);

        // 输入区高度自适应放上面，解析报告吃掉剩余空间
        p.add(inputCard, BorderLayout.NORTH);
        p.add(outputCard, BorderLayout.CENTER);

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
        JPanel p = tabBody();

        String[] caOptions = {"Let's Encrypt 生产环境", "Let's Encrypt 测试环境", "ZeroSSL", "自定义 Directory URL"};
        acmeCaCombo = Fields.combo(caOptions, 200);

        acmeCustomCaUrlField = Fields.text("https://acme-v02.api.letsencrypt.org/directory");
        acmeCustomCaUrlField.setEnabled(false);

        acmeEmailField = Fields.text("admin@example.com");
        acmeDomainsField = Fields.text("example.com, *.example.com");

        String[] challengeOptions = {
                "DNS-01 自动验证",
                "DNS-01 手动验证",
                "HTTP-01 内置服务",
                "HTTP-01 目录写入"
        };
        acmeChallengeCombo = Fields.combo(challengeOptions, 200);

        acmeCfTokenField = Fields.text("");
        acmeCfTokenField.setToolTipText("请提供具有 Zone.DNS 权限的 Cloudflare API Token");

        acmeHttpPortField = Fields.text("80");
        acmeHttpPortField.setEnabled(false);
        acmeWebDirField = Fields.text("/var/www/html");
        acmeWebDirField.setEnabled(false);

        acmeKeyAlgCombo = Fields.combo(new String[]{"RSA 2048", "RSA 4096", "EC P-256"}, 160);

        // 配置项排成两列，左列是「选什么」，右列是「填什么」
        FormGrid caCol = new FormGrid();
        caCol.rowCompact("CA 机构服务：", acmeCaCombo);
        caCol.row("联系 Email：", acmeEmailField);
        caCol.rowCompact("域名验证方式：", acmeChallengeCombo);
        caCol.rowCompact("域名密钥算法：", acmeKeyAlgCombo);
        caCol.glue();

        FormGrid domainCol = new FormGrid();
        domainCol.row("自定义 Directory：", acmeCustomCaUrlField);
        domainCol.row("申请域名：", acmeDomainsField);
        domainCol.row("Cloudflare Token：", acmeCfTokenField);
        domainCol.glue();

        // 两个 HTTP-01 参数同属一项，横跨整行放得下，不必挤进半列
        FormGrid httpForm = new FormGrid();
        // 显式给 wrapRow 传 vgap=0：它回报的首选高度没把 FlowLayout 上下两道 vgap 算进去，
        // 用默认间距时单行会比实际需要矮一截，行内输入框的底边会被卡片裁掉
        httpForm.row("HTTP-01 参数：", Layouts.wrapRow(Tokens.SPACE_SM, 0,
                Fields.label("内置端口:"), acmeHttpPortField,
                Fields.label("Web根目录:"), acmeWebDirField));

        JPanel configBody = Layouts.box(0, Tokens.SPACE_SM);
        configBody.add(Layouts.columns(Tokens.SPACE_XL, caCol, domainCol), BorderLayout.NORTH);
        configBody.add(httpForm, BorderLayout.SOUTH);

        step1Btn = Buttons.primary("1. 初始化 & 提交申请");
        // 先按倒计时文案定宽再换回正式文案，否则计时中的 "(15s)" 会把按钮文字挤掉
        step2Btn = Buttons.primary("2. 确认部署并开始验证 (15s)");
        step2Btn.setText("2. 确认部署并开始验证");
        JButton clearAcmeBtn = Buttons.ghost("清空");
        step2Btn.setEnabled(false);

        Card step1Card = Card.titled("第 1 步：自动化申请配置",
                "填好 CA、域名与验证方式后提交，随后按日志里的指引完成域名验证部署");
        step1Card.setContent(configBody);
        step1Card.addHeaderAction(step1Btn);

        Card step2Card = Card.titled("第 2 步：确认部署并开始验证");
        step2Card.setContent(Fields.caption(
                "验证记录部署生效后再点右侧按钮，CA 才会来校验并签发；倒计时结束前按钮不可点。"));
        step2Card.addHeaderAction(step2Btn);

        // ===== 日志：铺满型卡片吃掉剩余空间，整个流程都靠它反馈 =====
        acmeLogArea = Fields.output(10, 60);
        Card logCard = Card.flush("申请流程与日志控制台");
        logCard.setContent(Fields.scroll(acmeLogArea));
        logCard.addHeaderAction(clearAcmeBtn);

        // ===== 签发结果 =====
        acmeCertOut = Fields.output(3, 30);
        acmeKeyOut = Fields.output(3, 30);

        JButton acmeCertDlBtn = Buttons.secondary("下载证书");
        acmeCertDlBtn.addActionListener(e -> {
            String prefix = buildDefaultCertFileNamePrefix();
            downloadPem(acmeCertOut.getText(), prefix + ".pem");
        });

        JButton acmeKeyDlBtn = Buttons.secondary("下载私钥");
        acmeKeyDlBtn.addActionListener(e -> {
            String prefix = buildDefaultCertFileNamePrefix();
            downloadPem(acmeKeyOut.getText(), prefix + ".key");
        });

        JButton acmeZipDlBtn = Buttons.secondary("一键打包下载");
        acmeZipDlBtn.addActionListener(e -> {
            String prefix = buildDefaultCertFileNamePrefix();
            downloadCertZip(acmeCertOut.getText(), acmeKeyOut.getText(), prefix + ".zip");
        });

        Card acmeCertCard = Card.flush("证书");
        acmeCertCard.setContent(Fields.scroll(acmeCertOut));
        acmeCertCard.addHeaderAction(acmeZipDlBtn);
        acmeCertCard.addHeaderAction(acmeCertDlBtn);

        Card acmeKeyCard = Card.flush("私钥");
        acmeKeyCard.setContent(Fields.scroll(acmeKeyOut));
        acmeKeyCard.addHeaderAction(acmeKeyDlBtn);

        JPanel acmeResults = Layouts.columns(Tokens.SPACE_LG, acmeCertCard, acmeKeyCard);
        // 结果区允许收得很矮：流程进行中真正要盯的是日志，证书内容随时可以往上拖开
        acmeResults.setMinimumSize(new Dimension(0, Tokens.CONTROL_HEIGHT * 2));

        // 两张步骤卡片放进滚动区：窗口矮的时候它们可以让出高度给日志，
        // 否则「配置永远按首选高度铺满」会把日志和结果一起顶出可视区域。
        final JScrollPane stepsScroll = Fields.scrollTransparent(
                new WidthTrackingBody(Layouts.stack(Tokens.SPACE_LG, step1Card, step2Card)));
        stepsScroll.setMinimumSize(new Dimension(0, Tokens.CONTROL_HEIGHT * 3));

        final JComponent logAndResults = Layouts.splitVertical(logCard, acmeResults, 0.7);
        // 权重 0：窗口变高时多出来的空间全部给日志与结果，步骤卡片保持自身高度
        final JSplitPane acmeSplit = Layouts.splitVertical(stepsScroll, logAndResults, 0.0);

        // JSplitPane 的初始分隔位置是按权重瓜分整块高度算的，不看首选尺寸：
        // 不干预的话步骤区要么被压成一条缝、要么留出大片空白。等它第一次拿到真实高度后，
        // 把分隔条放到「两张步骤卡片刚好完整显示」的位置，并保证日志与结果至少留出最小高度。
        acmeSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                acmeSplit.removeComponentListener(this);
                int wanted = stepsScroll.getPreferredSize().height;
                int room = acmeSplit.getHeight() - acmeSplit.getDividerSize()
                        - logAndResults.getMinimumSize().height;
                acmeSplit.setDividerLocation(Math.max(0, Math.min(wanted, room)));
            }
        });
        p.add(acmeSplit, BorderLayout.CENTER);

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

    /**
     * 宽度跟随视口的滚动内容容器。
     *
     * <p>默认的 {@link JScrollPane} 会按内容首选宽度铺开并弹出横向滚动条，
     * 但卡片里的表单本来就能自适应宽度，横向滚动纯属打扰；这里让内容宽度跟着视口走，
     * 只在纵向不够时滚动。内容挂在 NORTH，卡片被拉高时也不会跟着抻长。</p>
     */
    private static final class WidthTrackingBody extends JPanel implements Scrollable {

        WidthTrackingBody(Component content) {
            super(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.NORTH);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
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
