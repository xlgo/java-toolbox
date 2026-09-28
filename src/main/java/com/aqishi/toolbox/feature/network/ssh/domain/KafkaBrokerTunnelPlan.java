package com.aqishi.toolbox.feature.network.ssh.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decides where each Kafka broker is reached locally when the cluster sits behind an SSH
 * server. Pure logic; opening the forwards and installing the resolver is done elsewhere.
 *
 * <h2>Why a plan is needed</h2>
 * A Kafka client uses {@code bootstrap.servers} only for the first Metadata request. After
 * that it connects to every broker's <em>advertised</em> {@code host:port}. kafka-clients
 * (3.4 on this classpath) offers no public way to rewrite those addresses; its internal
 * {@code HostResolver} can map a <em>host name</em> to an IP address, but the port is always
 * the advertised one. The former approach mapped every host to 127.0.0.1 and relied on the
 * bootstrap forward happening to listen on the advertised port, so with
 * {@code broker-1:9092} and {@code broker-2:9092} every request went to whichever broker
 * owned 127.0.0.1:9092 — requests silently reached the wrong broker.
 *
 * <h2>Design</h2>
 * Every distinct advertised {@code host:port} gets its own forward whose local port equals
 * the advertised port, and every distinct host gets its own local address, so
 * {@code (local address, advertised port)} identifies exactly one broker:
 * <ul>
 *   <li><b>Several loopback addresses usable</b> (Linux and Windows route all of
 *   127.0.0.0/8 to loopback): the advertised host {@code 127.0.0.1} keeps 127.0.0.1, every
 *   other host gets the next free address from 127.0.0.2 on. Brokers sharing a port on
 *   different hosts are therefore unambiguous.</li>
 *   <li><b>Only 127.0.0.1 usable</b> (macOS without {@code lo0} aliases): all hosts map to
 *   127.0.0.1, which is only correct when no two hosts advertise the same port. Otherwise
 *   planning fails with {@link Problem#DUPLICATE_PORT} instead of mis-routing.</li>
 * </ul>
 * A local address:port that is already taken is never replaced by another port: the plan
 * picks another loopback address, or fails with {@link Problem#LOCAL_PORT_IN_USE}.
 */
public final class KafkaBrokerTunnelPlan {

    /** The address every tunnel listens on when no alias is available. */
    public static final String PRIMARY_LOOPBACK = "127.0.0.1";
    private static final int FIRST_ALIAS = 2;
    private static final int LAST_ALIAS = 254;

    /** Why no correct plan exists. */
    public enum Problem {
        /** Only one loopback address is usable and several hosts advertise the same port. */
        DUPLICATE_PORT,
        /** The advertised port is taken on every loopback address that could be used. */
        LOCAL_PORT_IN_USE
    }

    /** An advertised broker endpoint; the host is normalized (lower case, no brackets). */
    public record Endpoint(String host, int port) {
        public Endpoint {
            host = normalizeHost(host);
            if (host.isEmpty()) throw new IllegalArgumentException("empty broker host");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("broker port " + port);
        }

        @Override
        public String toString() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }
    }

    /** One forward: connections to {@code localAddress:advertised.port} reach {@code advertised}. */
    public record Route(Endpoint advertised, String localAddress) {
        public int localPort() {
            return advertised.port();
        }
    }

    /** Tells whether a local address:port can still be bound. */
    @FunctionalInterface
    public interface BindProbe {
        boolean isFree(String address, int port);
    }

    /** No plan avoids ambiguity; {@link #endpoints()} lists the brokers concerned. */
    public static final class PlanningException extends Exception {
        private final Problem problem;
        private final List<Endpoint> endpoints;

        PlanningException(Problem problem, List<Endpoint> endpoints) {
            super(problem + ": " + endpoints);
            this.problem = problem;
            this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
        }

        public Problem problem() {
            return problem;
        }

        public List<Endpoint> endpoints() {
            return endpoints;
        }
    }

    private final List<Route> routes;
    private final Map<String, String> hostAddresses;

    private KafkaBrokerTunnelPlan(List<Route> routes, Map<String, String> hostAddresses) {
        this.routes = Collections.unmodifiableList(routes);
        this.hostAddresses = Collections.unmodifiableMap(hostAddresses);
    }

    /**
     * @param advertised                broker endpoints from cluster metadata (duplicates ignored)
     * @param multipleLoopbackAddresses whether addresses other than 127.0.0.1 can be bound
     * @param probe                     checks whether a local address:port is still free
     */
    public static KafkaBrokerTunnelPlan create(Collection<Endpoint> advertised,
                                               boolean multipleLoopbackAddresses,
                                               BindProbe probe) throws PlanningException {
        Objects.requireNonNull(probe, "probe");
        Map<String, Set<Integer>> portsByHost = new LinkedHashMap<>();
        if (advertised != null) {
            for (Endpoint endpoint : advertised) {
                if (endpoint == null) continue;
                portsByHost.computeIfAbsent(endpoint.host(), h -> new LinkedHashSet<>()).add(endpoint.port());
            }
        }
        Map<String, String> hostAddresses = multipleLoopbackAddresses
                ? assignAliases(portsByHost, probe)
                : assignPrimary(portsByHost, probe);

        List<Route> routes = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> entry : portsByHost.entrySet()) {
            for (int port : entry.getValue()) {
                routes.add(new Route(new Endpoint(entry.getKey(), port), hostAddresses.get(entry.getKey())));
            }
        }
        return new KafkaBrokerTunnelPlan(routes, hostAddresses);
    }

    private static Map<String, String> assignAliases(Map<String, Set<Integer>> portsByHost,
                                                     BindProbe probe) throws PlanningException {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        // A broker advertising 127.0.0.1 must keep it: the resolver cannot tell that host
        // apart from the literal 127.0.0.1 used for the bootstrap connection.
        Set<Integer> primaryPorts = portsByHost.get(PRIMARY_LOOPBACK);
        if (primaryPorts != null) {
            requireFree(PRIMARY_LOOPBACK, PRIMARY_LOOPBACK, primaryPorts, probe);
            result.put(PRIMARY_LOOPBACK, PRIMARY_LOOPBACK);
            used.add(PRIMARY_LOOPBACK);
        }
        int next = FIRST_ALIAS;
        for (Map.Entry<String, Set<Integer>> entry : portsByHost.entrySet()) {
            if (result.containsKey(entry.getKey())) continue;
            String chosen = null;
            while (chosen == null && next <= LAST_ALIAS) {
                String candidate = "127.0.0." + next++;
                if (!used.contains(candidate) && allFree(candidate, entry.getValue(), probe)) {
                    chosen = candidate;
                }
            }
            if (chosen == null) {
                throw new PlanningException(Problem.LOCAL_PORT_IN_USE,
                        endpointsOf(entry.getKey(), entry.getValue()));
            }
            used.add(chosen);
            result.put(entry.getKey(), chosen);
        }
        return result;
    }

    private static Map<String, String> assignPrimary(Map<String, Set<Integer>> portsByHost,
                                                     BindProbe probe) throws PlanningException {
        Map<Integer, List<Endpoint>> byPort = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : portsByHost.entrySet()) {
            for (int port : entry.getValue()) {
                byPort.computeIfAbsent(port, p -> new ArrayList<>()).add(new Endpoint(entry.getKey(), port));
            }
        }
        List<Endpoint> ambiguous = new ArrayList<>();
        for (List<Endpoint> sharing : byPort.values()) {
            if (sharing.size() > 1) ambiguous.addAll(sharing);
        }
        if (!ambiguous.isEmpty()) throw new PlanningException(Problem.DUPLICATE_PORT, ambiguous);

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : portsByHost.entrySet()) {
            requireFree(entry.getKey(), PRIMARY_LOOPBACK, entry.getValue(), probe);
            result.put(entry.getKey(), PRIMARY_LOOPBACK);
        }
        return result;
    }

    private static void requireFree(String host, String address, Set<Integer> ports,
                                    BindProbe probe) throws PlanningException {
        List<Endpoint> taken = new ArrayList<>();
        for (int port : ports) {
            if (!probe.isFree(address, port)) taken.add(new Endpoint(host, port));
        }
        if (!taken.isEmpty()) throw new PlanningException(Problem.LOCAL_PORT_IN_USE, taken);
    }

    private static boolean allFree(String address, Set<Integer> ports, BindProbe probe) {
        for (int port : ports) {
            if (!probe.isFree(address, port)) return false;
        }
        return true;
    }

    private static List<Endpoint> endpointsOf(String host, Set<Integer> ports) {
        List<Endpoint> result = new ArrayList<>();
        for (int port : ports) result.add(new Endpoint(host, port));
        return result;
    }

    /** Forwards to open, in metadata order. */
    public List<Route> routes() {
        return routes;
    }

    /** Normalized advertised host to the local address the client must resolve it to. */
    public Map<String, String> hostAddresses() {
        return hostAddresses;
    }

    /** Local address for an advertised host, or null when the host is not part of the plan. */
    public String localAddressFor(String host) {
        return host == null ? null : hostAddresses.get(normalizeHost(host));
    }

    /** Lower-cases, trims and strips IPv6 brackets so metadata and resolver lookups agree. */
    public static String normalizeHost(String host) {
        if (host == null) return "";
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 1 && value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
