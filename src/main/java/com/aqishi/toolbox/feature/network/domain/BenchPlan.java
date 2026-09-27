package com.aqishi.toolbox.feature.network.domain;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/**
 * 一次压测的完整参数，构造后不可变，所有取值在 {@link Builder#build()} 时校验并收敛到硬上限内。
 *
 * <p>停止条件二选一：{@link StopMode#REQUESTS} 发满 {@code totalRequests} 个（不含预热）；
 * {@link StopMode#DURATION} 跑满 {@code duration}（不含预热时长）。预热的单位跟随停止条件——
 * 按次数停就预热若干个请求，按时长停就预热若干秒；预热期间的请求照常发送、不计入任何统计，
 * 用来让连接池、JIT 和服务端缓存先热起来。</p>
 *
 * <p>目标速率 {@code targetRps > 0} 时按固定节拍发送：第 k 个请求的<em>计划发送时刻</em>是
 * {@code 起点 + k / targetRps}。延迟从计划时刻而不是实际发出时刻算起——
 * 服务端变慢导致工作线程全被占满时，排队等待的时间也计入延迟，避免"协调遗漏"
 * （coordinated omission）把卡顿藏起来。不限速时延迟就是实际发送到收完响应的耗时。</p>
 */
public final class BenchPlan {

    public static final int MAX_CONCURRENCY = 1000;
    public static final long MAX_TOTAL_REQUESTS = 10_000_000L;
    public static final Duration MAX_DURATION = Duration.ofHours(1);
    public static final double MAX_TARGET_RPS = 1_000_000.0;
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /** 停止条件。 */
    public enum StopMode {
        REQUESTS,
        DURATION
    }

    private final BenchRequest request;
    private final int concurrency;
    private final StopMode stopMode;
    private final long totalRequests;
    private final Duration duration;
    private final double targetRps;
    private final long warmupRequests;
    private final Duration warmupDuration;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final HttpClient.Version httpVersion;
    private final boolean followRedirects;
    private final boolean insecureTls;
    private final int expectedStatusMin;
    private final int expectedStatusMax;
    private final String bodyContains;

    private BenchPlan(Builder builder) {
        this.request = builder.request;
        this.concurrency = builder.concurrency;
        this.stopMode = builder.stopMode;
        this.totalRequests = builder.totalRequests;
        this.duration = builder.duration;
        this.targetRps = builder.targetRps;
        this.warmupRequests = builder.warmupRequests;
        this.warmupDuration = builder.warmupDuration;
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
        this.httpVersion = builder.httpVersion;
        this.followRedirects = builder.followRedirects;
        this.insecureTls = builder.insecureTls;
        this.expectedStatusMin = builder.expectedStatusMin;
        this.expectedStatusMax = builder.expectedStatusMax;
        this.bodyContains = builder.bodyContains;
    }

    public static Builder builder(BenchRequest request) {
        return new Builder(request);
    }

    public BenchRequest request() {
        return request;
    }

    public int concurrency() {
        return concurrency;
    }

    public StopMode stopMode() {
        return stopMode;
    }

    /** 按次数停止时要发出的计量请求数（不含预热）。 */
    public long totalRequests() {
        return totalRequests;
    }

    /** 按时长停止时的计量时长（不含预热）。 */
    public Duration duration() {
        return duration;
    }

    /** 目标速率（请求/秒），0 表示不限速。 */
    public double targetRps() {
        return targetRps;
    }

    public boolean isRateLimited() {
        return targetRps > 0;
    }

    /** 按次数停止时的预热请求数。 */
    public long warmupRequests() {
        return stopMode == StopMode.REQUESTS ? warmupRequests : 0L;
    }

