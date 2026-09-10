package com.aqishi.toolbox.feature.monitor.infra;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.aqishi.toolbox.feature.monitor.domain.DesktopMessage;

class TcpDirectConnectorTest {

    @Test
    void usesBoundedNamedDaemonWorkersAndStopsThePool() throws Exception {
        TcpDirectConnector connector = new TcpDirectConnector(false);
        ThreadPoolExecutor workers = null;
        try {
            connector.startConnector(ignored -> { }, () -> { }, ignored -> { });
            Field workerField = TcpDirectConnector.class.getDeclaredField("workerExecutor");
            workerField.setAccessible(true);
            workers = (ThreadPoolExecutor) workerField.get(connector);

            assertEquals(4, workers.getCorePoolSize());
            assertEquals(4, workers.getMaximumPoolSize());
            assertEquals(128, workers.getQueue().size() + workers.getQueue().remainingCapacity());

            CountDownLatch ran = new CountDownLatch(1);
            AtomicReference<Thread> observedThread = new AtomicReference<>();
            workers.submit(() -> {
                observedThread.set(Thread.currentThread());
                ran.countDown();
            });
            assertTrue(ran.await(1, TimeUnit.SECONDS));
            assertNotNull(observedThread.get());
            assertTrue(observedThread.get().isDaemon());
            assertTrue(observedThread.get().getName().startsWith("tcp-direct-connect-"));

            Field inFlightField = TcpDirectConnector.class.getDeclaredField("inFlight");
            inFlightField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<InetSocketAddress> inFlight = (Set<InetSocketAddress>) inFlightField.get(connector);
            inFlight.add(new InetSocketAddress("127.0.0.1", 12345));
        } finally {
            connector.stop();
        }

        assertNotNull(workers);
        assertTrue(workers.isShutdown());
        Field inFlightField = TcpDirectConnector.class.getDeclaredField("inFlight");
        inFlightField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<InetSocketAddress> inFlight = (Set<InetSocketAddress>) inFlightField.get(connector);
        assertTrue(inFlight.isEmpty());
    }

    @Test
    void establishesDirectTcpChannelFromCandidateReceivedBeforeConnectorStarts() throws Exception {
        TcpDirectConnector host = new TcpDirectConnector(false);
        TcpDirectConnector controller = new TcpDirectConnector(false);
        AtomicReference<SocketChannelImpl> hostChannel = new AtomicReference<>();
        AtomicReference<SocketChannelImpl> controllerChannel = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(2);

        host.reset();
        controller.reset();
        int port = host.startListener(channel -> {
            // RemoteDesktopPanel.setupHostChannel 会停止协商器，已选中的 socket 必须继续存活。
            host.stop();
            hostChannel.set(channel);
            connected.countDown();
        }, () -> { }, ignored -> { });
        assertTrue(port > 0);

        controller.addCandidate(
                "candidate:tcp-host 1 tcp 1518280447 127.0.0.1 " + port + " typ host");
        controller.startConnector(channel -> {
            // 双向 TCP 协商会停止另一条监听/连接路径，选中的主动连接也必须继续存活。
            controller.stop();
            controllerChannel.set(channel);
            connected.countDown();
        }, () -> { }, ignored -> { });

        try {
            assertTrue(connected.await(3, TimeUnit.SECONDS));
            assertNotNull(hostChannel.get());
            assertNotNull(controllerChannel.get());

            byte[] expected = "direct-tcp-ready".getBytes(StandardCharsets.UTF_8);
            AtomicReference<DesktopMessage> actual = new AtomicReference<>();
            CountDownLatch received = new CountDownLatch(1);
            hostChannel.get().setMessageListener(message -> {
                actual.set(message);
                received.countDown();
            });
            controllerChannel.get().send(
                    new DesktopMessage(DesktopMessage.TYPE_HEARTBEAT, expected));

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertNotNull(actual.get());
            assertArrayEquals(expected, actual.get().getPayload());
        } finally {
            if (hostChannel.get() != null) hostChannel.get().close();
            if (controllerChannel.get() != null) controllerChannel.get().close();
            host.stop();
            controller.stop();
        }
    }
}
