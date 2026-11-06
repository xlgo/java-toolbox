package com.aqishi.toolbox.feature.system.domain;

import com.aqishi.toolbox.util.I18n;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.*;

/**
 * 多 Cron 任务冲突检测与执行时间窗预测器。
 *
 * <p>支持批量解析任务名称与 Cron 表达式，预测未来时间窗内的全部触发时间点，
 * 检测同一时间戳上的精确碰撞（并发冲突）及密集触发窗口。</p>
 */
public final class CronConflictDetector {

    public record TaskSpec(String name, String expression) {}

    public record Collision(Date time, List<String> taskNames) {
        public int concurrency() {
            return taskNames.size();
        }
    }

    public record TaskStat(String name, String expression, int executionCount, Long avgIntervalSeconds) {}

    public record TimelineEntry(Date time, String taskName) {}

    public record ConflictReport(
            int taskCount,
            Duration window,
            int totalExecutions,
            List<Collision> collisions,
            List<TaskStat> taskStats,
            List<TimelineEntry> timelineSample) {}

    private static final int MAX_TOTAL_EXECUTIONS = 10_000;
    private static final int MAX_TASKS = 200;

    private final Duration windowDuration;
    private final ZoneId zoneId;

    public CronConflictDetector(Duration windowDuration, ZoneId zoneId) {
        this.windowDuration = windowDuration != null ? windowDuration : Duration.ofHours(24);
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    public String analyze(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(I18n.get("cron.conflict.empty"));
        }
        if (text.length() > 2_000_000) {
            throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
        }

        List<TaskSpec> tasks = parseTasks(text);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException(I18n.get("cron.conflict.empty"));
        }
        if (tasks.size() > MAX_TASKS) {
            throw new IllegalArgumentException(I18n.get("cron.conflict.taskLimit", MAX_TASKS));
        }

