package com.aqishi.toolbox.crypto;

import com.aqishi.toolbox.ui.ToolPanel;
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
        super("加密", "摘要与编解码");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部输入区 =====
        JTextArea input = new JTextArea(5, 40);
        input.setFont(UIUtils.monoFont());
        input.setLineWrap(true);
        root.add(UIUtils.scrollText(input, "输入文本"), BorderLayout.NORTH);

        // ===== 算法按钮区 =====
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton md5 = UIUtils.button("MD5", 90);
        JButton sha1 = UIUtils.button("SHA-1", 90);
        JButton sha256 = UIUtils.button("SHA-256", 90);
        JButton sm3 = UIUtils.button("SM3", 90);
        JButton b64enc = UIUtils.button("Base64 编码", 110);
        JButton b64dec = UIUtils.button("Base64 解码", 110);
        JButton aesEnc = UIUtils.button("AES 加密", 100);
        JButton aesDec = UIUtils.button("AES 解密", 100);
        JButton clear = UIUtils.button("清空", 80);
        btns.add(md5); btns.add(sha1); btns.add(sha256); btns.add(sm3);
        btns.add(b64enc); btns.add(b64dec);
        btns.add(aesEnc); btns.add(aesDec); btns.add(clear);
        root.add(btns, BorderLayout.CENTER);

        // ===== 输出区 =====
        JTextArea output = new JTextArea(8, 40);
        output.setFont(UIUtils.monoFont());
        output.setLineWrap(true);
        output.setEditable(false);
        root.add(UIUtils.scrollText(output, "输出结果"), BorderLayout.SOUTH);

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

        aesEnc.addActionListener(e -> {
            String key = UIUtils.input(root, "请输入 AES 密钥（16/24/32 字节，留空随机生成）", "");
            try {
                SecretKeySpec sk = key == null || key.isEmpty() ? randomAesKey(16)
                        : new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
                byte[] iv = new byte[16];
                new SecureRandom().nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.ENCRYPT_MODE, sk, new IvParameterSpec(iv));
                byte[] enc = c.doFinal(input.getText().getBytes(StandardCharsets.UTF_8));
                // 拼接 iv || 密文 后 Base64
                byte[] all = new byte[iv.length + enc.length];
                System.arraycopy(iv, 0, all, 0, iv.length);
                System.arraycopy(enc, 0, all, iv.length, enc.length);
                output.setText("KEY=" + new String(sk.getEncoded(), StandardCharsets.UTF_8)
                        + "\nDATA=" + Base64.getEncoder().encodeToString(all));
            } catch (Exception ex) {
                UIUtils.error(root, "AES 加密失败：" + ex.getMessage());
            }
        });
        aesDec.addActionListener(e -> {
            String key = UIUtils.input(root, "请输入 AES 密钥", "");
            if (key == null || key.isEmpty()) return;
            try {
                String dataLine = input.getText();
                // 兼容输出区格式 KEY=...\nDATA=...
                if (dataLine.contains("DATA=")) {
                    dataLine = dataLine.substring(dataLine.indexOf("DATA=") + 5).trim();
                }
                byte[] all = Base64.getDecoder().decode(dataLine.trim());
                byte[] iv = new byte[16];
                byte[] enc = new byte[all.length - 16];
                System.arraycopy(all, 0, iv, 0, 16);
                System.arraycopy(all, 16, enc, 0, enc.length);
                SecretKeySpec sk = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, sk, new IvParameterSpec(iv));
                output.setText(new String(c.doFinal(enc), StandardCharsets.UTF_8));
            } catch (Exception ex) {
                UIUtils.error(root, "AES 解密失败：" + ex.getMessage());
            }
        });
        clear.addActionListener(e -> { input.setText(""); output.setText(""); });

        return root;
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

    private static SecretKeySpec randomAesKey(int bytes) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(bytes * 8);
        SecretKey k = kg.generateKey();
        return new SecretKeySpec(k.getEncoded(), "AES");
    }
}
