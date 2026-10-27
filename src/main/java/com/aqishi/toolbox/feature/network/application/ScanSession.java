package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.*;
import com.aqishi.toolbox.feature.network.domain.ScanTargetParser.Target;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.infra.network.scan.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One bounded scan. At most concurrency probes exist at a time; no host/port Cartesian task list.
 */
public final class ScanSession implements AutoCloseable {
    public enum Phase {
        RESOLVING,
        RUNNING,
        COMPLETE,
        CANCELLED,
        FAILED
    }

    public record Progress(
            Phase phase,
            long total,
            long completed,
            long open,
            long timeouts,
            int hosts,
            int completedHosts,
            int dnsFailures,
            long stored,
            String error) {
        public boolean finished() {
            return phase == Phase.COMPLETE || phase == Phase.CANCELLED || phase == Phase.FAILED;
        }
    }

    private final ScanPlan plan;
    private final ScanPlanResolver resolver;
    private final TcpConnectProbe probe;
    private final ScanResultRepository repository;
    private final AtomicBoolean cancelled = new AtomicBoolean(), disposed = new AtomicBoolean();
    private volatile Progress progress = new Progress(Phase.RESOLVING, 0, 0, 0, 0, 0, 0, 0, 0, "");
    private volatile boolean exited;
    private boolean started;
    private final Thread coordinator;
    private volatile ThreadPoolExecutor workers;

    public ScanSession(
            ScanPlan plan,
            ScanPlanResolver resolver,
            TcpConnectProbe probe,
            ScanResultRepository repository) {
        this.plan = plan;
        this.resolver = resolver;
        this.probe = probe;
        this.repository = repository;
        coordinator = DaemonThreads.factory("portscan-session").newThread(this::run);
    }

    public synchronized ScanSession start() {
        if (disposed.get() || started)
            throw new IllegalStateException("Scan session already started or closed");
        started = true;
        coordinator.start();
        return this;
    }

    public ScanPlan plan() {
        return plan;
    }

    public Progress progress() {
        return progress;
    }

    public ScanResultRepository repository() {
        return repository;
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        coordinator.join(unit.toMillis(timeout));
        return exited;
    }

    private static final class HostCursor {
        final Target target;
        int nextPort, active, completed;

        HostCursor(Target target) {
            this.target = target;
        }
    }

    private record Completed(int host, ProbeOutcome result) {}

