package com.aqishi.toolbox.feature.system.domain;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Classpath 诊断全量报告模型。
 */
public record ClasspathDiagnosticReport(
        List<ClasspathSource> sources,
        List<ClassConflict> conflicts,
        List<KnownConflictDetector.Match> knownMatches,
        int totalClassesScanned,
        long elapsedMillis
) {

    public ClasspathDiagnosticReport {
        sources = (sources == null) ? Collections.emptyList() : List.copyOf(sources);
        conflicts = (conflicts == null) ? Collections.emptyList() : List.copyOf(conflicts);
        knownMatches = (knownMatches == null) ? Collections.emptyList() : List.copyOf(knownMatches);
    }

    public int breakingCount() {
        int count = 0;
        for (ClassConflict conflict : conflicts) {
            if (conflict.worstRiskLevel() == ClassConflict.RiskLevel.BREAKING) {
                count++;
            }
        }
        return count;
    }

    public int warningCount() {
        int count = 0;
        for (ClassConflict conflict : conflicts) {
            if (conflict.worstRiskLevel() == ClassConflict.RiskLevel.WARNING) {
                count++;
            }
        }
        return count;
    }

    public int safeCount() {
        int count = 0;
        for (ClassConflict conflict : conflicts) {
            if (conflict.worstRiskLevel() == ClassConflict.RiskLevel.SAFE) {
                count++;
            }
        }
        return count;
    }

    public int knownCount() {
        return knownMatches.size();
    }

    /**
     * 生成一份通用的 Markdown 格式诊断摘要报告（无硬编码中文，方便日志及跨语言导出）。
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Classpath Diagnostic Report\n\n");
        sb.append("## Overview\n");
        sb.append("- Sources Analyzed: ").append(sources.size()).append("\n");
        sb.append("- Total Classes Scanned: ").append(totalClassesScanned).append("\n");
        sb.append("- High Risk (Breaking / NoSuchMethod): ").append(breakingCount()).append("\n");
        sb.append("- Medium Risk (Warning / Version Mismatch): ").append(warningCount()).append("\n");
        sb.append("- Low Risk (Safe / API Compatible): ").append(safeCount()).append("\n");
        sb.append("- Known Dependency Clashes: ").append(knownCount()).append("\n");
        sb.append("- Time Elapsed: ").append(elapsedMillis).append(" ms\n\n");

        if (!knownMatches.isEmpty()) {
            sb.append("## Known Conflicting Artifacts\n");
            for (KnownConflictDetector.Match match : knownMatches) {
                sb.append("### Rule: ").append(match.rule().id()).append("\n");
                sb.append("- Winner: ").append(match.firstSource().displayName()).append("\n");
                sb.append("- Conflicting: ").append(match.secondSource().displayName()).append("\n\n");
            }
        }

        if (breakingCount() > 0) {
            sb.append("## Breaking Class Conflicts\n");
            for (ClassConflict conflict : conflicts) {
                if (conflict.worstRiskLevel() == ClassConflict.RiskLevel.BREAKING) {
                    sb.append("### `").append(conflict.className()).append("`\n");
                    sb.append("- Winner: `").append(conflict.winnerSource().displayName()).append("`\n");
                    for (ClassConflict.MaskedOccurrence masked : conflict.maskedOccurrences()) {
                        sb.append("- Masked: `").append(masked.source().displayName()).append("` (Risk: ")
                                .append(masked.riskLevel()).append(")\n");
                        if (!masked.missingMethods().isEmpty()) {
                            sb.append("  - Missing Methods (NoSuchMethodError risk):\n");
                            for (ClassAbiSignature.MethodSignature m : masked.missingMethods()) {
                                sb.append("    - `").append(m.renderedSignature()).append("`\n");
                            }
                        }
                        if (!masked.incompatibleMethods().isEmpty()) {
                            sb.append("  - Incompatible Method Modifiers:\n");
                            for (ClassConflict.IncompatibleMethodChange m : masked.incompatibleMethods()) {
                                sb.append("    - `").append(m.masked().renderedSignature())
                                        .append("` (Reason: ").append(m.reason()).append(")\n");
                            }
                        }
                        if (!masked.missingFields().isEmpty()) {
                            sb.append("  - Missing Fields (NoSuchFieldError risk):\n");
                            for (ClassAbiSignature.FieldSignature f : masked.missingFields()) {
                                sb.append("    - `").append(f.renderedType()).append(" ").append(f.name()).append("`\n");
                            }
                        }
                    }
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }
}
