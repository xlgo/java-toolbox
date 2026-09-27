package com.aqishi.toolbox.feature.system.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@link JarInspector} 的分析结果（不可变）。
 *
 * <p>嵌套库（Spring Boot 的 {@code BOOT-INF/lib/*.jar}、WAR 的 {@code WEB-INF/lib/*.jar}）
 * 也用本类型表示，但不收集条目清单与包列表（{@code entries}/{@code packages} 为空），
 * 避免几百个依赖把内存撑爆。</p>
 *
 * @param name              文件名或嵌套条目路径
 * @param archiveSize       归档字节数（嵌套库为读取到的字节数）
 * @param entryCount        条目总数（含目录）
 * @param totalSize         所有条目解压后大小之和（已知部分）
 * @param compressedSize    所有条目压缩后大小之和（已知部分）
 * @param manifest          MANIFEST 主属性，保持文件内顺序
 * @param coordinates       pom.properties 中的坐标，可能有多个（shade 过的 JAR）
 * @param module            module-info 的 Module 属性，没有则为 {@code null}
 * @param moduleEntry       module-info.class 所在条目
 * @param signatureFiles    META-INF 下的签名文件（.SF/.RSA/.DSA/.EC）
 * @param services          META-INF/services 下的 SPI 声明
 * @param versionedClasses  多版本条目：Java 版本 N → 该目录下类的统计
 * @param majorHistogram    基线类（不含多版本条目与 module-info）的主版本号分布
 * @param classCount        基线类的数量
 * @param previewClasses    使用了预览特性的类的数量
 * @param packages          基线类的包统计（仅顶层）
 * @param layout            归档形态
 * @param springBoot        Spring Boot 可执行包信息；不是时为 {@code null}
 * @param libraries         嵌套库
 * @param entries           条目清单（仅顶层）
 * @param warnings          分析中跳过的条目与原因
 */
public record JarReport(
        String name,
        long archiveSize,
        int entryCount,
        long totalSize,
        long compressedSize,
        Map<String, String> manifest,
        List<MavenCoordinates> coordinates,
        ClassFileInfo.ModuleInfo module,
        String moduleEntry,
        List<String> signatureFiles,
        List<ServiceFile> services,
        SortedMap<Integer, VersionStats> versionedClasses,
        SortedMap<Integer, Integer> majorHistogram,
        int classCount,
        int previewClasses,
        List<PackageStats> packages,
        Layout layout,
        SpringBootInfo springBoot,
        List<NestedLibrary> libraries,
        List<EntryInfo> entries,
        List<Warning> warnings) {

    public JarReport {
        manifest = Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
        coordinates = List.copyOf(coordinates);
        signatureFiles = List.copyOf(signatureFiles);
        services = List.copyOf(services);
        versionedClasses = Collections.unmodifiableSortedMap(new TreeMap<>(versionedClasses));
        majorHistogram = Collections.unmodifiableSortedMap(new TreeMap<>(majorHistogram));
        packages = List.copyOf(packages);
        libraries = List.copyOf(libraries);
        entries = List.copyOf(entries);
        warnings = List.copyOf(warnings);
    }

    /** 归档形态。 */
    public enum Layout {
        /** 普通 JAR */
        PLAIN,
        /** Spring Boot 可执行 JAR（BOOT-INF/classes + BOOT-INF/lib） */
        SPRING_BOOT_JAR,
        /** Spring Boot 可执行 WAR（WEB-INF + org/springframework/boot/loader） */
        SPRING_BOOT_WAR,
        /** 标准 WAR（WEB-INF/classes、WEB-INF/lib） */
        WAR,
        /** EAR（顶层带 .jar/.war 模块或 META-INF/application.xml） */
        EAR
    }

    /** 多版本目录 {@code META-INF/versions/N/} 下类的统计。 */
    public record VersionStats(int release, int classCount, int maxMajor) {
    }

    /** 一个包及其类数。 */
    public record PackageStats(String name, int classCount) {
    }

    /** {@code META-INF/services/<service>} 及其中声明的实现类。 */
    public record ServiceFile(String service, List<String> providers) {
        public ServiceFile {
            providers = List.copyOf(providers);
        }
    }

    /** Spring Boot 可执行包的补充信息。 */
    public record SpringBootInfo(String version, String startClass, int loaderClassCount,
                                 List<String> classpathIndex, List<String> layers) {
        public SpringBootInfo {
            classpathIndex = List.copyOf(classpathIndex);
            layers = List.copyOf(layers);
        }
    }

    /**
     * 嵌套库。{@code report} 为 {@code null} 表示没有展开分析（超过嵌套深度或读取失败，原因见 warnings）。
     */
    public record NestedLibrary(String path, long size, JarReport report) {

        /** 优先 pom.properties 坐标，其次 MANIFEST 版本，最后按文件名猜。 */
        public MavenCoordinates bestCoordinates() {
            if (report != null && !report.coordinates().isEmpty()) {
                return report.primaryCoordinates(path);
            }
            return MavenCoordinates.guessFromFileName(path);
        }
    }

    /** 顶层条目。{@code size}/{@code compressedSize} 未知时为 -1。 */
    public record EntryInfo(String name, long size, long compressedSize, boolean directory) {
    }

    /** 跳过某个条目或提前停止的原因。 */
    public record Warning(Code code, String entry, String detail) {
        public enum Code {
            /** 条目超过单条读取上限 */
            ENTRY_TOO_LARGE,
            /** 压缩比过高（疑似 zip 炸弹） */
            COMPRESSION_RATIO,
            /** class 文件损坏 */
            CORRUPT_CLASS,
            /** 嵌套归档无法读取 */
            NESTED_UNREADABLE,
            /** 超过嵌套深度，未展开 */
            NESTED_TOO_DEEP,
            /** 嵌套归档条目数超过上限，已提前停止 */
            NESTED_ENTRY_LIMIT,
            /** MANIFEST 无法解析 */
            BAD_MANIFEST
        }
    }

    // ==========================================
    // 便捷访问
    // ==========================================
    public String manifestValue(String key) {
        return manifest.get(key);
    }

    /** 基线类中最高的主版本号；没有类时为 0。 */
    public int maxMajor() {
        return majorHistogram.isEmpty() ? 0 : majorHistogram.lastKey();
    }

    /** 运行本归档自身代码所需的最低 Java 特性版本；没有类时为 0。 */
    public int requiredRelease() {
        return ClassFileInfo.featureRelease(maxMajor());
    }

    /** 连同嵌套库在内的最高主版本号（多版本条目除外）。 */
    public int maxMajorIncludingLibraries() {
        int max = maxMajor();
        for (NestedLibrary library : libraries) {
            if (library.report() != null) {
                max = Math.max(max, library.report().maxMajorIncludingLibraries());
            }
        }
        return max;
    }

    public boolean signed() {
        return !signatureFiles.isEmpty();
    }

    /** MANIFEST 声明 {@code Multi-Release: true}，或实际存在多版本条目。 */
    public boolean multiRelease() {
        return "true".equalsIgnoreCase(manifestValue("Multi-Release")) || !versionedClasses.isEmpty();
    }

    /**
     * 最能代表本归档的坐标：只有一个时就是它；shade 过的 JAR 有多个时，
     * 挑 artifactId 与文件名吻合的那个，都不吻合取第一个。
     */
    public MavenCoordinates primaryCoordinates(String fileName) {
        if (coordinates.isEmpty()) {
            return null;
        }
        if (coordinates.size() > 1 && fileName != null) {
            MavenCoordinates guess = MavenCoordinates.guessFromFileName(fileName);
            if (guess != null) {
                for (MavenCoordinates candidate : coordinates) {
                    if (guess.artifactId().equals(candidate.artifactId())) {
                        return candidate;
                    }
                }
            }
        }
        return coordinates.get(0);
    }
}
