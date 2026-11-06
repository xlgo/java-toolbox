package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.cloud.domain.K8sManifestDiff;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Kubernetes 多文档 Manifest 结构化差异对比面板。
 *
 * <p>按 kind/namespace/name 对齐多资源 YAML 文档，自动过滤运行时状态与元数据噪声，
 * 精确呈现资源级增删与字段级结构化差异。</p>
 */
public final class K8sManifestDiffPanel extends ToolPanel implements ManagedResourceOwner {

    private static final String SAMPLE_BEFORE =
            "apiVersion: apps/v1\n"
                    + "kind: Deployment\n"
                    + "metadata:\n"
                    + "  name: order-service\n"
                    + "  namespace: prod\n"
                    + "  resourceVersion: '123456'\n"
                    + "spec:\n"
                    + "  replicas: 2\n"
                    + "  template:\n"
                    + "    spec:\n"
                    + "      containers:\n"
                    + "      - name: app\n"
                    + "        image: order-svc:1.0.0\n"
                    + "        env:\n"
                    + "        - name: ENV\n"
                    + "          value: prod\n"
                    + "---\n"
                    + "apiVersion: v1\n"
                    + "kind: ConfigMap\n"
                    + "metadata:\n"
                    + "  name: legacy-config\n"
                    + "  namespace: prod\n"
                    + "data:\n"
                    + "  oldKey: oldValue\n";

    private static final String SAMPLE_AFTER =
            "apiVersion: apps/v1\n"
                    + "kind: Deployment\n"
                    + "metadata:\n"
                    + "  name: order-service\n"
                    + "  namespace: prod\n"
                    + "spec:\n"
                    + "  replicas: 4\n"
                    + "  template:\n"
                    + "    spec:\n"
                    + "      containers:\n"
                    + "      - name: app\n"
                    + "        image: order-svc:1.1.0\n"
                    + "        env:\n"
                    + "        - name: ENV\n"
                    + "          value: prod\n"
                    + "        - name: LOG_LEVEL\n"
                    + "          value: INFO\n"
                    + "---\n"
                    + "apiVersion: v1\n"
                    + "kind: Service\n"
                    + "metadata:\n"
                    + "  name: order-service\n"
                    + "  namespace: prod\n"
                    + "spec:\n"
                    + "  ports:\n"
                    + "  - port: 8080\n"
                    + "    targetPort: 8080\n";

    private final JTextArea beforeInput = Fields.area(10, 35);
    private final JTextArea afterInput = Fields.area(10, 35);
    private final JTextArea outputArea = Fields.output(12, 45);

    private final JCheckBox filterNoiseCheck = Fields.check(I18n.get("k8s.diff.filterNoise"), true);
    private final JTextField customIgnoreField = Fields.text("");
    private final JTextField filterKeywordField = Fields.text("");

    private final JButton runButton = Buttons.primary(I18n.get("devtools.run"));
    private final JButton cancelButton = Buttons.secondary(I18n.get("devtools.cancel"));
    private final JLabel statusLabel = Fields.caption(" ");

    private SwingWorker<String, Void> worker;

