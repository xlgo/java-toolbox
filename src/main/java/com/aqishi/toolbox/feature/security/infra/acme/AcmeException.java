package com.aqishi.toolbox.feature.security.infra.acme;

import java.time.Duration;

/**
 * Failure of an ACME operation. The message is technical and always carries the
 * server-provided problem detail; the UI uses {@link #reason()}, {@link #problem()}
 * and {@link #subject()} to build a localized message.
 */
public class AcmeException extends RuntimeException {

    /** What went wrong, independent of the language the message is shown in. */
    public enum Reason {
        /** The server answered with a problem document (see {@link #problem()}). */
        SERVER_PROBLEM,
        /** Unexpected HTTP status without a parsable problem document. */
        HTTP_ERROR,
        /** The server offered no challenge of the requested type for an identifier. */
        CHALLENGE_UNAVAILABLE,
        /** An authorization ended in a non-valid final state (invalid/expired/...). */
        AUTHORIZATION_INVALID,
        /** The order became invalid. */
        ORDER_INVALID,
        /** The overall wait deadline passed while polling. */
        TIMEOUT,
        /** The user cancelled. */
        CANCELLED,
        /** The server response did not follow RFC 8555 (missing field, bad JSON...). */
        PROTOCOL
    }

    private final Reason reason;
    private final AcmeProblem problem;
    private final String subject;
    private final int httpStatus;
    private final Duration retryAfter;

    public AcmeException(Reason reason, String subject, AcmeProblem problem, int httpStatus,
                         Duration retryAfter, String message) {
        super(message);
        this.reason = reason;
        this.subject = subject;
        this.problem = problem;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public AcmeException(Reason reason, String subject, String message) {
        this(reason, subject, null, 0, null, message);
    }

    public Reason reason() {
        return reason;
    }

    /** Problem document from the server, or {@code null}. */
    public AcmeProblem problem() {
        return problem;
    }

    /** What the failure is about: an identifier, an operation name or a URL; may be {@code null}. */
    public String subject() {
        return subject;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Retry-After the server sent with the failure (e.g. for {@code rateLimited}), or {@code null}. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** {@code true} when the server reported {@code urn:ietf:params:acme:error:<shortType>}. */
    public boolean isProblem(String shortType) {
        return problem != null && problem.is(shortType);
    }
}
