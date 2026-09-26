package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.feature.security.domain.CertUtils;
import com.aqishi.toolbox.feature.security.domain.GmCrypto;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 签发页的国密双证书开关：只对 SM2 可用，勾选后输出区多一行加密证书。 */
class CertPanelSm2Test {

    @Test
    void dualCertificateToggleOnlyAppliesToSm2() throws Exception {
        CertUtils.CertResult root = CertUtils.createRootCA("SM2", "GM Root", "Org", "", "", "", "CN", 1);
        CertPanel panel = new CertPanel();
        silenceDialogs(true);
        try {
            run(panel, root);
        } finally {
            silenceDialogs(false);
        }
    }

    private static void run(CertPanel panel, CertUtils.CertResult root) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                panel.getView();
                JComboBox<?> alg = field(panel, "signAlgCombo", JComboBox.class);
                JCheckBox dual = field(panel, "signDualCheck", JCheckBox.class);
                JPanel grid = field(panel, "signGrid", JPanel.class);

                assertFalse(dual.isEnabled(), "RSA is selected by default");
                alg.setSelectedItem("SM2");
                assertTrue(dual.isEnabled());

                dual.doClick(0);
                assertEquals(3, ((JPanel) grid.getComponent(0)).getComponentCount(), "CA / signing / encryption rows");

                field(panel, "signCaCertArea", JTextArea.class).setText(root.getCertificatePem());
                field(panel, "signCaKeyArea", JTextArea.class).setText(root.getPrivateKeyPem());
                Method sign = CertPanel.class.getDeclaredMethod("doSignCertificate");
                sign.setAccessible(true);
                sign.invoke(panel);

                String signPem = field(panel, "signCertOut", JTextArea.class).getText();
                String encPem = field(panel, "encCertOut", JTextArea.class).getText();
                X509Certificate signCert = CertUtils.parseCertFromPem(signPem);
                X509Certificate encCert = CertUtils.parseCertFromPem(encPem);
                assertTrue(signCert.getKeyUsage()[0]);
                assertTrue(encCert.getKeyUsage()[2]);
                GmCrypto.verify(encCert, root.getCertificate().getPublicKey());

                // 切回非 SM2 时自动取消勾选并收起第三行
                alg.setSelectedItem("RSA 2048");
                assertFalse(dual.isSelected());
                assertFalse(dual.isEnabled());
                assertEquals(2, ((JPanel) grid.getComponent(0)).getComponentCount());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    /** 签发成功会弹模态提示框；换成什么都不做的出口，否则测试会卡在对话框上。 */
    private static void silenceDialogs(boolean silent) throws Exception {
        Class<?> sinkType = Class.forName("com.aqishi.toolbox.util.UIUtils$DialogSink");
        Object sink = silent ? Proxy.newProxyInstance(sinkType.getClassLoader(), new Class<?>[]{sinkType},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.FALSE
                        : method.getReturnType() == int.class ? 0 : null) : null;
        Method setter = Class.forName("com.aqishi.toolbox.util.UIUtils").getDeclaredMethod("setDialogSink", sinkType);
        setter.setAccessible(true);
        setter.invoke(null, sink);
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = CertPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }
}