    public K8sManifestDiffPanel() {
        super(ToolCatalog.K8S_MANIFEST_DIFF);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        FormGrid options = new FormGrid();
        options.row("", filterNoiseCheck);
        options.row(I18n.get("k8s.diff.customIgnore"), customIgnoreField);
        options.row(I18n.get("k8s.diff.filterKeyword"), filterKeywordField);
        options.fullRow(Fields.note(I18n.get("k8s.diff.hint")));

        JPanel heading = Layouts.box(0, Tokens.SPACE_SM);
        heading.add(Fields.scrollVertical(options), BorderLayout.CENTER);
        heading.setPreferredSize(new Dimension(0, Math.min(180, options.getPreferredSize().height + 14)));

        // Before 输入卡片
        Card beforeCard = Card.flush(I18n.get("k8s.diff.before"));
        beforeCard.setContent(Fields.scroll(beforeInput));
        JButton loadBefore = Buttons.snug(I18n.get("devtools.open"));
        loadBefore.addActionListener(e -> loadFile(beforeInput));
        JButton sampleBtn = Buttons.snug(I18n.get("devtools.sample"));
        sampleBtn.addActionListener(e -> {
            beforeInput.setText(SAMPLE_BEFORE);
            afterInput.setText(SAMPLE_AFTER);
        });
        beforeCard.addHeaderAction(loadBefore);
        beforeCard.addHeaderAction(sampleBtn);

        // After 输入卡片
        Card afterCard = Card.flush(I18n.get("k8s.diff.after"));
        afterCard.setContent(Fields.scroll(afterInput));
        JButton loadAfter = Buttons.snug(I18n.get("devtools.open"));
        loadAfter.addActionListener(e -> loadFile(afterInput));
        afterCard.addHeaderAction(loadAfter);

        Component inputSplit = Layouts.splitHorizontal(beforeCard, afterCard, 0.5, 0.5);
        inputSplit.setMinimumSize(new Dimension(200, 150));

        // Output 输出卡片
        Card resultCard = Card.flush(I18n.get("devtools.output"));
        resultCard.setContent(Fields.scroll(outputArea));
        JButton copyBtn = Buttons.snug(I18n.get("devtools.copy"));
        copyBtn.addActionListener(e -> UIUtils.copyToClipboard(outputArea.getText()));
        JButton saveBtn = Buttons.snug(I18n.get("devtools.save"));
        saveBtn.addActionListener(e -> saveOutputFile());
        resultCard.addHeaderAction(copyBtn);
        resultCard.addHeaderAction(saveBtn);
        resultCard.setMinimumSize(new Dimension(200, 150));

        Component mainSplit = Layouts.splitVertical(inputSplit, resultCard, 0.45);

        // 底部动作栏
        JPanel actions = Layouts.box();
        actions.add(statusLabel);
        actions.add(Layouts.wrapRow(cancelButton, runButton), BorderLayout.EAST);

        root.add(heading, BorderLayout.NORTH);
        root.add(mainSplit, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);

        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> {
            if (worker != null) {
                worker.cancel(true);
            }
        });

        runButton.addActionListener(e -> executeDiff());

        beforeInput.setText(SAMPLE_BEFORE);
        afterInput.setText(SAMPLE_AFTER);

        return root;
    }

    private void executeDiff() {
        if (worker != null) return;

        String bText = beforeInput.getText();
        String aText = afterInput.getText();
        boolean noise = filterNoiseCheck.isSelected();
        String customStr = customIgnoreField.getText();
        String kw = filterKeywordField.getText();

        Set<String> customIgnored = new HashSet<>();
        if (customStr != null && !customStr.isBlank()) {
            customIgnored.addAll(Arrays.asList(customStr.split("[,;\\s]+")));
        }

        runButton.setEnabled(false);
        cancelButton.setEnabled(true);
        statusLabel.setText(I18n.get("devtools.working"));

        worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                return new K8sManifestDiff().compareToReport(bText, aText, noise, customIgnored, kw);
            }

            @Override
            protected void done() {
                try {
                    if (!isCancelled()) {
                        outputArea.setText(get());
                        outputArea.setCaretPosition(0);
                        statusLabel.setText(I18n.get("devtools.done"));
                    } else {
                        statusLabel.setText(I18n.get("devtools.cancelled"));
                    }
                } catch (Exception error) {
                    statusLabel.setText(Errors.describeRoot(error));
                    statusLabel.setToolTipText(statusLabel.getText());
                } finally {
                    worker = null;
                    runButton.setEnabled(true);
                    cancelButton.setEnabled(false);
                }
            }
        };
        worker.execute();
    }

    private void loadFile(JTextArea target) {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(target) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        try {
            if (Files.size(path) > 2_000_000) {
                throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
            }
            target.setText(Files.readString(path));
        } catch (Exception error) {
            UIUtils.error(target, Errors.describeRoot(error));
        }
    }

    private void saveOutputFile() {
        if (outputArea.getText().isBlank()) return;
        JFileChooser chooser = new JFileChooser();
        if (chooser.showSaveDialog(outputArea) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        try {
            new com.aqishi.toolbox.vault.AtomicFiles()
                    .write(path, outputArea.getText().getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            UIUtils.error(outputArea, Errors.describeRoot(error));
        }
    }

    @Override
    public void closeResources() {
        if (worker != null) {
            worker.cancel(true);
        }
    }
}
