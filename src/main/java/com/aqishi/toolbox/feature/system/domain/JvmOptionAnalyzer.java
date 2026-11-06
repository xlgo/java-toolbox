package com.aqishi.toolbox.feature.system.domain;

import com.aqishi.toolbox.feature.system.domain.JvmOptionDictionary.*;
import com.aqishi.toolbox.feature.system.domain.JvmOptionParser.*;
import com.aqishi.toolbox.util.I18n;

import java.util.*;
import java.util.regex.*;

/**
 * JVM 启动参数分析器。
 *
 * <p>评估参数语义、冲突检测、重复覆盖、JDK 版本兼容性（废弃/移除检测）及最佳实践建议，
 * 纯离线生成结构化中文排查报告。</p>
 */
public final class JvmOptionAnalyzer {

    private static final Set<String> GC_FLAGS = Set.of(
            "UseG1GC", "UseParallelGC", "UseSerialGC", "UseConcMarkSweepGC",
            "UseZGC", "UseShenandoahGC", "UseEpsilonGC"
    );

    private static final Pattern SIZE_UNIT_PAT = Pattern.compile("^(\\d+)([kKmMgGtT])?$");

    private final int targetJdkVersion;

    public JvmOptionAnalyzer(int targetJdkVersion) {
        this.targetJdkVersion = targetJdkVersion > 0 ? targetJdkVersion : 17;
    }

    public String analyze(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException(I18n.get("jvm.empty"));
        }
        if (rawText.length() > 2_000_000) {
            throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
        }

        ParseResult parseResult = new JvmOptionParser().parse(rawText);
        List<OptionToken> options = parseResult.options();
        if (options.isEmpty()) {
            throw new IllegalArgumentException(I18n.get("jvm.empty"));
        }

        StringBuilder out = new StringBuilder();

        // 统计数据
        long sysPropCount = options.stream().filter(o -> o.type() == OptionType.SYSTEM_PROP).count();
        long agentCount = options.stream().filter(o -> o.type() == OptionType.AGENT).count();
        long jvmOptCount = options.size() - sysPropCount - agentCount;

        // 提取堆大小与 GC 信息
        String xms = null;
        String xmx = null;
        String xmn = null;
        List<String> enabledGcs = new ArrayList<>();
        Map<String, List<OptionToken>> keyGroup = new LinkedHashMap<>();

        boolean hasHeapDump = false;
        boolean hasHeapDumpPath = false;
        boolean hasContainerRam = false;

        for (OptionToken token : options) {
            String norm = JvmOptionDictionary.normalizeKey(token.key());
            keyGroup.computeIfAbsent(norm, k -> new ArrayList<>()).add(token);

            if (token.key().equals("-Xms")) xms = token.value();
            if (token.key().equals("-Xmx")) xmx = token.value();
            if (token.key().equals("-Xmn")) xmn = token.value();

            if (GC_FLAGS.contains(token.key()) && token.isBooleanFlag() && token.booleanValue()) {
                enabledGcs.add(token.key());
            }

            if (token.key().equals("HeapDumpOnOutOfMemoryError") && token.isBooleanFlag() && token.booleanValue()) {
                hasHeapDump = true;
            }
            if (token.key().equals("HeapDumpPath")) {
                hasHeapDumpPath = true;
            }
            if (token.key().equals("MaxRAMPercentage") || token.key().equals("InitialRAMPercentage")) {
                hasContainerRam = true;
            }
        }

        // 1. 摘要总览
        out.append("=== ").append(I18n.get("jvm.section.summary")).append(" ===\n");
        out.append(I18n.get("jvm.targetJdk", targetJdkVersion)).append("\n");
        out.append(I18n.get("jvm.summary", jvmOptCount, sysPropCount, agentCount)).append("\n");

        if (!enabledGcs.isEmpty()) {
            out.append(I18n.get("jvm.detectedGc", String.join(", ", enabledGcs))).append("\n");
        }
        if (xms != null || xmx != null) {
            out.append(I18n.get("jvm.detectedHeap", xms != null ? xms : "-", xmx != null ? xmx : "-")).append("\n");
        }
        out.append("\n");

        // 2. 诊断与告警
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        // 2.1 GC 冲突
        if (enabledGcs.size() > 1) {
            errors.add(I18n.get("jvm.diag.conflict.gc", String.join(" vs ", enabledGcs)));
        }

        // 2.2 堆大小冲突检测
        Long xmsBytes = parseSizeToBytes(xms);
        Long xmxBytes = parseSizeToBytes(xmx);
        Long xmnBytes = parseSizeToBytes(xmn);

        if (xmsBytes != null && xmxBytes != null) {
            if (xmsBytes > xmxBytes) {
                errors.add(I18n.get("jvm.diag.conflict.heap", xms, xmx));
            } else if (xmsBytes < xmxBytes) {
                recommendations.add(I18n.get("jvm.diag.recommend.heap", xms, xmx));
            }
        }
        if (xmnBytes != null && xmxBytes != null && xmnBytes >= xmxBytes) {
            errors.add(I18n.get("jvm.diag.conflict.young", xmn, xmx));
        }

