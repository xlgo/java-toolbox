package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 编解码工具面板。
 * <p>支持 JWT 实时解析（解码 + 签名验证）与构造生成（编码，支持 HS256 签名）。</p>
 */
public class JwtPanel extends ToolPanel {

    // ==================== 解码组件 ====================
    private JTextArea decInputArea;
    private JTextArea decHeaderArea;
    private JTextArea decPayloadArea;
    private JLabel decInfoLabel;
    private JPasswordField decSecretField;
    private JCheckBox decShowSecretCheck;

    // ==================== 编码组件 ====================
    private JTextArea encHeaderArea;
    private JTextArea encPayloadArea;
    private JTextField encSecretField;
    private JTextArea encOutputArea;

    /** HMAC 算法名与 JWT alg 的映射 */
    private static final Map<String, String> HMAC_ALG_MAP = new LinkedHashMap<>();

    static {
        HMAC_ALG_MAP.put("HS256", "HmacSHA256");
        HMAC_ALG_MAP.put("HS384", "HmacSHA384");
        HMAC_ALG_MAP.put("HS512", "HmacSHA512");
    }

    public JwtPanel() {
        super("dev", "jwt.codec",
                "JWT", "Token", "HS256", "签名", "JWT解码",
                "JWT编码", "Json Web Token", "JWT验证", "令牌");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        JTabbedPane tabs = new JTabbedPane();
        // 标签页自身不再画边框，卡片的描边已经足够划分区域
        tabs.setBorder(null);
        tabs.addTab("JWT 解码 (Decode)", buildDecodeTab());
        tabs.addTab("JWT 编码 (Encode)", buildEncodeTab());

        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    // ================================================================
    //  1. 解码面板
    // ================================================================
    private JComponent buildDecodeTab() {
        JPanel p = tabBody();

        // ---- 输入区：token 与验证密钥同属一次「解码请求」，合成一张配置卡片 ----
        decInputArea = Fields.area(4, 40);

        decSecretField = Fields.password();
        decSecretField.setFont(Tokens.fontMono());

        decShowSecretCheck = Fields.check("显示", false);
        decShowSecretCheck.addActionListener(e ->
                decSecretField.setEchoChar(decShowSecretCheck.isSelected() ? (char) 0 : '•'));

        JButton decodeBtn = Buttons.primary("解码");
        JButton clearBtn = Buttons.ghost("清空");

        FormGrid secretForm = new FormGrid();
        secretForm.row("签名密钥（验证用, 可选）：", decSecretField, decShowSecretCheck);

        JPanel inputBody = Layouts.box(0, Tokens.SPACE_MD);
        inputBody.add(boxedScroll(decInputArea), BorderLayout.CENTER);
        inputBody.add(secretForm, BorderLayout.SOUTH);

        Card inputCard = Card.titled("输入 JWT (header.payload.signature)");
        inputCard.setContent(inputBody);
        inputCard.addHeaderAction(decodeBtn);
        inputCard.addHeaderAction(clearBtn);

        // ---- Header / Payload 展示：左右等宽，两段 JSON 长度相近 ----
        decHeaderArea = Fields.output(6, 30);
        decPayloadArea = Fields.output(6, 30);

        Card headerCard = Card.flush("Header (头部)");
        headerCard.setContent(Fields.scroll(decHeaderArea));
        Card payloadCard = Card.flush("Payload (载荷)");
        payloadCard.setContent(Fields.scroll(decPayloadArea));

        // ---- 第三段 Signature：校验结论始终以文字说明为主，颜色只是辅助 ----
        decInfoLabel = new JLabel("  就绪");
        decInfoLabel.setFont(Tokens.fontBody());
        Card signatureCard = Card.titled("Signature (签名校验)");
        signatureCard.setContent(decInfoLabel);

        p.add(inputCard, BorderLayout.NORTH);
        p.add(Layouts.columns(Tokens.SPACE_LG, headerCard, payloadCard), BorderLayout.CENTER);
        p.add(signatureCard, BorderLayout.SOUTH);

        // 事件
        decodeBtn.addActionListener(e -> doDecode());
        clearBtn.addActionListener(e -> {
            decInputArea.setText("");
            decSecretField.setText("");
            decHeaderArea.setText("");
            decPayloadArea.setText("");
            decInfoLabel.setText("  就绪");
            decInfoLabel.setForeground(Tokens.foreground());
        });

        // 默认演示 JWT
        decInputArea.setText("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjI1MTYyMzkwMjJ9." +
                "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
        // 同时填入演示密钥
        decSecretField.setText("your-256-bit-secret");
        doDecode();

        return p;
    }

    // ================================================================
    //  2. 编码面板
    // ================================================================
    private JComponent buildEncodeTab() {
        JPanel p = tabBody();

        // 左侧：两段 JSON 编辑区等高分行，各自一张铺满内容的卡片
        encHeaderArea = Fields.area(6, 30);
        encHeaderArea.setText("{\n  \"alg\": \"HS256\",\n  \"typ\": \"JWT\"\n}");

        encPayloadArea = Fields.area(6, 30);
        encPayloadArea.setText("{\n  \"sub\": \"1234567890\",\n  \"name\": \"John Doe\",\n  \"iat\": 1516239022\n}");

        Card headerCard = Card.flush("编辑 Header (JSON格式)");
        headerCard.setContent(Fields.scroll(encHeaderArea));
        Card payloadCard = Card.flush("编辑 Payload (JSON格式)");
        payloadCard.setContent(Fields.scroll(encPayloadArea));
        JPanel leftPanel = Layouts.rows(Tokens.SPACE_LG, headerCard, payloadCard);

        // 右侧：密钥表单在上、Token 结果吸收剩余高度，生成/复制放标题右侧
        encSecretField = Fields.mono("your-256-bit-secret");

        JButton genBtn = Buttons.primary("生成 JWT Token");
        JButton copyBtn = Buttons.ghost("复制 Token");

        FormGrid secretForm = new FormGrid();
        secretForm.row("HMAC 密钥：", encSecretField);

        encOutputArea = Fields.output(4, 30);
        JPanel tokenBox = Layouts.box(0, Tokens.SPACE_XS);
        tokenBox.add(Fields.caption("生成的 JWT Token"), BorderLayout.NORTH);
        tokenBox.add(boxedScroll(encOutputArea), BorderLayout.CENTER);

        JPanel rightBody = Layouts.box(0, Tokens.SPACE_MD);
        rightBody.add(secretForm, BorderLayout.NORTH);
        rightBody.add(tokenBox, BorderLayout.CENTER);

        Card rightPanel = Card.titled("签名密钥与生成结果");
        rightPanel.setContent(rightBody);
        rightPanel.addHeaderAction(genBtn);
        rightPanel.addHeaderAction(copyBtn);

        // 左右分割
        p.add(Layouts.splitHorizontal(leftPanel, rightPanel, 0.5), BorderLayout.CENTER);

        // 事件
        genBtn.addActionListener(e -> doEncode());
        copyBtn.addActionListener(e -> {
            String token = encOutputArea.getText().trim();
            if (!token.isEmpty()) {
                UIUtils.copyToClipboard(token);
                UIUtils.info(p, "JWT Token 已成功复制到剪贴板。");
            }
        });

        return p;
    }

    /**
     * 标签页内容容器。
     *
     * <p>左右下三边的留白由 {@link Layouts#page()} 统一提供，这里只补一段标签条与
     * 卡片之间的间距，避免出现「页边距 + 标签页边距」的双层留白。</p>
     */
    private static JPanel tabBody() {
        JPanel panel = Layouts.box(Tokens.SPACE_LG, Tokens.SPACE_LG);
        panel.setBorder(KitBorders.padding(Tokens.SPACE_LG, 0, 0, 0));
        return panel;
    }

    /**
     * 卡片内部嵌的文本域滚动区。
     *
     * <p>卡片底色与文本域底色相同，不描一条细线的话输入框会整个「消失」在卡片里；
     * 这里只用最弱的分隔色画 1px，不会和卡片描边叠成双层边框。</p>
     */
    private static JScrollPane boxedScroll(JTextArea area) {
        JScrollPane scroll = Fields.scroll(area);
        scroll.setBorder(KitBorders.lineSubtle(1, 1, 1, 1));
        return scroll;
    }

    // ================================================================
    //  3. 解码逻辑（含签名验证）
    // ================================================================
    private void doDecode() {
        String token = decInputArea.getText().trim();
        if (token.isEmpty()) {
            decInfoLabel.setText("  提示：请输入 JWT Token");
            decInfoLabel.setForeground(Tokens.danger());
            return;
        }

        String[] parts = token.split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            decInfoLabel.setText("  错误：JWT 格式不正确（必须包含点 \".\" 分隔符）");
            decInfoLabel.setForeground(Tokens.danger());
            decHeaderArea.setText("");
            decPayloadArea.setText("");
            return;
        }

        try {
            // 1. 解码 Header
            byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
            String headerJson = new String(headerBytes, StandardCharsets.UTF_8);
            decHeaderArea.setText(JsonFormatter.pretty(headerJson));

            // 2. 解码 Payload
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            decPayloadArea.setText(JsonFormatter.pretty(payloadJson));

            // 3. 检查 exp 过期
            parseExpiration(payloadJson);

            // 4. 签名验证（如果有签名段且提供了密钥）
            String secret = new String(decSecretField.getPassword());
            if (parts.length == 3 && !secret.isEmpty()) {
                doVerifySignature(headerJson, parts, secret);
            } else if (parts.length == 3 && secret.isEmpty()) {
                decInfoLabel.setText(decInfoLabel.getText() + "  |  输入签名密钥可验证签名");
                decInfoLabel.setForeground(Tokens.foreground());
            }
            // parts.length == 2 时无签名，不做验证

        } catch (Exception ex) {
            decHeaderArea.setText("");
            decPayloadArea.setText("");
            decInfoLabel.setText("  错误：Base64/JSON 解码失败 — " + ex.getMessage());
            decInfoLabel.setForeground(Tokens.danger());
        }
    }

    /**
     * 验证 JWT 签名。
     * @param headerJson 原始解码后的 Header JSON 字符串
     * @param parts      JWT 各部分（[header, payload, signature]）
     * @param secret     签名密钥
     */
    private void doVerifySignature(String headerJson, String[] parts, String secret) {
        try {
            // 解析 header 中的 alg 字段
            String alg = extractAlg(headerJson);
            if (alg == null || alg.isEmpty()) {
                decInfoLabel.setText(decInfoLabel.getText() + "  |  签名验证：Header 缺少 alg 字段");
                decInfoLabel.setForeground(Tokens.danger());
                return;
            }

            // 查找对应的 Java Mac 算法名
            String macAlg = HMAC_ALG_MAP.get(alg);
            if (macAlg == null) {
                decInfoLabel.setText(decInfoLabel.getText()
                        + "  |  签名验证：不支持的算法 " + alg + "（仅支持 HS256/HS384/HS512）");
                decInfoLabel.setForeground(Tokens.danger());
                return;
            }

            // 计算期望签名
            String content = parts[0] + "." + parts[1];
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlg);
            Mac mac = Mac.getInstance(macAlg);
            mac.init(keySpec);
            byte[] expectedSigBytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            String expectedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedSigBytes);

            // 对比签名
            String actualSig = parts[2];
            if (expectedSig.equals(actualSig)) {
                decInfoLabel.setText(decInfoLabel.getText() + "  |  ✅ 签名验证通过 (" + alg + ")");
                decInfoLabel.setForeground(Tokens.success());
            } else {
                decInfoLabel.setText(decInfoLabel.getText() + "  |  ❌ 签名验证失败 (" + alg + ")");
                decInfoLabel.setForeground(Tokens.danger());
            }
        } catch (Exception ex) {
            decInfoLabel.setText(decInfoLabel.getText() + "  |  签名验证异常：" + ex.getMessage());
            decInfoLabel.setForeground(Tokens.danger());
        }
    }

    /**
     * 从 Header JSON 中提取 alg 字段值。
     */
    private static String extractAlg(String headerJson) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"alg\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(headerJson);
        return m.find() ? m.group(1) : null;
    }

    // ================================================================
    //  4. 编码逻辑
    // ================================================================
    private void doEncode() {
        try {
            String headerRaw = encHeaderArea.getText().trim();
            String payloadRaw = encPayloadArea.getText().trim();
            String secret = encSecretField.getText();

            // 压缩 JSON
            String headerJson = JsonFormatter.compact(headerRaw);
            String payloadJson = JsonFormatter.compact(payloadRaw);

            // Base64URL 编码 Header 与 Payload
            String headerB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String content = headerB64 + "." + payloadB64;

            // 签名计算（默认使用 HS256 / HmacSHA256）
            String signatureB64 = "";
            if (secret != null && !secret.isEmpty()) {
                SecretKeySpec secretKey = new SecretKeySpec(
                        secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(secretKey);
                byte[] rawHmac = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
                signatureB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
            }

            encOutputArea.setText(content + "." + signatureB64);
        } catch (Exception ex) {
            UIUtils.error(encOutputArea, "JWT 生成失败：" + ex.getMessage());
        }
    }

    // ================================================================
    //  5. 过期时间解析
    // ================================================================
    private void parseExpiration(String json) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");
            java.util.regex.Matcher m = p.matcher(json);
            if (m.find()) {
                long expSeconds = Long.parseLong(m.group(1));
                long expMillis = expSeconds * 1000;
                Date expDate = new Date(expMillis);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String formattedDate = sdf.format(expDate);

                long now = System.currentTimeMillis();
                if (now > expMillis) {
                    decInfoLabel.setText("  状态：已过期，过期时间为 " + formattedDate);
                    decInfoLabel.setForeground(Tokens.danger());
                } else {
                    long diffHours = (expMillis - now) / (1000 * 60 * 60);
                    if (diffHours > 24) {
                        long diffDays = diffHours / 24;
                        decInfoLabel.setText("  状态：有效，" + diffDays + " 天后过期 (" + formattedDate + ")");
                    } else {
                        decInfoLabel.setText("  状态：有效，" + diffHours + " 小时后过期 (" + formattedDate + ")");
                    }
                    decInfoLabel.setForeground(Tokens.success());
                }
            } else {
                decInfoLabel.setText("  状态：解析成功 (未包含 exp 过期声明)");
                decInfoLabel.setForeground(Tokens.foreground());
            }
        } catch (Exception e) {
            decInfoLabel.setText("  状态：解析成功");
            decInfoLabel.setForeground(Tokens.foreground());
        }
    }
}
