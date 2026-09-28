package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.feature.security.infra.acme.AcmeClient;
import com.aqishi.toolbox.feature.security.infra.acme.AcmeException;
import com.aqishi.toolbox.feature.security.infra.acme.AcmeIssuance;
import com.aqishi.toolbox.feature.security.infra.acme.AcmeProblem;
import com.aqishi.toolbox.feature.security.infra.acme.ChallengeProvisioner;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ACME tab: readable failures and cancel-triggered cleanup. */
class CertPanelAcmeTest {

    @Test
    void failureTextKeepsTheServerDetail() {
        AcmeProblem problem = new AcmeProblem(AcmeProblem.ACME_PREFIX + "unauthorized",
                "Incorrect TXT record found", 403, null, List.of());
        AcmeException authz = new AcmeException(AcmeException.Reason.AUTHORIZATION_INVALID, "example.com",
                problem, 0, null, "Authorization for example.com is invalid: " + problem.describe());
        assertTrue(CertPanel.describeAcmeError(authz).contains("Incorrect TXT record found"),
                CertPanel.describeAcmeError(authz));

        AcmeProblem limited = new AcmeProblem(AcmeProblem.ACME_PREFIX + "rateLimited",
                "too many certificates", 429, null, List.of());
        AcmeException rate = new AcmeException(AcmeException.Reason.SERVER_PROBLEM, "newOrder",
                limited, 429, Duration.ofHours(1), "ACME newOrder failed");
        assertTrue(CertPanel.describeAcmeError(rate).contains("too many certificates"));

        AcmeException timeout = new AcmeException(AcmeException.Reason.TIMEOUT, "order", "Timed out after 300s");
        assertTrue(CertPanel.describeAcmeError(timeout).contains("Timed out after 300s"));
        assertTrue(CertPanel.describeAcmeError(new IllegalStateException("boom")).contains("boom"));
    }

    @Test
    void cancelButtonAbandonsPreparedOrderAndCleansUp() throws Exception {
        CertPanel panel = new CertPanel();
        CountDownLatch closed = new CountDownLatch(1);
        ChallengeProvisioner provisioner = new ChallengeProvisioner() {
            @Override
            public String provision(AcmeClient.AcmeChallenge challenge) {
                return "";
            }

            @Override
            public void cleanup(AcmeClient.AcmeChallenge challenge) {
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        AcmeIssuance issuance = new AcmeIssuance(new AcmeClient("http://127.0.0.1:1/directory"),
                null, provisioner, null);

        SwingUtilities.invokeAndWait(() -> {
            panel.getView();
            JButton cancel = field(panel, "acmeCancelBtn", JButton.class);
            assertNotNull(cancel);
            assertFalse(cancel.isEnabled(), "nothing to cancel yet");
            setField(panel, "currentIssuance", issuance);
            cancel.setEnabled(true);
            cancel.doClick(0);
        });

        assertTrue(closed.await(5, TimeUnit.SECONDS), "provisioner closed after cancel");
        assertFalse(issuance.isOpen());
        assertNull(field(panel, "currentIssuance", AcmeIssuance.class));
        Method close = CertPanel.class.getMethod("closeResources");
        close.invoke(panel);
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field f = CertPanel.class.getDeclaredField(name);
            f.setAccessible(true);
            return type.cast(f.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = CertPanel.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
