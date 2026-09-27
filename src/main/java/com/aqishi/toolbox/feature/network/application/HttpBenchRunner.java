package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.BenchErrorKind;
import com.aqishi.toolbox.feature.network.domain.BenchPlan;
import com.aqishi.toolbox.feature.network.domain.BenchResult;
import com.aqishi.toolbox.feature.network.domain.BenchSnapshot;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * HTTP 压测执行器（ab / wrk 风格的闭环压测）。
 *
 * <p><b>并发模型</b>：固定 N 个工作线程，每个线程循环"取序号 → 发送 → 等响应 → 记录"，
 * 用阻塞的 {@link HttpClient#send} 保证同一时刻在途请求数<em>结构上</em>不可能超过 N。
 * 取消时中断工作线程：JDK 17 的 {@code send} 被中断会取消底层交换并立即返回，
 * 所以停止是及时的；线程池与客户端执行器在结束时都会关闭，不留线程。</p>
 *
 * <p><b>限速</b>：设置目标速率后，第 k 个请求的计划发送时刻固定为 {@code 起点 + k/速率}，
 * 工作线程先等到计划时刻再发；如果所有线程都被慢响应占着，错过的节拍会在线程空出后立即补发。
 * 延迟从<em>计划时刻</em>算起，排队时间计入延迟（修正协调遗漏，与 wrk2 同理）。
 * 相当于容量为 1 的令牌桶：不攒突发额度，只按节拍放行。</p>
 *
 * <p><b>按时长停止</b>：计划时刻越过截止时刻后不再发新请求；已在途的请求再宽限
 * {@code min(请求超时, 2 秒)}，仍未完成的被取消且不计入统计。</p>
 *
 * <p><b>统计</b>：写入固定数量的 {@link BenchStripe} 分片，{@link #snapshot()} 时合并，
 * 记录路径上没有全局锁。快照可以从任意线程、以任意频率调用。</p>
 */
public final class HttpBenchRunner {

    private static final int MAX_STRIPES = 16;
    private static final Duration MAX_GRACE = Duration.ofSeconds(2);

    private final BenchPlan plan;
    private final BenchStripe[] stripes;
    private final BenchTimeline timeline = new BenchTimeline();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong issued = new AtomicLong();
    private final AtomicLong warmupCompleted = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicLong measureStart = new AtomicLong(Long.MAX_VALUE);
    private final CompletableFuture<BenchResult> completion = new CompletableFuture<>();

    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile long runStartNanos;
    private volatile long measureBoundaryNanos;
    private volatile long endNanos;
    private volatile Instant startedAt;
    private volatile ExecutorService workers;
    private volatile ExecutorService clientExecutor;
    private HttpClient client;
    private HttpRequest request;
    private HttpResponse.BodyHandler<BenchBodySubscriber.Body> bodyHandler;
    private double intervalNanos;

    public HttpBenchRunner(BenchPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
        int count = Math.min(plan.concurrency(), MAX_STRIPES);
        this.stripes = new BenchStripe[count];
        for (int i = 0; i < count; i++) {
            stripes[i] = new BenchStripe();
        }
    }

    public BenchPlan plan() {
        return plan;
    }

    /**
     * 开始压测并立即返回。请求对象非法（例如请求头含非法字符）时在这里同步抛出。
     *
     * @throws IllegalStateException 重复启动
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Runner already started");
        }
        request = BenchClientFactory.request(plan);
        clientExecutor = Executors.newCachedThreadPool(DaemonThreads.factory("http-bench-client"));
        try {
            client = BenchClientFactory.client(plan, clientExecutor);
        } catch (RuntimeException error) {
            DaemonThreads.shutdownQuietly(clientExecutor);
            throw error;
        }
        bodyHandler = BenchBodySubscriber.handler(plan.bodyContains());
        intervalNanos = plan.isRateLimited() ? 1_000_000_000.0 / plan.targetRps() : 0.0;

        startedAt = Instant.now();
        long now = System.nanoTime();
        runStartNanos = now;
        measureBoundaryNanos = now + plan.warmupDuration().toNanos();
        endNanos = plan.stopMode() == BenchPlan.StopMode.DURATION
                ? measureBoundaryNanos + plan.duration().toNanos() : Long.MAX_VALUE;
        if (plan.stopMode() == BenchPlan.StopMode.DURATION) {
            // 按时长停止时计量窗口是固定的，不依赖哪个请求先发出
            measureStart.set(measureBoundaryNanos);
        }

        workers = Executors.newFixedThreadPool(plan.concurrency(), DaemonThreads.factory("http-bench-worker"));
        for (int i = 0; i < plan.concurrency(); i++) {
            BenchStripe stripe = stripes[i % stripes.length];
            workers.execute(() -> workerLoop(stripe));
        }
        workers.shutdown();
        Thread coordinator = DaemonThreads.factory("http-bench-coordinator").newThread(this::coordinate);
        coordinator.start();
    }

    /** 请求停止：不再发新请求，在途请求被取消且不计入统计。可重复调用。 */
    public void cancel() {
        cancelled = true;
        ExecutorService pool = workers;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isFinished() {
        return finished;
    }

    /** 结束（含取消）时完成的 future，不会异常完成。 */
    public CompletableFuture<BenchResult> completion() {
        return completion;
    }

    /** 启动并阻塞到结束，便于脚本与测试使用。 */
    public BenchResult run() throws InterruptedException {
        start();
        try {
            return completion.get();
        } catch (java.util.concurrent.ExecutionException impossible) {
            throw new IllegalStateException(impossible.getCause());
        } catch (InterruptedException interrupted) {
            cancel();
            throw interrupted;
        }
    }

    // ------------------------------------------------------------------ 工作线程

    private void workerLoop(BenchStripe stripe) {
        boolean requestsMode = plan.stopMode() == BenchPlan.StopMode.REQUESTS;
        long warmupRequests = plan.warmupRequests();
        long limit = requestsMode ? warmupRequests + plan.totalRequests() : Long.MAX_VALUE;
        while (!cancelled && !Thread.currentThread().isInterrupted()) {
            long seq = issued.getAndIncrement();
            if (seq >= limit) {
                return;
            }
            long intended = plan.isRateLimited()
                    ? runStartNanos + (long) (seq * intervalNanos) : System.nanoTime();
            if (intended >= endNanos) {
                return;
            }
            if (plan.isRateLimited() && !parkUntil(intended)) {
                return;
            }
            // 限速且落后于节拍时计划时刻可能早于截止，但截止之后不再发任何新请求——
            // 积压的节拍随之放弃，与 wrk2 的处理一致
            if (endNanos != Long.MAX_VALUE && System.nanoTime() >= endNanos) {
                return;
            }
            boolean warmup = requestsMode ? seq < warmupRequests : intended < measureBoundaryNanos;
            long startNanos = plan.isRateLimited() ? intended : System.nanoTime();
            if (!warmup && requestsMode && startNanos < measureStart.get()) {
                measureStart.accumulateAndGet(startNanos, Math::min);
            }
            if (!sendOnce(stripe, warmup, startNanos)) {
                return;
            }
        }
    }

    /** @return false 表示被中断或取消，工作线程应退出 */
    private boolean sendOnce(BenchStripe stripe, boolean warmup, long startNanos) {
        enterFlight();
        HttpResponse<BenchBodySubscriber.Body> response;
        try {
            response = client.send(request, bodyHandler);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                return false;
            }
            if (warmup) {
                warmupCompleted.incrementAndGet();
            } else {
                stripe.recordFailure(BenchErrorKind.fromThrowable(failure), measureStart.get());
            }
            return true;
        } finally {
            inFlight.decrementAndGet();
        }
        long latencyMicros = (System.nanoTime() - startNanos) / 1_000L;
        if (warmup) {
            warmupCompleted.incrementAndGet();
            return true;
        }
        int status = response.statusCode();
        BenchBodySubscriber.Body body = response.body();
        BenchErrorKind error = null;
        if (!plan.isExpectedStatus(status)) {
            error = BenchErrorKind.fromUnexpectedStatus(status);
        } else if (!body.containsExpected) {
            error = BenchErrorKind.ASSERTION_FAILED;
        }
        stripe.recordResponse(latencyMicros, status, body.bytes, error, measureStart.get());
        return true;
    }

    private void enterFlight() {
        int current = inFlight.incrementAndGet();
        int peak = maxInFlight.get();
        while (current > peak && !maxInFlight.compareAndSet(peak, current)) {
            peak = maxInFlight.get();
        }
    }

    /** 等到计划时刻；被中断或取消返回 false。 */
    private boolean parkUntil(long deadline) {
        while (true) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                return false;
            }
            long wait = deadline - System.nanoTime();
            if (wait <= 0) {
                return true;
            }
            LockSupport.parkNanos(this, Math.min(wait, 50_000_000L));
        }
    }

    // ------------------------------------------------------------------ 协调与收尾

    private void coordinate() {
        String failure = null;
        try {
            ExecutorService pool = workers;
            if (plan.stopMode() == BenchPlan.StopMode.DURATION) {
                long grace = Math.min(plan.requestTimeout().toNanos(), MAX_GRACE.toNanos());
                long waitNanos = endNanos + grace - System.nanoTime();
                if (!pool.awaitTermination(Math.max(0L, waitNanos), TimeUnit.NANOSECONDS)) {
                    // 截止后仍未返回的请求不再等：取消并丢弃，避免慢服务把结束时间无限拖长
                    pool.shutdownNow();
                }
            }
            while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                if (cancelled) {
                    pool.shutdownNow();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure = "Coordinator interrupted";
            cancel();
        } catch (RuntimeException error) {
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
            cancel();
        } finally {
            finish(failure);
        }
    }

    private void finish(String failure) {
        DaemonThreads.shutdownQuietly(clientExecutor);
        BenchSnapshot last;
        synchronized (timeline) {
            finished = true;
            last = buildSnapshot(true);
        }
        BenchResult.Outcome outcome = failure != null ? BenchResult.Outcome.FAILED
                : cancelled ? BenchResult.Outcome.CANCELLED : BenchResult.Outcome.COMPLETED;
        completion.complete(new BenchResult(plan, outcome, last, startedAt, failure));
    }

    // ------------------------------------------------------------------ 快照

    /** 当前统计的独立副本，线程安全。未启动时返回空快照。 */
    public BenchSnapshot snapshot() {
        if (!started.get() || runStartNanos == 0L) {
            return BenchSnapshot.idle();
        }
        synchronized (timeline) {
            return buildSnapshot(finished);
        }
    }

    private BenchSnapshot buildSnapshot(boolean sealAll) {
        long now = System.nanoTime();
        long measureFrom = measureStart.get();
        boolean measuring = measureFrom != Long.MAX_VALUE && now >= measureFrom;
        long currentSecond = measuring ? (now - measureFrom) / 1_000_000_000L : 0L;
        BenchStripe.Totals totals = new BenchStripe.Totals();
        long sealBefore = sealAll ? Long.MAX_VALUE : (measuring ? currentSecond : 0L);
        timeline.collect(stripes, totals, sealBefore, sealAll ? -1L : currentSecond);

        long elapsed;
        if (!measuring) {
            elapsed = 0L;
        } else if (sealAll) {
            elapsed = totals.lastEndNanos > measureFrom ? totals.lastEndNanos - measureFrom : 0L;
        } else {
            elapsed = now - measureFrom;
        }
        BenchSnapshot.Phase phase = sealAll ? BenchSnapshot.Phase.FINISHED
                : measuring ? BenchSnapshot.Phase.RUNNING : BenchSnapshot.Phase.WARMUP;
        return timeline.toSnapshot(totals, phase, elapsed, progress(now, totals.completed, sealAll),
                warmupCompleted.get(), inFlight.get(), maxInFlight.get());
    }

    private double progress(long now, long measuredCompleted, boolean done) {
        if (done && !cancelled) {
            return 1.0;
        }
        if (plan.stopMode() == BenchPlan.StopMode.REQUESTS) {
            double total = plan.warmupRequests() + plan.totalRequests();
            return (warmupCompleted.get() + measuredCompleted) / total;
        }
        double total = plan.warmupDuration().toNanos() + plan.duration().toNanos();
        return (now - runStartNanos) / total;
    }
}
