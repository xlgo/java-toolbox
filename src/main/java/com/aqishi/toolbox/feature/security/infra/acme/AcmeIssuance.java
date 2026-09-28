package com.aqishi.toolbox.feature.security.infra.acme;

import com.aqishi.toolbox.util.I18n;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One certificate order from provisioning to download, with guaranteed cleanup.
 *
 * <p>{@link #prepare} fetches the challenges and provisions every authorization that
 * is not valid yet. {@link #complete} (optionally) waits until the responses are
 * visible, lets the CA validate, finalizes and downloads the chain. Every provisioned
 * challenge is cleaned up exactly once, on success, failure or cancellation, and also
 * when {@link #prepare} fails half-way or the caller abandons the order via
 * {@link #cleanup()}.</p>
 */
public final class AcmeIssuance {

    private final AcmeClient client;
    private final KeyPair accountKey;
    private final ChallengeProvisioner provisioner;
    private final Consumer<String> logger;
    private final List<AcmeClient.AcmeChallenge> provisioned = new ArrayList<>();

    private AcmeClient.AcmeOrder order;
    private List<AcmeClient.AcmeChallenge> challenges = Collections.emptyList();
    private boolean closed;

    public AcmeIssuance(AcmeClient client, KeyPair accountKey, ChallengeProvisioner provisioner, Consumer<String> logger) {
        this.client = client;
        this.accountKey = accountKey;
        this.provisioner = provisioner;
        this.logger = logger == null ? s -> { } : logger;
    }

    /** Loads the order's challenges of {@code challengeType} and provisions each pending one. */
    public List<AcmeClient.AcmeChallenge> prepare(AcmeClient.AcmeOrder order, String challengeType) throws Exception {
        this.order = order;
        try {
            List<AcmeClient.AcmeChallenge> loaded = client.getChallenges(accountKey, order, challengeType);
            challenges = Collections.unmodifiableList(new ArrayList<>(loaded));
            for (AcmeClient.AcmeChallenge ch : challenges) {
                if (ch.isAlreadyValid()) {
                    logger.accept(I18n.get("tool.cert.acme.log.alreadyValid", ch.domain));
                    continue;
                }
                String where = provisioner.provision(ch);
                synchronized (this) {
                    provisioned.add(ch);
                }
                logger.accept(I18n.get("tool.cert.acme.log.provisioned", ch.domain, where));
            }
            return challenges;
        } catch (Exception | Error e) {
            cleanup();
            throw e;
        }
    }

    /**
     * Validates, finalizes and downloads the certificate chain; cleans up in all outcomes.
     * Must follow a successful {@link #prepare}.
     */
    public String complete(KeyPair domainKey, List<String> domains, BooleanSupplier cancelled) throws Exception {
        if (order == null) {
            throw new IllegalStateException("prepare() has not run");
        }
        try {
            List<AcmeClient.AcmeChallenge> pending;
            synchronized (this) {
                if (closed) {
                    throw new AcmeException(AcmeException.Reason.CANCELLED, "order", "Order was already cleaned up");
                }
                pending = new ArrayList<>(provisioned);
            }
            if (!pending.isEmpty()) {
                logger.accept(I18n.get("tool.cert.acme.log.propagationWait"));
                if (provisioner.awaitVisible(pending, cancelled)) {
                    logger.accept(I18n.get("tool.cert.acme.log.propagationOk"));
                } else {
                    logger.accept(I18n.get("tool.cert.acme.log.propagationTimeout"));
                }
            }
            return client.issueCertificate(accountKey, domainKey, domains, order, challenges, cancelled);
        } finally {
            cleanup();
        }
    }

    public List<AcmeClient.AcmeChallenge> challenges() {
        return challenges;
    }

    public AcmeClient.AcmeOrder order() {
        return order;
    }

    /** {@code true} until {@link #cleanup()} ran. */
    public synchronized boolean isOpen() {
        return !closed;
    }

    /** Removes every provisioned response and closes the provisioner; safe to call repeatedly. */
    public void cleanup() {
        List<AcmeClient.AcmeChallenge> toClean;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toClean = new ArrayList<>(provisioned);
            provisioned.clear();
        }
        // A cancel interrupts the worker; the cleanup requests must still go out.
        boolean interrupted = Thread.interrupted();
        try {
            if (!toClean.isEmpty()) {
                logger.accept(I18n.get("tool.cert.acme.log.cleanupStart"));
            }
            for (AcmeClient.AcmeChallenge ch : toClean) {
                try {
                    provisioner.cleanup(ch);
                    logger.accept(I18n.get("tool.cert.acme.log.cleanupOk", ch.domain));
                } catch (Exception e) {
                    logger.accept(I18n.get("tool.cert.acme.log.cleanupFailed", ch.domain, e.getMessage()));
                }
            }
            try {
                provisioner.close();
            } catch (RuntimeException e) {
                logger.accept(I18n.get("tool.cert.acme.log.cleanupFailed", "-", e.getMessage()));
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