        ConflictReport report = detect(tasks, Instant.now());
        return formatReport(report);
    }

    public ConflictReport detect(List<TaskSpec> tasks, Instant start) {
        Instant end = start.plus(windowDuration);
        Map<Long, List<String>> timeSlots = new TreeMap<>();
        Map<String, List<Long>> taskExecutionTimes = new LinkedHashMap<>();

        int totalExecutions = 0;

        for (TaskSpec task : tasks) {
            List<Long> times = new ArrayList<>();
            Instant current = start;

            while (current.isBefore(end) && totalExecutions < MAX_TOTAL_EXECUTIONS) {
                List<Date> nextDates = CronSchedule.next(task.expression(), 50, current, zoneId);
                if (nextDates.isEmpty()) break;

                boolean advanced = false;
                for (Date d : nextDates) {
                    Instant inst = d.toInstant();
                    if (!inst.isBefore(end)) {
                        break;
                    }
                    long epochSec = inst.getEpochSecond();
                    times.add(epochSec);
                    timeSlots.computeIfAbsent(epochSec, k -> new ArrayList<>()).add(task.name());
                    totalExecutions++;
                    current = inst;
                    advanced = true;
                    if (totalExecutions >= MAX_TOTAL_EXECUTIONS) break;
                }
                if (!advanced) break;
            }
            taskExecutionTimes.put(task.name(), times);
        }

        // 提取精确碰撞列表（按并发数降序，时间升序）
        List<Collision> collisions = new ArrayList<>();
        for (Map.Entry<Long, List<String>> entry : timeSlots.entrySet()) {
            if (entry.getValue().size() > 1) {
                collisions.add(new Collision(new Date(entry.getKey() * 1000L), entry.getValue()));
            }
        }
        collisions.sort((a, b) -> {
            int cmp = Integer.compare(b.concurrency(), a.concurrency());
            return cmp != 0 ? cmp : a.time().compareTo(b.time());
        });

        // 任务统计信息
        List<TaskStat> stats = new ArrayList<>();
        for (TaskSpec task : tasks) {
            List<Long> times = taskExecutionTimes.getOrDefault(task.name(), List.of());
            Long avgInterval = null;
            if (times.size() > 1) {
                long totalInterval = 0;
                for (int i = 1; i < times.size(); i++) {
                    totalInterval += (times.get(i) - times.get(i - 1));
                }
                avgInterval = totalInterval / (times.size() - 1);
            }
            stats.add(new TaskStat(task.name(), task.expression(), times.size(), avgInterval));
        }

        // 时间线采样（前 100 条）
        List<TimelineEntry> timeline = new ArrayList<>();
        int added = 0;
        for (Map.Entry<Long, List<String>> entry : timeSlots.entrySet()) {
            Date d = new Date(entry.getKey() * 1000L);
            for (String name : entry.getValue()) {
                timeline.add(new TimelineEntry(d, name));
                if (++added >= 100) break;
            }
            if (added >= 100) break;
        }

        return new ConflictReport(tasks.size(), windowDuration, totalExecutions, collisions, stats, timeline);
    }

    public static List<TaskSpec> parseTasks(String text) {
        List<TaskSpec> list = new ArrayList<>();
        String[] lines = text.split("\\R");
        int autoId = 1;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            // 过滤行尾注释
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash).trim();
            if (line.isEmpty()) continue;

            // 支持: "Task Name" 0 0 2 * * ? 或 task_name 0 0 2 * * ? 或 直接 cron 表达式
            String name;
            String expr;

            if (line.startsWith("\"")) {
                int nextQuote = line.indexOf('"', 1);
                if (nextQuote > 1) {
                    name = line.substring(1, nextQuote).trim();
                    expr = line.substring(nextQuote + 1).trim();
                } else {
                    name = "task_" + autoId++;
                    expr = line;
                }
            } else {
                String[] tokens = line.split("\\s+");
                if (tokens.length == 5) {
                    // 5 字段 cron 表达式
                    name = "task_" + autoId++;
                    expr = line;
                } else if (tokens.length == 6) {
                    // 检查第 0 个 token 是否像 cron 字段（如 0, *, */5 等以数字或星号开头）
                    char c = tokens[0].charAt(0);
                    if (Character.isDigit(c) || c == '*' || c == '?') {
                        name = "task_" + autoId++;
                        expr = line;
                    } else {
                        name = tokens[0];
                        expr = line.substring(tokens[0].length()).trim();
                    }
                } else if (tokens.length >= 7) {
                    name = tokens[0];
                    expr = line.substring(tokens[0].length()).trim();
                } else {
                    name = "task_" + autoId++;
                    expr = line;
                }
            }

            list.add(new TaskSpec(name, expr));
        }
        return list;
    }

    private static boolean isValidCronFieldCount(String expr) {
        String[] fields = expr.trim().split("\\s+");
        return fields.length == 5 || fields.length == 6;
    }

    private String formatReport(ConflictReport report) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone(zoneId));

        long hours = report.window().toHours();
        String windowDesc = hours >= 24 ? (hours / 24) + "d" : hours + "h";

        sb.append("=== ").append(I18n.get("cron.conflict.section.summary")).append(" ===\n");
        sb.append(I18n.get("cron.conflict.summary", report.taskCount(), windowDesc, report.totalExecutions(), report.collisions().size()))
                .append("\n\n");

        sb.append("=== ").append(I18n.get("cron.conflict.section.collision")).append(" ===\n");
        if (report.collisions().isEmpty()) {
            sb.append(I18n.get("cron.conflict.none")).append("\n\n");
        } else {
            int showLimit = Math.min(report.collisions().size(), 30);
            for (int i = 0; i < showLimit; i++) {
                Collision c = report.collisions().get(i);
                sb.append(String.format("  [!] %s -> %d tasks: %s\n",
                        sdf.format(c.time()), c.concurrency(), String.join(", ", c.taskNames())));
            }
            if (report.collisions().size() > showLimit) {
                sb.append("  ... ").append(report.collisions().size() - showLimit)
                        .append(" more collisions omitted\n");
            }
            sb.append("\n");
        }

        sb.append("=== ").append(I18n.get("cron.conflict.section.stats")).append(" ===\n");
        for (TaskStat stat : report.taskStats()) {
            sb.append("  • ").append(stat.name()).append(" [").append(stat.expression()).append("]: ")
                    .append(I18n.get("cron.conflict.statRow", stat.executionCount()));
            if (stat.avgIntervalSeconds() != null) {
                sb.append(", ").append(I18n.get("cron.conflict.intervalRow", stat.avgIntervalSeconds()));
            }
            sb.append("\n");
        }
        sb.append("\n");

        sb.append("=== ").append(I18n.get("cron.conflict.section.timeline")).append(" ===\n");
        for (TimelineEntry te : report.timelineSample()) {
            sb.append(String.format("  %s  %s\n", sdf.format(te.time()), te.taskName()));
        }
        if (report.totalExecutions() > report.timelineSample().size()) {
            sb.append("  ...\n");
        }

        return sb.toString().trim();
    }
}
