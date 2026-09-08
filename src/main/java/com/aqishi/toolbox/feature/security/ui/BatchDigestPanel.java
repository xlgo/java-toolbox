package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.security.domain.BatchDigestService;
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
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;
import java.util.List;

/**
 * 文件批量摘要与签名校验面板。
 */
public class BatchDigestPanel extends ToolPanel {

    private final BatchDigestService service;

    // 文件列表与结果
    private final List<File> selectedFiles = new ArrayList<>();
    private final List<BatchDigestService.DigestResult> results = new ArrayList<>();

    // 控件
    private JCheckBox md5Check, sha1Check, sha256Check, sha512Check, sm3Check;
    private JProgressBar progressBar;
    private JLabel progressLabel;
    private JButton startBtn, stopBtn;
    private volatile boolean cancelRequested = false;

    // 结果表格
    private DefaultTableModel tableModel;
    private JTable resultTable;

    // 清单与签名
    private JTextArea checksumArea;
    private JComboBox<String> checksumAlgCombo;
    private JTextArea signKeyArea;
    private JTextField signatureField;

    public BatchDigestPanel() {
        this(ToolCatalog.FILE_BATCH_DIGEST, new BatchDigestService());
    }

    public BatchDigestPanel(BatchDigestService service) {
        this(ToolCatalog.FILE_BATCH_DIGEST, service);
    }

    public BatchDigestPanel(ToolDescriptor descriptor, BatchDigestService service) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ================= 1. 顶部控制栏 =================
        Card controlCard = Card.titled(I18n.get("tool.batchdigest.control.title", "批量摘要任务配置"));

        JButton addFilesBtn = Buttons.primary(I18n.get("tool.batchdigest.btn.addFiles", "添加文件..."));
        JButton addDirBtn = Buttons.secondary(I18n.get("tool.batchdigest.btn.addDir", "添加文件夹..."));
        JButton clearBtn = Buttons.ghost(I18n.get("tool.batchdigest.btn.clear", "清空列表"));

        addFilesBtn.addActionListener(e -> chooseFiles());
        addDirBtn.addActionListener(e -> chooseDirectory());
        clearBtn.addActionListener(e -> clearFiles());

        JPanel fileActions = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        fileActions.setOpaque(false);
        fileActions.add(addFilesBtn);
        fileActions.add(addDirBtn);
        fileActions.add(clearBtn);

        // 算法复选框
        md5Check = Fields.check("MD5", true);
        sha1Check = Fields.check("SHA-1", false);
        sha256Check = Fields.check("SHA-256", true);
        sha512Check = Fields.check("SHA-512", false);
        sm3Check = Fields.check("SM3 (国密)", true);

