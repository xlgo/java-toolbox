package com.aqishi.toolbox.feature.system.domain;

import java.util.*;

/**
 * JVM 参数元数据知识库。
 *
 * <p>涵盖内存、GC、JIT、元空间、日志、安全与诊断参数在不同 JDK 版本下的支持状态
 * （支持、废弃 Deprecated、彻底移除 Removed/Obsolete）及说明文案键名。</p>
 */
public final class JvmOptionDictionary {

    public enum Category {
        MEMORY,
        GC,
        JIT,
        METASPACE,
        DIAGNOSTICS,
        PERFORMANCE,
        SYSTEM_SECURITY,
        GENERAL
    }

    public enum VersionStatus {
        SUPPORTED,
        DEPRECATED,
        REMOVED
    }

    public record OptionMeta(
            String key,
            Category category,
            int deprecatedSince,
            int removedSince,
            String suggestedReplacementKey) {

        public VersionStatus statusForJdk(int targetJdkVersion) {
            if (removedSince > 0 && targetJdkVersion >= removedSince) {
                return VersionStatus.REMOVED;
            }
            if (deprecatedSince > 0 && targetJdkVersion >= deprecatedSince) {
                return VersionStatus.DEPRECATED;
            }
            return VersionStatus.SUPPORTED;
        }
    }

    private static final Map<String, OptionMeta> METAS = new HashMap<>();

    static {
        // 内存与堆
        reg("-Xms", Category.MEMORY);
        reg("-Xmx", Category.MEMORY);
        reg("-Xmn", Category.MEMORY);
        reg("-Xss", Category.MEMORY);
        reg("NewRatio", Category.MEMORY);
        reg("SurvivorRatio", Category.MEMORY);
        reg("InitialRAMPercentage", Category.MEMORY);
        reg("MaxRAMPercentage", Category.MEMORY);
        reg("MinRAMPercentage", Category.MEMORY);
        reg("UseContainerSupport", Category.MEMORY);
        reg("MaxDirectMemorySize", Category.MEMORY);
        reg("AlwaysPreTouch", Category.MEMORY);

        // 元空间与永久代 (PermGen 在 JDK 8 已移除)
        reg("MetaspaceSize", Category.METASPACE);
        reg("MaxMetaspaceSize", Category.METASPACE);
        regWithLifecycle("PermSize", Category.METASPACE, 8, 8, "jvm.replace.metaspace");
        regWithLifecycle("MaxPermSize", Category.METASPACE, 8, 8, "jvm.replace.metaspace");

        // 垃圾回收器启用标志
        reg("UseG1GC", Category.GC);
        reg("UseParallelGC", Category.GC);
        reg("UseSerialGC", Category.GC);
        reg("UseZGC", Category.GC);
        reg("UseShenandoahGC", Category.GC);
        reg("UseEpsilonGC", Category.GC);
        regWithLifecycle("UseConcMarkSweepGC", Category.GC, 9, 14, "jvm.replace.g1zgc");
        regWithLifecycle("UseParNewGC", Category.GC, 8, 9, "jvm.replace.g1");

        // GC 调优参数
        reg("MaxGCPauseMillis", Category.GC);
        reg("G1HeapRegionSize", Category.GC);
        reg("InitiatingHeapOccupancyPercent", Category.GC);
        reg("ParallelGCThreads", Category.GC);
        reg("ConcGCThreads", Category.GC);
        reg("DisableExplicitGC", Category.GC);
        reg("ExplicitGCInvokesConcurrent", Category.GC);
        reg("CMSInitiatingOccupancyFraction", Category.GC, 9, 14, "jvm.replace.g1");
        reg("UseCMSInitiatingOccupancyOnly", Category.GC, 9, 14, "jvm.replace.g1");
        reg("CMSClassUnloadingEnabled", Category.GC, 9, 14, "jvm.replace.g1");

        // 日志与诊断
        reg("-Xlog", Category.DIAGNOSTICS);
        regWithLifecycle("-Xloggc", Category.DIAGNOSTICS, 9, -1, "jvm.replace.xlog");
        regWithLifecycle("PrintGC", Category.DIAGNOSTICS, 9, -1, "jvm.replace.xlog");
        regWithLifecycle("PrintGCDetails", Category.DIAGNOSTICS, 9, -1, "jvm.replace.xlog");
        regWithLifecycle("PrintGCDateStamps", Category.DIAGNOSTICS, 9, -1, "jvm.replace.xlog");
        regWithLifecycle("PrintGCTimeStamps", Category.DIAGNOSTICS, 9, -1, "jvm.replace.xlog");
        reg("HeapDumpOnOutOfMemoryError", Category.DIAGNOSTICS);
        reg("HeapDumpPath", Category.DIAGNOSTICS);
        reg("CrashOnOutOfMemoryError", Category.DIAGNOSTICS);
        reg("ExitOnOutOfMemoryError", Category.DIAGNOSTICS);
        reg("OnOutOfMemoryError", Category.DIAGNOSTICS);
        reg("PrintCommandLineFlags", Category.DIAGNOSTICS);
        reg("TraceClassLoading", Category.DIAGNOSTICS);
        reg("TraceClassUnloading", Category.DIAGNOSTICS);

        // 性能与 JIT 编译
        reg("TieredCompilation", Category.JIT);
        reg("ReservedCodeCacheSize", Category.JIT);
        reg("-Xmaxjitcodesize", Category.JIT);
        reg("UseCompressedOops", Category.PERFORMANCE);
        reg("UseCompressedClassPointers", Category.PERFORMANCE);
        reg("StringTableSize", Category.PERFORMANCE);
        reg("UseStringDeduplication", Category.PERFORMANCE);
        reg("AutoBoxCacheMax", Category.PERFORMANCE);
        regWithLifecycle("UseBiasedLocking", Category.PERFORMANCE, 15, 18, "jvm.replace.locking");

        // 系统属性与模块
        reg("file.encoding", Category.SYSTEM_SECURITY);
        reg("user.timezone", Category.SYSTEM_SECURITY);
        reg("java.security.egd", Category.SYSTEM_SECURITY);
        reg("java.awt.headless", Category.SYSTEM_SECURITY);
    }

    private static void reg(String key, Category cat) {
        METAS.put(normalizeKey(key), new OptionMeta(key, cat, -1, -1, null));
    }

    private static void reg(String key, Category cat, int deprecatedSince, int removedSince, String replacement) {
        METAS.put(normalizeKey(key), new OptionMeta(key, cat, deprecatedSince, removedSince, replacement));
    }

    private static void regWithLifecycle(String key, Category cat, int deprecatedSince, int removedSince, String replacement) {
        METAS.put(normalizeKey(key), new OptionMeta(key, cat, deprecatedSince, removedSince, replacement));
    }

    public static String normalizeKey(String key) {
        if (key == null) return "";
        String k = key.trim();
        if (k.startsWith("-XX:")) {
            k = k.substring(4);
            if (k.startsWith("+") || k.startsWith("-")) {
                k = k.substring(1);
            }
            int eq = k.indexOf('=');
            if (eq > 0) {
                k = k.substring(0, eq);
            }
        }
        return k;
    }

    public static Optional<OptionMeta> findMeta(String key) {
        return Optional.ofNullable(METAS.get(normalizeKey(key)));
    }
}
