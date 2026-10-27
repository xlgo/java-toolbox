package com.aqishi.toolbox.feature.network.application;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.network.domain.*;
import com.aqishi.toolbox.feature.network.domain.ScanTargetParser.Target;
import com.aqishi.toolbox.infra.network.scan.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class ScanSessionTest {
    @TempDir Path temp;

    private ScanPlan plan(String targets, String ports, int global, int perHost, int rate) {
        return new ScanPlan(
                ScanTargetParser.parse(targets, false),
                ScanTargetParser.parse("", true),
                List.copyOf(PortExpressionParser.parse(ports)),
                new ScanPlan.Settings(100, global, perHost, rate, 4096, 1000000, 16));
    }

    @Test
    void boundedSchedulingUsesBothHostsAndHonorsGlobalAndPerHostConcurrency() throws Exception {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        Map<String, AtomicInteger> perHost = new ConcurrentHashMap<>();
        Map<String, Integer> peaks = new ConcurrentHashMap<>();
        List<String> first = Collections.synchronizedList(new ArrayList<>());
        TcpConnectProbe probe =
                new TcpConnectProbe() {
                    @Override
                    public ProbeOutcome probe(Target target, int port, int timeout) {
                        int count = active.incrementAndGet();
                        peak.accumulateAndGet(count, Math::max);
                        int local =
                                perHost.computeIfAbsent(target.ip(), k -> new AtomicInteger())
                                        .incrementAndGet();
                        peaks.merge(target.ip(), local, Math::max);
                        first.add(target.ip());
                        try {
                            Thread.sleep(35);
                        } catch (InterruptedException ignored) {
                        } finally {
                            perHost.get(target.ip()).decrementAndGet();
                            active.decrementAndGet();
                        }
                        return new ProbeOutcome(
                                target.source(),
                                target.ip(),
                                port,
                                ProbeOutcome.State.OPEN,
                                1000,
                                "",
                                System.currentTimeMillis());
                    }
                };
        try (ScanResultRepository store = new ScanResultRepository(temp, 16);
                ScanSession session =
                        new ScanSession(
                                        plan("127.0.0.1-2", "1-10", 4, 2, 10000),
                                        new ScanPlanResolver(),
                                        probe,
                                        store)
                                .start()) {
            assertTrue(session.await(4, TimeUnit.SECONDS));
            assertEquals(ScanSession.Phase.COMPLETE, session.progress().phase());
            assertEquals(20, session.progress().completed());
            assertEquals(20, store.page(ScanResultRepository.Filter.all(), 0, 500).total());
            assertTrue(peak.get() <= 4);
            assertTrue(peak.get() >= 2);
            assertTrue(peaks.values().stream().allMatch(n -> n <= 2));
            assertEquals(2, new HashSet<>(first.subList(0, 4)).size());
            assertEquals(2, session.progress().completedHosts());
        }
    }

    @Test
    void rateLimitAppliesToConnectionStarts() throws Exception {
        TcpConnectProbe probe =
                new TcpConnectProbe() {
                    @Override
                    public ProbeOutcome probe(Target t, int p, int timeout) {
                        return new ProbeOutcome(
                                t.source(), t.ip(), p, ProbeOutcome.State.TIMEOUT, 1, "", 0);
                    }
                };
        long start = System.nanoTime();
        try (var store = new ScanResultRepository(temp, 16);
                var session =
                        new ScanSession(
                                        plan("127.0.0.1", "1-8", 8, 8, 20),
                                        new ScanPlanResolver(),
                                        probe,
                                        store)
                                .start()) {
            assertTrue(session.await(4, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 320);
            assertEquals(8, session.progress().timeouts());
        }
    }

    @Test
    void selectingOneGlobalWorkerIsStrictlySerial() throws Exception {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        TcpConnectProbe probe =
                new TcpConnectProbe() {
                    @Override
                    public ProbeOutcome probe(Target t, int p, int timeout) {
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {
                        } finally {
                            active.decrementAndGet();
                        }
                        return new ProbeOutcome(
                                t.source(), t.ip(), p, ProbeOutcome.State.OPEN, 0, "", 0);
                    }
                };
        try (var store = new ScanResultRepository(temp, 16);
                var session =
                        new ScanSession(
                                        plan("127.0.0.1-2", "1-5", 1, 8, 10000),
                                        new ScanPlanResolver(),
                                        probe,
                                        store)
                                .start()) {
            assertTrue(session.await(3, TimeUnit.SECONDS));
            assertEquals(1, peak.get());
            assertEquals(10, session.progress().completed());
        }
    }

    @Test
    void cancellationClosesTheProbeAndDoesNotFabricateClosedResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        TcpConnectProbe probe =
                new TcpConnectProbe() {
                    @Override
                    public ProbeOutcome probe(Target t, int p, int timeout) {
                        calls.incrementAndGet();
                        entered.countDown();
                        try {
                            closed.await();
                        } catch (InterruptedException ignored) {
                        }
                        return new ProbeOutcome(
                                t.source(), t.ip(), p, ProbeOutcome.State.CANCELLED, 0, "", 0);
                    }

                    @Override
                    public void close() {
                        closed.countDown();
                    }
                };
        try (var store = new ScanResultRepository(temp, 16);
                var session =
                        new ScanSession(
                                        plan("127.0.0.1-10", "1-1000", 4, 2, 10000),
                                        new ScanPlanResolver(),
                                        probe,
                                        store)
                                .start()) {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            session.cancel();
            assertTrue(session.await(2, TimeUnit.SECONDS));
            assertEquals(ScanSession.Phase.CANCELLED, session.progress().phase());
            assertTrue(calls.get() <= 4);
            assertTrue(session.progress().completed() < 10000);
        }
    }

    @Test
    void cancellationDrainsResultsThatAlreadyCompletedWhileStorageWasBusy() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger returned = new AtomicInteger();
        TcpConnectProbe probe =
                new TcpConnectProbe() {
                    @Override
                    public ProbeOutcome probe(Target target, int port, int timeout) {
                        entered.countDown();
                        try {
                            release.await();
                            Thread.sleep(1);
                        } catch (InterruptedException ignored) {
                        }
                        returned.incrementAndGet();
                        return new ProbeOutcome(
                                target.source(),
                                target.ip(),
                                port,
                                ProbeOutcome.State.OPEN,
                                1,
                                "",
                                0);
                    }
                };
        try (var store = new ScanResultRepository(temp, 16);
                var session =
                        new ScanSession(
                                        plan("127.0.0.1-2", "1-10000", 32, 16, 10000),
                                        new ScanPlanResolver(),
                                        probe,
                                        store)
                                .start()) {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            synchronized (store) {
                release.countDown();
                Thread.sleep(250);
                assertTrue(returned.get() > session.progress().completed());
                session.cancel();
            }
            assertTrue(session.await(3, TimeUnit.SECONDS));
            assertEquals(ScanSession.Phase.CANCELLED, session.progress().phase());
            assertEquals(returned.get(), session.progress().completed());
            assertEquals(
                    returned.get(), store.page(ScanResultRepository.Filter.all(), 0, 500).total());
        } finally {
            release.countDown();
        }
    }

    @Test
    void realLoopbackOpenAndConnectionFailureAreDistinct() throws Exception {
        try (ServerSocket listening = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                TcpConnectProbe probe = new TcpConnectProbe()) {
            int closed;
            try (ServerSocket reserve =
                    new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
                closed = reserve.getLocalPort();
            }
            assertEquals(
                    ProbeOutcome.State.OPEN,
                    probe.probe(new Target("local", "127.0.0.1"), listening.getLocalPort(), 500)
                            .state());
            assertEquals(
                    ProbeOutcome.State.CONNECT_FAILED,
                    probe.probe(new Target("local", "127.0.0.1"), closed, 500).state());
            assertEquals(0, probe.activeSockets());
        }
    }

    @Test
    void realIpv6LoopbackConnectsWithoutDns() throws Exception {
        ServerSocket server;
        try {
            server = new ServerSocket(0, 8, InetAddress.getByName("::1"));
        } catch (java.io.IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "IPv6 unavailable");
            return;
        }
        try (server;
                TcpConnectProbe probe = new TcpConnectProbe()) {
            var targets = ScanTargetParser.parse("::1", false).expand(1);
            assertEquals(
                    ProbeOutcome.State.OPEN,
                    probe.probe(targets.get(0), server.getLocalPort(), 500).state());
        }
    }

    @Test
    void dnsIsResolvedOnceAndFailuresArePerHostNotPerPort() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        ScanPlanResolver resolver =
                new ScanPlanResolver(
                        name -> {
                            lookups.incrementAndGet();
                            if (name.equals("broken.invalid")) throw new UnknownHostException();
                            return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                        });
        var resolved =
                resolver.resolve(
                        plan("server-a,SERVER-A,127.0.0.1,broken.invalid", "1-100", 4, 2, 1000),
                        () -> false);
        assertEquals(1, resolved.targets().size());
        assertEquals(2, lookups.get());
        assertEquals(1, resolved.failures().size());
        assertEquals(ProbeOutcome.State.DNS_FAILED, resolved.failures().get(0).state());
        var excluded =
                new ScanPlan(
                        ScanTargetParser.parse("127.0.0.1", false),
                        ScanTargetParser.parse("server-a", true),
                        List.of(80),
                        ScanPlan.Settings.defaults());
        assertTrue(resolver.resolve(excluded, () -> false).targets().isEmpty());
    }

    @Test
    void failedExclusionResolutionAbortsBeforeAnyProbe() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        TcpConnectProbe probe =
                new TcpConnectProbe() {
                    @Override
                    public ProbeOutcome probe(Target t, int p, int timeout) {
                        probes.incrementAndGet();
                        throw new AssertionError();
                    }
                };
        var plan =
                new ScanPlan(
                        ScanTargetParser.parse("127.0.0.1", false),
                        ScanTargetParser.parse("unknown.invalid", true),
                        List.of(80),
                        ScanPlan.Settings.defaults());
        try (var store = new ScanResultRepository(temp, 16);
                var session =
                        new ScanSession(
                                        plan,
                                        new ScanPlanResolver(
                                                name -> {
                                                    throw new UnknownHostException();
                                                }),
                                        probe,
                                        store)
                                .start()) {
            assertTrue(session.await(2, TimeUnit.SECONDS));
            assertEquals(0, probes.get());
            assertEquals(ScanSession.Phase.FAILED, session.progress().phase());
        }
    }

    @Test
    void closingBeforeStartDeletesTemporaryStore() throws Exception {
        var store = new ScanResultRepository(temp, 16);
        var session =
                new ScanSession(
                        plan("127.0.0.1", "80", 1, 1, 100),
                        new ScanPlanResolver(),
                        new TcpConnectProbe(),
                        store);
        Path file = store.path();
        session.close();
        assertFalse(java.nio.file.Files.exists(file));
        assertThrows(IllegalStateException.class, session::start);
    }
}
