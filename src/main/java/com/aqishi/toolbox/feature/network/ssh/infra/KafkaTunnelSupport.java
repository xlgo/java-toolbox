package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan;
import org.apache.kafka.clients.HostResolver;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes Kafka clients reach each broker through its own SSH forward.
 *
 * <p>A Kafka client uses the bootstrap address once and then connects to the addresses
 * advertised in Metadata responses. kafka-clients has no public hook to rewrite them, so this
 * class installs an internal {@link HostResolver} by reflection (the only version-specific
 * part, kept here). A resolver can only change the IP address a host name resolves to, never
 * the port, which is why {@link KafkaBrokerTunnelPlan} gives every advertised host its own
 * loopback address and every forward the advertised port.</p>
 *
 * <p>The resolver is <em>strict</em>: a host that is neither routed through a planned forward
 * nor a bootstrap literal fails with {@link UnknownHostException}. The previous resolver sent
 * every private or unknown address to 127.0.0.1, which silently reached whichever broker (or
 * local process) listened there.</p>
 */
public final class KafkaTunnelSupport {

    private static final String NETWORK_CLIENT_CLASS = "org.apache.kafka.clients.NetworkClient";
    private static final String ALIAS_PROBE_ADDRESS = "127.0.0.2";
    private static volatile Boolean multipleLoopback;

    private KafkaTunnelSupport() {
    }

    /**
     * Host name to local address mapping handed to Kafka clients. Immutable; shared between
     * the admin client and the short-lived consumers and producers of one connection.
     */
    public static final class Routing {
        private final Map<String, InetAddress> routes;
        private final Set<String> passthrough;

        private Routing(Map<String, InetAddress> routes, Set<String> passthrough) {
            this.routes = Collections.unmodifiableMap(routes);
            this.passthrough = Collections.unmodifiableSet(passthrough);
        }

        /** Only the given literal addresses (the local ends of bootstrap forwards) resolve. */
        public static Routing bootstrapOnly(Collection<String> bootstrapLocalHosts) {
            return new Routing(new LinkedHashMap<>(), normalized(bootstrapLocalHosts));
        }

        /** Advertised brokers resolve to their planned local address; bootstrap literals as-is. */
        public static Routing of(KafkaBrokerTunnelPlan plan, Collection<String> bootstrapLocalHosts)
                throws UnknownHostException {
            Map<String, InetAddress> routes = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : plan.hostAddresses().entrySet()) {
                routes.put(entry.getKey(), InetAddress.getByName(entry.getValue()));
            }
            return new Routing(routes, normalized(bootstrapLocalHosts));
        }

        /** What a Kafka client connecting to {@code host} will actually dial. */
        public InetAddress[] resolve(String host) throws UnknownHostException {
            String key = KafkaBrokerTunnelPlan.normalizeHost(host);
            InetAddress routed = routes.get(key);
            if (routed != null) return new InetAddress[]{routed};
            if (passthrough.contains(key)) return new InetAddress[]{InetAddress.getByName(key)};
            throw new UnknownHostException(host + " is not routed through the SSH tunnel");
        }

        public Map<String, InetAddress> routes() {
            return routes;
        }

