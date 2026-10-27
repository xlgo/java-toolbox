package com.aqishi.toolbox.feature.network.domain;

/** Saved only by explicit action; loading a profile never starts network access. */
public record ScanProfile(
        String targets,
        String excludedTargets,
        boolean includeEdges,
        String ports,
        ScanPlan.Settings settings) {
    public ScanProfile {
        if (targets == null || excludedTargets == null || ports == null || settings == null)
            throw new IllegalArgumentException("portscan.error.profile");
        new ScanPlan(
                ScanTargetParser.parse(targets, includeEdges),
                ScanTargetParser.parse(excludedTargets, true),
                java.util.List.copyOf(PortExpressionParser.parse(ports)),
                settings);
    }
}
