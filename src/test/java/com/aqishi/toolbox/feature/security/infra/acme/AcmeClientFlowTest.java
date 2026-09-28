package com.aqishi.toolbox.feature.security.infra.acme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end RFC 8555 flow against the fake server: the order of the calls is the point. */
class AcmeClientFlowTest {

    private FakeAcmeServer server;
    private AcmeTestSupport.ManualClock clock;
    private AcmeClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeAcmeServer();
        clock = new AcmeTestSupport.ManualClock();
        client = newClient();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private AcmeClient newClient() throws Exception {
        AcmeClient c = new AcmeClient(server.directoryUrl());
        c.setPoller(clock.poller(Duration.ofMinutes(5)));
        c.init();
        c.registerAccount(AcmeTestSupport.accountKey(), "admin@example.com");
        assertEquals(server.directoryUrl().replace("/directory", "/acct/1"), c.getAccountKid());
        return c;
    }

    private AcmeClient.AcmeOrder order() throws Exception {
        return order(List.of("example.com"));
    }

    private AcmeClient.AcmeOrder order(List<String> domains) throws Exception {
        AcmeClient.AcmeOrder order = client.createOrder(AcmeTestSupport.accountKey(), domains);
        assertTrue(server.violations.isEmpty(), "JWS violations: " + server.violations);
        return order;
    }

    private List<AcmeClient.AcmeChallenge> dnsChallenges(AcmeClient.AcmeOrder order) throws Exception {
        return client.getChallenges(AcmeTestSupport.accountKey(), order, "dns-01");
    }

    @Test
    @DisplayName("challenge -> authorization valid -> finalize -> order valid -> certificate")
    void happyPathWaitsForAuthorizationsBeforeFinalize() throws Exception {
        server.pollsUntilFinal = 3;
        List<String> domains = List.of("example.com", "*.example.com");
        AcmeClient.AcmeOrder order = order(domains);
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        assertEquals(2, challenges.size());
        assertEquals("example.com", challenges.get(0).domain);
        assertEquals("*.example.com", challenges.get(1).domain, "wildcard keeps its star");
        assertTrue(challenges.get(1).wildcard);
        assertEquals("_acme-challenge.example.com", challenges.get(1).dnsTxtRecordName);
        assertFalse(challenges.get(1).dnsTxtRecordValue.isEmpty());
        assertTrue(challenges.get(0).challengeUrl.endsWith("/chall/0"), "dns-01 is the preferred type");
        assertTrue(challenges.get(1).authorizationUrl.endsWith("/authz/1"));

        String chain = client.issueCertificate(AcmeTestSupport.accountKey(), AcmeTestSupport.domainKey(),
                domains, order, challenges, null);

        assertTrue(chain.contains("BEGIN CERTIFICATE"), "chain downloaded");
        assertEquals(1, server.finalizeCalls.get(), "finalize exactly once");
        assertTrue(server.violations.isEmpty(), "JWS violations: " + server.violations);

        // Protocol order: trigger, authz polled until valid, then finalize, then certificate.
        List<String> events = server.events;
        int finalize = events.indexOf("finalize");
        for (int i = 0; i < 2; i++) {
            int trigger = events.indexOf("trigger " + i);
            int valid = events.indexOf("authz " + i + " valid");
            assertTrue(trigger >= 0 && trigger < valid, "authz " + i + " polled after trigger: " + events);
            assertTrue(valid < finalize, "authz " + i + " valid before finalize: " + events);
            assertEquals(3, events.subList(trigger, valid).stream()
                    .filter(("authz " + i + " pending")::equals).count(), "polled while pending: " + events);
        }
        assertTrue(finalize < events.indexOf("certificate"), "certificate after finalize: " + events);
        // 2 authorizations x 3 pending polls x Retry-After 2s, plus 1 processing poll x 1s.
        assertEquals(Duration.ofSeconds(13), clock.totalSlept());
        assertTrue(clock.sleeps.stream().allMatch(d -> d.toMillis() <= 250), "sleeps are sliced for cancel");
    }

    @Test
    @DisplayName("an absurd Retry-After is clamped to the maximum delay")
    void clampsHugeRetryAfterWhenPolling() throws Exception {
        server.pollsUntilFinal = 1;
        server.authzRetryAfter = "999";
        server.orderRetryAfter = null;
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        client.issueCertificate(AcmeTestSupport.accountKey(), AcmeTestSupport.domainKey(),
                List.of("example.com"), order, challenges, null);

        // authz: one pending poll, 999 s clamped to 30 s; order: one processing poll, default 3 s.
        assertEquals(Duration.ofSeconds(33), clock.totalSlept());
        assertTrue(server.violations.isEmpty(), "JWS violations: " + server.violations);
    }

