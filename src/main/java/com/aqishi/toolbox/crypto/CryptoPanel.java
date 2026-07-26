package com.aqishi.toolbox.crypto;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 加密工具面板：MD5 / SHA-1 / SHA-256 / AES（CBC） / Base64 编解码。
 */
public class CryptoPanel extends ToolPanel {

    public CryptoPanel() {
        super("crypto", "hash.codec",
                "MD5", "SHA-1", "SHA-256", "SHA256", "SM3", "哈希", "Hash",
                "消息摘要", "散列", "Base64", "编解码", "编码", "解码",
                "国密", "Hmac", "HMAC");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 输入卡片：操作按钮跟输入放在同一张卡里，避免按钮行悬空在页面中央 =====
        JTextArea input = Fields.area(5, 40);
        Card inputCard = Card.titled("输入文本");

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, Tokens.SPACE_SM));
        actions.setOpaque(false);
        JButton md5 = Buttons.primary("MD5");
        JButton sha1 = Buttons.secondary("SHA-1");
        JButton sha256 = Buttons.secondary("SHA-256");
        JButton sm3 = Buttons.secondary("SM3");
        JButton b64enc = Buttons.secondary("Base64 编码");
        JButton b64dec = Buttons.secondary("Base64 解码");
        JButton clear = Buttons.ghost("清空");
        actions.add(md5); actions.add(sha1); actions.add(sha256); actions.add(sm3);
        actions.add(b64enc); actions.add(b64dec);
        actions.add(clear);

        JPanel inputBody = Layouts.box(0, Tokens.SPACE_MD);
        inputBody.add(Fields.scroll(input), BorderLayout.CENTER);
        inputBody.add(actions, BorderLayout.SOUTH);
        inputCard.setContent(inputBody);

        // ===== 输出卡片：占据剩余空间，长哈希与长 Base64 都有地方展开 =====
        JTextArea output = Fields.output(8, 40);
        Card outputCard = Card.flush("输出结果");
        outputCard.setContent(Fields.scroll(output));
        outputCard.addHeaderAction(copyButton(output));

        root.add(inputCard, BorderLayout.NORTH);
        root.add(outputCard, BorderLayout.CENTER);

        // ===== 事件 =====
        Consumer<String> digest = (algo) -> {
            String text = input.getText();
            if (text.isEmpty()) {
                UIUtils.info(root, "请输入文本"); return;
            }
            output.setText(hash(text, algo));
        };
        md5.addActionListener(e -> digest.accept("MD5"));
        sha1.addActionListener(e -> digest.accept("SHA-1"));
        sha256.addActionListener(e -> digest.accept("SHA-256"));

        sm3.addActionListener(e -> {
            String text = input.getText();
            if (text.isEmpty()) {
                UIUtils.info(root, "请输入文本"); return;
            }
            output.setText(SM3Utils.hash(text));
        });

        b64enc.addActionListener(e -> {
            String text = input.getText();
            output.setText(Base64.getEncoder().encodeToString(
                    text.getBytes(StandardCharsets.UTF_8)));
        });
        b64dec.addActionListener(e -> {
            try {
                byte[] d = Base64.getDecoder().decode(input.getText().trim());
                output.setText(new String(d, StandardCharsets.UTF_8));
            } catch (Exception ex) {
                UIUtils.error(root, "Base64 解码失败：" + ex.getMessage());
            }
        });

        clear.addActionListener(e -> { input.setText(""); output.setText(""); });

        return root;
    }

    /** 输出卡片标题栏的复制按钮：结果区最常用的动作，放在标题右侧免得再往下找 */
    private static JButton copyButton(final JTextArea output) {
        JButton copy = Buttons.ghost("复制");
        copy.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                UIUtils.copyToClipboard(output.getText());
            }
        });
        return copy;
    }

    private static String hash(String text, String algo) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

}
