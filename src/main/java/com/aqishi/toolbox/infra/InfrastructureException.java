package com.aqishi.toolbox.infra;

/**
 * Runtime boundary for failures raised by external systems.
 *
 * <p>The stable {@link Kind} lets UI and application services map failures to
 * localized messages without depending on a vendor client's exception type.</p>
 */
public class InfrastructureException extends RuntimeException {

    /**
     * Broad failure categories exposed by infrastructure adapters.
     */
    public enum Kind {
        CONFIGURATION,
        CONNECTION,
        TIMEOUT,
        PROTOCOL,
        CANCELLED,
        CLOSED
    }

    private final Kind kind;

    /**
     * Creates an infrastructure failure.
     *
     * @param kind stable failure category
     * @param message human-readable diagnostic message
     */
    public InfrastructureException(Kind kind, String message) {
        super(message);
        this.kind = requireKind(kind);
    }

    /**
     * Creates an infrastructure failure while preserving the original cause.
     *
     * @param kind stable failure category
     * @param message human-readable diagnostic message
     * @param cause original vendor or JDK exception
     */
    public InfrastructureException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = requireKind(kind);
    }

    /**
     * Returns the stable category of this failure.
     *
     * @return failure category
     */
    public Kind getKind() {
        return kind;
    }

    private static Kind requireKind(Kind value) {
        if (value == null) {
            throw new NullPointerException("kind");
        }
        return value;
    }
}
