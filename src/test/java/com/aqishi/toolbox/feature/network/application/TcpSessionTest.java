package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketError;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.feature.network.domain.SocketFrameSplitter;
import com.aqishi.toolbox.feature.network.domain.SocketOpenException;
import com.aqishi.toolbox.feature.network.domain.SocketSendException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TcpSessionTest {

    private final List<SocketSession> sessions = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (SocketSession session : sessions) {
            session.close();
        }
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private TcpServerSession server(EventRecorder events, TcpServerSession.Options options) throws IOException {
        TcpServerSession server = new TcpServerSession(options.bindHost("127.0.0.1"), events);
        sessions.add(server);
        server.open();
        return server;
    }

    private TcpClientSession client(EventRecorder events, int port) throws IOException {
        return client(events, new TcpClientSession.Options().port(port));
    }

    private TcpClientSession client(EventRecorder events, TcpClientSession.Options options) throws IOException {
        TcpClientSession client = new TcpClientSession(options.host("127.0.0.1"), events);
        sessions.add(client);
        client.open();
        return client;
    }

    @Test
    void clientAndServerExchangeBothWays() throws Exception {
        EventRecorder serverEvents = new EventRecorder();
        TcpServerSession server = server(serverEvents, new TcpServerSession.Options().port(0));
        assertTrue(server.actualPort() > 0);
        assertEquals(server.actualPort(), server.localAddress().getPort());

        EventRecorder clientEvents = new EventRecorder();
        TcpClientSession client = client(clientEvents, server.actualPort());
        assertTrue(client.isOpen());
        assertNotNull(clientEvents.await(SocketEvent.Type.CONNECTED));
        SocketEvent joined = serverEvents.await(SocketEvent.Type.CLIENT_JOINED);

        client.send(b("hello server"));
        serverEvents.awaitReceived(joined.getClientId(), "hello server");
        clientEvents.await(SocketEvent.Type.SENT);

        server.sendTo(joined.getClientId(), b("hello client"));
        clientEvents.awaitReceived(0, "hello client");

        assertEquals(12, client.stats().snapshot().getBytesSent());
        assertEquals(12, client.stats().snapshot().getBytesReceived());
        assertEquals(1, server.clients().size());
        assertEquals(12, server.clients().get(0).getBytesIn());
    }

    @Test
    void serverHandlesSeveralClientsAndRejectsBeyondMax() throws Exception {
        EventRecorder serverEvents = new EventRecorder();
        TcpServerSession server = server(serverEvents, new TcpServerSession.Options().port(0).maxClients(3));
        EventRecorder[] clientEvents = new EventRecorder[3];
        for (int i = 0; i < 3; i++) {
            clientEvents[i] = new EventRecorder();
            client(clientEvents[i], server.actualPort());
        }
        serverEvents.await(e -> serverEvents.count(x -> x.getType() == SocketEvent.Type.CLIENT_JOINED) == 3);
        List<TcpServerSession.ClientInfo> clients = server.clients();
        assertEquals(3, clients.size());

        // 第 4 个客户端被拒绝：连接能建立但立即被服务端关闭
        EventRecorder rejectedEvents = new EventRecorder();
        client(rejectedEvents, server.actualPort());
        SocketEvent rejected = serverEvents.await(e -> e.getError() == SocketError.CLIENT_REJECTED);
        assertNotNull(rejected.getPeer());
        rejectedEvents.await(SocketEvent.Type.DISCONNECTED);
        assertEquals(3, server.clients().size());

        // 发给其中一个：客户端编号与 CLIENT_JOINED 顺序一致，但我们按端口对应到具体客户端
        server.sendTo(clients.get(0).getId(), b("one"));
        server.send(b("all"));
        int withOne = 0;
        for (EventRecorder events : clientEvents) {
            String text = events.awaitReceived(0, "all");
            if (text.contains("one")) {
                withOne++;
            }
        }
        assertEquals(1, withOne);

        server.sendTo(Arrays.asList(clients.get(1).getId(), clients.get(2).getId()), b("pair"));
        assertTrue(server.disconnect(clients.get(1).getId()));
        assertFalse(server.disconnect(9999));
        SocketEvent left = serverEvents.await(SocketEvent.Type.CLIENT_LEFT);
        assertEquals(SocketEvent.Reason.KICKED, left.getReason());
        assertEquals(2, server.clients().size());
        assertEquals(SocketError.NO_SUCH_CLIENT,
                assertThrows(SocketSendException.class, () -> server.sendTo(clients.get(1).getId(), b("x"))).getError());
    }

    @Test
    void echoModeSendsBackWhatClientSends() throws Exception {
        EventRecorder serverEvents = new EventRecorder();
        TcpServerSession server = server(serverEvents, new TcpServerSession.Options().port(0).echo(true));
        EventRecorder clientEvents = new EventRecorder();
        TcpClientSession client = client(clientEvents, server.actualPort());
        client.send(b("ping-123"));
        clientEvents.awaitReceived(0, "ping-123");
        assertTrue(server.isEcho());
    }

    @Test
    void disconnectsAreDetectedOnBothSides() throws Exception {
        EventRecorder serverEvents = new EventRecorder();
        TcpServerSession server = server(serverEvents, new TcpServerSession.Options().port(0));
        EventRecorder clientEvents = new EventRecorder();
        TcpClientSession client = client(clientEvents, server.actualPort());
        serverEvents.await(SocketEvent.Type.CLIENT_JOINED);

        client.close();
        SocketEvent left = serverEvents.await(SocketEvent.Type.CLIENT_LEFT);
        assertEquals(SocketEvent.Reason.PEER_CLOSED, left.getReason());
        assertEquals(SocketEvent.Reason.LOCAL_CLOSE, clientEvents.await(SocketEvent.Type.DISCONNECTED).getReason());
        assertTrue(client.isClosed());

        EventRecorder secondEvents = new EventRecorder();
        TcpClientSession second = client(secondEvents, server.actualPort());
        serverEvents.await(e -> serverEvents.count(x -> x.getType() == SocketEvent.Type.CLIENT_JOINED) == 2);
        server.close();
        assertEquals(SocketEvent.Reason.PEER_CLOSED, secondEvents.await(SocketEvent.Type.DISCONNECTED).getReason());
        assertTrue(second.isClosed());
        assertEquals(SocketError.NOT_CONNECTED,
                assertThrows(SocketSendException.class, () -> second.send(b("x"))).getError());
    }

    @Test
    void clientReconnectsAfterServerRestart() throws Exception {
        EventRecorder serverEvents = new EventRecorder();
        TcpServerSession server = server(serverEvents, new TcpServerSession.Options().port(0));
        int port = server.actualPort();
        EventRecorder clientEvents = new EventRecorder();
        TcpClientSession client = client(clientEvents, new TcpClientSession.Options().port(port)
                .autoReconnect(true).reconnectInitialMillis(50).reconnectMaxMillis(200));
        serverEvents.await(SocketEvent.Type.CLIENT_JOINED);

        server.close();
        clientEvents.await(SocketEvent.Type.DISCONNECTED);
        clientEvents.await(SocketEvent.Type.RECONNECTING);
        assertFalse(client.isClosed(), "auto-reconnect keeps the session alive");

        EventRecorder restartedEvents = new EventRecorder();
        TcpServerSession restarted = restartOnPort(restartedEvents, port);
        restartedEvents.await(SocketEvent.Type.CLIENT_JOINED);
        clientEvents.await(e -> clientEvents.count(x -> x.getType() == SocketEvent.Type.CONNECTED) == 2);
        client.send(b("back"));
        restartedEvents.awaitReceived(0, "back");

        // 手动断开后不再重连
        client.close();
        restartedEvents.await(SocketEvent.Type.CLIENT_LEFT);
        assertTrue(client.isClosed());
        assertTrue(restarted.clients().isEmpty());
    }

    /** 同一端口重新监听；个别系统上 TIME_WAIT 会短暂占用端口，重试一小会儿，仍失败则跳过。 */
    private TcpServerSession restartOnPort(EventRecorder events, int port) throws Exception {
        long end = System.currentTimeMillis() + 3000;
        while (true) {
            TcpServerSession candidate = new TcpServerSession(
                    new TcpServerSession.Options().bindHost("127.0.0.1").port(port), events);
            try {
                candidate.open();
                sessions.add(candidate);
                return candidate;
            } catch (SocketOpenException busy) {
                assumeTrue(System.currentTimeMillis() < end, "port " + port + " not reusable: " + busy.getMessage());
                Thread.sleep(50);
            }
        }
    }

    @Test
    void fullSendQueueFailsFast() throws Exception {
        // 一个只接受不读取的服务端：客户端写满内核缓冲后写线程阻塞，队列随之填满
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            EventRecorder clientEvents = new EventRecorder();
            TcpClientSession client = client(clientEvents,
                    new TcpClientSession.Options().port(silent.getLocalPort()).queueCapacity(2));
            try (Socket accepted = silent.accept()) {
                byte[] chunk = new byte[1024 * 1024];
                SocketSendException full = null;
                for (int i = 0; i < 512 && full == null; i++) {
                    try {
                        client.send(chunk);
                    } catch (SocketSendException error) {
                        full = error;
                    }
                }
                assertNotNull(full, "queue never filled");
                assertEquals(SocketError.SEND_QUEUE_FULL, full.getError());
                assertNotNull(accepted);
            }
        }
    }

    @Test
    void connectFailureReportsCode() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        TcpClientSession client = new TcpClientSession(new TcpClientSession.Options()
                .host("127.0.0.1").port(closedPort).connectTimeoutMillis(2000), new EventRecorder());
        sessions.add(client);
        SocketOpenException error = assertThrows(SocketOpenException.class, client::open);
        assertEquals(SocketError.CONNECT_FAILED, error.getError());
        assertTrue(client.isClosed());
    }

    @Test
    void retriesInitialFailureUntilServerStarts() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }
        EventRecorder events = new EventRecorder();
        TcpClientSession client = client(events, new TcpClientSession.Options().port(port)
                .autoReconnect(true).reconnectInitialMillis(50).reconnectMaxMillis(100));
        events.await(SocketEvent.Type.RECONNECTING);
        assertFalse(client.isClosed());
        assertFalse(client.isOpen());
        EventRecorder serverEvents = new EventRecorder();
        server(serverEvents, new TcpServerSession.Options().port(port).echo(true));
        events.await(SocketEvent.Type.CONNECTED);
        client.send(b("started later"));
        events.awaitReceived(0, "started later");
    }

    @Test
    void initialRetryCanBeCancelledWithoutLeakingThreads() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }
        EventRecorder events = new EventRecorder();
        TcpClientSession client = client(events, new TcpClientSession.Options().port(port)
                .autoReconnect(true).reconnectInitialMillis(60_000));
        events.await(SocketEvent.Type.RECONNECTING);
        client.close();
        assertTrue(client.isClosed());
        assertTrue(EventRecorder.awaitNoSessionThreads(3000));
        assertEquals(0, events.count(e -> e.getType() == SocketEvent.Type.CONNECTED));
    }

    @Test
    void connectedEventAlwaysPrecedesImmediatePeerDisconnect() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            EventRecorder events = new EventRecorder();
            TcpClientSession client = new TcpClientSession(new TcpClientSession.Options()
                    .port(server.getLocalPort()), event -> {
                events.onEvent(event);
                // Runs synchronously during publication, before readers should start.
                if (event.getType() == SocketEvent.Type.CONNECTED) {
                    try { server.accept().close(); } catch (IOException e) { throw new RuntimeException(e); }
                }
            });
            sessions.add(client);
            client.open();
            events.await(SocketEvent.Type.DISCONNECTED);
            assertEquals(SocketEvent.Type.CONNECTED, events.snapshot().get(0).getType());
            assertTrue(client.isClosed());
        }
    }

    @Test
    void serverSplitsFramesByDelimiter() throws Exception {
        EventRecorder serverEvents = new EventRecorder();
        TcpServerSession server = server(serverEvents, new TcpServerSession.Options().port(0)
                .frame(SocketFrameSplitter.Config.delimiter(b("\n"), false)));
        TcpClientSession client = client(new EventRecorder(), server.actualPort());
        client.send(b("first\nsec"));
        client.send(b("ond\nthird"));
        serverEvents.await(e -> serverEvents.count(x -> x.getType() == SocketEvent.Type.RECEIVED) == 2);
        List<String> frames = new ArrayList<>();
        for (SocketEvent event : serverEvents.snapshot()) {
            if (event.getType() == SocketEvent.Type.RECEIVED) {
                frames.add(new String(event.getData(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(Arrays.asList("first", "second"), frames);
        // 断开时剩余的半帧也要吐出来
        client.close();
        serverEvents.await(e -> e.getType() == SocketEvent.Type.RECEIVED
                && "third".equals(new String(e.getData(), StandardCharsets.UTF_8)));
    }

    @Test
    void closeReleasesAllSessionThreads() throws Exception {
        EventRecorder serverEvents = new EventRecorder();
        TcpServerSession server = server(serverEvents, new TcpServerSession.Options().port(0));
        TcpClientSession a = client(new EventRecorder(), server.actualPort());
        TcpClientSession b = client(new EventRecorder(), new TcpClientSession.Options()
                .port(server.actualPort()).autoReconnect(true).reconnectInitialMillis(50));
        serverEvents.await(e -> serverEvents.count(x -> x.getType() == SocketEvent.Type.CLIENT_JOINED) == 2);
        assertFalse(EventRecorder.sessionThreads().isEmpty());

        server.close();
        server.close();
        a.close();
        b.close();
        b.close();
        if (!EventRecorder.awaitNoSessionThreads(3000)) {
            fail("session threads still alive: " + EventRecorder.sessionThreads());
        }
    }
}
