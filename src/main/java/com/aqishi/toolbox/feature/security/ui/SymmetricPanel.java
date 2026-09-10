package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.feature.security.domain.SymmetricUtils;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 对称加密面板（支持 AES / DES / 3DES / SM4）。
 */
public class SymmetricPanel extends ToolPanel {

    private static final int UTF8_ENCODING_INDEX = 2;

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
                "GCM", "ECB", "CBC", "PKCS5", "密钥", "加密", "解密",
                "对称");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        algoCombo = Fields.combo(new String[]{"AES", "DES", "3DES", "SM4"});
        // GCM first: it authenticates the ciphertext, so it is the right
        // default. ECB stays only to decrypt data produced by older versions.
        modeCombo = Fields.combo(SymmetricUtils.MODES);
        paddingCombo = Fields.combo(SymmetricUtils.PADDINGS);
        keySizeCombo = Fields.combo(new Integer[0]);
        encodingCombo = Fields.combo(new String[]{"Base64", "Hex", I18n.get("tool.symmetric.encoding.utf8")});

        // 五个参数下拉各占一行会把配置卡片撑到半屏高，
        // 拆成左右两列后卡片只有两行，明密文区才有空间展开。
        FormGrid cipherParams = new FormGrid();
        cipherParams.row(I18n.get("tool.symmetric.label.algorithm"), algoCombo);
        cipherParams.row(I18n.get("tool.symmetric.label.padding"), paddingCombo);

        FormGrid modeParams = new FormGrid();
        modeParams.row(I18n.get("tool.symmetric.label.mode"), modeCombo);
        modeParams.row(I18n.get("tool.symmetric.label.keySize"), keySizeCombo);

        keyArea = Fields.area(2, 40);
        ivField = Fields.mono("");
        ivField.setEnabled(false);
        customIvCheckbox = Fields.check(I18n.get("tool.symmetric.label.customIv"), false);

        // 密钥格式 / 密钥 / IV 是同一组「密钥材料」，用整宽表单让输入列一起拉伸；
        // IV 的开关放行尾，勾选状态与输入框保持在同一视线上。
        FormGrid keyForm = new FormGrid();
        keyForm.row(I18n.get("tool.symmetric.label.keyFormat"), encodingCombo);
        keyForm.row(I18n.get("tool.symmetric.label.key"), boxedScroll(keyArea));
        keyForm.row(I18n.get("tool.symmetric.label.iv"), ivField, customIvCheckbox);

        JButton genKeyBtn = Buttons.secondary(I18n.get("tool.symmetric.button.generateKey"));
        JButton copyKeyBtn = Buttons.ghost(I18n.get("tool.symmetric.button.copyKey"));

        Card configCard = Card.titled(I18n.get("tool.symmetric.card.config"));
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

        JButton encryptBtn = Buttons.primary(I18n.get("tool.symmetric.button.encrypt"));
        JButton decryptBtn = Buttons.secondary(I18n.get("tool.symmetric.button.decrypt"));
        JButton clearBtn = Buttons.ghost(I18n.get("tool.symmetric.button.clear"));

        Card inputCard = Card.flush(I18n.get("tool.symmetric.card.input"));
        inputCard.setContent(Fields.scroll(inputArea));
        inputCard.addHeaderAction(encryptBtn);
        inputCard.addHeaderAction(decryptBtn);
        inputCard.addHeaderAction(clearBtn);

        Card outputCard = Card.flush(I18n.get("tool.symmetric.card.output"));
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
            String mode = (String) modeCombo.getSelectedItem();
            ivField.setEnabled(customIvCheckbox.isSelected() && SymmetricUtils.requiresIv(mode));
            if (!ivField.isEnabled()) {
                ivField.setText("");
            }
        });

        genKeyBtn.addActionListener(e -> {
            try {
                String algo = getSelectedAlgo();
                int size = (int) keySizeCombo.getSelectedItem();
                String encoding = (String) encodingCombo.getSelectedItem();

                if (encodingCombo.getSelectedIndex() == UTF8_ENCODING_INDEX) {
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
                outputArea.setText(I18n.get("tool.symmetric.status.keyGenerated", algo, size));
            } catch (Exception ex) {
                UIUtils.error(root, I18n.get("tool.symmetric.error.keyGenerate", ex.getMessage()));
            }
        });

        copyKeyBtn.addActionListener(e -> {
            String key = keyArea.getText().trim();
            if (key.isEmpty()) {
                UIUtils.error(root, I18n.get("tool.symmetric.error.keyEmpty"));
                return;
            }
            UIUtils.copyToClipboard(key);
            outputArea.setText(I18n.get("tool.symmetric.status.keyCopied"));
        });

        encryptBtn.addActionListener(e -> {
            try {
                String text = inputArea.getText();
                if (text.isEmpty()) {
                    UIUtils.error(root, I18n.get("tool.symmetric.error.encryptInput"));
                    return;
                }
                byte[] keyBytes = getKeyBytes();
                if (keyBytes == null) {
                    UIUtils.error(root, I18n.get("tool.symmetric.error.invalidKey"));
                    return;
                }

                String algo = getSelectedAlgo();
                String mode = (String) modeCombo.getSelectedItem();
                String padding = (String) paddingCombo.getSelectedItem();
                byte[] ivBytes = getIvBytes();

                String cipher = SymmetricUtils.encrypt(algo, mode, padding, text, keyBytes, ivBytes, false);
                String header = I18n.get("tool.symmetric.status.encryptSuccess", algo, mode,
                        SymmetricUtils.isAuthenticated(mode) ? "NoPadding" : padding);
                if ("ECB".equalsIgnoreCase(mode)) {
                    header += "\n" + I18n.get("tool.symmetric.warning.ecb");
                }
                outputArea.setText(header + "\n" + I18n.get("tool.symmetric.status.ciphertext") + "\n" + cipher);
            } catch (Exception ex) {
                UIUtils.error(root, I18n.get("tool.symmetric.error.encrypt", ex.getMessage()));
            }
        });

        decryptBtn.addActionListener(e -> {
            try {
                String text = inputArea.getText().trim();
                if (text.isEmpty()) {
                    UIUtils.error(root, I18n.get("tool.symmetric.error.decryptInput"));
                    return;
                }
                byte[] keyBytes = getKeyBytes();
                if (keyBytes == null) {
                    UIUtils.error(root, I18n.get("tool.symmetric.error.invalidKey"));
                    return;
                }

                String algo = getSelectedAlgo();
                String mode = (String) modeCombo.getSelectedItem();
                String padding = (String) paddingCombo.getSelectedItem();
                byte[] ivBytes = getIvBytes();

                String plain = SymmetricUtils.decrypt(algo, mode, padding, text, keyBytes, ivBytes, false);
                outputArea.setText(I18n.get("tool.symmetric.status.decryptSuccess") + "\n"
                        + I18n.get("tool.symmetric.status.plaintext") + "\n" + plain);
            } catch (Exception ex) {
                UIUtils.error(root, I18n.get("tool.symmetric.error.decrypt", ex.getMessage()));
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

    /**
     * IV 只在 CBC / GCM 下有意义；GCM 自带认证标签，padding 对它不适用。
     *
     * <p>ECB 保留可解密能力，但它不使用 IV，也不提供语义安全——相同的明文块
     * 永远得到相同的密文块——所以这里同时把它标注出来。</p>
     */
    private void updateIvStatus() {
        String mode = (String) modeCombo.getSelectedItem();
        boolean needsIv = SymmetricUtils.requiresIv(mode);
        boolean authenticated = SymmetricUtils.isAuthenticated(mode);

        paddingCombo.setEnabled(!authenticated);
        paddingCombo.setToolTipText(authenticated
                ? I18n.get("tool.symmetric.tooltip.gcmPadding")
                : null);

        customIvCheckbox.setEnabled(needsIv);
        if (!needsIv) {
            customIvCheckbox.setSelected(false);
            ivField.setText("");
        }
        ivField.setEnabled(needsIv && customIvCheckbox.isSelected());
    }

    private byte[] getKeyBytes() {
        String keyText = keyArea.getText().trim();
        if (keyText.isEmpty()) return null;
        String format = (String) encodingCombo.getSelectedItem();
        try {
            if ("Hex".equals(format)) {
                return SymmetricUtils.hexToBytes(keyText);
            } else if (encodingCombo.getSelectedIndex() == UTF8_ENCODING_INDEX) {
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
