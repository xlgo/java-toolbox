package com.aqishi.toolbox.feature.network.ssh.domain;

import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan.BindProbe;
import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan.Endpoint;
import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan.PlanningException;
import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan.Problem;
import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan.Route;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Broker to local address mapping. The reported defect was a client that reached whichever
 * broker happened to own 127.0.0.1:9092, so the plan must give brokers that share a port on
 * different hosts distinct local addresses and must refuse the mapping when it cannot.
 */
class KafkaBrokerTunnelPlanTest {

    private static final BindProbe FREE = (address, port) -> true;

    private static Endpoint endpoint(String host, int port) {
        return new Endpoint(host, port);
    }

    /** Reported defect: broker-1:9092 and broker-2:9092 used to collide on 127.0.0.1:9092. */
    @Test
    void samePortOnDifferentHostsGetsDistinctLocalAddresses() throws Exception {
        KafkaBrokerTunnelPlan plan = KafkaBrokerTunnelPlan.create(
                List.of(endpoint("broker-1", 9092), endpoint("broker-2", 9092),
                        endpoint("broker-3", 9092)),
                true, FREE);

        assertEquals(3, plan.routes().size());
        // 127.0.0.1 stays reserved for the bootstrap forwards and a broker advertising it.
        assertEquals("127.0.0.2", plan.localAddressFor("broker-1"));
        assertEquals("127.0.0.3", plan.localAddressFor("broker-2"));
        assertEquals("127.0.0.4", plan.localAddressFor("broker-3"));
        for (Route route : plan.routes()) {
            assertEquals(9092, route.localPort(), "the advertised port is never rewritten");
        }
        assertEquals("127.0.0.3:9092", describe(plan, "broker-2", 9092));
    }

    @Test
    void oneLoopbackAddressIsEnoughWhenNoPortIsShared() throws Exception {
        KafkaBrokerTunnelPlan plan = KafkaBrokerTunnelPlan.create(
                List.of(endpoint("broker-1", 9092), endpoint("broker-2", 9093)),
                false, FREE);

        assertEquals("127.0.0.1", plan.localAddressFor("broker-1"));
        assertEquals("127.0.0.1", plan.localAddressFor("broker-2"));
        assertEquals(2, plan.routes().size());
    }

    /** macOS without lo0 aliases: only 127.0.0.1 binds, so the shared port is ambiguous. */
    @Test
    void sharedPortWithASingleLoopbackAddressIsRejected() {
        PlanningException problem = assertThrows(PlanningException.class,
                () -> KafkaBrokerTunnelPlan.create(
                        List.of(endpoint("broker-1", 9092), endpoint("broker-2", 9092)),
                        false, FREE));

        assertEquals(Problem.DUPLICATE_PORT, problem.problem());
        assertEquals(2, problem.endpoints().size());
        assertTrue(problem.endpoints().toString().contains("broker-1:9092"));
        assertTrue(problem.endpoints().toString().contains("broker-2:9092"));
    }

    /** A host that advertises 127.0.0.1 must keep it: the client cannot tell it from the tunnel. */
    @Test
    void aLoopbackAdvertisedHostKeepsThePrimaryAddress() throws Exception {
        KafkaBrokerTunnelPlan plan = KafkaBrokerTunnelPlan.create(
                List.of(endpoint("127.0.0.1", 9092), endpoint("broker-2", 9092)), true, FREE);

        assertEquals("127.0.0.1", plan.localAddressFor("127.0.0.1"));
        assertEquals("127.0.0.2", plan.localAddressFor("broker-2"));
    }

    /** A taken port is not moved to another port: the client would dial a dead port. */
    @Test
    void aTakenPrimaryPortFailsInsteadOfMovingTheRoute() {
        BindProbe taken = (address, port) -> !("127.0.0.1".equals(address) && port == 9092);

        PlanningException problem = assertThrows(PlanningException.class,
                () -> KafkaBrokerTunnelPlan.create(List.of(endpoint("broker-1", 9092)), false, taken));

        assertEquals(Problem.LOCAL_PORT_IN_USE, problem.problem());
        assertEquals("broker-1:9092", problem.endpoints().get(0).toString());
    }

    /** A busy port on the first alias is skipped for the next one. */
    @Test
    void aTakenAliasIsSkippedForTheNextAddress() throws Exception {
        BindProbe busy = (address, port) -> !"127.0.0.2".equals(address);

        KafkaBrokerTunnelPlan plan = KafkaBrokerTunnelPlan.create(
                List.of(endpoint("broker-1", 9092), endpoint("broker-2", 9092)), true, busy);

        assertEquals("127.0.0.3", plan.localAddressFor("broker-1"));
        assertEquals("127.0.0.4", plan.localAddressFor("broker-2"));
    }

    @Test
    void everyAliasTakenReportsTheBrokersThatCouldNotBePlaced() {
        BindProbe busy = (address, port) -> "127.0.0.1".equals(address);

        PlanningException problem = assertThrows(PlanningException.class,
                () -> KafkaBrokerTunnelPlan.create(
                        List.of(endpoint("broker-1", 9092), endpoint("broker-2", 9092)), true, busy));

        assertEquals(Problem.LOCAL_PORT_IN_USE, problem.problem());
        assertEquals("broker-1:9092", problem.endpoints().get(0).toString());
    }

    @Test
    void duplicateMetadataEntriesCollapseIntoOneRoute() throws Exception {
        KafkaBrokerTunnelPlan plan = KafkaBrokerTunnelPlan.create(
                List.of(endpoint("broker-1", 9092), endpoint("broker-1", 9092), endpoint("BROKER-1", 9092)),
                true, FREE);

        assertEquals(1, plan.routes().size());
        assertEquals("broker-1", plan.routes().get(0).advertised().host());
    }

    @Test
    void hostsAreNormalizedAndAnIpv6LiteralIsUnwrapped() {
        assertEquals("broker-1", KafkaBrokerTunnelPlan.normalizeHost(" Broker-1 "));
        assertEquals("::1", KafkaBrokerTunnelPlan.normalizeHost("[::1]"));
        assertEquals(endpoint("broker-1", 9092), endpoint("BROKER-1", 9092));
    }

    @Test
    void anEmptyMetadataListPlansNothing() throws Exception {
        KafkaBrokerTunnelPlan plan = KafkaBrokerTunnelPlan.create(List.of(), true, FREE);
        assertTrue(plan.routes().isEmpty());
    }

    private static String describe(KafkaBrokerTunnelPlan plan, String host, int port) {
        for (Route route : plan.routes()) {
            if (route.advertised().host().equals(host) && route.advertised().port() == port) {
                return route.localAddress() + ":" + route.localPort();
            }
        }
        throw new AssertionError("no route for " + host + ":" + port);
    }

    /** The resolver lookup must agree with the plan for every advertised host. */
    @Test
    void everyAdvertisedHostHasALocalAddress() throws Exception {
        List<Endpoint> endpoints = List.of(endpoint("broker-1", 9092), endpoint("broker-2", 9092),
                endpoint("broker-3", 9094));

        KafkaBrokerTunnelPlan plan = KafkaBrokerTunnelPlan.create(endpoints, true, FREE);
        for (Endpoint endpoint : endpoints) {
            assertNotNull(plan.localAddressFor(endpoint.host()), endpoint.toString());
            assertNotNull(plan.localAddressFor(endpoint.host().toUpperCase(java.util.Locale.ROOT)));
        }
        assertEquals(3, plan.hostAddresses().size());
    }
}
