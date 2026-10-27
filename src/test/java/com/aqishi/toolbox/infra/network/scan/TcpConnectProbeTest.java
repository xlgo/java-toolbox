package com.aqishi.toolbox.infra.network.scan;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.network.domain.*;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.util.concurrent.*;

class TcpConnectProbeTest {
    @Test
    void stoppingActivelyClosesTheConnectingSocket() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1);
        Socket socket =
                new Socket() {
                    @Override
                    public void connect(SocketAddress endpoint, int timeout)
                            throws java.io.IOException {
                        entered.countDown();
                        try {
                            closed.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                        }
                        throw new SocketException("closed");
                    }

                    @Override
                    public void close() {
                        closed.countDown();
                    }
                };
        TcpConnectProbe probe =
                new TcpConnectProbe() {
                    @Override
                    protected Socket createSocket() {
                        return socket;
                    }
                };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProbeOutcome> result =
                    executor.submit(
                            () ->
                                    probe.probe(
                                            new ScanTargetParser.Target("local", "127.0.0.1"),
                                            1234,
                                            60000));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(1, probe.activeSockets());
            probe.close();
            assertEquals(ProbeOutcome.State.CANCELLED, result.get(1, TimeUnit.SECONDS).state());
            assertEquals(0, probe.activeSockets());
        } finally {
            probe.close();
            executor.shutdownNow();
        }
    }
}