        private static Set<String> normalized(Collection<String> hosts) {
            Set<String> result = new LinkedHashSet<>();
            if (hosts != null) {
                for (String host : hosts) {
                    String key = KafkaBrokerTunnelPlan.normalizeHost(host);
                    if (!key.isEmpty()) result.add(key);
                }
            }
            return result;
        }
    }

    /**
     * Installs {@code routing} on an AdminClient, Consumer or Producer, including connection
     * states the client created before (their cached addresses are dropped).
     */
    public static void configure(Object kafkaClient, Routing routing) throws Exception {
        if (kafkaClient == null) {
            throw new IllegalArgumentException("Kafka 客户端不能为空");
        }
        if (routing == null) throw new IllegalArgumentException("routing");

        Object networkClient = findNetworkClient(kafkaClient);
        if (networkClient == null) {
            throw new IllegalStateException("无法访问 Kafka 客户端的 NetworkClient，无法配置 SSH 隧道地址映射");
        }

        Object connectionStates = readField(networkClient, "connectionStates");
        if (connectionStates == null) {
            throw new IllegalStateException("Kafka NetworkClient 缺少连接状态，无法配置 SSH 隧道地址映射");
        }

        HostResolver resolver = routing::resolve;
        setField(findField(connectionStates.getClass(), "hostResolver"), connectionStates, resolver);

        Object states = readField(connectionStates, "nodeState");
        if (!(states instanceof Map)) return;
        for (Object nodeState : ((Map<?, ?>) states).values()) {
            if (nodeState == null) continue;
            // NodeConnectionState keeps its own resolver and may have cached addresses
            // resolved before the routing was known.
            setField(findField(nodeState.getClass(), "hostResolver"), nodeState, resolver);
            clearCachedAddresses(nodeState);
        }
    }

    /** The resolver currently installed on a Kafka client; for tests and diagnostics. */
    static HostResolver installedResolver(Object kafkaClient) throws Exception {
        Object networkClient = findNetworkClient(kafkaClient);
        if (networkClient == null) return null;
        Object connectionStates = readField(networkClient, "connectionStates");
        return connectionStates == null ? null : (HostResolver) readField(connectionStates, "hostResolver");
    }

    /**
     * Waits until an AdminClient has received its first Metadata response (sent to the
     * bootstrap forward — no other connection is needed) and returns every broker it lists.
     * Returns an empty list when the metadata did not arrive in time or this kafka-clients
     * version keeps it elsewhere; callers then fall back to a Metadata request of their own.
     */
    public static List<KafkaBrokerTunnelPlan.Endpoint> awaitAdvertisedBrokers(Object adminClient,
                                                                             Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        do {
            List<KafkaBrokerTunnelPlan.Endpoint> brokers = advertisedBrokers(adminClient);
            if (brokers == null) return Collections.emptyList();
            if (!brokers.isEmpty()) return brokers;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return Collections.emptyList();
    }

    /** Brokers of the admin client's current metadata; empty while it is still bootstrapping,
     * null when the metadata cannot be read by reflection. */
    static List<KafkaBrokerTunnelPlan.Endpoint> advertisedBrokers(Object adminClient) {
        try {
            Object manager = readField(adminClient, "metadataManager");
            Object cluster = manager == null ? null : readField(manager, "cluster");
            if (!(cluster instanceof Cluster)) return null;
            if (((Cluster) cluster).isBootstrapConfigured()) return Collections.emptyList();
            return endpointsOf(((Cluster) cluster).nodes());
        } catch (Exception | LinkageError unsupported) {
            return null;
        }
    }

    /**
     * Distinct broker endpoints sorted by host and port. Kafka shuffles {@code Cluster.nodes()},
     * so sorting keeps the local address of each broker stable across reconnects.
     */
    public static List<KafkaBrokerTunnelPlan.Endpoint> endpointsOf(Collection<Node> nodes) {
        Set<KafkaBrokerTunnelPlan.Endpoint> result = new LinkedHashSet<>();
        if (nodes != null) {
            for (Node node : nodes) {
                if (node == null || node.isEmpty() || node.host() == null) continue;
                result.add(new KafkaBrokerTunnelPlan.Endpoint(node.host(), node.port()));
            }
        }
        List<KafkaBrokerTunnelPlan.Endpoint> sorted = new ArrayList<>(result);
        sorted.sort(java.util.Comparator.comparing(KafkaBrokerTunnelPlan.Endpoint::host)
                .thenComparingInt(KafkaBrokerTunnelPlan.Endpoint::port));
        return sorted;
    }

    /**
     * Whether addresses of 127.0.0.0/8 other than 127.0.0.1 can be bound. True on Linux and
     * Windows; false on macOS unless {@code lo0} aliases were added. Probed once.
     */
    public static boolean supportsMultipleLoopbackAddresses() {
        Boolean cached = multipleLoopback;
        if (cached == null) {
            cached = isBindable(ALIAS_PROBE_ADDRESS, 0);
            multipleLoopback = cached;
        }
        return cached;
    }

    /** Probe used for planning: a short-lived listen on {@code address:port}. */
    public static boolean isBindable(String address, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName(address), port), 1);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static Object findNetworkClient(Object root) throws IllegalAccessException {
        Deque<Object> pending = new ArrayDeque<>();
        Map<Object, Boolean> visited = new IdentityHashMap<>();
        pending.add(root);

        while (!pending.isEmpty()) {
            Object current = pending.removeFirst();
            if (current == null || visited.put(current, Boolean.TRUE) != null) continue;
            if (NETWORK_CLIENT_CLASS.equals(current.getClass().getName())) return current;

            for (String fieldName : new String[]{"client", "sender"}) {
                Field field = findFieldOrNull(current.getClass(), fieldName);
                if (field == null || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(current);
                    if (value != null) pending.addLast(value);
                } catch (RuntimeException ignored) {
                    // Try the next known Kafka client link.
                }
            }
        }
        return null;
    }

    private static Object readField(Object target, String name) throws IllegalAccessException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void clearCachedAddresses(Object nodeState) throws IllegalAccessException {
        Field addresses = findFieldOrNull(nodeState.getClass(), "addresses");
        if (addresses != null) {
            addresses.setAccessible(true);
            // Kafka's 3.x state machine expects an empty list here. A null
            // value makes its reconnect path call isEmpty() and fail.
            addresses.set(nodeState, Collections.emptyList());
        }
        Field addressIndex = findFieldOrNull(nodeState.getClass(), "addressIndex");
        if (addressIndex != null) {
            addressIndex.setAccessible(true);
            addressIndex.setInt(nodeState, 0);
        }
    }

    private static void setField(Field field, Object target, Object value) throws IllegalAccessException {
        if (field == null) throw new IllegalStateException("Kafka 客户端内部字段不存在");
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) {
        Field field = findFieldOrNull(type, name);
        if (field == null) {
            throw new IllegalStateException("Kafka 客户端内部字段不存在: " + type.getName() + "." + name);
        }
        return field;
    }

    private static Field findFieldOrNull(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
