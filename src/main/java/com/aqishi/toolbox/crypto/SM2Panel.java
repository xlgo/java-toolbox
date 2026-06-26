package com.aqishi.toolbox.crypto;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * SM2 国密非对称加密面板。
 * <p>功能：密钥对生成、公钥加密/私钥解密、私钥签名/公钥验签。</p>
 */
public class SM2Panel extends ToolPanel {

    public SM2Panel() {
        super("加密", "SM2 非对称加密");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 密钥区 =====
        JPanel keyPanel = new JPanel(new GridBagLayout());
        keyPanel.setBorder(BorderFactory.createTitledBorder("SM2 密钥对"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 4, 3, 4);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        keyPanel.add(new JLabel("公钥："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        JTextArea pubKeyArea = new JTextArea(3, 40);
        pubKeyArea.setFont(UIUtils.monoFont().deriveFont(11f));
        pubKeyArea.setLineWrap(true);
        pubKeyArea.setEditable(false);
        JScrollPane pubScroll = new JScrollPane(pubKeyArea);
        pubScroll.setPreferredSize(new Dimension(400, 50));
        keyPanel.add(pubScroll, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        keyPanel.add(new JLabel("私钥："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        JTextArea priKeyArea = new JTextArea(3, 40);
        priKeyArea.setFont(UIUtils.monoFont().deriveFont(11f));
        priKeyArea.setLineWrap(true);
        priKeyArea.setEditable(false);
        JScrollPane priScroll = new JScrollPane(priKeyArea);
        priScroll.setPreferredSize(new Dimension(400, 50));
        keyPanel.add(priScroll, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        JButton genKeyBtn = UIUtils.button("生成密钥对", 120);
        JButton copyPubBtn = UIUtils.button("复制公钥", 100);
        JButton copyPriBtn = UIUtils.button("复制私钥", 100);
        JPanel keyBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
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

        // 加密
        gbc2.gridx = 0; gbc2.gridy = 0; gbc2.weightx = 0;
        opPanel.add(new JLabel("加密输入："), gbc2);
        gbc2.gridx = 1; gbc2.weightx = 1;
        JTextArea encryptInput = new JTextArea(2, 40);
        encryptInput.setFont(UIUtils.monoFont());
        encryptInput.setLineWrap(true);
        JScrollPane encScroll = new JScrollPane(encryptInput);
        encScroll.setPreferredSize(new Dimension(400, 40));
        opPanel.add(encScroll, gbc2);

        gbc2.gridx = 0; gbc2.gridy = 1;
        opPanel.add(new JLabel("解密输入："), gbc2);
        gbc2.gridx = 1;
        JTextArea decryptInput = new JTextArea(2, 40);
        decryptInput.setFont(UIUtils.monoFont());
        decryptInput.setLineWrap(true);
        JScrollPane decScroll = new JScrollPane(decryptInput);
        decScroll.setPreferredSize(new Dimension(400, 40));
        opPanel.add(decScroll, gbc2);

        gbc2.gridx = 0; gbc2.gridy = 2; gbc2.gridwidth = 2;
        JButton encryptBtn = UIUtils.button("公钥加密", 100);
        JButton decryptBtn = UIUtils.button("私钥解密", 100);
        JButton signBtn = UIUtils.button("私钥签名", 100);
        JButton verifyBtn = UIUtils.button("公钥验签", 100);
        JButton clearBtn = UIUtils.button("清空", 80);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.add(encryptBtn); btnRow.add(decryptBtn);
        btnRow.add(signBtn); btnRow.add(verifyBtn); btnRow.add(clearBtn);
        opPanel.add(btnRow, gbc2);

        root.add(opPanel, BorderLayout.CENTER);

        // ===== 输出区 =====
        JTextArea output = new JTextArea(6, 40);
        output.setFont(UIUtils.monoFont());
        output.setLineWrap(true);
        output.setEditable(false);
        root.add(UIUtils.scrollText(output, "输出结果"), BorderLayout.SOUTH);

        // ===== 事件绑定 =====
        genKeyBtn.addActionListener(e -> {
            try {
                SM2Utils.SM2KeyPair kp = SM2Utils.generateKeyPair();
                pubKeyArea.setText(kp.publicKey);
                priKeyArea.setText(kp.privateKey);
                output.setText("[信息] SM2 密钥对已生成。\n\n公钥长度: " + kp.publicKey.length() + " 字符"
                        + "\n私钥长度: " + kp.privateKey.length() + " 字符");
            } catch (Exception ex) {
                UIUtils.error(root, "密钥生成失败：" + ex.getMessage());
            }
        });

        copyPubBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(pubKeyArea.getText());
            output.setText("[信息] 公钥已复制到剪贴板。");
        });

        copyPriBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(priKeyArea.getText());
            output.setText("[信息] 私钥已复制到剪贴板。");
        });

        encryptBtn.addActionListener(e -> {
            String pub = pubKeyArea.getText().trim();
            String text = encryptInput.getText();
            if (pub.isEmpty()) { UIUtils.error(root, "请先生成或输入公钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入要加密的文本"); return; }
            try {
                String cipher = SM2Utils.encrypt(text, pub);
                output.setText("[SM2 加密结果]\n\n" + cipher);
                decryptInput.setText(cipher);
            } catch (Exception ex) {
                UIUtils.error(root, "加密失败：" + ex.getMessage());
            }
        });

        decryptBtn.addActionListener(e -> {
            String pri = priKeyArea.getText().trim();
            String text = decryptInput.getText();
            if (pri.isEmpty()) { UIUtils.error(root, "请先生成或输入私钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入要解密的密文"); return; }
            try {
                String plain = SM2Utils.decrypt(text, pri);
                output.setText("[SM2 解密结果]\n\n" + plain);
            } catch (Exception ex) {
                UIUtils.error(root, "解密失败：" + ex.getMessage());
            }
        });

        signBtn.addActionListener(e -> {
            String pri = priKeyArea.getText().trim();
            String text = encryptInput.getText();
            if (pri.isEmpty()) { UIUtils.error(root, "请先生成或输入私钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请在上方输入要签名的文本"); return; }
            try {
                String sig = SM2Utils.sign(text, pri);
                output.setText("[SM2 签名结果]\n\n" + sig);
            } catch (Exception ex) {
                UIUtils.error(root, "签名失败：" + ex.getMessage());
            }
        });

        verifyBtn.addActionListener(e -> {
            String pub = pubKeyArea.getText().trim();
            String text = encryptInput.getText();
            String sig = decryptInput.getText();
            if (pub.isEmpty()) { UIUtils.error(root, "请先生成或输入公钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入原始消息文本"); return; }
            if (sig.isEmpty()) { UIUtils.error(root, "请在下方输入签名值"); return; }
            try {
                boolean ok = SM2Utils.verify(text, sig, pub);
                if (ok) {
                    output.setText("[SM2 验签结果]\n\n✓ 验签通过 — 签名有效，消息未被篡改。");
                } else {
                    output.setText("[SM2 验签结果]\n\n✗ 验签失败 — 签名无效或消息已被篡改！");
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