        // 2.3 重复参数检测
        for (Map.Entry<String, List<OptionToken>> entry : keyGroup.entrySet()) {
            if (entry.getValue().size() > 1 && !entry.getKey().startsWith("D") && !entry.getKey().equals("-add-opens")) {
                OptionToken last = entry.getValue().get(entry.getValue().size() - 1);
                warnings.add(I18n.get("jvm.diag.duplicate", entry.getKey(), last.raw()));
            }
        }

        // 2.4 版本废弃与移除检测
        for (OptionToken token : options) {
            String norm = JvmOptionDictionary.normalizeKey(token.key());
            Optional<OptionMeta> metaOpt = JvmOptionDictionary.findMeta(norm);
            if (metaOpt.isPresent()) {
                OptionMeta meta = metaOpt.get();
                VersionStatus status = meta.statusForJdk(targetJdkVersion);
                String replaceHint = meta.suggestedReplacementKey() != null ? I18n.get(meta.suggestedReplacementKey()) : "";
                if (status == VersionStatus.REMOVED) {
                    errors.add(I18n.get("jvm.diag.removed", token.raw(), targetJdkVersion, replaceHint));
                } else if (status == VersionStatus.DEPRECATED) {
                    warnings.add(I18n.get("jvm.diag.deprecated", token.raw(), targetJdkVersion, replaceHint));
                }
            }
        }

        // 2.5 最佳实践建议
        if (!hasHeapDump) {
            recommendations.add(I18n.get("jvm.diag.recommend.heapdump"));
        } else if (!hasHeapDumpPath) {
            recommendations.add(I18n.get("jvm.diag.recommend.dumppath"));
        }
        if (!hasContainerRam && xmx == null) {
            recommendations.add(I18n.get("jvm.diag.recommend.container"));
        }

        // 输出诊断板块
        out.append("=== ").append(I18n.get("jvm.section.diag")).append(" ===\n");
        if (errors.isEmpty() && warnings.isEmpty() && recommendations.isEmpty()) {
            out.append(I18n.get("jvm.diag.allGood")).append("\n");
        } else {
            for (String err : errors) {
                out.append("  [!] ").append(err).append("\n");
            }
            for (String warn : warnings) {
                out.append("  [*] ").append(warn).append("\n");
            }
            for (String rec : recommendations) {
                out.append("  [i] ").append(rec).append("\n");
            }
        }
        out.append("\n");

        // 3. 参数详细清单（按分类）
        out.append("=== ").append(I18n.get("jvm.section.details")).append(" ===\n");
        Map<Category, List<OptionToken>> categorized = new LinkedHashMap<>();
        for (Category cat : Category.values()) {
            categorized.put(cat, new ArrayList<>());
        }

        for (OptionToken token : options) {
            Category cat = resolveCategory(token);
            categorized.get(cat).add(token);
        }

        for (Map.Entry<Category, List<OptionToken>> entry : categorized.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            Category cat = entry.getKey();
            out.append("• ").append(I18n.get("jvm.cat." + cat.name())).append(" (").append(entry.getValue().size()).append("):\n");

            for (OptionToken token : entry.getValue()) {
                out.append("   - ").append(token.raw());
                String desc = describeOption(token);
                if (desc != null && !desc.isEmpty()) {
                    out.append("  ->  ").append(desc);
                }
                out.append("\n");
            }
            out.append("\n");
        }

        // 忽略的非 JVM 指令或文件
        if (!parseResult.ignoredTokens().isEmpty()) {
            out.append("• ").append(I18n.get("jvm.section.ignored")).append(": ")
                    .append(String.join(" ", parseResult.ignoredTokens())).append("\n");
        }

        return out.toString().trim();
    }

    private Category resolveCategory(OptionToken token) {
        if (token.type() == OptionType.SYSTEM_PROP || token.type() == OptionType.MODULE_ACCESS) {
            return Category.SYSTEM_SECURITY;
        }
        String norm = JvmOptionDictionary.normalizeKey(token.key());
        Optional<OptionMeta> meta = JvmOptionDictionary.findMeta(norm);
        if (meta.isPresent()) {
            return meta.get().category();
        }
        if (token.key().startsWith("-Xm") || token.key().startsWith("-Xs")) {
            return Category.MEMORY;
        }
        return Category.GENERAL;
    }

    private String describeOption(OptionToken token) {
        String norm = JvmOptionDictionary.normalizeKey(token.key());
        String i18nKey = "jvm.opt." + norm;
        String desc = I18n.get(i18nKey);
        if (desc.equals(i18nKey)) {
            // 没有专用说明，给出通用格式描述
            if (token.type() == OptionType.SYSTEM_PROP) {
                return I18n.get("jvm.opt.sysprop.desc", token.key(), token.value());
            }
            if (token.type() == OptionType.AGENT) {
                return I18n.get("jvm.opt.agent.desc", token.value());
            }
            return "";
        }
        return desc;
    }

    public static Long parseSizeToBytes(String str) {
        if (str == null || str.isBlank()) return null;
        Matcher m = SIZE_UNIT_PAT.matcher(str.trim());
        if (!m.matches()) return null;
        long num = Long.parseLong(m.group(1));
        String unit = m.group(2);
        if (unit == null) return num;
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "k" -> num * 1024L;
            case "m" -> num * 1024L * 1024L;
            case "g" -> num * 1024L * 1024L * 1024L;
            case "t" -> num * 1024L * 1024L * 1024L * 1024L;
            default -> num;
        };
    }
}
