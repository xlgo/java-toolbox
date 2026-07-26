package com.aqishi.toolbox.crypto;

import com.aqishi.toolbox.ui.ToolPanel;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 对称加密面板（支持 AES / DES / 3DES / SM4）。
 */
public class SymmetricPanel extends ToolPanel {

    private JComboBox<String> algoCombo;
    private JComboBox<String> modeCombo;
    private JComboBox<String> paddingCombo;
    private JComboBox<Integer> keySizeCombo;
    private JComboBox<String> encodingCombo; // Base64 或 Hex
    private JTextArea keyArea;
    private JTextField ivField;
    private JCheckBox customIvCheckbox;
    private JTextArea inputArea;
    private JTextArea outputArea;

    public SymmetricPanel() {
        super("crypto", "symmetric.crypto",
                "AES", "DES", "3DES", "SM4", "国密",
                "ECB", "CBC", "PKCS5", "密钥", "加密", "解密",
                "对称");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        algoCombo = Fields.combo(new String[]{"AES", "DES", "3DES", "SM4"});
        modeCombo = Fields.combo(new String[]{"CBC", "ECB"});
        paddingCombo = Fields.combo(SymmetricUtils.PADDINGS);
        keySizeCombo = Fields.combo(new Integer[0]);
        encodingCombo = Fields.combo(new String[]{"Base64", "Hex", "UTF-8 文本"});

        // 五个参数下拉各占一行会把配置卡片撑到半屏高，
        // 拆成左右两列后卡片只有两行，明密文区才有空间展开。
        FormGrid cipherParams = new FormGrid();
        cipherParams.row("算法：", algoCombo);
        cipherParams.row("填充：", paddingCombo);

        FormGrid modeParams = new FormGrid();
        modeParams.row("模式：", modeCombo);
        modeParams.row("密钥长度：", keySizeCombo);

        keyArea = Fields.area(2, 40);
        ivField = Fields.mono("");
        ivField.setEnabled(false);
        customIvCheckbox = Fields.check("自定义 IV（留空为自动随机 IV）", false);

        // 密钥格式 / 密钥 / IV 是同一组「密钥材料」，用整宽表单让输入列一起拉伸；
        // IV 的开关放行尾，勾选状态与输入框保持在同一视线上。
        FormGrid keyForm = new FormGrid();
        keyForm.row("密钥格式：", encodingCombo);
        keyForm.row("密钥：", boxedScroll(keyArea));
        keyForm.row("IV 向量：", ivField, customIvCheckbox);

        JButton genKeyBtn = Buttons.secondary("生成密钥");
        JButton copyKeyBtn = Buttons.ghost("复制密钥");

        Card configCard = Card.titled("参数与密钥配置");
        JPanel configBody = Layouts.box(0, Tokens.SPACE_MD);
        configBody.add(Layouts.columns(Tokens.SPACE_XL, cipherParams, modeParams), BorderLayout.NORTH);
        configBody.add(keyForm, BorderLayout.CENTER);
        configCard.setContent(configBody);
        // 密钥动作只作用于本卡片，放标题右侧，避免页面中央再多一条按钮行
        configCard.addHeaderAction(genKeyBtn);
        configCard.addHeaderAction(copyKeyBtn);

        // ===== 明文 / 密文：上下分栏，两个大文本域都能拖动分配高度 =====
        inputArea = Fields.area(4, 40);
        outputArea = Fields.output(6, 40);

        JButton encryptBtn = Buttons.primary("加密");
        JButton decryptBtn = Buttons.secondary("解密");
        JButton clearBtn = Buttons.ghost("清空");

        Card inputCard = Card.flush("输入文本（加密输明文，解密输密文）");
        inputCard.setContent(Fields.scroll(inputArea));
        inputCard.addHeaderAction(encryptBtn);
        inputCard.addHeaderAction(decryptBtn);
        inputCard.addHeaderAction(clearBtn);

        Card outputCard = Card.flush("输出结果");
        outputCard.setContent(Fields.scroll(outputArea));

        root.add(configCard, BorderLayout.NORTH);
        root.add(Layouts.splitVertical(inputCard, outputCard, 0.4), BorderLayout.CENTER);

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
                String padding = (String) paddingCombo.getSelectedItem();
                byte[] ivBytes = getIvBytes();

                String cipher = SymmetricUtils.encrypt(algo, mode, padding, text, keyBytes, ivBytes, false);
                outputArea.setText("[加密成功]\n算法：" + algo + "-" + mode + "-" + padding + "\n密文 (Base64)：\n" + cipher);
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
                String padding = (String) paddingCombo.getSelectedItem();
                byte[] ivBytes = getIvBytes();

                String plain = SymmetricUtils.decrypt(algo, mode, padding, text, keyBytes, ivBytes, false);
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

    /**
     * 卡片内部嵌的文本域滚动区。
     *
     * <p>卡片底色与文本域底色相同，不描一条细线的话密钥框会整个「消失」在卡片里；
     * 这里只用最弱的分隔色画 1px，不会和卡片描边叠成双层边框。</p>
     */
    private static JScrollPane boxedScroll(JTextArea area) {
        JScrollPane scroll = Fields.scroll(area);
        scroll.setBorder(KitBorders.lineSubtle(1, 1, 1, 1));
        return scroll;
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
