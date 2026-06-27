package com.aqishi.toolbox.crypto;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * 非对称加密与签名面板（支持 RSA / SM2）。
 */
public class AsymmetricPanel extends ToolPanel {

    private JComboBox<String> algoCombo;
    private JComboBox<Integer> keySizeCombo;
    private JComboBox<String> sigAlgoCombo;
    private JLabel keySizeLabel;
    private JLabel sigAlgoLabel;
    
    private JTextArea pubKeyArea;
    private JTextArea priKeyArea;
    private JTextArea encryptInput;
    private JTextArea decryptInput;
    private JTextArea output;

    public AsymmetricPanel() {
        super("加密", "非对称加密",
                "RSA", "SM2", "国密", "公钥", "私钥", "签名", "验签",
                "非对称", "密钥对", "数字签名");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 密钥区 =====
        JPanel keyPanel = new JPanel(new GridBagLayout());
        keyPanel.setBorder(BorderFactory.createTitledBorder("密钥对配置与生成"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 4, 3, 4);

        // 算法选择
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        keyPanel.add(new JLabel("算法："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        algoCombo = new JComboBox<>(new String[]{"RSA", "SM2"});
        keyPanel.add(algoCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        keyPanel.add(new JLabel("公钥："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        pubKeyArea = new JTextArea(3, 40);
        pubKeyArea.setFont(UIUtils.monoFont().deriveFont(11f));
        pubKeyArea.setLineWrap(true);
        JScrollPane pubScroll = new JScrollPane(pubKeyArea);
        pubScroll.setPreferredSize(new Dimension(400, 50));
        keyPanel.add(pubScroll, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        keyPanel.add(new JLabel("私钥："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        priKeyArea = new JTextArea(3, 40);
        priKeyArea.setFont(UIUtils.monoFont().deriveFont(11f));
        priKeyArea.setLineWrap(true);
        JScrollPane priScroll = new JScrollPane(priKeyArea);
        priScroll.setPreferredSize(new Dimension(400, 50));
        keyPanel.add(priScroll, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        keySizeCombo = new JComboBox<>(new Integer[]{1024, 2048, 4096});
        keySizeCombo.setSelectedItem(2048);
        keySizeCombo.setPreferredSize(new Dimension(80, 30));
        keySizeLabel = new JLabel("密钥长度：");
        
        JButton genKeyBtn = UIUtils.button("生成密钥对", 120);
        JButton copyPubBtn = UIUtils.button("复制公钥", 100);
        JButton copyPriBtn = UIUtils.button("复制私钥", 100);
        
        JPanel keyBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        keyBtnRow.add(keySizeLabel);
        keyBtnRow.add(keySizeCombo);
        keyBtnRow.add(genKeyBtn);
        keyBtnRow.add(copyPubBtn);
        keyBtnRow.add(copyPriBtn);
        keyPanel.add(keyBtnRow, gbc);

        root.add(keyPanel, BorderLayout.NORTH);

        // ===== 操作区 =====
        JPanel opPanel = new JPanel(new GridBagLayout());
        opPanel.setBorder(BorderFactory.createTitledBorder("加解密与签名验签"));
        GridBagConstraints gbc2 = new GridBagConstraints();
        gbc2.fill = GridBagConstraints.HORIZONTAL;
        gbc2.insets = new Insets(3, 4, 3, 4);

        // 输入/明文
        gbc2.gridx = 0; gbc2.gridy = 0; gbc2.weightx = 0;
        opPanel.add(new JLabel("输入文本/明文："), gbc2);
        gbc2.gridx = 1; gbc2.weightx = 1;
        encryptInput = new JTextArea(2, 40);
        encryptInput.setFont(UIUtils.monoFont());
        encryptInput.setLineWrap(true);
        JScrollPane encScroll = new JScrollPane(encryptInput);
        encScroll.setPreferredSize(new Dimension(400, 40));
        opPanel.add(encScroll, gbc2);

        // 输入/密文/签名
        gbc2.gridx = 0; gbc2.gridy = 1; gbc2.weightx = 0;
        opPanel.add(new JLabel("输入密文/签名："), gbc2);
        gbc2.gridx = 1; gbc2.weightx = 1;
        decryptInput = new JTextArea(2, 40);
        decryptInput.setFont(UIUtils.monoFont());
        decryptInput.setLineWrap(true);
        JScrollPane decScroll = new JScrollPane(decryptInput);
        decScroll.setPreferredSize(new Dimension(400, 40));
        opPanel.add(decScroll, gbc2);

        gbc2.gridx = 0; gbc2.gridy = 2; gbc2.gridwidth = 2;
        sigAlgoCombo = new JComboBox<>(new String[]{"SHA256withRSA", "SHA1withRSA", "MD5withRSA"});
        sigAlgoCombo.setPreferredSize(new Dimension(140, 30));
        sigAlgoLabel = new JLabel("签名算法：");
        
        JButton encryptBtn = UIUtils.button("公钥加密", 100);
        JButton decryptBtn = UIUtils.button("私钥解密", 100);
        JButton signBtn = UIUtils.button("私钥签名", 100);
        JButton verifyBtn = UIUtils.button("公钥验签", 100);
        JButton clearBtn = UIUtils.button("清空", 80);
        
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.add(sigAlgoLabel);
        btnRow.add(sigAlgoCombo);
        btnRow.add(encryptBtn); btnRow.add(decryptBtn);
        btnRow.add(signBtn); btnRow.add(verifyBtn); btnRow.add(clearBtn);
        opPanel.add(btnRow, gbc2);

        root.add(opPanel, BorderLayout.CENTER);

        // ===== 输出区 =====
        output = new JTextArea(6, 40);
        output.setFont(UIUtils.monoFont());
        output.setLineWrap(true);
        output.setEditable(false);
        root.add(UIUtils.scrollText(output, "输出结果"), BorderLayout.SOUTH);

        // ===== 逻辑与事件绑定 =====
        algoCombo.addActionListener(e -> {
            boolean isRsa = "RSA".equals(algoCombo.getSelectedItem());
            keySizeCombo.setVisible(isRsa);
            keySizeLabel.setVisible(isRsa);
            sigAlgoCombo.setVisible(isRsa);
            sigAlgoLabel.setVisible(isRsa);
            // 清空旧数据
            pubKeyArea.setText("");
            priKeyArea.setText("");
            output.setText("");
        });

        genKeyBtn.addActionListener(e -> {
            String algo = (String) algoCombo.getSelectedItem();
            try {
                if ("RSA".equals(algo)) {
                    int size = (int) keySizeCombo.getSelectedItem();
                    RSAUtils.RSAKeyPair kp = RSAUtils.generateKeyPair(size);
                    pubKeyArea.setText(kp.publicKey);
                    priKeyArea.setText(kp.privateKey);
                    output.setText("[信息] RSA 密钥对已生成（长度：" + size + " 位）。\n\n公钥格式：Base64 (X.509)\n私钥格式：Base64 (PKCS#8)");
                } else {
                    SM2Utils.SM2KeyPair kp = SM2Utils.generateKeyPair();
                    pubKeyArea.setText(kp.publicKey);
                    priKeyArea.setText(kp.privateKey);
                    output.setText("[信息] SM2 密钥对已生成（256 位）。\n\n公钥格式：Hex (04非压缩)\n私钥格式：Hex (D值)");
                }
            } catch (Exception ex) {
                UIUtils.error(root, "密钥生成失败：" + ex.getMessage());
            }
        });

        copyPubBtn.addActionListener(e -> {
            String pub = pubKeyArea.getText().trim();
            if (pub.isEmpty()) {
                UIUtils.error(root, "公钥为空");
                return;
            }
            UIUtils.copyToClipboard(pub);
            output.setText("[信息] 公钥已复制到剪贴板。");
        });

        copyPriBtn.addActionListener(e -> {
            String pri = priKeyArea.getText().trim();
            if (pri.isEmpty()) {
                UIUtils.error(root, "私钥为空");
                return;
            }
            UIUtils.copyToClipboard(pri);
            output.setText("[信息] 私钥已复制到剪贴板。");
        });

        encryptBtn.addActionListener(e -> {
            String algo = (String) algoCombo.getSelectedItem();
            String pub = pubKeyArea.getText().trim();
            String text = encryptInput.getText();
            if (pub.isEmpty()) { UIUtils.error(root, "请先生成或输入公钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入要加密的文本"); return; }
            try {
                if ("RSA".equals(algo)) {
                    String cipher = RSAUtils.encrypt(text, pub);
                    output.setText("[RSA 加密结果 (Base64)]\n\n" + cipher);
                    decryptInput.setText(cipher);
                } else {
                    String cipher = SM2Utils.encrypt(text, pub);
                    output.setText("[SM2 加密结果 (Base64)]\n\n" + cipher);
                    decryptInput.setText(cipher);
                }
            } catch (Exception ex) {
                UIUtils.error(root, "加密失败：" + ex.getMessage());
            }
        });

        decryptBtn.addActionListener(e -> {
            String algo = (String) algoCombo.getSelectedItem();
            String pri = priKeyArea.getText().trim();
            String text = decryptInput.getText().trim();
            if (pri.isEmpty()) { UIUtils.error(root, "请先生成或输入私钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入要解密的密文"); return; }
            try {
                if ("RSA".equals(algo)) {
                    String plain = RSAUtils.decrypt(text, pri);
                    output.setText("[RSA 解密结果]\n\n" + plain);
                } else {
                    String plain = SM2Utils.decrypt(text, pri);
                    output.setText("[SM2 解密结果]\n\n" + plain);
                }
            } catch (Exception ex) {
                UIUtils.error(root, "解密失败：" + ex.getMessage());
            }
        });

        signBtn.addActionListener(e -> {
            String algo = (String) algoCombo.getSelectedItem();
            String pri = priKeyArea.getText().trim();
            String text = encryptInput.getText();
            if (pri.isEmpty()) { UIUtils.error(root, "请先生成或输入私钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请在上方输入要签名的明文"); return; }
            try {
                if ("RSA".equals(algo)) {
                    String sigAlgo = (String) sigAlgoCombo.getSelectedItem();
                    String sig = RSAUtils.sign(text, pri, sigAlgo);
                    output.setText("[RSA 签名结果 (Base64)]\n算法：" + sigAlgo + "\n\n" + sig);
                    decryptInput.setText(sig);
                } else {
                    String sig = SM2Utils.sign(text, pri);
                    output.setText("[SM2 签名结果 (Base64)]\n算法：SM3withSM2\n\n" + sig);
                    decryptInput.setText(sig);
                }
            } catch (Exception ex) {
                UIUtils.error(root, "签名失败：" + ex.getMessage());
            }
        });

        verifyBtn.addActionListener(e -> {
            String algo = (String) algoCombo.getSelectedItem();
            String pub = pubKeyArea.getText().trim();
            String text = encryptInput.getText();
            String sig = decryptInput.getText().trim();
            if (pub.isEmpty()) { UIUtils.error(root, "请先生成或输入公钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入原始明文"); return; }
            if (sig.isEmpty()) { UIUtils.error(root, "请在下方输入签名值 (Base64)"); return; }
            try {
                if ("RSA".equals(algo)) {
                    String sigAlgo = (String) sigAlgoCombo.getSelectedItem();
                    boolean ok = RSAUtils.verify(text, sig, pub, sigAlgo);
                    if (ok) {
                        output.setText("[RSA 验签结果]\n算法：" + sigAlgo + "\n\n✓ 验签通过 — 签名有效，消息未被篡改。");
                    } else {
                        output.setText("[RSA 验签结果]\n算法：" + sigAlgo + "\n\n✗ 验签失败 — 签名无效或消息已被篡改！");
                    }
                } else {
                    boolean ok = SM2Utils.verify(text, sig, pub);
                    if (ok) {
                        output.setText("[SM2 验签结果]\n算法：SM3withSM2\n\n✓ 验签通过 — 签名有效，消息未被篡改。");
                    } else {
                        output.setText("[SM2 验签结果]\n算法：SM3withSM2\n\n✗ 验签失败 — 签名无效或消息已被篡改！");
                    }
                }
            } catch (Exception ex) {
                UIUtils.error(root, "验签失败：" + ex.getMessage());
            }
        });

        clearBtn.addActionListener(e -> {
            encryptInput.setText("");
            decryptInput.setText("");
            output.setText("");
        });

        return root;
    }
}
