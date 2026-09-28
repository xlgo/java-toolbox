package com.aqishi.toolbox.feature.security.infra.acme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provisioning and guaranteed cleanup around the ACME flow. */
class AcmeIssuanceTest {

    private static final List<String> DOMAINS = List.of("example.com", "*.example.com");

    private FakeAcmeServer server;
    private AcmeTestSupport.ManualClock clock;
    private AcmeClient client;
    private final AcmeTestSupport.FakeDns dns = new AcmeTestSupport.FakeDns();
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeAcmeServer();
        server.pollsUntilFinal = 1;
        clock = new AcmeTestSupport.ManualClock();
        client = new AcmeClient(server.directoryUrl());
        client.setPoller(clock.poller(Duration.ofMinutes(5)));
        client.init();
        client.registerAccount(AcmeTestSupport.accountKey(), "");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private AcmeIssuance issuance(ChallengeProvisioner provisioner) throws Exception {
        return new AcmeIssuance(client, AcmeTestSupport.accountKey(), provisioner, log::add);
    }

    private AcmeIssuance prepared(ChallengeProvisioner provisioner, String type) throws Exception {
        AcmeIssuance issuance = issuance(provisioner);
        issuance.prepare(client.createOrder(AcmeTestSupport.accountKey(), DOMAINS), type);
        return issuance;
    }

    private ChallengeProvisioner dnsProvisioner() {
        return ChallengeProvisioners.dnsApi(dns, null, null);
    }

    @Test
    @DisplayName("DNS-01 success: records added, certificate issued, records deleted")
    void dnsRecordsCleanedUpAfterSuccess() throws Exception {
        AcmeIssuance issuance = prepared(dnsProvisioner(), "dns-01");
        assertEquals(List.of("rec-0", "rec-1"), dns.added);
        assertEquals(List.of("_acme-challenge.example.com", "_acme-challenge.example.com"), dns.names);
        assertTrue(dns.deleted.isEmpty(), "records stay until validation is done");

        String chain = issuance.complete(AcmeTestSupport.domainKey(), DOMAINS, null);

        assertTrue(chain.contains("BEGIN CERTIFICATE"));
        assertEquals(dns.added, dns.deleted);
        assertFalse(issuance.isOpen());
        assertTrue(server.violations.isEmpty(), "violations: " + server.violations);
    }

    @Test
    @DisplayName("DNS-01 failure: invalid authorization still deletes every record")
    void dnsRecordsCleanedUpAfterFailure() throws Exception {
        server.failAuthzIndex = 1;
        AcmeIssuance issuance = prepared(dnsProvisioner(), "dns-01");

        AcmeException failure = assertThrows(AcmeException.class,
                () -> issuance.complete(AcmeTestSupport.domainKey(), DOMAINS, null));

        assertEquals(AcmeException.Reason.AUTHORIZATION_INVALID, failure.reason());
        assertEquals("*.example.com", failure.subject());
        assertEquals(0, server.finalizeCalls.get());
        assertEquals(dns.added, dns.deleted);
    }

    @Test
    @DisplayName("cancel during polling stops and deletes every record")
    void dnsRecordsCleanedUpAfterCancel() throws Exception {
        server.pollsUntilFinal = Integer.MAX_VALUE;
        AcmeIssuance issuance = prepared(dnsProvisioner(), "dns-01");
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger slices = new AtomicInteger();
        clock.onSleep = () -> {
            if (slices.incrementAndGet() == 10) {
                cancelled.set(true);
            }
        };

        AcmeException failure = assertThrows(AcmeException.class,
                () -> issuance.complete(AcmeTestSupport.domainKey(), DOMAINS, cancelled::get));

        assertEquals(AcmeException.Reason.CANCELLED, failure.reason());
        assertEquals(0, server.finalizeCalls.get());
        assertEquals(dns.added, dns.deleted);
    }

    @Test
    @DisplayName("cleanup still runs when the worker thread was interrupted")
    void cleanupSurvivesInterrupt() throws Exception {
        server.pollsUntilFinal = Integer.MAX_VALUE;
        AcmeIssuance issuance = prepared(dnsProvisioner(), "dns-01");
        clock.onSleep = () -> Thread.currentThread().interrupt();

        AcmeException failure = assertThrows(AcmeException.class,
                () -> issuance.complete(AcmeTestSupport.domainKey(), DOMAINS, null));

        assertEquals(AcmeException.Reason.CANCELLED, failure.reason());
        assertEquals(dns.added, dns.deleted);
        assertEquals(List.of(false, false), dns.interruptedOnDelete, "DNS API calls run without the interrupt");
        assertTrue(Thread.interrupted(), "interrupt status is preserved for the caller");
    }