    private void run() {
        long total = 0, completed = 0, open = 0, timeouts = 0, stored = 0;
        int hostCount = 0, finishedHosts = 0, dnsFailures = 0;
        String failure = "";
        List<ProbeOutcome> batch = new ArrayList<>();
        HostCursor[] hosts = new HostCursor[0];
        ExecutorCompletionService<Completed> completions = null;
        try {
            var resolved = resolver.resolve(plan, cancelled::get);
            if (cancelled.get()) throw new InterruptedException();
            hostCount = resolved.targets().size();
            dnsFailures = resolved.failures().size();
            total = Math.multiplyExact((long) hostCount, plan.ports().size());
            batch.addAll(resolved.failures());
            repository.registerTargets(resolved.targets());
            if (!batch.isEmpty()) {
                repository.append(batch);
                stored += batch.size();
                batch.clear();
            }
            hosts = resolved.targets().stream().map(HostCursor::new).toArray(HostCursor[]::new);
            workers =
                    new ThreadPoolExecutor(
                            plan.settings().concurrency(),
                            plan.settings().concurrency(),
                            0,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(plan.settings().concurrency() * 2),
                            DaemonThreads.factory("portscan-probe"));
            completions = new ExecutorCompletionService<>(workers);
            int inFlight = 0, round = 0;
            long submitted = 0, lastFlush = System.nanoTime(), lastRateUpdate = System.nanoTime();
            double credits = 1;
            int burst =
                    Math.max(
                            1,
                            Math.min(plan.settings().concurrency(), plan.settings().rate() / 20));
            progress =
                    new Progress(
                            Phase.RUNNING, total, 0, 0, 0, hostCount, 0, dnsFailures, stored, "");
            while (!cancelled.get() && (submitted < total || inFlight > 0)) {
                long now = System.nanoTime();
                credits =
                        Math.min(
                                burst,
                                credits
                                        + (now - lastRateUpdate)
                                                * plan.settings().rate()
                                                / 1_000_000_000.0);
                lastRateUpdate = now;
                boolean hostLimited = false;
                while (submitted < total
                        && inFlight < plan.settings().concurrency()
                        && credits >= 1
                        && !cancelled.get()) {
                    int chosen = -1;
                    for (int visit = 0; visit < hosts.length; visit++) {
                        int index = (round + visit) % hosts.length;
                        HostCursor host = hosts[index];
                        if (host.nextPort < plan.ports().size()
                                && host.active < plan.settings().perHost()) {
                            chosen = index;
                            break;
                        }
                    }
                    if (chosen < 0) {
                        hostLimited = true;
                        break;
                    }
                    int index = chosen;
                    HostCursor host = hosts[index];
                    int port = plan.ports().get(host.nextPort++);
                    host.active++;
                    completions.submit(
                            () -> {
                                ProbeOutcome outcome;
                                try {
                                    outcome =
                                            probe.probe(
                                                    host.target,
                                                    port,
                                                    plan.settings().timeoutMillis());
                                } catch (Exception error) {
                                    outcome =
                                            new ProbeOutcome(
                                                    host.target.source(),
                                                    host.target.ip(),
                                                    port,
                                                    ProbeOutcome.State.ERROR,
                                                    0,
                                                    error.getClass().getSimpleName(),
                                                    System.currentTimeMillis());
                                }
                                return new Completed(index, outcome);
                            });
                    submitted++;
                    inFlight++;
                    round = (index + 1) % hosts.length;
                    credits -= 1;
                }
                long wait = TimeUnit.MILLISECONDS.toNanos(50);
                if (!hostLimited && submitted < total && inFlight < plan.settings().concurrency())
                    wait =
                            Math.min(
                                    wait,
                                    Math.max(
                                            1,
                                            (long)
                                                    Math.ceil(
                                                            (1 - credits)
                                                                    * 1_000_000_000.0
                                                                    / plan.settings().rate())));
                Future<Completed> ready = completions.poll(wait, TimeUnit.NANOSECONDS);
                if (ready != null) {
                    Completed result = ready.get();
                    inFlight--;
                    HostCursor host = hosts[result.host];
                    host.active--;
                    host.completed++;
                    if (host.completed == plan.ports().size()) finishedHosts++;
                    completed++;
                    if (result.result.state() == ProbeOutcome.State.OPEN) open++;
                    if (result.result.state() == ProbeOutcome.State.TIMEOUT) timeouts++;
                    batch.add(result.result);
                }
                if (batch.size() >= 500
                        || (!batch.isEmpty()
                                && System.nanoTime() - lastFlush
                                        >= TimeUnit.MILLISECONDS.toNanos(100))) {
                    repository.append(batch);
                    stored += batch.size();
                    batch.clear();
                    lastFlush = System.nanoTime();
                }
                progress =
                        new Progress(
                                Phase.RUNNING,
                                total,
                                completed,
                                open,
                                timeouts,
                                hostCount,
                                finishedHosts,
                                dnsFailures,
                                stored,
                                "");
            }
        } catch (InterruptedException error) {
            cancelled.set(true);
        } catch (Exception error) {
            failure =
                    error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage();
        } finally {
            Thread.interrupted();
            probe.close();
            ThreadPoolExecutor pool = workers;
            if (pool != null) {
                pool.shutdownNow();
                try {
                    pool.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.interrupted();
                }
            }
            // Completed before cancellation is still a result. Never fabricate outcomes for queued
            // work.
            if (completions != null && failure.isEmpty()) {
                Future<Completed> ready;
                while ((ready = completions.poll()) != null) {
                    try {
                        Completed result = ready.get();
                        if (result.result.state() == ProbeOutcome.State.CANCELLED) continue;
                        HostCursor host = hosts[result.host];
                        host.completed++;
                        if (host.completed == plan.ports().size()) finishedHosts++;
                        completed++;
                        if (result.result.state() == ProbeOutcome.State.OPEN) open++;
                        if (result.result.state() == ProbeOutcome.State.TIMEOUT) timeouts++;
                        batch.add(result.result);
                    } catch (Exception error) {
                        if (failure.isEmpty()) failure = error.getClass().getSimpleName();
                    }
                }
            }
            if (!batch.isEmpty() && failure.isEmpty()) {
                try {
                    repository.append(batch);
                    stored += batch.size();
                } catch (Exception error) {
                    failure =
                            error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage();
                }
            }
            Phase phase =
                    !failure.isEmpty()
                            ? Phase.FAILED
                            : cancelled.get() ? Phase.CANCELLED : Phase.COMPLETE;
            progress =
                    new Progress(
                            phase,
                            total,
                            completed,
                            open,
                            timeouts,
                            hostCount,
                            finishedHosts,
                            dnsFailures,
                            stored,
                            failure);
            exited = true;
            if (disposed.get()) repository.close();
        }
    }

    public void cancel() {
        cancelled.set(true);
        probe.close();
        coordinator.interrupt();
    }

    @Override
    public synchronized void close() {
        disposed.set(true);
        cancel();
        if (!started) {
            exited = true;
            progress = new Progress(Phase.CANCELLED, 0, 0, 0, 0, 0, 0, 0, 0, "");
        }
        if (exited) repository.close();
    }
}
