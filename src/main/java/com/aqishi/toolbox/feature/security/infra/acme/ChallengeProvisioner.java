package com.aqishi.toolbox.feature.security.infra.acme;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Publishes and removes the response to an ACME challenge (a DNS TXT record, an
 * HTTP-01 token served locally, a file in a web root...). {@link AcmeIssuance}
 * guarantees {@link #cleanup} for every successful {@link #provision} and a final
 * {@link #close}, whatever the outcome.
 */
public interface ChallengeProvisioner {

    /** Makes the response for {@code challenge} available; returns a short description for the log. */
    String provision(AcmeClient.AcmeChallenge challenge) throws Exception;

    /** Removes what {@link #provision} created for {@code challenge}. */
    void cleanup(AcmeClient.AcmeChallenge challenge) throws Exception;

    /**
     * Best-effort wait until the provisioned responses are visible, before the CA is
     * asked to validate. Returns {@code false} when it gave up waiting (the caller
     * continues anyway); the default does not check and returns {@code true}.
     */
    default boolean awaitVisible(List<AcmeClient.AcmeChallenge> challenges, BooleanSupplier cancelled) throws Exception {
        return true;
    }

    /** Releases shared resources (e.g. a local HTTP server) after all cleanups. */
    default void close() {
    }
}
