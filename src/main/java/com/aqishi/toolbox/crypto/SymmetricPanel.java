package com.aqishi.toolbox.crypto;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 对称加密面板（支持 AES / DES / 3DES / SM4）。
 */
public class SymmetricPanel extends ToolPanel {

    private JComboBox<String> algoCombo;
    private JComboBox<String> modeCombo;
    private JComboBox<Integer> keySizeCombo;
    private JComboBox<String> encodingCombo; // Base64 或 Hex
    private JTextArea keyArea;
    private JTextField ivField;
    private JCheckBox customIvCheckbox;
    private JTextArea inputArea;
    private JTextArea outputArea;

    public SymmetricPanel() {
        super("加密", "对称加密");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：参数配置与密钥区 =====
        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("参数与密钥配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 6, 4, 6);

        // 1. 算法 & 模式 & 长度配置行
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        configPanel.add(new JLabel("算法："), gbc);
        gbc.gridx = 1; gbc.weightx = 0.2;
        algoCombo = new JComboBox<>(new String[]{"AES", "DES", "3DES", "SM4"});
        configPanel.add(algoCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        configPanel.add(new JLabel("模式："), gbc);
        gbc.gridx = 3; gbc.weightx = 0.2;
        modeCombo = new JComboBox<>(new String[]{"CBC", "ECB"});
        configPanel.add(modeCombo, gbc);

        gbc.gridx = 4; gbc.weightx = 0;
        configPanel.add(new JLabel("密钥长度："), gbc);
        gbc.gridx = 5; gbc.weightx = 0.2;
        keySizeCombo = new JComboBox<>();
        configPanel.add(keySizeCombo, gbc);

        gbc.gridx = 6; gbc.weightx = 0;
        configPanel.add(new JLabel("密钥格式："), gbc);
        gbc.gridx = 7; gbc.weightx = 0.2;
        encodingCombo = new JComboBox<>(new String[]{"Base64", "Hex", "UTF-8 文本"});
        configPanel.add(encodingCombo, gbc);

        // 2. 密钥输入框
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        configPanel.add(new JLabel("密钥："), gbc);
        gbc.gridx = 1; gbc.gridwidth = 5; gbc.weightx = 1.0;
        keyArea = new JTextArea(2, 40);
        keyArea.setFont(UIUtils.monoFont().deriveFont(11f));
        keyArea.setLineWrap(true);
        configPanel.add(new JScrollPane(keyArea), gbc);

        gbc.gridx = 6; gbc.gridwidth = 2; gbc.weightx = 0;
        JPanel keyBtnCol = new JPanel(new GridLayout(2, 1, 0, 4));
        JButton genKeyBtn = UIUtils.button("生成密钥", 90);
        JButton copyKeyBtn = UIUtils.button("复制密钥", 90);
        keyBtnCol.add(genKeyBtn);
        keyBtnCol.add(copyKeyBtn);
        configPanel.add(keyBtnCol, gbc);

        // 3. IV 向量配置行
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0;
        configPanel.add(new JLabel("IV 向量："), gbc);
        gbc.gridx = 1; gbc.gridwidth = 4; gbc.weightx = 0.6;
        ivField = new JTextField();
        ivField.setFont(UIUtils.monoFont());
        ivField.setEnabled(false);
        configPanel.add(ivField, gbc);

        gbc.gridx = 5; gbc.gridwidth = 3; gbc.weightx = 0.4;
        customIvCheckbox = new JCheckBox("自定义 IV（留空为自动随机 IV）");
        customIvCheckbox.setFont(UIUtils.plainFont());
        configPanel.add(customIvCheckbox, gbc);

        root.add(configPanel, BorderLayout.NORTH);

        // ===== 中部：输入文本区 =====
        inputArea = new JTextArea(4, 40);
        inputArea.setFont(UIUtils.monoFont());
        inputArea.setLineWrap(true);
        root.add(UIUtils.scrollText(inputArea, "输入文本（加密输明文，解密输密文）"), BorderLayout.CENTER);

        // ===== 底部：按钮 + 输出区 =====
        JPanel bottomPanel = new JPanel(new BorderLayout(4, 4));

        JPanel actionBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton encryptBtn = UIUtils.button("加密", 100);
        JButton decryptBtn = UIUtils.button("解密", 100);
        JButton clearBtn = UIUtils.button("清空", 80);
        actionBtnRow.add(encryptBtn);
        actionBtnRow.add(decryptBtn);
        actionBtnRow.add(clearBtn);
        bottomPanel.add(actionBtnRow, BorderLayout.NORTH);

        outputArea = new JTextArea(6, 40);
        outputArea.setFont(UIUtils.monoFont());
        outputArea.setLineWrap(true);
        outputArea.setEditable(false);
        bottomPanel.add(UIUtils.scrollText(outputArea, "输出结果"), BorderLayout.CENTER);

        root.add(bottomPanel, BorderLayout.SOUTH);

        // ===== 逻辑与事件绑定 =====
        updateKeySizeOptions();

        algoCombo.addActionListener(e -> {
            updateKeySizeOptions();
            updateIvStatus();
        });

        modeCombo.addActionListener(e -> updateIvStatus());

        customIvCheckbox.addActionListener(e -> {
            boolean selected = customIvCheckbox.isSelected();
            ivField.setEnabled(selected && modeCombo.getSelectedItem().equals("CBC"));
            if (!ivField.isEnabled()) {
                ivField.setText("");
            }
        });

        genKeyBtn.addActionListener(e -> {
            try {
                String algo = getSelectedAlgo();
                int size = (int) keySizeCombo.getSelectedItem();
                String encoding = (String) encodingCombo.getSelectedItem();

                if ("UTF-8 文本".equals(encoding)) {
                    // 生成纯 ASCII 字符的文本密钥，避免乱码
                    String charSource = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
                    java.security.SecureRandom random = new java.security.SecureRandom();
                    int charCount = size / 8; // 128位->16字节, 192位->24字节, 256位->32字节
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < charCount; i++) {
                        sb.append(charSource.charAt(random.nextInt(charSource.length())));
                    }
                    keyArea.setText(sb.toString());
                } else {
                    String keyStr = SymmetricUtils.generateKey(algo, size);
                    byte[] raw = Base64.getDecoder().decode(keyStr);
                    if ("Hex".equals(encoding)) {
                        keyArea.setText(SymmetricUtils.bytesToHex(raw));
                    } else {
                        keyArea.setText(keyStr);
                    }
                }
                outputArea.setText("[信息] 已随机生成密钥 (" + algo + ", " + size + " 位)。");
            } catch (Exception ex) {
                UIUtils.error(root, "生成密钥失败：" + ex.getMessage());
            }
        });

        copyKeyBtn.addActionListener(e -> {
            String key = keyArea.getText().trim();
            if (key.isEmpty()) {
                UIUtils.error(root, "当前密钥为空，无法复制");
                return;
            }
            UIUtils.copyToClipboard(key);
            outputArea.setText("[信息] 密钥已成功复制到剪贴板。");
        });

        encryptBtn.addActionListener(e -> {
            try {
                String text = inputArea.getText();
                if (text.isEmpty()) {
                    UIUtils.error(root, "请输入需要加密的文本");
                    return;
                }
                byte[] keyBytes = getKeyBytes();
                if (keyBytes == null) {
                    UIUtils.error(root, "密钥不合法，请检查密钥与密钥格式");
                    return;
                }

                String algo = getSelectedAlgo();
                String mode = (String) modeCombo.getSelectedItem();
                byte[] ivBytes = getIvBytes();

                String cipher = SymmetricUtils.encrypt(algo, mode, text, keyBytes, ivBytes, false);
                outputArea.setText("[加密成功]\n算法：" + algo + "-" + mode + "\n密文 (Base64)：\n" + cipher);
            } catch (Exception ex) {
                UIUtils.error(root, "加密失败：" + ex.getMessage());
            }
        });

        decryptBtn.addActionListener(e -> {
            try {
                String text = inputArea.getText().trim();
                if (text.isEmpty()) {
                    UIUtils.error(root, "请输入需要解密的密文");
                    return;
                }
                byte[] keyBytes = getKeyBytes();
                if (keyBytes == null) {
                    UIUtils.error(root, "密钥不合法，请检查密钥与密钥格式");
                    return;
                }

                String algo = getSelectedAlgo();
                String mode = (String) modeCombo.getSelectedItem();
                byte[] ivBytes = getIvBytes();

                String plain = SymmetricUtils.decrypt(algo, mode, text, keyBytes, ivBytes, false);
                outputArea.setText("[解密成功]\n原文：\n" + plain);
            } catch (Exception ex) {
                UIUtils.error(root, "解密失败：" + ex.getMessage());
            }
        });

        clearBtn.addActionListener(e -> {
            inputArea.setText("");
            outputArea.setText("");
        });

        return root;
    }

    private String getSelectedAlgo() {
        String sel = (String) algoCombo.getSelectedItem();
        if ("3DES".equals(sel)) return "DESede";
        return sel;
    }

    private void updateKeySizeOptions() {
        keySizeCombo.removeAllItems();
        String algo = (String) algoCombo.getSelectedItem();
        if ("AES".equals(algo)) {
            keySizeCombo.addItem(128);
            keySizeCombo.addItem(192);
            keySizeCombo.addItem(256);
        } else if ("DES".equals(algo)) {
            keySizeCombo.addItem(64);
        } else if ("3DES".equals(algo)) {
            keySizeCombo.addItem(192);
        } else if ("SM4".equals(algo)) {
            keySizeCombo.addItem(128);
        }
    }

    private void updateIvStatus() {
        boolean isCBC = "CBC".equals(modeCombo.getSelectedItem());
        if (!isCBC) {
            ivField.setEnabled(false);
            customIvCheckbox.setEnabled(false);
            customIvCheckbox.setSelected(false);
            ivField.setText("");
        } else {
            customIvCheckbox.setEnabled(true);
            ivField.setEnabled(customIvCheckbox.isSelected());
        }
    }

    private byte[] getKeyBytes() {
        String keyText = keyArea.getText().trim();
        if (keyText.isEmpty()) return null;
        String format = (String) encodingCombo.getSelectedItem();
        try {
            if ("Hex".equals(format)) {
                return SymmetricUtils.hexToBytes(keyText);
            } else if ("UTF-8 文本".equals(format)) {
                return keyText.getBytes(StandardCharsets.UTF_8);
            } else {
                // Base64
                return Base64.getDecoder().decode(keyText);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] getIvBytes() {
        if (!customIvCheckbox.isSelected() || !customIvCheckbox.isEnabled()) {
            return null;
        }
        String ivText = ivField.getText().trim();
        if (ivText.isEmpty()) return null;
        // 支持 Hex 或直接普通字符串，这里优先将其作为 Base64 解码，失败则直接取字节
        try {
            return Base64.getDecoder().decode(ivText);
        } catch (Exception e) {
            return ivText.getBytes(StandardCharsets.UTF_8);
        }
    }
}
