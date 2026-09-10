package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.feature.security.domain.CertInspectorService;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CertInspectorPanelAsyncTest {

    @Test
    void runsCsrAndChainValidationOffTheEventDispatchThread() throws Exception {
        BlockingCertInspectorService service = new BlockingCertInspectorService();
        CertInspectorPanel panel = build(service);
        try {
            SwingUtilities.invokeAndWait(() -> {
                field(panel, "csrInputArea", JTextArea.class).setText("test-csr");
                invoke(panel, "parseCsr");
            });
            assertTrue(service.csrStarted.await(3, TimeUnit.SECONDS));
            assertFalse(service.csrCalledOnEdt.get());
            assertButtonEnabled(panel, "csrParseBtn", false);
            assertComponentEnabled(panel, "csrInputArea", false);

            service.releaseCsr.countDown();
            awaitEdt(() -> field(panel, "csrResultArea", JTextArea.class)
                    .getText().contains("CN=async.csr"));
            assertButtonEnabled(panel, "csrParseBtn", true);
            assertComponentEnabled(panel, "csrInputArea", true);

            SwingUtilities.invokeAndWait(() -> {
                field(panel, "chainInputArea", JTextArea.class).setText("test-chain");
                invoke(panel, "validateChain");
            });
            assertTrue(service.chainStarted.await(3, TimeUnit.SECONDS));
            assertFalse(service.chainCalledOnEdt.get());
            assertButtonEnabled(panel, "chainValidateBtn", false);
            assertComponentEnabled(panel, "chainInputArea", false);

            service.releaseChain.countDown();
            awaitEdt(() -> field(panel, "chainLogArea", JTextArea.class)
                    .getText().contains("validated asynchronously"));
            assertButtonEnabled(panel, "chainValidateBtn", true);
            assertComponentEnabled(panel, "chainInputArea", true);
        } finally {
            service.releaseAll();
            panel.closeResources();
        }
    }

    @Test
    void clearingCsrCancelsTheActiveWorkerAndRestoresActions() throws Exception {
        BlockingCertInspectorService service = new BlockingCertInspectorService();
        CertInspectorPanel panel = build(service);
        try {
            SwingUtilities.invokeAndWait(() -> {
                field(panel, "csrInputArea", JTextArea.class).setText("test-csr");
                invoke(panel, "parseCsr");
            });
            assertTrue(service.csrStarted.await(3, TimeUnit.SECONDS));
            assertButtonEnabled(panel, "csrParseBtn", false);

            SwingUtilities.invokeAndWait(() -> field(panel, "csrClearBtn", JButton.class).doClick());
            assertButtonEnabled(panel, "csrParseBtn", true);
            assertEquals("", text(panel, "csrInputArea"));
            assertEquals("", text(panel, "csrResultArea"));
        } finally {
            service.releaseAll();
            panel.closeResources();
        }
    }

    private static CertInspectorPanel build(CertInspectorService service) throws Exception {
        AtomicReference<CertInspectorPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            CertInspectorPanel panel = new CertInspectorPanel(service);
            panel.getView();
            reference.set(panel);
        });
        return reference.get();
    }

    private static void assertButtonEnabled(CertInspectorPanel panel, String name, boolean expected)
            throws Exception {
        AtomicBoolean actual = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> actual.set(field(panel, name, JButton.class).isEnabled()));
        assertEquals(expected, actual.get(),
                () -> name + " enabled state expected=" + expected + ", actual=" + actual.get());
    }

    private static void assertComponentEnabled(CertInspectorPanel panel, String name, boolean expected)
            throws Exception {
        AtomicBoolean actual = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> actual.set(field(panel, name, JTextArea.class).isEnabled()));
        assertEquals(expected, actual.get(),
                () -> name + " enabled state expected=" + expected + ", actual=" + actual.get());
    }

    private static String text(CertInspectorPanel panel, String fieldName) throws Exception {
        AtomicReference<String> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> value.set(
                field(panel, fieldName, JTextArea.class).getText()));
        return value.get();
    }

    private static void awaitEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            AtomicBoolean complete = new AtomicBoolean();
            SwingUtilities.invokeAndWait(() -> complete.set(condition.getAsBoolean()));
            if (complete.get()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Timed out waiting for SwingWorker completion");
    }

    private static void invoke(CertInspectorPanel panel, String methodName) {
        try {
            Method method = CertInspectorPanel.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(panel);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException error) {
            throw new AssertionError(error);
        }
    }

    private static <T> T field(CertInspectorPanel panel, String fieldName, Class<T> type) {
        try {
            Field field = CertInspectorPanel.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return type.cast(field.get(panel));
        } catch (NoSuchFieldException | IllegalAccessException error) {
            throw new AssertionError(error);
        }
    }

    private static final class BlockingCertInspectorService extends CertInspectorService {
        private final CountDownLatch csrStarted = new CountDownLatch(1);
        private final CountDownLatch releaseCsr = new CountDownLatch(1);
        private final CountDownLatch chainStarted = new CountDownLatch(1);
        private final CountDownLatch releaseChain = new CountDownLatch(1);
        private final AtomicBoolean csrCalledOnEdt = new AtomicBoolean(true);
        private final AtomicBoolean chainCalledOnEdt = new AtomicBoolean(true);

        @Override
        public CsrInfo parseCsr(String csrPem) throws Exception {
            csrCalledOnEdt.set(SwingUtilities.isEventDispatchThread());
            csrStarted.countDown();
            await(releaseCsr);

            CsrInfo info = new CsrInfo();
            info.setSubject("CN=async.csr");
            info.setSignatureAlgorithm("SHA256withRSA");
            info.setPublicKeyAlgorithm("RSA");
            info.setKeySize(2048);
            info.setSignatureValid(true);
            return info;
        }

        @Override
        public ChainValidationResult validateCertificateChainFromPem(String pemsContent)
                throws Exception {
            chainCalledOnEdt.set(SwingUtilities.isEventDispatchThread());
            chainStarted.countDown();
            await(releaseChain);

            ChainValidationResult result = new ChainValidationResult();
            result.setValid(true);
            result.getLogs().add("validated asynchronously");
            return result;
        }

        private void releaseAll() {
            releaseCsr.countDown();
            releaseChain.countDown();
        }

        private static void await(CountDownLatch latch) throws InterruptedException {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test worker was not released");
            }
        }
    }
}