    /** 按时长停止时的预热时长。 */
    public Duration warmupDuration() {
        return stopMode == StopMode.DURATION ? warmupDuration : Duration.ZERO;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public HttpClient.Version httpVersion() {
        return httpVersion;
    }

    public boolean followRedirects() {
        return followRedirects;
    }

    /** 是否跳过证书与主机名校验；只能由用户显式开启。 */
    public boolean insecureTls() {
        return insecureTls;
    }

    public int expectedStatusMin() {
        return expectedStatusMin;
    }

    public int expectedStatusMax() {
        return expectedStatusMax;
    }

    /** 响应体必须包含的文本，空串表示不检查。 */
    public String bodyContains() {
        return bodyContains;
    }

    public boolean isExpectedStatus(int status) {
        return status >= expectedStatusMin && status <= expectedStatusMax;
    }

    /**
     * 解析期望状态码范围：{@code 200-299}、{@code 200}（单个）或 {@code 2xx} 形式。
     *
     * @return 两个元素的数组 {min, max}
     * @throws IllegalArgumentException 格式错误或超出 100-599
     */
    public static int[] parseStatusRange(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        int[] range;
        try {
            if (value.matches("[1-5]xx")) {
                int base = (value.charAt(0) - '0') * 100;
                range = new int[]{base, base + 99};
            } else if (value.contains("-")) {
                String[] parts = value.split("-", 2);
                range = new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
            } else {
                int single = Integer.parseInt(value);
                range = new int[]{single, single};
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid status range: " + text, error);
        }
        if (range[0] < 100 || range[1] > 599 || range[0] > range[1]) {
            throw new IllegalArgumentException("Expected status range must lie within 100-599");
        }
        return range;
    }

    /** 可变构建器；未设置的项取常见默认值。 */
    public static final class Builder {
        private final BenchRequest request;
        private int concurrency = 10;
        private StopMode stopMode = StopMode.REQUESTS;
        private long totalRequests = 1000;
        private Duration duration = Duration.ofSeconds(10);
        private double targetRps;
        private long warmupRequests;
        private Duration warmupDuration = Duration.ZERO;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private HttpClient.Version httpVersion = HttpClient.Version.HTTP_1_1;
        private boolean followRedirects;
        private boolean insecureTls;
        private int expectedStatusMin = 200;
        private int expectedStatusMax = 299;
        private String bodyContains = "";

        private Builder(BenchRequest request) {
            this.request = Objects.requireNonNull(request, "request");
        }

        public Builder concurrency(int value) {
            this.concurrency = value;
            return this;
        }

        public Builder totalRequests(long value) {
            this.stopMode = StopMode.REQUESTS;
            this.totalRequests = value;
            return this;
        }

        public Builder duration(Duration value) {
            this.stopMode = StopMode.DURATION;
            this.duration = value;
            return this;
        }

        public Builder targetRps(double value) {
            this.targetRps = value;
            return this;
        }

        public Builder warmupRequests(long value) {
            this.warmupRequests = value;
            return this;
        }

        public Builder warmupDuration(Duration value) {
            this.warmupDuration = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            this.connectTimeout = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            this.requestTimeout = value;
            return this;
        }

        public Builder httpVersion(HttpClient.Version value) {
            this.httpVersion = value;
            return this;
        }

        public Builder followRedirects(boolean value) {
            this.followRedirects = value;
            return this;
        }

        public Builder insecureTls(boolean value) {
            this.insecureTls = value;
            return this;
        }

        public Builder expectedStatus(int min, int max) {
            this.expectedStatusMin = min;
            this.expectedStatusMax = max;
            return this;
        }

        public Builder bodyContains(String value) {
            this.bodyContains = value == null ? "" : value;
            return this;
        }

        /**
         * @throws IllegalArgumentException 参数越界；消息为英文，界面层据此提示
         */
        public BenchPlan build() {
            if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
                throw new IllegalArgumentException("Concurrency must be between 1 and " + MAX_CONCURRENCY);
            }
            if (stopMode == StopMode.REQUESTS && (totalRequests < 1 || totalRequests > MAX_TOTAL_REQUESTS)) {
                throw new IllegalArgumentException("Total requests must be between 1 and " + MAX_TOTAL_REQUESTS);
            }
            if (stopMode == StopMode.DURATION && (duration == null || duration.isNegative()
                    || duration.isZero() || duration.compareTo(MAX_DURATION) > 0)) {
                throw new IllegalArgumentException("Duration must be between 1 ms and 1 hour");
            }
            if (Double.isNaN(targetRps) || targetRps < 0 || targetRps > MAX_TARGET_RPS) {
                throw new IllegalArgumentException("Target rate must be between 0 and " + (long) MAX_TARGET_RPS);
            }
            if (warmupRequests < 0 || warmupRequests > MAX_TOTAL_REQUESTS) {
                throw new IllegalArgumentException("Warm-up requests out of range");
            }
            if (warmupDuration == null || warmupDuration.isNegative()
                    || warmupDuration.compareTo(MAX_DURATION) > 0) {
                throw new IllegalArgumentException("Warm-up duration out of range");
            }
            checkTimeout(connectTimeout, "Connect timeout");
            checkTimeout(requestTimeout, "Request timeout");
            if (httpVersion == null) {
                throw new IllegalArgumentException("HTTP version is required");
            }
            if (expectedStatusMin < 100 || expectedStatusMax > 599 || expectedStatusMin > expectedStatusMax) {
                throw new IllegalArgumentException("Expected status range must lie within 100-599");
            }
            return new BenchPlan(this);
        }

        private static void checkTimeout(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero() || value.compareTo(MAX_TIMEOUT) > 0) {
                throw new IllegalArgumentException(name + " must be between 1 ms and 10 minutes");
            }
        }
    }
}
