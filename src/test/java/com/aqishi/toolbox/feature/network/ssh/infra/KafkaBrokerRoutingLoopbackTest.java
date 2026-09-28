package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.domain.KafkaBrokerTunnelPlan.Endpoint;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End to end over loopback sockets: two "brokers" on different hosts advertise the same port.
 * The SSH forward is replaced by a plain TCP relay, but everything else — planning, bind
 * addresses, resolver — is the production code. The client must reach each broker through
 * its own address; before the fix both names resolved to 127.0.0.1 and hit the same forward.
 */
class KafkaBrokerRoutingLoopbackTest {

    @Test
    void eachAdvertisedBrokerIsReachedThroughItsOwnForward() throws Exception {
        assumeTrue(KafkaTunnelSupport.supportsMultipleLoopbackAddresses(),
                "needs 127.0.0.2+ (Linux/Windows; macOS only with lo0 aliases)");
        List<AutoCloseable> resources = new ArrayList<>();
        try (ServerSocket brokerOne = backend((byte) 1, resources);
             ServerSocket brokerTwo = backend((byte) 2, resources)) {
            int advertisedPort = freePortOnAliases();
            // What the SSH server would resolve: host name to real broker socket.
            java.util.Map<String, Integer> remote = java.util.Map.of(
                    "broker-1", brokerOne.getLocalPort(), "broker-2", brokerTwo.getLocalPort());

            try (KafkaBrokerTunnels tunnels = KafkaBrokerTunnels.open(
                    List.of(new Endpoint("broker-1", advertisedPort), new Endpoint("broker-2", advertisedPort)),
                    true, KafkaTunnelSupport::isBindable,
                    (host, port, bind, localPort) -> relay(bind, localPort, remote.get(host), resources),
                    Set.of("127.0.0.1"))) {

                assertEquals(1, readBrokerId(tunnels.routing().resolve("broker-1")[0], advertisedPort));
                assertEquals(2, readBrokerId(tunnels.routing().resolve("broker-2")[0], advertisedPort));
            }
        } finally {
            for (AutoCloseable resource : resources) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static int freePortOnAliases() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            int port;
            try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = probe.getLocalPort();
            }
            if (KafkaTunnelSupport.isBindable("127.0.0.2", port)
                    && KafkaTunnelSupport.isBindable("127.0.0.3", port)) {
                return port;
            }
        }
        throw new IllegalStateException("no common free port on 127.0.0.2/127.0.0.3");
    }

    /** A broker that writes its id to every connection. */
    private static ServerSocket backend(byte id, List<AutoCloseable> resources) throws Exception {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(id);
                    socket.getOutputStream().flush();
                } catch (Exception closed) {
                    return;
                }
            }
        }, "fake-broker-" + id);
        thread.setDaemon(true);
        thread.start();
        resources.add(server);
        return server;
    }

    /** Stand-in for an SSH local forward: bind exactly {@code bind:localPort}, relay one byte. */
    private static AutoCloseable relay(String bind, int localPort, int targetPort,
                                       List<AutoCloseable> resources) throws Exception {
        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName(bind), localPort), 5);
        Thread thread = new Thread(() -> {
            while (!listener.isClosed()) {
                try (Socket client = listener.accept();
                     Socket target = new Socket(InetAddress.getByName("127.0.0.1"), targetPort)) {
                    InputStream in = target.getInputStream();
                    OutputStream out = client.getOutputStream();
                    out.write(in.read());
                    out.flush();
                } catch (Exception closed) {
                    return;
                }
            }
        }, "fake-forward-" + bind + ":" + localPort);
        thread.setDaemon(true);
        thread.start();
        resources.add(listener);
        return listener;
    }

    private static int readBrokerId(InetAddress address, int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 2_000);
            socket.setSoTimeout(2_000);
            return socket.getInputStream().read();
        }
    }
}
