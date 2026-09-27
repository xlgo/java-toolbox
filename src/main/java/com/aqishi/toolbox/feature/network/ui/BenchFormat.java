package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.BenchErrorKind;
import com.aqishi.toolbox.feature.network.domain.BenchPlan;
import com.aqishi.toolbox.feature.network.domain.BenchResult;
import com.aqishi.toolbox.feature.network.domain.BenchSnapshot;
import com.aqishi.toolbox.feature.network.domain.LatencyHistogram;
import com.aqishi.toolbox.util.FormatUtils;
import com.aqishi.toolbox.util.I18n;

import java.util.Locale;
import java.util.Map;

/**
 * 压测结果的数字格式与纯文本报告（排版参照 wrk 的输出）。
 *
 * <p>数字统一用 {@link Locale#ROOT} 格式化：报告常被贴进工单和聊天里对比，
 * 不能因为界面语言不同就出现千分位逗号或小数逗号。</p>
 */
final class BenchFormat {

    static final double[] PERCENTILES = {50, 75, 90, 95, 99, 99.9};

    private BenchFormat() {
    }

    /** 微秒转成 wrk 风格的延迟：{@code 850us}、{@code 12.34ms}、{@code 1.23s}。 */
    static String latency(double micros) {
        if (micros < 1_000) {
            return String.format(Locale.ROOT, "%.0fus", micros);
        }
        if (micros < 1_000_000) {
            return String.format(Locale.ROOT, "%.2fms", micros / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.2fs", micros / 1_000_000.0);
    }

    static String rate(double perSecond) {
        return String.format(Locale.ROOT, "%.1f", perSecond);
    }

    static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.2f%%", fraction * 100.0);
    }

    static String percentileLabel(double percentile) {
        return percentile == Math.rint(percentile)
                ? "p" + (long) percentile : "p" + String.format(Locale.ROOT, "%.1f", percentile);
    }

    static String seconds(double seconds) {
        return String.format(Locale.ROOT, "%.2fs", seconds);
    }

    static String errorLabel(BenchErrorKind kind) {
        switch (kind) {
            case CONNECT_REFUSED:
                return I18n.get("tool.httpbench.error.connectRefused");
            case CONNECT_TIMEOUT:
                return I18n.get("tool.httpbench.error.connectTimeout");
            case REQUEST_TIMEOUT:
                return I18n.get("tool.httpbench.error.requestTimeout");
            case TLS_ERROR:
                return I18n.get("tool.httpbench.error.tls");
            case DNS_FAILURE:
                return I18n.get("tool.httpbench.error.dns");
            case IO_ERROR:
                return I18n.get("tool.httpbench.error.io");
            case ASSERTION_FAILED:
                return I18n.get("tool.httpbench.error.assertion");
            case HTTP_4XX:
                return I18n.get("tool.httpbench.error.http4xx");
            case HTTP_5XX:
                return I18n.get("tool.httpbench.error.http5xx");
            default:
                return I18n.get("tool.httpbench.error.other");
        }
    }

    static String outcomeLabel(BenchResult.Outcome outcome) {
        switch (outcome) {
            case CANCELLED:
                return I18n.get("tool.httpbench.outcome.cancelled");
            case FAILED:
                return I18n.get("tool.httpbench.outcome.failed");
            default:
                return I18n.get("tool.httpbench.outcome.completed");
        }
    }

    /** 纯文本报告，便于粘贴到工单或对比两次压测。 */
    static String report(BenchResult result) {
        BenchPlan plan = result.plan();
        BenchSnapshot snapshot = result.snapshot();
        LatencyHistogram latency = snapshot.latency();
        StringBuilder text = new StringBuilder();
        text.append(I18n.get("tool.httpbench.report.title", plan.request().method(),
                plan.request().uri().toString())).append('\n');
        String stop = plan.stopMode() == BenchPlan.StopMode.REQUESTS
                ? I18n.get("tool.httpbench.report.stopRequests", String.valueOf(plan.totalRequests()))
                : I18n.get("tool.httpbench.report.stopDuration", String.valueOf(plan.duration().getSeconds()));
        String target = plan.isRateLimited()
                ? I18n.get("tool.httpbench.report.rateTarget", rate(plan.targetRps()))
                : I18n.get("tool.httpbench.report.rateUnlimited");
        text.append("  ").append(I18n.get("tool.httpbench.report.config",
                String.valueOf(plan.concurrency()), stop, target,
                plan.httpVersion() == java.net.http.HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1")).append('\n');
        text.append("  ").append(I18n.get("tool.httpbench.report.outcome", outcomeLabel(result.outcome())));
        if (!result.failure().isEmpty()) {
            text.append(" - ").append(result.failure());
        }
        text.append("\n\n");

        text.append(String.format(Locale.ROOT, "  %-12s %10s %10s %10s %10s\n",
                I18n.get("tool.httpbench.report.latency"), "Avg", "Stdev", "Min", "Max"));
        text.append(String.format(Locale.ROOT, "  %-12s %10s %10s %10s %10s\n", "",
                latency(latency.mean()), latency(latency.stddev()),
                latency(latency.min()), latency(latency.max())));
        text.append("  ").append(I18n.get("tool.httpbench.report.distribution")).append('\n');
        for (double p : PERCENTILES) {
            text.append(String.format(Locale.ROOT, "    %6s  %10s\n", percentileLabel(p),
                    latency(latency.valueAtPercentile(p))));
        }
        text.append('\n');
        text.append("  ").append(I18n.get("tool.httpbench.report.requests", String.valueOf(snapshot.completed()),
                seconds(snapshot.elapsedSeconds()), FormatUtils.bytes(snapshot.bytesReceived()))).append('\n');
        text.append("  ").append(I18n.get("tool.httpbench.report.errors", String.valueOf(snapshot.errors()),
                percent(snapshot.errorRate()))).append('\n');
        text.append("  Requests/sec: ").append(rate(snapshot.requestsPerSecond())).append('\n');
        text.append("  Transfer/sec: ").append(FormatUtils.bytes((long) snapshot.bytesPerSecond())).append('\n');
        if (plan.isRateLimited()) {
            text.append("  ").append(I18n.get("tool.httpbench.report.coNote")).append('\n');
        }

        text.append('\n').append("  ").append(I18n.get("tool.httpbench.report.statusCodes")).append('\n');
        for (Map.Entry<Integer, Long> entry : snapshot.statusCounts().entrySet()) {
            text.append(String.format(Locale.ROOT, "    %5d  %d\n", entry.getKey(), entry.getValue()));
        }
        if (!snapshot.errorCounts().isEmpty()) {
            text.append('\n').append("  ").append(I18n.get("tool.httpbench.report.errorKinds")).append('\n');
            for (Map.Entry<BenchErrorKind, Long> entry : snapshot.errorCounts().entrySet()) {
                text.append("    ").append(errorLabel(entry.getKey())).append(": ")
                        .append(entry.getValue()).append('\n');
            }
        }
        return text.toString();
    }
}
