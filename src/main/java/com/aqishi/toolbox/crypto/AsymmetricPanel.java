package com.aqishi.toolbox.crypto;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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
        super("crypto", "asymmetric.crypto",
                "RSA", "SM2", "国密", "公钥", "私钥", "签名", "验签",
                "非对称", "密钥对", "数字签名");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 密钥卡片：算法与公私钥同属一张表单，复制动作贴在各自行尾 =====
        algoCombo = Fields.combo(new String[]{"RSA", "SM2"});
        pubKeyArea = Fields.area(2, 40);
        priKeyArea = Fields.area(2, 40);

        keySizeCombo = Fields.combo(new Integer[]{1024, 2048, 4096}, 96);
        keySizeCombo.setSelectedItem(2048);
        keySizeLabel = Fields.label("密钥长度：");

        JButton genKeyBtn = Buttons.secondary("生成密钥对");
        JButton copyPubBtn = Buttons.ghost("复制公钥");
        JButton copyPriBtn = Buttons.ghost("复制私钥");

        FormGrid keyForm = new FormGrid();
        keyForm.row("算法：", algoCombo);
        keyForm.row("公钥：", boxedScroll(pubKeyArea), copyPubBtn);
        keyForm.row("私钥：", boxedScroll(priKeyArea), copyPriBtn);

        Card keyCard = Card.titled("密钥对配置与生成");
        keyCard.setContent(keyForm);
        // 密钥长度只在 RSA 下可见，跟生成按钮绑在标题右侧一起显隐，位置更好找
        keyCard.addHeaderAction(keySizeLabel);
        keyCard.addHeaderAction(keySizeCombo);
        keyCard.addHeaderAction(genKeyBtn);

        // ===== 操作卡片：两个输入左右并排，动作合并成一行操作条 =====
        encryptInput = Fields.area(2, 40);
        decryptInput = Fields.area(2, 40);

        sigAlgoCombo = Fields.combo(
                new String[]{"SHA256withRSA", "SHA1withRSA", "MD5withRSA"}, 150);
        sigAlgoLabel = Fields.label("签名算法：");

        JButton encryptBtn = Buttons.primary("公钥加密");
        JButton decryptBtn = Buttons.secondary("私钥解密");
        JButton signBtn = Buttons.secondary("私钥签名");
        JButton verifyBtn = Buttons.secondary("公钥验签");
        JButton clearBtn = Buttons.ghost("清空");

        ActionBar actions = new ActionBar();
        actions.right(encryptBtn);
        actions.right(decryptBtn);
        actions.right(signBtn);
        actions.right(verifyBtn);
        actions.right(clearBtn);

        // 签名算法放操作条左端，紧挨着「私钥签名 / 公钥验签」，用到它的地方就在旁边。
        // 用 BorderLayout 而不是把下拉丢进 ActionBar：BoxLayout 会把下拉框拉到几百像素宽。
        JPanel sigGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        sigGroup.setOpaque(false);
        sigGroup.add(sigAlgoLabel);
        sigGroup.add(sigAlgoCombo);

        JPanel opBar = Layouts.box(Tokens.SPACE_SM, 0);
        opBar.add(sigGroup, BorderLayout.WEST);
        opBar.add(actions, BorderLayout.CENTER);

        JPanel opBody = Layouts.box(0, Tokens.SPACE_MD);
        opBody.add(Layouts.columns(Tokens.SPACE_LG,
                titledArea("输入文本/明文：", encryptInput),
                titledArea("输入密文/签名：", decryptInput)), BorderLayout.CENTER);
        opBody.add(opBar, BorderLayout.SOUTH);

        Card opCard = Card.titled("加解密与签名验签");
        opCard.setContent(opBody);

        // ===== 输出区占满剩余空间：签名、密钥说明都是长文本 =====
        output = Fields.output(6, 40);
        Card outputCard = Card.flush("输出结果");
        outputCard.setContent(Fields.scroll(output));

        // 两张配置卡片按内容高度堆在顶部，剩下的高度全给输出卡片
        root.add(Layouts.stack(Tokens.SPACE_LG, keyCard, opCard), BorderLayout.NORTH);
        root.add(outputCard, BorderLayout.CENTER);

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

    /** 卡片内的子区块：小标题压在输入框上方，两栏并排时标题不会被挤到左边一列 */
    private static JPanel titledArea(String title, JTextArea area) {
        JPanel box = Layouts.box(0, Tokens.SPACE_XS);
        box.add(Fields.caption(title), BorderLayout.NORTH);
        box.add(boxedScroll(area), BorderLayout.CENTER);
        return box;
    }
}
