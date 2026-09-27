package com.aqishi.toolbox.feature.monitor.domain;

/**
 * Failure of the remote-desktop secure channel handshake.
 *
 * <p>Every instance is fatal for the channel it was raised on: the caller must
 * close the underlying transport. The {@link Reason} is what the UI turns into
 * a user-facing message, so it has to distinguish "wrong password" from
 * "incompatible version" from "timed out".</p>
 */
public final class HandshakeException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the handshake failed. Alert codes are part of the wire format. */
    public enum Reason {
        /** Peer speaks a different (or the pre-encryption) protocol version. */
        INCOMPATIBLE_VERSION(1),
        /** Key confirmation failed: wrong access password or tampered handshake. */
        AUTH_FAILED(2),
        /** The host requires an access password but the controller has none. */
        PASSWORD_REQUIRED(3),
        /** Malformed or out-of-order handshake message. */
        PROTOCOL_ERROR(4),
        /** Handshake did not finish within the deadline. */
        TIMEOUT(0),
        /** Too many bytes (or an oversize frame) before authentication. */
        TOO_LARGE(0),
        /** Transport closed before the handshake completed. */
        CLOSED(0);

        private final int alertCode;

        Reason(int alertCode) {
            this.alertCode = alertCode;
        }

        /** Wire code sent in an ALERT, or 0 when this reason is never sent. */
        public int alertCode() {
            return alertCode;
        }

        /** Maps a received alert code back to a reason ({@link #PROTOCOL_ERROR} if unknown). */
        public static Reason fromAlertCode(int code) {
            for (Reason reason : values()) {
                if (reason.alertCode != 0 && reason.alertCode == code) return reason;
            }
            return PROTOCOL_ERROR;
        }

        /**
         * Whether this reason tells the user something actionable (version,
         * password), as opposed to a transient network condition.
         */
        public boolean isUserActionable() {
            return this == INCOMPATIBLE_VERSION || this == AUTH_FAILED || this == PASSWORD_REQUIRED;
        }
    }

    private final Reason reason;
    private final boolean reportedByPeer;

    public HandshakeException(Reason reason, String detail) {
        this(reason, detail, false);
    }

    public HandshakeException(Reason reason, String detail, boolean reportedByPeer) {
        super(reason + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.reason = reason;
        this.reportedByPeer = reportedByPeer;
    }

    public Reason getReason() {
        return reason;
    }

    /** True when the failure was announced by the peer through an ALERT. */
    public boolean isReportedByPeer() {
        return reportedByPeer;
    }
}
