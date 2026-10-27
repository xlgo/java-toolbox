package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.*;
import com.aqishi.toolbox.feature.network.domain.ScanTargetParser.Target;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Bounded process-wide DNS pool: an uninterruptible platform lookup cannot spawn unlimited threads.
 */
public final class ScanPlanResolver {
    @FunctionalInterface
    public interface Dns {
        InetAddress[] lookup(String name) throws Exception;
    }

    public record Resolved(List<Target> targets, List<ProbeOutcome> failures) {
        public Resolved {
            targets = List.copyOf(targets);
            failures = List.copyOf(failures);
        }
    }

    private static final ThreadPoolExecutor DNS_POOL =
            new ThreadPoolExecutor(
                    4,
                    4,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    DaemonThreads.factory("portscan-dns"),
                    new ThreadPoolExecutor.AbortPolicy());
    private final Dns dns;

    public ScanPlanResolver() {
        this(InetAddress::getAllByName);
    }

    public ScanPlanResolver(Dns dns) {
        this.dns = dns;
    }

    public Resolved resolve(ScanPlan plan, BooleanSupplier cancelled) throws InterruptedException {
        var included = plan.targets();
        Map<String, Target> targets = new LinkedHashMap<>();
        List<ProbeOutcome> failures = new ArrayList<>();
        Set<String> excluded = new HashSet<>(plan.excluded().literals().keySet());
        for (String name : plan.excluded().names()) {
            check(cancelled);
            try {
                excluded.addAll(resolveName(name, plan.settings().timeoutMillis(), cancelled));
            } catch (InterruptedException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalArgumentException("portscan.error.excludeDns|" + name);
            }
        }
        for (Target target : included.expand(plan.settings().maxHosts())) {
            check(cancelled);
            if (!excluded.contains(target.ip())) targets.putIfAbsent(target.ip(), target);
        }
        for (String name : included.names()) {
            check(cancelled);
            List<String> addresses;
            try {
                addresses = resolveName(name, plan.settings().timeoutMillis(), cancelled);
                if (addresses.isEmpty()) throw new UnknownHostException(name);
            } catch (InterruptedException error) {
                throw error;
            } catch (Exception error) {
                failures.add(
                        new ProbeOutcome(
                                name,
                                "",
                                0,
                                ProbeOutcome.State.DNS_FAILED,
                                0,
                                error.getClass().getSimpleName(),
                                System.currentTimeMillis()));
                continue;
            }
            for (String ip : addresses) {
                if (!plan.excluded().contains(ip) && !excluded.contains(ip))
                    targets.putIfAbsent(ip, new Target(name, ip));
                ScanPlan.checkBudget(targets.size(), plan.ports().size(), plan.settings());
            }
        }
        ScanPlan.checkBudget(targets.size(), plan.ports().size(), plan.settings());
        return new Resolved(new ArrayList<>(targets.values()), failures);
    }

    private List<String> resolveName(String name, int timeout, BooleanSupplier cancelled)
            throws Exception {
        Future<InetAddress[]> future = DNS_POOL.submit(() -> dns.lookup(name));
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1000, timeout));
        try {
            while (true) {
                check(cancelled);
                long left = end - System.nanoTime();
                if (left <= 0) throw new TimeoutException();
                try {
                    return Arrays.stream(
                                    future.get(
                                            Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)),
                                            TimeUnit.NANOSECONDS))
                            .map(InetAddress::getHostAddress)
                            .distinct()
                            .toList();
                } catch (TimeoutException timeoutSlice) {
                    if (System.nanoTime() >= end) throw timeoutSlice;
                }
            }
        } finally {
            future.cancel(true);
            DNS_POOL.purge();
        }
    }

    private static void check(BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedException();
    }
}
