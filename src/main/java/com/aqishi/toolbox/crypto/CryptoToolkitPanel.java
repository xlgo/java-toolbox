package com.aqishi.toolbox.crypto;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * 密码学工具箱面板
 * 合并了 哈希摘要、对称加密、非对称加密 面板
 */
public class CryptoToolkitPanel extends ToolPanel {

    public CryptoToolkitPanel() {
        super("crypto", "cryptotoolkit",
                "MD5", "SHA-1", "SHA-256", "SHA256", "SM3", "哈希", "Hash",
                "消息摘要", "散列", "Base64", "编解码", "编码", "解码",
                "AES", "DES", "3DES", "SM4", "国密",
                "ECB", "CBC", "PKCS5", "密钥", "加密", "解密",
                "RSA", "SM2", "国密", "公钥", "私钥", "签名", "验签",
                "非对称", "密钥对", "数字签名");
    }

    @Override
    protected JComponent build() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 12));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        JTabbedPane tabbedPane = new JTabbedPane();
        
        ToolPanel hashPanel = new CryptoPanel();
        tabbedPane.addTab(I18n.get("tool.hash.codec"), hashPanel.getView());

        ToolPanel symPanel = new SymmetricPanel();
        tabbedPane.addTab(I18n.get("tool.symmetric.crypto"), symPanel.getView());

        ToolPanel asymPanel = new AsymmetricPanel();
        tabbedPane.addTab(I18n.get("tool.asymmetric.crypto"), asymPanel.getView());

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        return mainPanel;
    }
}
