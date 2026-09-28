package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The per-broker SSH forwards of one Kafka connection.
 *
 * <p>Sequence used by the Kafka tool:</p>
 * <ol>
 *   <li>forward each bootstrap server to 127.0.0.1 on an ephemeral port and connect with
 *   {@link KafkaTunnelSupport.Routing#bootstrapOnly} — nothing but the bootstrap forwards is
 *   reachable yet, so no request can land on a wrong broker;</li>
 *   <li>read the advertised brokers from the first Metadata response (served by any broker,
 *   so going through a bootstrap forward is correct);</li>
 *   <li>{@link #open}: plan one local address per advertised host
 *   ({@link KafkaBrokerTunnelPlan}), forward every advertised {@code host:port} to exactly that
 *   local address and port, and verify each target through SSH;</li>
 *   <li>install {@link #routing()} on every Kafka client of the connection.</li>
 * </ol>
 * <p>An ambiguous or unavailable mapping fails in step 3 with a {@link
 * KafkaBrokerTunnelPlan.PlanningException}; nothing is left open then.</p>
 */
public final class KafkaBrokerTunnels implements AutoCloseable {

    /** Opens one forward; implemented with {@link SshTunnelBridge} outside of tests. */
    @FunctionalInterface
    public interface TunnelOpener {
        /**
         * Forwards {@code bindAddress:localPort} (exactly that port, no fallback) to
         * {@code remoteHost:remotePort} as seen from the SSH server.
         */
        AutoCloseable open(String remoteHost, int remotePort, String bindAddress, int localPort)
                throws Exception;
    }

    private final KafkaBrokerTunnelPlan plan;
    private final KafkaTunnelSupport.Routing routing;
    private final List<AutoCloseable> tunnels;
    private boolean closed;

    private KafkaBrokerTunnels(KafkaBrokerTunnelPlan plan, KafkaTunnelSupport.Routing routing,
                               List<AutoCloseable> tunnels) {
        this.plan = plan;
        this.routing = routing;
        this.tunnels = tunnels;
    }

    /**
     * Plans and opens the broker forwards. On any failure the forwards opened so far are
     * closed again before the exception propagates.
     *
     * @param bootstrapLocalHosts local literal addresses of the bootstrap forwards; they keep
     *                            resolving to themselves
     */
    public static KafkaBrokerTunnels open(Collection<KafkaBrokerTunnelPlan.Endpoint> advertised,
                                          boolean multipleLoopbackAddresses,
                                          KafkaBrokerTunnelPlan.BindProbe probe,
                                          TunnelOpener opener,
                                          Collection<String> bootstrapLocalHosts) throws Exception {
        KafkaBrokerTunnelPlan plan = KafkaBrokerTunnelPlan.create(advertised, multipleLoopbackAddresses, probe);
        List<AutoCloseable> opened = new ArrayList<>();
        try {
            for (KafkaBrokerTunnelPlan.Route route : plan.routes()) {
                opened.add(opener.open(route.advertised().host(), route.advertised().port(),
                        route.localAddress(), route.localPort()));
            }
            return new KafkaBrokerTunnels(plan, KafkaTunnelSupport.Routing.of(plan, bootstrapLocalHosts), opened);
        } catch (Exception error) {
            closeAll(opened);
            throw error;
        }
    }

    public KafkaBrokerTunnelPlan plan() {
        return plan;
    }

    public KafkaTunnelSupport.Routing routing() {
        return routing;
    }

    public List<AutoCloseable> tunnels() {
        return Collections.unmodifiableList(tunnels);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        closeAll(tunnels);
    }

    private static void closeAll(List<AutoCloseable> tunnels) {
        for (AutoCloseable tunnel : tunnels) {
            try {
                if (tunnel != null) tunnel.close();
            } catch (Exception ignored) {
                // Keep releasing the remaining forwards.
            }
        }
    }
}