    @Test
    @DisplayName("a failure while provisioning removes the records created so far")
    void prepareFailureCleansPartialProvisioning() throws Exception {
        dns.failOnAdd = 1;
        AcmeIssuance issuance = issuance(dnsProvisioner());
        AcmeClient.AcmeOrder order = client.createOrder(AcmeTestSupport.accountKey(), DOMAINS);

        assertThrows(IllegalStateException.class, () -> issuance.prepare(order, "dns-01"));

        assertEquals(List.of("rec-0"), dns.added);
        assertEquals(List.of("rec-0"), dns.deleted);
        assertFalse(issuance.isOpen());
    }

    @Test
    @DisplayName("abandoning a prepared order (cancel / clear / new step 1) deletes its records once")
    void abandonedOrderIsCleanedUpOnce() throws Exception {
        AcmeIssuance issuance = prepared(dnsProvisioner(), "dns-01");

        issuance.cleanup();
        issuance.cleanup();

        assertEquals(dns.added, dns.deleted);
        assertThrows(AcmeException.class, () -> issuance.complete(AcmeTestSupport.domainKey(), DOMAINS, null));
        assertEquals(dns.added, dns.deleted, "no second deletion");
    }

    @Test
    @DisplayName("HTTP-01 web root: token file written, then removed after issuance")
    void webRootFilesRemoved(@TempDir Path webRoot) throws Exception {
        AcmeIssuance issuance = prepared(ChallengeProvisioners.webRoot(webRoot.toString()), "http-01");
        File token = webRoot.resolve(".well-known/acme-challenge/http-token-0").toFile();
        assertTrue(token.isFile(), "token file written");
        assertTrue(Files.readString(token.toPath()).startsWith("http-token-0."));

        String chain = issuance.complete(AcmeTestSupport.domainKey(), DOMAINS, null);

        assertTrue(chain.contains("BEGIN CERTIFICATE"));
        assertTrue(server.events.contains("trigger 0"), "http-01 challenge triggered: " + server.events);
        assertFalse(token.exists(), "token file removed");
        assertFalse(webRoot.resolve(".well-known/acme-challenge/http-token-1").toFile().exists());
    }

    @Test
    @DisplayName("DNS propagation check waits until the TXT value resolves")
    void propagationCheckWaitsForTxt() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        List<String> published = Collections.synchronizedList(new ArrayList<>());
        DnsProvider provider = new DnsProvider() {
            @Override
            public String addTxtRecord(String domain, String name, String value) {
                published.add(value);
                return "id-" + published.size();
            }

            @Override
            public void deleteTxtRecord(String domain, String recordId) {
            }
        };
        DnsTxtLookup lookup = name -> lookups.incrementAndGet() < 3 ? List.of() : new ArrayList<>(published);
        AcmeTestSupport.ManualClock dnsClock = new AcmeTestSupport.ManualClock();
        ChallengeProvisioner provisioner = ChallengeProvisioners.dnsApi(provider, lookup,
                dnsClock.poller(Duration.ofMinutes(2)));
        AcmeIssuance issuance = prepared(provisioner, "dns-01");

        issuance.complete(AcmeTestSupport.domainKey(), DOMAINS, null);

        assertTrue(lookups.get() >= 3, "looked up until visible");
        assertEquals(Duration.ofSeconds(6), dnsClock.totalSlept(), "two waits at the 3 s default");
    }

    @Test
    @DisplayName("DNS propagation check gives up after its timeout and lets the CA try")
    void propagationCheckTimesOutSoftly() throws Exception {
        AcmeTestSupport.ManualClock dnsClock = new AcmeTestSupport.ManualClock();
        ChallengeProvisioner provisioner = ChallengeProvisioners.manualDns(name -> List.of(),
                dnsClock.poller(Duration.ofSeconds(10)));
        AcmeIssuance issuance = prepared(provisioner, "dns-01");

        String chain = issuance.complete(AcmeTestSupport.domainKey(), DOMAINS, null);

        assertTrue(chain.contains("BEGIN CERTIFICATE"), "issuance continued after the soft timeout");
        assertTrue(log.contains(com.aqishi.toolbox.util.I18n.get("tool.cert.acme.log.propagationTimeout")),
                "warned: " + log);
        assertEquals(Duration.ofSeconds(9), dnsClock.totalSlept(), "gave up within the 10 s budget");
    }
}
