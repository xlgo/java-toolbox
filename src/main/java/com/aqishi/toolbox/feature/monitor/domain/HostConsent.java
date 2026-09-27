package com.aqishi.toolbox.feature.monitor.domain;

import java.util.concurrent.CompletableFuture;

/**
 * Host-side consent contract: who asks, and how the answer is obtained.
 *
 * <p>The strategy is injected so the consent flow can be exercised in tests
 * without showing a dialog; the Swing implementation is
 * {@code ui.RemoteConsentDialog}.</p>
 */
public final class HostConsent {

    private HostConsent() {
    }

    /**
     * Details shown to the host user. Name and ID come from signaling and are
     * therefore claims, not verified identities; the SAS is what binds the
     * request to the person on the other end.
     */
    public static final class Request {
        private final String controllerName;
        private final String controllerId;
        private final String remoteAddress;
        private final String sas;
        private final String transport;
        private final boolean passwordVerified;

        public Request(String controllerName, String controllerId, String remoteAddress,
                       String sas, String transport, boolean passwordVerified) {
            this.controllerName = controllerName;
            this.controllerId = controllerId;
            this.remoteAddress = remoteAddress;
            this.sas = sas;
            this.transport = transport;
            this.passwordVerified = passwordVerified;
        }

        public String controllerName() {
            return controllerName;
        }

        public String controllerId() {
            return controllerId;
        }

        public String remoteAddress() {
            return remoteAddress;
        }

        public String sas() {
            return sas;
        }

        public String transport() {
            return transport;
        }

        public boolean passwordVerified() {
            return passwordVerified;
        }
    }

    /** Obtains the host user's decision. */
    public interface Strategy {
        /**
         * Must not block. Completes with the granted permissions, or with
         * {@link RemotePermissions#none()} to deny. The caller may complete
         * or cancel the returned future itself (timeout, session closed), and
         * implementations should then withdraw their prompt.
         */
        CompletableFuture<RemotePermissions> requestConsent(Request request);
    }
}
