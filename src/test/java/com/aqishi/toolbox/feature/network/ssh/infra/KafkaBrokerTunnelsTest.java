package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan;
import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan.Endpoint;
import org.apache.kafka.clients.HostResolver;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opening broker forwards from metadata and routing Kafka clients through them. */
class KafkaBrokerTunnelsTest {

    private static final KafkaBrokerTunnelPlan.BindProbe FREE = (address, port) -> true;

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    /** Records what would be forwarded instead of talking to an SSH server. */
    private static final class RecordingOpener implements KafkaBrokerTunnels.TunnelOpener {
        final List<String> opened = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        String failOn;

        @Override
        public AutoCloseable open(String remoteHost, int remotePort, String bindAddress, int localPort)
                throws Exception {
            String forward = bindAddress + ":" + localPort + "->" + remoteHost + ":" + remotePort;
            if (forward.equals(failOn)) throw new IllegalStateException("forward refused: " + forward);
            opened.add(forward);
            return () -> closed.add(forward);
        }
    }

    @Test
    void brokersSharingAPortAreForwardedAndResolvedSeparately() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        try (KafkaBrokerTunnels tunnels = KafkaBrokerTunnels.open(
                List.of(new Endpoint("broker-1", 9092), new Endpoint("broker-2", 9092)),
                true, FREE, opener, Set.of("127.0.0.1"))) {

            assertEquals(List.of("127.0.0.2:9092->broker-1:9092", "127.0.0.3:9092->broker-2:9092"),
                    opener.opened);
            KafkaTunnelSupport.Routing routing = tunnels.routing();
            assertArrayEquals(new InetAddress[]{ip("127.0.0.2")}, routing.resolve("broker-1"));
            assertArrayEquals(new InetAddress[]{ip("127.0.0.3")}, routing.resolve("BROKER-2"));
            assertArrayEquals(new InetAddress[]{ip("127.0.0.1")}, routing.resolve("127.0.0.1"),
                    "bootstrap forwards stay reachable");
        }
        assertEquals(opener.opened, opener.closed);
    }

    @Test
    void anAmbiguousClusterOpensNothing() {
        RecordingOpener opener = new RecordingOpener();
        KafkaBrokerTunnelPlan.PlanningException problem = assertThrows(
                KafkaBrokerTunnelPlan.PlanningException.class,
                () -> KafkaBrokerTunnels.open(
                        List.of(new Endpoint("broker-1", 9092), new Endpoint("broker-2", 9092)),
                        false, FREE, opener, Set.of("127.0.0.1")));
        assertEquals(KafkaBrokerTunnelPlan.Problem.DUPLICATE_PORT, problem.problem());
        assertTrue(opener.opened.isEmpty());
    }

    @Test
    void aFailingForwardClosesTheOnesAlreadyOpened() {
        RecordingOpener opener = new RecordingOpener();
        opener.failOn = "127.0.0.3:9092->broker-2:9092";
        assertThrows(IllegalStateException.class, () -> KafkaBrokerTunnels.open(
                List.of(new Endpoint("broker-1", 9092), new Endpoint("broker-2", 9092)),
                true, FREE, opener, Set.of("127.0.0.1")));
        assertEquals(List.of("127.0.0.2:9092->broker-1:9092"), opener.closed);
    }

    /**
     * Strict resolution: an unplanned host must fail rather than resolve normally — a broker
     * advertising localhost:9092 would otherwise reach a Kafka running on this machine.
     */
    @Test
    void unplannedHostsDoNotResolve() {
        KafkaTunnelSupport.Routing routing = KafkaTunnelSupport.Routing.bootstrapOnly(Set.of("127.0.0.1"));
        assertThrows(UnknownHostException.class, () -> routing.resolve("localhost"));
        assertThrows(UnknownHostException.class, () -> routing.resolve("10.0.0.5"));
        assertThrows(UnknownHostException.class, () -> routing.resolve("broker-1"));
    }

    @Test
    void theRoutingIsInstalledOnARealConsumer() throws Exception {
        KafkaBrokerTunnelPlan plan = KafkaBrokerTunnelPlan.create(
                List.of(new Endpoint("broker-1", 9092), new Endpoint("broker-2", 9092)), true, FREE);
        KafkaTunnelSupport.Routing routing = KafkaTunnelSupport.Routing.of(plan, Set.of("127.0.0.1"));
        Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            KafkaTunnelSupport.configure(consumer, routing);

            HostResolver resolver = KafkaTunnelSupport.installedResolver(consumer);
            assertNotNull(resolver);
            assertArrayEquals(new InetAddress[]{ip("127.0.0.3")}, resolver.resolve("broker-2"));
            assertThrows(UnknownHostException.class, () -> resolver.resolve("localhost"));
        }
    }

    /**
     * Discovery reads the admin client's own metadata, fed here with a fake cluster so no
     * broker is needed.
     */
    @Test
    void advertisedBrokersComeFromTheAdminClientMetadata() throws Exception {
        Properties props = new Properties();
        props.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
        props.setProperty(AdminClientConfig.RECONNECT_BACKOFF_MS_CONFIG, "10000");
        AdminClient admin = AdminClient.create(props);
        try {
            assertTrue(KafkaTunnelSupport.advertisedBrokers(admin).isEmpty(), "still bootstrapping");

            Node one = new Node(1, "broker-1", 9092);
            Node two = new Node(2, "broker-2", 9092);
            Cluster cluster = new Cluster("fake", List.of(one, two), Collections.emptyList(),
                    Collections.emptySet(), Collections.emptySet(), one);
            Field managerField = admin.getClass().getDeclaredField("metadataManager");
            managerField.setAccessible(true);
            Object manager = managerField.get(admin);
            Method update = manager.getClass().getMethod("update", Cluster.class, long.class);
            update.invoke(manager, cluster, System.currentTimeMillis());

            assertEquals(List.of(new Endpoint("broker-1", 9092), new Endpoint("broker-2", 9092)),
                    KafkaTunnelSupport.awaitAdvertisedBrokers(admin, Duration.ofSeconds(2)));
        } finally {
            admin.close(Duration.ZERO);
        }
    }

    @Test
    void endpointsSkipEmptyNodesAndDuplicates() {
        List<Endpoint> endpoints = KafkaTunnelSupport.endpointsOf(List.of(
                new Node(1, "broker-1", 9092), new Node(1, "broker-1", 9092), Node.noNode()));
        assertEquals(List.of(new Endpoint("broker-1", 9092)), endpoints);
    }
}
