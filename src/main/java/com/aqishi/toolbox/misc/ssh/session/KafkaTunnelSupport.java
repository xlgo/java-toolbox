package com.aqishi.toolbox.misc.ssh.session;

import org.apache.kafka.clients.DefaultHostResolver;
import org.apache.kafka.clients.HostResolver;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Makes Kafka clients use the local side of an SSH forward for broker addresses
 * returned in Metadata responses.
 *
 * <p>Kafka's public configuration accepts a bootstrap address, but each client
 * subsequently connects to the broker addresses advertised by Kafka. A local
 * port forward therefore needs a resolver that maps those advertised hosts to
 * loopback. Kafka does not expose that resolver on the public client APIs, so
 * this class keeps the small version-specific reflection boundary in one place.
 */
public final class KafkaTunnelSupport {

    private static final String NETWORK_CLIENT_CLASS = "org.apache.kafka.clients.NetworkClient";
    private static final InetAddress LOOPBACK = loopbackAddress();

    private KafkaTunnelSupport() {
    }

    /**
     * Installs a tunnel-aware host resolver on an AdminClient, Consumer or
     * Producer. The supplied hosts are the broker host names returned by
     * Kafka Metadata. Private-network addresses are also mapped automatically,
     * which covers brokers that advertise a literal RFC-1918 address.
     */
    public static void configure(Object kafkaClient, Collection<String> brokerHosts) throws Exception {
        if (kafkaClient == null) {
            throw new IllegalArgumentException("Kafka 客户端不能为空");
        }

        Object networkClient = findNetworkClient(kafkaClient);
        if (networkClient == null) {
            throw new IllegalStateException("无法访问 Kafka 客户端的 NetworkClient，无法配置 SSH 隧道地址映射");
        }

        Object connectionStates = readField(networkClient, "connectionStates");
        if (connectionStates == null) {
            throw new IllegalStateException("Kafka NetworkClient 缺少连接状态，无法配置 SSH 隧道地址映射");
        }

        Set<String> knownHosts = normalizeHosts(brokerHosts);
        HostResolver resolver = new TunnelHostResolver(knownHosts);
        Field resolverField = findField(connectionStates.getClass(), "hostResolver");
        setField(resolverField, connectionStates, resolver);

        Object states = readField(connectionStates, "nodeState");
        if (!(states instanceof Map)) return;

        for (Map.Entry<?, ?> entry : ((Map<?, ?>) states).entrySet()) {
            Object nodeState = entry.getValue();
            if (nodeState == null) continue;

            String host = stringField(nodeState, "host");
            if (!shouldMapHost(host, knownHosts)) continue;

            // NodeConnectionState keeps its own resolver and may have cached
            // addresses from before the tunnel was installed.
            setField(findField(nodeState.getClass(), "hostResolver"), nodeState, resolver);
            clearCachedAddresses(nodeState);
        }
    }

    /** Returns a normalized, immutable set for callers that need stable host mappings. */
    public static Set<String> normalizeHosts(Collection<String> hosts) {
        if (hosts == null || hosts.isEmpty()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (String host : hosts) {
            if (host != null && !host.trim().isEmpty()) {
                result.add(normalizeHost(host));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    static boolean shouldMapHost(String host, Set<String> knownHosts) {
        if (host == null || host.trim().isEmpty()) return false;
        String normalized = normalizeHost(host);
        if (knownHosts != null && knownHosts.contains(normalized)) return true;
        try {
            InetAddress address = InetAddress.getByName(host.trim());
            return isPrivateOrLoopback(address);
        } catch (Exception ignored) {
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

    private static String stringField(Object target, String name) {
        try {
            Object value = readField(target, name);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
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

    private static String normalizeHost(String host) {
        return host.trim().toLowerCase(Locale.ROOT);
    }

    private static InetAddress loopbackAddress() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static boolean isPrivateOrLoopback(InetAddress address) {
        if (address == null) return false;
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 10
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 169 && second == 254);
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    private static final class TunnelHostResolver implements HostResolver {
        private final Set<String> knownHosts;
        private final DefaultHostResolver delegate = new DefaultHostResolver();

        private TunnelHostResolver(Set<String> knownHosts) {
            this.knownHosts = knownHosts;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (shouldMapHost(host, knownHosts)) {
                return new InetAddress[]{LOOPBACK};
            }

            InetAddress[] resolved = delegate.resolve(host);
            for (InetAddress address : resolved) {
                if (isPrivateOrLoopback(address)) {
                    return new InetAddress[]{LOOPBACK};
                }
            }
            return resolved;
        }
    }
}
