package com.aqishi.toolbox.feature.security.infra.acme;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared fakes for the ACME tests: keys, a manual clock/sleeper, a recording DNS provider. */
final class AcmeTestSupport {

    private AcmeTestSupport() {
    }

    private static KeyPair account;
    private static KeyPair domain;

    static synchronized KeyPair accountKey() throws Exception {
        if (account == null) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            account = kpg.generateKeyPair();
        }
        return account;
    }

    static synchronized KeyPair domainKey() throws Exception {
        if (domain == null) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            domain = kpg.generateKeyPair();
        }
        return domain;
    }

    /** A clock that only moves when the fake sleeper sleeps. */
    static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        /** Runs after every sleep; lets a test cancel or interrupt mid-poll. */
        Runnable onSleep = () -> { };

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }

        synchronized void advance(Duration d) {
            now = now.plus(d);
        }

        AcmePoller.Sleeper sleeper() {
            return d -> {
                sleeps.add(d);
                advance(d);
                onSleep.run();
            };
        }

        Duration totalSlept() {
            synchronized (sleeps) {
                return sleeps.stream().reduce(Duration.ZERO, Duration::plus);
            }
        }

        AcmePoller poller(Duration timeout) {
            return new AcmePoller(this, sleeper(), Duration.ofSeconds(1), Duration.ofSeconds(30),
                    Duration.ofSeconds(3), timeout);
        }
    }

    /** Records every TXT add/delete; optionally fails the N-th add. */
    static final class FakeDns implements DnsProvider {
        final List<String> added = Collections.synchronizedList(new ArrayList<>());
        final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
        final List<String> names = Collections.synchronizedList(new ArrayList<>());
        int failOnAdd = -1;

        @Override
        public String addTxtRecord(String domain, String recordName, String recordValue) {
            if (added.size() == failOnAdd) {
                throw new IllegalStateException("DNS API down");
            }
            String id = "rec-" + added.size();
            added.add(id);
            names.add(recordName);
            return id;
        }

        @Override
        public void deleteTxtRecord(String domain, String recordId) {
            interruptedOnDelete.add(Thread.currentThread().isInterrupted());
            deleted.add(recordId);
        }

        final List<Boolean> interruptedOnDelete = Collections.synchronizedList(new ArrayList<>());
    }
}