    @Test
    @DisplayName("Retry-After as HTTP date and as absurd seconds are clamped")
    void clampsRetryAfter() {
        java.time.Instant now = java.time.Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(Duration.ofSeconds(10), RetryAfter.parse("10", now));
        assertEquals(Duration.ofSeconds(30),
                RetryAfter.parse("Thu, 01 Jan 2026 00:00:30 GMT", now));
        assertEquals(Duration.ZERO, RetryAfter.parse("Wed, 01 Jan 2025 00:00:00 GMT", now));
        assertNull(RetryAfter.parse("whenever", now));
        assertNull(RetryAfter.parse(null, now));
        assertEquals(Duration.ofSeconds(3), RetryAfter.clamp(null, Duration.ofSeconds(3),
                Duration.ofSeconds(1), Duration.ofSeconds(30)));
        assertEquals(Duration.ofSeconds(1), RetryAfter.clamp(Duration.ofMillis(10), Duration.ofSeconds(3),
                Duration.ofSeconds(1), Duration.ofSeconds(30)));
        assertEquals(Duration.ofSeconds(30), RetryAfter.clamp(Duration.ofHours(3), Duration.ofSeconds(3),
                Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("invalid authorization fails with the problem detail and never finalizes")
    void invalidAuthorizationFailsWithoutFinalize() throws Exception {
        server.pollsUntilFinal = 1;
        server.failAuthzIndex = 0;
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        AcmeException failure = assertThrows(AcmeException.class, () -> client.issueCertificate(
                AcmeTestSupport.accountKey(), AcmeTestSupport.domainKey(), List.of("example.com"),
                order, challenges, null));

        assertEquals(AcmeException.Reason.AUTHORIZATION_INVALID, failure.reason());
        assertNotNull(failure.problem());
        assertTrue(failure.problem().is("unauthorized"));
        assertTrue(failure.getMessage().contains("Incorrect TXT record found at _acme-challenge.example.com"),
                "detail is reported: " + failure.getMessage());
        assertEquals(0, server.finalizeCalls.get(), "must not finalize");
    }

    @Test
    @DisplayName("subproblems of the authorization error are reported too")
    void reportsSubproblems() throws Exception {
        server.pollsUntilFinal = 1;
        server.failAuthzIndex = 0;
        server.failError = "{\"type\":\"urn:ietf:params:acme:error:unauthorized\","
                + "\"detail\":\"Some authorizations failed\",\"subproblems\":[{\"type\":"
                + "\"urn:ietf:params:acme:error:dns\",\"detail\":\"no TXT record found\","
                + "\"identifier\":{\"type\":\"dns\",\"value\":\"example.com\"}}]}";
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        AcmeException failure = assertThrows(AcmeException.class, () -> client.issueCertificate(
                AcmeTestSupport.accountKey(), AcmeTestSupport.domainKey(), List.of("example.com"),
                order, challenges, null));

        assertEquals(1, failure.problem().subproblems().size());
        assertTrue(failure.problem().describe().contains("example.com: dns - no TXT record found"),
                "subproblem rendered: " + failure.problem().describe());
        assertEquals(0, server.finalizeCalls.get());
    }

    @Test
    @DisplayName("order processing -> valid, with Retry-After, then certificate")
    void waitsForOrderToBecomeValid() throws Exception {
        server.pollsUntilFinal = 1;
        server.orderProcessingPolls = 2;
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        String chain = client.issueCertificate(AcmeTestSupport.accountKey(), AcmeTestSupport.domainKey(),
                List.of("example.com"), order, challenges, null);

        assertTrue(chain.contains("BEGIN CERTIFICATE"));
        assertEquals("valid", order.status);
        assertNotNull(order.certificateUrl);
        // authz: one pending poll x 2 s; order: two processing polls x Retry-After 1 s.
        assertEquals(Duration.ofSeconds(4), clock.totalSlept());
        assertEquals(2, server.events.stream().filter("order processing"::equals).count(),
                "polled while processing: " + server.events);
        assertTrue(server.violations.isEmpty(), "JWS violations: " + server.violations);
    }

    @Test
    @DisplayName("an order turning invalid fails with the order error")
    void invalidOrderFails() throws Exception {
        server.pollsUntilFinal = 1;
        server.orderError = "{\"type\":\"urn:ietf:params:acme:error:rejectedIdentifier\","
                + "\"detail\":\"example.com is not allowed\"}";
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        AcmeException failure = assertThrows(AcmeException.class, () -> client.issueCertificate(
                AcmeTestSupport.accountKey(), AcmeTestSupport.domainKey(), List.of("example.com"),
                order, challenges, null));

        assertEquals(AcmeException.Reason.ORDER_INVALID, failure.reason());
        assertTrue(failure.getMessage().contains("example.com is not allowed"), failure.getMessage());
    }

    @Test
    @DisplayName("badNonce is retried once with a fresh nonce")
    void retriesBadNonceOnce() throws Exception {
        server.badNoncePath = "/new-order";
        server.badNonceCount = 1;

        AcmeClient.AcmeOrder order = order();

        assertNotNull(order.orderUrl, "the retried newOrder succeeded");
        assertEquals(1, server.events.stream().filter(e -> e.startsWith("badNonce")).count());
        assertEquals(0, server.badNonceCount);
        assertTrue(server.violations.isEmpty(), "violations: " + server.violations);
    }

    @Test
    @DisplayName("a permanent badNonce is reported, not retried forever")
    void givesUpOnRepeatedBadNonce() throws Exception {
        server.badNoncePath = "/new-order";
        server.badNonceCount = 5;

        AcmeException failure = assertThrows(AcmeException.class,
                () -> client.createOrder(AcmeTestSupport.accountKey(), List.of("example.com")));

        assertTrue(failure.isProblem("badNonce"), "reported as badNonce: " + failure.getMessage());
    }

    @Test
    @DisplayName("rateLimited carries the problem type and the server's Retry-After")
    void reportsRateLimited() throws Exception {
        server.rateLimitRetryAfter = "3600";

        AcmeException failure = assertThrows(AcmeException.class,
                () -> client.createOrder(AcmeTestSupport.accountKey(), List.of("example.com")));

        assertTrue(failure.isProblem("rateLimited"));
        assertTrue(failure.getMessage().contains("too many certificates"), failure.getMessage());
        assertEquals(Duration.ofHours(1), failure.retryAfter());
    }

    @Test
    @DisplayName("an authorization that is already valid is not provisioned again")
    void skipsAlreadyValidAuthorizations() throws Exception {
        server.preValid = true;
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        assertTrue(challenges.get(0).isAlreadyValid());
        String chain = client.issueCertificate(AcmeTestSupport.accountKey(), AcmeTestSupport.domainKey(),
                List.of("example.com"), order, challenges, null);

        assertTrue(chain.contains("BEGIN CERTIFICATE"));
        assertFalse(server.events.contains("trigger 0"), "no challenge triggered: " + server.events);
        assertEquals(1, server.finalizeCalls.get());
    }

    @Test
    @DisplayName("polling stops with TIMEOUT when the authorization never becomes valid")
    void timesOutWhileWaiting() throws Exception {
        server.pollsUntilFinal = Integer.MAX_VALUE;
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        AcmeException failure = assertThrows(AcmeException.class, () -> client.validateAuthorizations(
                AcmeTestSupport.accountKey(), challenges, null));

        assertEquals(AcmeException.Reason.TIMEOUT, failure.reason());
        assertNotNull(failure.getMessage());
        assertTrue(clock.totalSlept().compareTo(Duration.ofMinutes(5)) >= 0, "waited the full timeout");
    }

    @Test
    @DisplayName("cancelling stops the poll and reports CANCELLED")
    void cancellationStopsPolling() throws Exception {
        server.pollsUntilFinal = Integer.MAX_VALUE;
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        clock.onSleep = () -> cancelled.set(true);
        int authzBefore = server.events.stream().filter("authz 0 pending"::equals).toList().size();

        AcmeException failure = assertThrows(AcmeException.class,
                () -> client.validateAuthorizations(AcmeTestSupport.accountKey(), challenges, cancelled::get));

        assertEquals(AcmeException.Reason.CANCELLED, failure.reason());
        int authzAfter = server.events.stream().filter("authz 0 pending"::equals).toList().size();
        assertTrue(authzAfter - authzBefore <= 2, "at most one more poll after cancelling: " + server.events);
        assertEquals(0, server.finalizeCalls.get());
    }

    @Test
    @DisplayName("an interrupted worker stops as cancelled")
    void interruptionCancels() throws Exception {
        server.pollsUntilFinal = Integer.MAX_VALUE;
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        clock.onSleep = () -> Thread.currentThread().interrupt();
        AcmeException failure = assertThrows(AcmeException.class,
                () -> client.validateAuthorizations(AcmeTestSupport.accountKey(), challenges, null));

        assertEquals(AcmeException.Reason.CANCELLED, failure.reason());
        assertTrue(Thread.interrupted(), "test cleans the interrupt flag");
    }

    @Test
    @DisplayName("finalize is refused while the order is still pending")
    void finalizeWaitsForReady() throws Exception {
        server.pollsUntilFinal = 2;
        AcmeClient.AcmeOrder order = order();
        List<AcmeClient.AcmeChallenge> challenges = dnsChallenges(order);

        // Skip the authorization step on purpose: finalize must wait for "ready" itself.
        assertEquals(0, server.finalizeCalls.get());
        AcmeException failure = assertThrows(AcmeException.class, () -> client.finalizeOrder(
                AcmeTestSupport.accountKey(), AcmeTestSupport.domainKey(), List.of("example.com"),
                order, null));

        assertEquals(AcmeException.Reason.TIMEOUT, failure.reason());
        assertEquals(0, server.finalizeCalls.get(), "never finalized a pending order");
        assertTrue(server.violations.isEmpty(), "violations: " + server.violations);
    }
}