        JPanel algBar = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_MD, 0));
        algBar.setOpaque(false);
        algBar.add(md5Check);
        algBar.add(sha1Check);
        algBar.add(sha256Check);
        algBar.add(sha512Check);
        algBar.add(sm3Check);

        // 执行与进度控制
        startBtn = Buttons.primary(I18n.get("tool.batchdigest.btn.start", "开始计算"));
        stopBtn = Buttons.danger(I18n.get("tool.batchdigest.btn.stop", "停止"));
        stopBtn.setEnabled(false);

        startBtn.addActionListener(e -> startBatchCalculation());
        stopBtn.addActionListener(e -> cancelRequested = true);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressLabel = Fields.caption("待添加文件");

        JPanel execBar = new JPanel(new BorderLayout(Tokens.SPACE_MD, 0));
        execBar.setOpaque(false);
        JPanel execBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        execBtns.setOpaque(false);
        execBtns.add(startBtn);
        execBtns.add(stopBtn);
        execBar.add(execBtns, BorderLayout.WEST);
        execBar.add(progressBar, BorderLayout.CENTER);
        execBar.add(progressLabel, BorderLayout.EAST);

        FormGrid configGrid = new FormGrid();
        configGrid.row(I18n.get("tool.batchdigest.label.files", "待测文件"), fileActions);
        configGrid.row(I18n.get("tool.batchdigest.label.algorithms", "计算算法"), algBar);
        configGrid.row(I18n.get("tool.batchdigest.label.action", "任务执行"), execBar);
        controlCard.setContent(configGrid);

        // ================= 2. 中部表格卡片 =================
        Card tableCard = Card.flush(I18n.get("tool.batchdigest.table.title", "摘要计算结果清单"));

        String[] columnNames = {"比对状态", "文件名", "文件大小", "SHA-256", "MD5", "SM3", "耗时"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultTable = new JTable(tableModel);
        resultTable.setRowHeight(26);
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(260);
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(220);
        resultTable.getColumnModel().getColumn(5).setPreferredWidth(260);
        resultTable.getColumnModel().getColumn(6).setPreferredWidth(70);

        // 状态列高亮着色
        resultTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(CENTER);
                String val = String.valueOf(value);
                if ("PASS".equalsIgnoreCase(val)) {
                    setForeground(new Color(40, 167, 69));
                    setFont(Tokens.fontBodyStrong());
                } else if ("FAIL".equalsIgnoreCase(val)) {
                    setForeground(new Color(220, 53, 69));
                    setFont(Tokens.fontBodyStrong());
                } else {
                    setForeground(Tokens.mutedForeground());
                    setFont(Tokens.fontBody());
                }
                return c;
            }
        });

        tableCard.setContent(Fields.scroll(resultTable));

        // ================= 3. 底部选项卡：清单比对与数字签名 =================
        JTabbedPane bottomTabs = new JTabbedPane();

        // ----- Tab 1: 校验清单比对与导出 -----
        JPanel checksumPanel = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        checksumPanel.setOpaque(false);
        checksumPanel.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM));

        checksumArea = Fields.area(5, 30);
        checksumArea.putClientProperty("JTextField.placeholderText", "粘贴 sha256sum 或 md5sum 文本清单，例如：\n2c7a... *app.jar\n5d41...  config.yaml");

        checksumAlgCombo = Fields.combo(new String[]{"SHA-256", "MD5", "SM3", "SHA-1"}, 120);
        JButton matchBtn = Buttons.primary("执行清单比对");
        JButton exportBtn = Buttons.secondary("导出为清单文本");
        JButton loadChecksumFileBtn = Buttons.ghost("从文件加载清单...");

        matchBtn.addActionListener(e -> runChecksumMatch());
        exportBtn.addActionListener(e -> exportChecksumList());
        loadChecksumFileBtn.addActionListener(e -> loadChecksumFile());

        JPanel checkActions = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        checkActions.setOpaque(false);
        checkActions.add(new JLabel("目标算法:"));
        checkActions.add(checksumAlgCombo);
        checkActions.add(matchBtn);
        checkActions.add(exportBtn);
        checkActions.add(loadChecksumFileBtn);

        checksumPanel.add(checkActions, BorderLayout.NORTH);
        checksumPanel.add(Fields.scroll(checksumArea), BorderLayout.CENTER);
        bottomTabs.addTab(I18n.get("tool.batchdigest.tab.checksum", "哈希清单校验 (Check)"), checksumPanel);

        // ----- Tab 2: 数字签名与验签 -----
        JPanel signPanel = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        signPanel.setOpaque(false);
        signPanel.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM));

        signKeyArea = Fields.area(4, 30);
        signKeyArea.putClientProperty("JTextField.placeholderText", "输入 RSA / SM2 私钥 PEM（用于签名）或公钥 PEM（用于验签）");

        signatureField = Fields.mono("");
        signatureField.putClientProperty("JTextField.placeholderText", "十六进制或 Base64 签名串");

        JComboBox<String> signAlgCombo = Fields.combo(new String[]{"SHA256withRSA", "SHA1withRSA", "SHA512withRSA"}, 160);
        JButton doSignBtn = Buttons.primary("为选中文件签名");
        JButton doVerifyBtn = Buttons.secondary("验证选中文件签名");

        doSignBtn.addActionListener(e -> runSignFile((String) signAlgCombo.getSelectedItem()));
        doVerifyBtn.addActionListener(e -> runVerifyFile((String) signAlgCombo.getSelectedItem()));

        FormGrid signGrid = new FormGrid();
        signGrid.row("签名算法", signAlgCombo);
        signGrid.row("密钥 (PEM)", Fields.scroll(signKeyArea));
        signGrid.row("签名结果/凭据", signatureField);

        JPanel signActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SPACE_SM, 0));
        signActions.setOpaque(false);
        signActions.add(doSignBtn);
        signActions.add(doVerifyBtn);

        signPanel.add(signGrid, BorderLayout.CENTER);
        signPanel.add(signActions, BorderLayout.SOUTH);
        bottomTabs.addTab(I18n.get("tool.batchdigest.tab.sign", "数字签名与验签 (Signature)"), signPanel);

        root.add(controlCard, BorderLayout.NORTH);
        root.add(tableCard, BorderLayout.CENTER);
        root.add(bottomTabs, BorderLayout.SOUTH);
        return root;
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("选择待计算摘要的文件");
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File[] files = chooser.getSelectedFiles();
            for (File f : files) {
                if (f.isFile() && !selectedFiles.contains(f)) {
                    selectedFiles.add(f);
                }
            }
            refreshTablePlaceholder();
        }
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择要批量扫描的文件夹");
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File dir = chooser.getSelectedFile();
            List<File> scanned = service.scanFiles(dir, true);
            for (File f : scanned) {
                if (!selectedFiles.contains(f)) {
                    selectedFiles.add(f);
                }
            }
            refreshTablePlaceholder();
        }
    }

    private void clearFiles() {
        selectedFiles.clear();
        results.clear();
        tableModel.setRowCount(0);
        progressBar.setValue(0);
        progressLabel.setText("已清空");
    }

    private void refreshTablePlaceholder() {
        tableModel.setRowCount(0);
        for (File f : selectedFiles) {
            tableModel.addRow(new Object[]{"-", f.getName(), formatSize(f.length()), "待计算...", "待计算...", "待计算...", "-"});
        }
        progressLabel.setText("共 " + selectedFiles.size() + " 个文件就绪");
    }

    private void startBatchCalculation() {
        if (selectedFiles.isEmpty()) {
            UIUtils.info(getView(), "请先添加文件或目录！");
            return;
        }

        List<String> selectedAlgs = new ArrayList<>();
        if (md5Check.isSelected()) selectedAlgs.add("MD5");
        if (sha1Check.isSelected()) selectedAlgs.add("SHA-1");
        if (sha256Check.isSelected()) selectedAlgs.add("SHA-256");
        if (sha512Check.isSelected()) selectedAlgs.add("SHA-512");
        if (sm3Check.isSelected()) selectedAlgs.add("SM3");

        if (selectedAlgs.isEmpty()) {
            UIUtils.info(getView(), "请至少勾选一种哈希算法！");
            return;
        }

        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        cancelRequested = false;
        results.clear();
        progressBar.setValue(0);
        progressBar.setMaximum(selectedFiles.size());

        new SwingWorker<Void, BatchDigestService.DigestResult>() {
            @Override
            protected Void doInBackground() {
                int count = 0;
                for (File f : selectedFiles) {
                    if (cancelRequested) break;
                    BatchDigestService.DigestResult res = service.computeFileDigest(f, selectedAlgs);
                    publish(res);
                    count++;
                    setProgress((int) ((count / (double) selectedFiles.size()) * 100));
                }
                return null;
            }

            @Override
            protected void process(List<BatchDigestService.DigestResult> chunks) {
                for (BatchDigestService.DigestResult r : chunks) {
                    results.add(r);
                    updateRow(r);
                    progressBar.setValue(results.size());
                    progressLabel.setText(String.format("进度: %d / %d", results.size(), selectedFiles.size()));
                }
            }

            @Override
            protected void done() {
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                progressLabel.setText(cancelRequested ? "已中断" : "计算完成！");
            }
        }.execute();
    }

    private void updateRow(BatchDigestService.DigestResult r) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (tableModel.getValueAt(i, 1).equals(r.getFileName())) {
                tableModel.setValueAt(r.getMatchStatus(), i, 0);
                tableModel.setValueAt(formatSize(r.getFileSize()), i, 2);
                tableModel.setValueAt(r.getHashes().getOrDefault("SHA-256", "-"), i, 3);
                tableModel.setValueAt(r.getHashes().getOrDefault("MD5", "-"), i, 4);
                tableModel.setValueAt(r.getHashes().getOrDefault("SM3", "-"), i, 5);
                tableModel.setValueAt(r.getDurationMillis() + " ms", i, 6);
                break;
            }
        }
    }

    private void runChecksumMatch() {
        if (results.isEmpty()) {
            UIUtils.info(getView(), "请先执行批量摘要计算！");
            return;
        }
        String content = checksumArea.getText();
        Map<String, String> map = service.parseChecksumFile(content);
        if (map.isEmpty()) {
            UIUtils.info(getView(), "未能识别到有效清单格式（格式样例: <hash>  <filename>）！");
            return;
        }
        String targetAlg = (String) checksumAlgCombo.getSelectedItem();
        service.matchChecksums(results, map, targetAlg);
        for (BatchDigestService.DigestResult r : results) {
            updateRow(r);
        }
        UIUtils.info(getView(), "清单比对完成！已更新表格「比对状态」列。");
    }

    private void exportChecksumList() {
        if (results.isEmpty()) {
            UIUtils.info(getView(), "暂无计算结果可供导出！");
            return;
        }
        String targetAlg = (String) checksumAlgCombo.getSelectedItem();
        String exported = service.exportChecksums(results, targetAlg);
        checksumArea.setText(exported);
        UIUtils.info(getView(), "已生成 " + targetAlg + " 校验清单！");
    }

    private void loadChecksumFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            try {
                byte[] bytes = Files.readAllBytes(chooser.getSelectedFile().toPath());
                checksumArea.setText(new String(bytes, StandardCharsets.UTF_8));
                runChecksumMatch();
            } catch (Exception ex) {
                UIUtils.error(getView(), "加载失败: " + ex.getMessage());
            }
        }
    }

    private void runSignFile(String alg) {
        int row = resultTable.getSelectedRow();
        if (row < 0 || row >= selectedFiles.size()) {
            UIUtils.info(getView(), "请先在表格中选中需要签名的文件！");
            return;
        }
        File targetFile = selectedFiles.get(row);
        String keyPem = signKeyArea.getText().trim();
        if (keyPem.isEmpty()) {
            UIUtils.info(getView(), "请在密钥文本框中输入私钥 PEM！");
            return;
        }
        try {
            PrivateKey pk = CertUtils.parsePrivateKeyFromPem(keyPem);
            byte[] sig = service.signFile(targetFile, pk, alg);
            String b64 = Base64.getEncoder().encodeToString(sig);
            signatureField.setText(b64);
            UIUtils.info(getView(), "签名成功！已生成 Base64 签名串。");
        } catch (Exception ex) {
            UIUtils.error(getView(), "签名失败: " + ex.getMessage());
        }
    }

    private void runVerifyFile(String alg) {
        int row = resultTable.getSelectedRow();
        if (row < 0 || row >= selectedFiles.size()) {
            UIUtils.info(getView(), "请先在表格中选中需要验签的文件！");
            return;
        }
        File targetFile = selectedFiles.get(row);
        String keyPem = signKeyArea.getText().trim();
        String sigStr = signatureField.getText().trim();
        if (keyPem.isEmpty() || sigStr.isEmpty()) {
            UIUtils.info(getView(), "请输入公钥（或证书）PEM 和签名串！");
            return;
        }
        try {
            PublicKey pk;
            if (keyPem.contains("CERTIFICATE")) {
                pk = CertUtils.parseCertFromPem(keyPem).getPublicKey();
            } else {
                pk = CertUtils.parseCertFromPem(keyPem).getPublicKey(); // 尝试当作证书解析
            }
            byte[] sigBytes = Base64.getDecoder().decode(sigStr);
            boolean pass = service.verifyFileSignature(targetFile, pk, sigBytes, alg);
            if (pass) {
                UIUtils.info(getView(), "验签结果：PASS（签名校验成功，文件未被篡改）！");
            } else {
                UIUtils.error(getView(), "验签结果：FAIL（签名不匹配或文件已被修改）！");
            }
        } catch (Exception ex) {
            UIUtils.error(getView(), "验签异常: " + ex.getMessage());
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
