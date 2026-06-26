package com.aqishi.toolbox.crypto;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * SM4 国密对称加密面板。
 * <p>功能：密钥生成、ECB 模式加解密、CBC 模式加解密。</p>
 */
public class SM4Panel extends ToolPanel {

    public SM4Panel() {
        super("加密", "SM4 对称加密");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 密钥区 =====
        JPanel keyPanel = new JPanel(new BorderLayout(4, 0));
        keyPanel.setBorder(BorderFactory.createTitledBorder("SM4 密钥"));
        JTextArea keyArea = new JTextArea(2, 40);
        keyArea.setFont(UIUtils.monoFont().deriveFont(11f));
        keyArea.setLineWrap(true);
        JScrollPane keyScroll = new JScrollPane(keyArea);
        keyScroll.setPreferredSize(new Dimension(400, 36));
        keyPanel.add(keyScroll, BorderLayout.CENTER);

        JPanel keyBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton genKeyBtn = UIUtils.button("生成随机密钥", 110);
        JButton copyKeyBtn = UIUtils.button("复制密钥", 90);
        keyBtnRow.add(genKeyBtn);
        keyBtnRow.add(copyKeyBtn);
        keyPanel.add(keyBtnRow, BorderLayout.SOUTH);

        root.add(keyPanel, BorderLayout.NORTH);

        // ===== 输入区 =====
        JTextArea input = new JTextArea(4, 40);
        input.setFont(UIUtils.monoFont());
        input.setLineWrap(true);
        root.add(UIUtils.scrollText(input, "输入文本（加密输明文，解密输密文）"), BorderLayout.CENTER);

        // ===== 底部：按钮 + 输出 =====
        JPanel bottom = new JPanel(new BorderLayout(4, 4));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton ecbEncBtn = UIUtils.button("ECB 加密", 100);
        JButton ecbDecBtn = UIUtils.button("ECB 解密", 100);
        JButton cbcEncBtn = UIUtils.button("CBC 加密", 100);
        JButton cbcDecBtn = UIUtils.button("CBC 解密", 100);
        JButton clearBtn = UIUtils.button("清空", 80);
        btnPanel.add(ecbEncBtn); btnPanel.add(ecbDecBtn);
        btnPanel.add(cbcEncBtn); btnPanel.add(cbcDecBtn);
        btnPanel.add(clearBtn);
        bottom.add(btnPanel, BorderLayout.NORTH);

        JTextArea output = new JTextArea(6, 40);
        output.setFont(UIUtils.monoFont());
        output.setLineWrap(true);
        output.setEditable(false);
        bottom.add(UIUtils.scrollText(output, "输出结果"), BorderLayout.CENTER);

        root.add(bottom, BorderLayout.SOUTH);

        // ===== 事件绑定 =====
        genKeyBtn.addActionListener(e -> {
            try {
                String key = SM4Utils.generateKey();
                keyArea.setText(key);
                output.setText("[信息] SM4 密钥已生成（Base64，16 字节 / 128 位）。\n\n" + key);
            } catch (Exception ex) {
                UIUtils.error(root, "密钥生成失败：" + ex.getMessage());
            }
        });

        copyKeyBtn.addActionListener(e -> {
            String key = keyArea.getText().trim();
            if (key.isEmpty()) {
                UIUtils.error(root, "请先生成或输入密钥");
                return;
            }
            UIUtils.copyToClipboard(key);
            output.setText("[信息] 密钥已复制到剪贴板。");
        });

        ecbEncBtn.addActionListener(e -> {
            String key = keyArea.getText().trim();
            String text = input.getText();
            if (key.isEmpty()) { UIUtils.error(root, "请先生成或输入 SM4 密钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入要加密的文本"); return; }
            try {
                String cipher = SM4Utils.encryptECB(text, key);
                output.setText("[SM4-ECB 加密结果]\n\n" + cipher);
            } catch (Exception ex) {
                UIUtils.error(root, "ECB 加密失败：" + ex.getMessage());
            }
        });

        ecbDecBtn.addActionListener(e -> {
            String key = keyArea.getText().trim();
            String text = input.getText();
            if (key.isEmpty()) { UIUtils.error(root, "请先生成或输入 SM4 密钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入要解密的密文"); return; }
            try {
                String plain = SM4Utils.decryptECB(text, key);
                output.setText("[SM4-ECB 解密结果]\n\n" + plain);
            } catch (Exception ex) {
                UIUtils.error(root, "ECB 解密失败：" + ex.getMessage());
            }
        });

        cbcEncBtn.addActionListener(e -> {
            String key = keyArea.getText().trim();
            String text = input.getText();
            if (key.isEmpty()) { UIUtils.error(root, "请先生成或输入 SM4 密钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入要加密的文本"); return; }
            try {
                String cipher = SM4Utils.encryptCBC(text, key);
                output.setText("[SM4-CBC 加密结果]\n\n（密文前 16 字节为随机 IV）\n" + cipher);
            } catch (Exception ex) {
                UIUtils.error(root, "CBC 加密失败：" + ex.getMessage());
            }
        });

        cbcDecBtn.addActionListener(e -> {
            String key = keyArea.getText().trim();
            String text = input.getText();
            if (key.isEmpty()) { UIUtils.error(root, "请先生成或输入 SM4 密钥"); return; }
            if (text.isEmpty()) { UIUtils.error(root, "请输入要解密的密文"); return; }
            try {
                String plain = SM4Utils.decryptCBC(text, key);
                output.setText("[SM4-CBC 解密结果]\n\n" + plain);
            } catch (Exception ex) {
                UIUtils.error(root, "CBC 解密失败：" + ex.getMessage());
            }
        });

        clearBtn.addActionListener(e -> {
            input.setText("");
            output.setText("");
        });

        return root;
    }
}
