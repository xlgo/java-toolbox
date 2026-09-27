package com.aqishi.toolbox.feature.system.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * JAR / WAR / EAR 分析器：全程在内存里读流，绝不解压到磁盘。
 *
 * <p>顶层归档用 {@link ZipFile} 打开——中央目录给出可靠的条目大小，未读的条目也不会被解压；
 * 嵌套库（{@code BOOT-INF/lib/*.jar} 等）从外层条目的流里用 {@code ZipInputStream} 递归分析，
 * 深度受 {@link Limits#maxDepth()} 限制（EAR → WAR → WEB-INF/lib 正好两层）。</p>
 *
 * <p>类版本统计只读每个 class 的前 8 个字节；只有 module-info 做完整解析。
 * {@code META-INF/versions/N/} 下的多版本条目不计入基线版本——它们只在 Java N 及以上才会被加载，
 * 把它们算进“最低 Java 版本”会得出“这个 Java 8 的库需要 Java 11”这样的错误结论。
 * module-info 同理不计入基线：Java 8 运行时直接忽略它。</p>
 */
public final class JarInspector {

    /** 单个归档里最多记录多少条警告，后续的只计数不记录。 */
    static final int MAX_WARNINGS = 500;

    /**
     * 安全上限。
     *
     * @param maxEntries          单个归档允许的最大条目数（顶层超过直接报错，嵌套超过则截断）
     * @param maxEntryBytes       为解析而读取的单个条目最大字节数（MANIFEST、class、services 等）
     * @param maxNestedBytes      单个嵌套归档允许的最大字节数
     * @param maxCompressionRatio 允许的最大压缩比
     * @param maxDepth            嵌套归档展开深度，顶层为 0
     * @param timeout             整体超时
     */
    public record Limits(int maxEntries, int maxEntryBytes, long maxNestedBytes, int maxCompressionRatio,
                         int maxDepth, Duration timeout) {

        public Limits {
            if (maxEntries < 1 || maxEntryBytes < 1024 || maxNestedBytes < 1 || maxDepth < 0) {
                throw new IllegalArgumentException("invalid limits");
            }
            Objects.requireNonNull(timeout, "timeout");
        }

        public static Limits defaults() {
            return new Limits(200_000, 16 * 1024 * 1024, 512L * 1024 * 1024, 200, 2, Duration.ofMinutes(3));
        }

        public Limits withMaxEntries(int value) {
            return new Limits(value, maxEntryBytes, maxNestedBytes, maxCompressionRatio, maxDepth, timeout);
        }

        public Limits withMaxEntryBytes(int value) {
            return new Limits(maxEntries, value, maxNestedBytes, maxCompressionRatio, maxDepth, timeout);
        }

        public Limits withMaxCompressionRatio(int value) {
            return new Limits(maxEntries, maxEntryBytes, maxNestedBytes, value, maxDepth, timeout);
        }

        public Limits withMaxDepth(int value) {
            return new Limits(maxEntries, maxEntryBytes, maxNestedBytes, maxCompressionRatio, value, timeout);
        }

        public Limits withTimeout(Duration value) {
            return new Limits(maxEntries, maxEntryBytes, maxNestedBytes, maxCompressionRatio, maxDepth, value);
        }
    }

    /** 整个分析无法继续的错误。消息为英文，界面按 {@link #code()} 映射文案。 */
    public static final class InspectionException extends IOException {
        public enum Code {
            /** 不是 zip 格式 */
            NOT_A_ZIP,
            /** 条目数超过上限 */
            TOO_MANY_ENTRIES,
            /** 超时 */
            TIMEOUT,
            /** 指定的条目不存在 */
            ENTRY_NOT_FOUND
        }

        private final Code code;

        public InspectionException(Code code, String message) {
            super(message);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    private final Limits limits;

    public JarInspector() {
        this(Limits.defaults());
    }

    public JarInspector(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Limits limits() {
        return limits;
    }

    /**
     * 分析磁盘上的归档。
     *
     * @param cancelled 取消标志，每个条目检查一次；取消时抛 {@link CancellationException}
     */
    public JarReport inspect(Path file, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(file, "file");
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        long deadline = System.nanoTime() + limits.timeout().toNanos();
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile());
        } catch (ZipException notZip) {
            throw new InspectionException(InspectionException.Code.NOT_A_ZIP,
                    "Not a zip archive: " + file.getFileName());
        }
        try (zip) {
            if (zip.size() > limits.maxEntries()) {
                throw new InspectionException(InspectionException.Code.TOO_MANY_ENTRIES,
                        "Archive has " + zip.size() + " entries, limit " + limits.maxEntries());
            }
            Analysis analysis = new Analysis(file.getFileName().toString(), 0, stop, deadline);
            try (ArchiveIO.EntrySource source = ArchiveIO.zipFileSource(zip)) {
                analysis.run(source);
            }
            return analysis.build(Files.size(file));
        }
    }

    /** 读取并解析归档中的一个 class 条目（用于在条目清单里点选查看）。 */
    public ClassFileInfo readClass(Path archive, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new InspectionException(InspectionException.Code.ENTRY_NOT_FOUND,
                        "No such entry: " + entryName);
            }
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = ArchiveIO.readBounded(in, Math.min(limits.maxEntryBytes(), ClassFileParser.MAX_CLASS_BYTES),
                        entry::getCompressedSize, limits.maxCompressionRatio());
            }
            return new ClassFileParser().parse(bytes);
        } catch (ZipException notZip) {
            throw new InspectionException(InspectionException.Code.NOT_A_ZIP,
                    "Not a zip archive: " + archive.getFileName());
        }
    }

    // ==========================================
    // 单个归档（顶层或嵌套）的分析状态
    // ==========================================
    private final class Analysis {
        private final String name;
        private final int depth;
        private final boolean top;
        private final BooleanSupplier cancelled;
        private final long deadline;

        private int entryCount;
        private long totalSize;
        private long compressedSize;
        private final Map<String, String> manifest = new LinkedHashMap<>();
        private final List<MavenCoordinates> coordinates = new ArrayList<>();
        private ClassFileInfo.ModuleInfo module;
        private String moduleEntry;
        private int moduleVersion = Integer.MAX_VALUE;
        private final List<String> signatureFiles = new ArrayList<>();
        private final List<JarReport.ServiceFile> services = new ArrayList<>();
        private final TreeMap<Integer, int[]> versioned = new TreeMap<>();
        private final TreeMap<Integer, Integer> histogram = new TreeMap<>();
        private int classCount;
        private int previewClasses;
        private final Map<String, Integer> packages = new HashMap<>();
        private boolean bootInf;
        private boolean webInf;
        private boolean earModules;
        private int loaderClasses;
        private final List<String> classpathIndex = new ArrayList<>();
        private final List<String> layers = new ArrayList<>();
        private final List<JarReport.NestedLibrary> libraries = new ArrayList<>();
        private final List<JarReport.EntryInfo> entries = new ArrayList<>();
        private final List<JarReport.Warning> warnings = new ArrayList<>();

        Analysis(String name, int depth, BooleanSupplier cancelled, long deadline) {
            this.name = name;
            this.depth = depth;
            this.top = depth == 0;
            this.cancelled = cancelled;
            this.deadline = deadline;
        }

        void run(ArchiveIO.EntrySource source) throws IOException {
            ArchiveIO.ArchiveEntry entry;
            while ((entry = source.next()) != null) {
                checkStop();
                if (++entryCount > limits.maxEntries()) {
                    if (top) {
                        throw new InspectionException(InspectionException.Code.TOO_MANY_ENTRIES,
                                "Archive has more than " + limits.maxEntries() + " entries");
                    }
                    entryCount--;
                    warn(JarReport.Warning.Code.NESTED_ENTRY_LIMIT, name, String.valueOf(limits.maxEntries()));
                    return;
                }
                process(entry);
            }
        }

        private void checkStop() throws InspectionException {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Inspection cancelled");
            }
            if (System.nanoTime() - deadline > 0) {
                throw new InspectionException(InspectionException.Code.TIMEOUT,
                        "Inspection exceeded " + limits.timeout().toSeconds() + "s");
            }
        }

        private void process(ArchiveIO.ArchiveEntry entry) throws IOException {
            String entryName = entry.name();
            if (entry.size() > 0) {
                totalSize += entry.size();
            }
            if (entry.compressedSize() > 0) {
                compressedSize += entry.compressedSize();
            }
            if (top) {
                entries.add(new JarReport.EntryInfo(entryName, entry.size(), entry.compressedSize(),
                        entry.directory()));
            }
            if (entryName.startsWith("BOOT-INF/")) {
                bootInf = true;
            } else if (entryName.startsWith("WEB-INF/")) {
                webInf = true;
            }
            if (entry.directory()) {
                return;
            }
            if (entryName.startsWith("org/springframework/boot/loader/") && entryName.endsWith(".class")) {
                loaderClasses++;
            }
            if (entryName.indexOf('/') < 0 && entryName.toLowerCase(Locale.ROOT).endsWith(".war")
                    || "META-INF/application.xml".equals(entryName)) {
                earModules = true;
            }
            try {
                dispatch(entry, entryName);
            } catch (ArchiveIO.EntryTooLargeException tooLarge) {
                warn(JarReport.Warning.Code.ENTRY_TOO_LARGE, entryName, tooLarge.getMessage());
            } catch (ArchiveIO.CompressionRatioException ratio) {
                warn(JarReport.Warning.Code.COMPRESSION_RATIO, entryName, ratio.getMessage());
            }
        }

        private void dispatch(ArchiveIO.ArchiveEntry entry, String entryName) throws IOException {
            if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entryName)) {
                parseManifest(read(entry, limits.maxEntryBytes()), entryName);
            } else if (entryName.startsWith("META-INF/maven/") && entryName.endsWith("/pom.properties")) {
                MavenCoordinates found = MavenCoordinates.fromPomProperties(read(entry, 64 * 1024), entryName);
                if (found != null) {
                    coordinates.add(found);
                }
            } else if (entryName.startsWith("META-INF/services/")
                    && entryName.indexOf('/', "META-INF/services/".length()) < 0) {
                services.add(new JarReport.ServiceFile(entryName.substring("META-INF/services/".length()),
                        parseServiceLines(read(entry, 256 * 1024))));
            } else if (isSignatureFile(entryName)) {
                signatureFiles.add(entryName);
            } else if ("BOOT-INF/classpath.idx".equals(entryName) || "WEB-INF/classpath.idx".equals(entryName)) {
                classpathIndex.addAll(parseIndexEntries(read(entry, 4 * 1024 * 1024)));
            } else if ("BOOT-INF/layers.idx".equals(entryName) || "WEB-INF/layers.idx".equals(entryName)) {
                layers.addAll(parseLayerNames(read(entry, 1024 * 1024)));
            } else if (entryName.endsWith(".class")) {
                handleClass(entry, entryName);
            } else if (ArchiveIO.isArchiveName(entryName)) {
                handleNested(entry, entryName);
            }
        }

        private byte[] read(ArchiveIO.ArchiveEntry entry, int maxBytes) throws IOException {
            try (InputStream in = entry.open()) {
                return ArchiveIO.readBounded(in, maxBytes, entry.compressedCounter(), limits.maxCompressionRatio());
            }
        }

        // ------------------------------------------
        // class 条目
        // ------------------------------------------
        private void handleClass(ArchiveIO.ArchiveEntry entry, String entryName) throws IOException {
            int release = ArchiveIO.versionOf(entryName);
            String classPath = ArchiveIO.classPath(entryName);
            if ("module-info.class".equals(classPath)) {
                // 根目录的 module-info 优先；只有多版本目录里有时取版本号最小的那份。
                if (release < moduleVersion) {
                    try {
                        module = new ClassFileParser().parse(
                                read(entry, Math.min(limits.maxEntryBytes(), ClassFileParser.MAX_CLASS_BYTES))).module();
                        moduleEntry = entryName;
                        moduleVersion = release;
                    } catch (ClassFileException corrupt) {
                        warn(JarReport.Warning.Code.CORRUPT_CLASS, entryName, corrupt.getMessage());
                    }
                }
                return;
            }
            ClassFileParser.Header header;
            try (InputStream in = entry.open()) {
                byte[] prefix = ArchiveIO.readPrefix(in, 8);
                header = ClassFileParser.parseHeader(prefix, prefix.length);
            } catch (ClassFileException corrupt) {
                warn(JarReport.Warning.Code.CORRUPT_CLASS, entryName, corrupt.getMessage());
                return;
            }
            int major = header.majorVersion();
            if (release > 0) {
                int[] stats = versioned.computeIfAbsent(release, key -> new int[2]);
                stats[0]++;
                stats[1] = Math.max(stats[1], major);
                return;
            }
            histogram.merge(major, 1, Integer::sum);
            classCount++;
            if (header.preview()) {
                previewClasses++;
            }
            if (top) {
                int slash = classPath.lastIndexOf('/');
                String pkg = slash < 0 ? "" : classPath.substring(0, slash).replace('/', '.');
                packages.merge(pkg, 1, Integer::sum);
            }
        }

        // ------------------------------------------
        // 嵌套归档
        // ------------------------------------------
        private void handleNested(ArchiveIO.ArchiveEntry entry, String entryName) throws IOException {
            if (depth + 1 > limits.maxDepth()) {
                libraries.add(new JarReport.NestedLibrary(entryName, entry.size(), null));
                warn(JarReport.Warning.Code.NESTED_TOO_DEEP, entryName, String.valueOf(limits.maxDepth()));
                return;
            }
            if (entry.size() > limits.maxNestedBytes()) {
                libraries.add(new JarReport.NestedLibrary(entryName, entry.size(), null));
                warn(JarReport.Warning.Code.ENTRY_TOO_LARGE, entryName, String.valueOf(entry.size()));
                return;
            }
            Analysis child = new Analysis(entryName, depth + 1, cancelled, deadline);
            ArchiveIO.GuardedInputStream guarded = null;
            try (InputStream in = entry.open()) {
                guarded = new ArchiveIO.GuardedInputStream(in, limits.maxNestedBytes(),
                        entry.compressedCounter(), limits.maxCompressionRatio());
                try (ArchiveIO.EntrySource source = ArchiveIO.streamSource(guarded, limits.maxEntryBytes(),
                        limits.maxCompressionRatio())) {
                    child.run(source);
                }
            } catch (ArchiveIO.EntryTooLargeException | ArchiveIO.CompressionRatioException guard) {
                // 嵌套库内部某个条目或它自身超限：整库放弃，已经统计到的部分不可信。
                libraries.add(new JarReport.NestedLibrary(entryName, guarded == null ? -1 : guarded.count(), null));
                warn(guard instanceof ArchiveIO.CompressionRatioException
                        ? JarReport.Warning.Code.COMPRESSION_RATIO : JarReport.Warning.Code.ENTRY_TOO_LARGE,
                        entryName, guard.getMessage());
                return;
            } catch (InspectionException fatal) {
                throw fatal;
            } catch (IOException unreadable) {
                libraries.add(new JarReport.NestedLibrary(entryName, guarded == null ? -1 : guarded.count(), null));
                warn(JarReport.Warning.Code.NESTED_UNREADABLE, entryName, unreadable.getMessage());
                return;
            }
            if (child.entryCount == 0) {
                // ZipInputStream 遇到非 zip 数据不报错，只是一个条目也读不出来。
                libraries.add(new JarReport.NestedLibrary(entryName, guarded.count(), null));
                warn(JarReport.Warning.Code.NESTED_UNREADABLE, entryName, "no entries");
                return;
            }
            libraries.add(new JarReport.NestedLibrary(entryName, guarded.count(), child.build(guarded.count())));
        }

        // ------------------------------------------
        // 文本条目
        // ------------------------------------------
        /**
         * 只取主段（第一个空行之前），保持原顺序。{@code java.util.jar.Manifest} 的 Attributes
         * 是无序的，而且遇到一行格式错误就整体失败；这里逐行宽松解析，坏行记一条警告。
         */
        private void parseManifest(byte[] bytes, String entryName) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = text.split("\r\n|\r|\n", -1);
            String lastKey = null;
            boolean malformed = false;
            for (String line : lines) {
                if (line.isEmpty()) {
                    if (lastKey != null || !manifest.isEmpty()) {
                        break;
                    }
                    continue;
                }
                if (line.charAt(0) == ' ' && lastKey != null) {
                    manifest.put(lastKey, manifest.get(lastKey) + line.substring(1));
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    malformed = true;
                    continue;
                }
                lastKey = line.substring(0, colon).trim();
                String value = line.substring(colon + 1);
                manifest.put(lastKey, value.startsWith(" ") ? value.substring(1) : value);
            }
            if (malformed) {
                warn(JarReport.Warning.Code.BAD_MANIFEST, entryName, null);
            }
        }

        private void warn(JarReport.Warning.Code code, String entry, String detail) {
            if (warnings.size() < MAX_WARNINGS) {
                warnings.add(new JarReport.Warning(code, entry, detail));
            }
        }

        JarReport build(long archiveSize) {
            TreeMap<Integer, JarReport.VersionStats> versionStats = new TreeMap<>();
            versioned.forEach((release, stats) ->
                    versionStats.put(release, new JarReport.VersionStats(release, stats[0], stats[1])));
            List<JarReport.PackageStats> packageStats = new ArrayList<>();
            packages.forEach((pkg, count) -> packageStats.add(new JarReport.PackageStats(pkg, count)));
            packageStats.sort((a, b) -> a.name().compareTo(b.name()));

            JarReport.Layout layout = detectLayout();
            JarReport.SpringBootInfo boot = null;
            if (layout == JarReport.Layout.SPRING_BOOT_JAR || layout == JarReport.Layout.SPRING_BOOT_WAR) {
                boot = new JarReport.SpringBootInfo(manifest.get("Spring-Boot-Version"), manifest.get("Start-Class"),
                        loaderClasses, classpathIndex, layers);
            }
            return new JarReport(name, archiveSize, entryCount, totalSize, compressedSize, manifest, coordinates,
                    module, moduleEntry, signatureFiles, services, versionStats, histogram, classCount,
                    previewClasses, packageStats, layout, boot, libraries, entries, warnings);
        }

        private JarReport.Layout detectLayout() {
            boolean bootManifest = manifest.containsKey("Spring-Boot-Version")
                    || manifest.getOrDefault("Main-Class", "").startsWith("org.springframework.boot.loader");
            if (bootInf) {
                return JarReport.Layout.SPRING_BOOT_JAR;
            }
            if (webInf) {
                return bootManifest || loaderClasses > 0 ? JarReport.Layout.SPRING_BOOT_WAR : JarReport.Layout.WAR;
            }
            if (earModules || name.toLowerCase(Locale.ROOT).endsWith(".ear")) {
                return JarReport.Layout.EAR;
            }
            return JarReport.Layout.PLAIN;
        }
    }

    // ==========================================
    // 纯函数
    // ==========================================
    /** META-INF 目录下直接存放的签名文件：.SF 签名清单与 .RSA/.DSA/.EC 签名块（以及 SIG-* 文件）。 */
    static boolean isSignatureFile(String entryName) {
        if (!entryName.startsWith("META-INF/") || entryName.indexOf('/', "META-INF/".length()) >= 0) {
            return false;
        }
        String upper = entryName.substring("META-INF/".length()).toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.startsWith("SIG-");
    }

    /** services 文件：每行一个实现类，{@code #} 之后为注释。 */
    static List<String> parseServiceLines(byte[] bytes) {
        List<String> providers = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\r\n|\r|\n")) {
            int hash = line.indexOf('#');
            String value = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (!value.isEmpty()) {
                providers.add(value);
            }
        }
        return providers;
    }

    /** classpath.idx：{@code - "BOOT-INF/lib/foo.jar"}。 */
    static List<String> parseIndexEntries(byte[] bytes) {
        List<String> items = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\r\n|\r|\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- \"") && trimmed.endsWith("\"") && trimmed.length() > 4) {
                items.add(trimmed.substring(3, trimmed.length() - 1));
            }
        }
        return items;
    }

    /** layers.idx：顶格的 {@code - "dependencies":} 是层名，缩进的是层内路径。 */
    static List<String> parseLayerNames(byte[] bytes) {
        List<String> names = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\r\n|\r|\n")) {
            if (line.startsWith("- \"") && line.endsWith("\":") && line.length() > 5) {
                names.add(line.substring(3, line.length() - 2));
            }
        }
        return names;
    }
}
