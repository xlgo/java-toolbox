package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketError;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.feature.network.domain.SocketOpenException;
import com.aqishi.toolbox.feature.network.domain.SocketSendException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UdpSessionTest {

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

    private UdpSession udp(EventRecorder events, UdpSession.Options options) throws IOException {
        UdpSession session = new UdpSession(options.bindHost("127.0.0.1").bindPort(0), events);
        sessions.add(session);
        session.open();
        return session;
    }

    @Test
    void sendsAndReceivesWithSenderAddress() throws Exception {
        EventRecorder aEvents = new EventRecorder();
        EventRecorder bEvents = new EventRecorder();
        UdpSession a = udp(aEvents, new UdpSession.Options());
        UdpSession b = udp(bEvents, new UdpSession.Options());
        assertTrue(a.localAddress().getPort() > 0);

        a.setTarget("127.0.0.1", b.localAddress().getPort());
        a.send(b("datagram"));
        SocketEvent received = bEvents.await(SocketEvent.Type.RECEIVED);
        assertEquals("datagram", new String(received.getData(), StandardCharsets.UTF_8));
        assertEquals(a.localAddress().getPort(), received.getPeer().getPort());
        aEvents.await(SocketEvent.Type.SENT);
        assertEquals(8, a.stats().snapshot().getBytesSent());
        assertEquals(1, b.stats().snapshot().getPacketsReceived());
    }

    @Test
    void repliesToLastSender() throws Exception {
        EventRecorder aEvents = new EventRecorder();
        EventRecorder bEvents = new EventRecorder();
        UdpSession a = udp(aEvents, new UdpSession.Options());
        // b 没有默认目标，只能回复最近发送方
        UdpSession b = udp(bEvents, new UdpSession.Options().replyToLastSender(true));
        assertEquals(SocketError.NO_TARGET,
                assertThrows(SocketSendException.class, () -> b.send(b("x"))).getError());

        a.sendTo(b.localAddress(), b("hi b"));
        bEvents.await(SocketEvent.Type.RECEIVED);
        assertEquals(a.localAddress().getPort(), b.lastSender().getPort());
        b.send(b("hi a"));
        aEvents.awaitReceived(0, "hi a");
    }

    @Test
    void echoModeSendsDatagramBack() throws Exception {
        EventRecorder aEvents = new EventRecorder();
        UdpSession a = udp(aEvents, new UdpSession.Options());
        UdpSession echo = udp(new EventRecorder(), new UdpSession.Options().echo(true));
        a.sendTo(echo.localAddress(), b("marco"));
        aEvents.awaitReceived(0, "marco");
    }

    @Test
    void rejectsOversizedDatagramAndUnboundSend() throws Exception {
        UdpSession a = udp(new EventRecorder(), new UdpSession.Options().target("127.0.0.1", 9));
        assertEquals(SocketError.DATAGRAM_TOO_LARGE, assertThrows(SocketSendException.class,
                () -> a.send(new byte[UdpSession.MAX_DATAGRAM + 1])).getError());
        a.close();
        a.close();
        assertTrue(a.isClosed());
        assertEquals(SocketError.NOT_CONNECTED,
                assertThrows(SocketSendException.class, () -> a.send(b("x"))).getError());
    }

    @Test
    void broadcastOptionIsApplied() throws Exception {
        UdpSession a = udp(new EventRecorder(), new UdpSession.Options().broadcast(true));
        assumeTrue(a.isBroadcast(), "OS refused SO_BROADCAST");
        try {
            a.setBroadcast(false);
        } catch (SocketOpenException refused) {
            assumeTrue(false, "OS refused toggling SO_BROADCAST: " + refused.getMessage());
        }
        assertFalse(a.isBroadcast());
    }

    @Test
    void joinsAndLeavesMulticastGroupOnCapableInterface() throws Exception {
        NetworkInterface nif = multicastInterface();
        assumeTrue(nif != null, "no multicast-capable IPv4 interface");
        EventRecorder events = new EventRecorder();
        UdpSession session = new UdpSession(new UdpSession.Options().bindHost("0.0.0.0").bindPort(0), events);
        sessions.add(session);
        session.open();
        try {
            session.joinGroup("239.255.43.21", nif.getName());
        } catch (SocketOpenException refused) {
            assumeTrue(false, "OS refused multicast join: " + refused.getMessage());
        }
        assertEquals(Collections.singletonList("239.255.43.21%" + nif.getName()), session.joinedGroups());
        session.leaveGroup("239.255.43.21", nif.getName());
        assertTrue(session.joinedGroups().isEmpty());

        SocketOpenException notMulticast = assertThrows(SocketOpenException.class,
                () -> session.joinGroup("10.0.0.1", null));
        assertEquals(SocketError.MULTICAST_FAILED, notMulticast.getError());
    }

    private static NetworkInterface multicastInterface() throws IOException {
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || !nif.supportsMulticast()) {
                continue;
            }
            for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                if (address instanceof Inet4Address) {
                    return nif;
                }
            }
        }
        return null;
    }

    @Test
    void closeReleasesThreads() throws Exception {
        UdpSession a = udp(new EventRecorder(), new UdpSession.Options());
        assertNotNull(a.localAddress());
        a.close();
        assertTrue(EventRecorder.awaitNoSessionThreads(3000), "threads: " + EventRecorder.sessionThreads());
    }
}
