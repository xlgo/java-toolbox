package com.aqishi.toolbox.feature.network.domain;

import com.aqishi.toolbox.feature.network.domain.ScanTargetParser.Targets;

import java.util.List;

public record ScanPlan(Targets included, Targets excluded, List<Integer> ports, Settings settings) {
    public record Settings(
            int timeoutMillis,
            int concurrency,
            int perHost,
            int rate,
            int maxHosts,
            long maxProbes,
            int cacheMiB) {
        public Settings {
            if (timeoutMillis < 50
                    || timeoutMillis > 60000
                    || concurrency < 1
                    || concurrency > 256
                    || perHost < 1
                    || perHost > 64
                    || rate < 1
                    || rate > 10000
                    || maxHosts < 1
                    || maxHosts > 65536
                    || maxProbes < 1
                    || maxProbes > 100000000
                    || cacheMiB < 16
                    || cacheMiB > 1024)
                throw new IllegalArgumentException("portscan.error.settings");
        }

        public static Settings defaults() {
            return new Settings(500, 64, 8, 200, 4096, 1000000, 256);
        }
    }

    public ScanPlan {
        ports = List.copyOf(new java.util.TreeSet<>(ports));
        if (ports.isEmpty() || ports.stream().anyMatch(port -> port < 1 || port > 65535))
            throw new IllegalArgumentException("portscan.error.noPorts");
        Targets targets = ScanTargetParser.subtract(included, excluded);
        if (targets.numericCount() == 0 && targets.names().isEmpty())
            throw new IllegalArgumentException("portscan.error.noTargets");
        checkBudget(targets.numericCount(), ports.size(), settings);
    }

    public Targets targets() {
        return ScanTargetParser.subtract(included, excluded);
    }

    public static void checkBudget(long hosts, int ports, Settings settings) {
        if (hosts > settings.maxHosts)
            throw new IllegalArgumentException("portscan.error.hostBudget");
        if (Math.multiplyExact(hosts, (long) ports) > settings.maxProbes)
            throw new IllegalArgumentException("portscan.error.probeBudget");
    }
}
