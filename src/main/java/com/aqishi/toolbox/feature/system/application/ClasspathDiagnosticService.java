package com.aqishi.toolbox.feature.system.application;

import com.aqishi.toolbox.feature.system.domain.ArchiveIO;
import com.aqishi.toolbox.feature.system.domain.ClassAbiSignature;
import com.aqishi.toolbox.feature.system.domain.ClassConflict;
import com.aqishi.toolbox.feature.system.domain.ClasspathDiagnosticReport;
import com.aqishi.toolbox.feature.system.domain.ClasspathSource;
import com.aqishi.toolbox.feature.system.domain.KnownConflictDetector;
import com.aqishi.toolbox.feature.system.domain.MavenCoordinates;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.util.Hex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Classpath 拓扑与类冲突诊断服务。
 */
public final class ClasspathDiagnosticService {

    private static final int MAX_CLASS_BYTES = 10 * 1024 * 1024; // 10MB 上限
    private static final String[] NESTED_LIB_PREFIXES = {"BOOT-INF/lib/", "WEB-INF/lib/"};

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int done, int total, String current);
    }

    private final KnownConflictDetector knownConflictDetector;

    public ClasspathDiagnosticService() {
        this(new KnownConflictDetector());
    }

    public ClasspathDiagnosticService(KnownConflictDetector detector) {
        this.knownConflictDetector = Objects.requireNonNull(detector, "detector");
    }

    /**
     * 对给定的 Classpath 路径序列执行全面拓扑与冲突诊断。
     */
    public ClasspathDiagnosticReport diagnose(
            List<Path> rawPaths,
            ProgressListener listener,
            BooleanSupplier cancelled
    ) {
        long startTime = System.currentTimeMillis();

        if (rawPaths == null || rawPaths.isEmpty()) {
            return new ClasspathDiagnosticReport(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), 0, 0);
        }

        List<ClasspathSource> sources = resolveSources(rawPaths, cancelled);
        if (sources.isEmpty() || isCancelled(cancelled)) {
            return new ClasspathDiagnosticReport(sources, Collections.emptyList(),
                    Collections.emptyList(), 0, System.currentTimeMillis() - startTime);
        }

        // 1. 并发扫描每一个 Source 中的类并构建索引
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = DaemonThreads.fixed("cp-diag", threads);
        List<SourceIndex> indexedSources;

        try {
            List<Callable<SourceIndex>> tasks = new ArrayList<>();
            for (ClasspathSource src : sources) {
                tasks.add(() -> scanSource(src, cancelled));
            }

            indexedSources = new ArrayList<>(sources.size());
            List<Future<SourceIndex>> futures = pool.invokeAll(tasks);
            int done = 0;
            int total = futures.size();

            for (Future<SourceIndex> f : futures) {
                if (isCancelled(cancelled)) {
                    throw new CancellationException();
                }
                SourceIndex idx = f.get();
                indexedSources.add(idx);
                done++;
                if (listener != null) {
                    listener.onProgress(done, total, idx.source.displayName());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("diagnostic execution failed", e);
        } finally {
            DaemonThreads.shutdownQuietly(pool);
        }

        // 2. 统计所有类与出现次数：全限定类名 -> [出现的 SourceIndex 与对应的 RawClassEntry]
        Map<String, List<EntryOccurrence>> classOccurrences = new LinkedHashMap<>();
        int totalClassesCount = 0;

        for (SourceIndex srcIndex : indexedSources) {
            for (RawClassEntry entry : srcIndex.entries.values()) {
                totalClassesCount++;
                classOccurrences.computeIfAbsent(entry.className, k -> new ArrayList<>(2))
                        .add(new EntryOccurrence(srcIndex.source, entry));
            }
        }

        // 3. 对出现次数 >= 2 的类做深层 ABI 比对
        List<ClassConflict> conflicts = new ArrayList<>();

        for (Map.Entry<String, List<EntryOccurrence>> entry : classOccurrences.entrySet()) {
            if (isCancelled(cancelled)) {
                throw new CancellationException();
            }

            List<EntryOccurrence> occurrences = entry.getValue();
            if (occurrences.size() < 2) {
                continue;
            }

            // 按 Classpath 顺序排序，排在前面的为 winner
            occurrences.sort(Comparator.comparingInt(occ -> occ.source.order()));

            EntryOccurrence winnerOcc = occurrences.get(0);
            ClassAbiSignature winnerAbi = winnerOcc.entry.resolveAbi();

            List<ClassConflict.MaskedOccurrence> maskedList = new ArrayList<>();
            for (int i = 1; i < occurrences.size(); i++) {
                EntryOccurrence maskedOcc = occurrences.get(i);
                ClassAbiSignature maskedAbi = maskedOcc.entry.resolveAbi();
                ClassConflict.MaskedOccurrence maskedComp = ClassConflict.compare(
                        maskedOcc.source, maskedAbi, winnerOcc.source, winnerAbi);
                maskedList.add(maskedComp);
            }

            conflicts.add(new ClassConflict(entry.getKey(), winnerOcc.source, winnerAbi, maskedList));
        }

        // 排序：高危 > 中危 > 安全，同级按类名排序
        conflicts.sort((c1, c2) -> {
            int r1 = rankRisk(c1.worstRiskLevel());
            int r2 = rankRisk(c2.worstRiskLevel());
            if (r1 != r2) {
                return Integer.compare(r1, r2);
            }
            return c1.className().compareTo(c2.className());
        });

        // 4. 常见依赖冲突库匹配
        List<KnownConflictDetector.Match> knownMatches = knownConflictDetector.detect(sources);

        long elapsed = System.currentTimeMillis() - startTime;
        return new ClasspathDiagnosticReport(sources, conflicts, knownMatches, totalClassesCount, elapsed);
    }

    private static int rankRisk(ClassConflict.RiskLevel level) {
        return switch (level) {
            case BREAKING -> 0;
            case WARNING -> 1;
            case SAFE -> 2;
        };
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }

    private List<ClasspathSource> resolveSources(List<Path> rawPaths, BooleanSupplier cancelled) {
        List<ClasspathSource> list = new ArrayList<>();
        int currentOrder = 1;

        for (Path path : rawPaths) {
            if (isCancelled(cancelled)) {
                break;
            }
            if (!Files.exists(path)) {
                continue;
            }

            if (Files.isDirectory(path)) {
                // 判断是否是类根目录（含有 .class 文件或子目录不含 jar）还是包含一堆 jar 的目录
                if (looksLikeClassDirectory(path)) {
                    list.add(new ClasspathSource(currentOrder++, ClasspathSource.Kind.DIRECTORY,
                            path, null, null, null));
                } else {
                    List<Path> jarsInDir = scanJarsInDirectory(path);
                    for (Path j : jarsInDir) {
                        MavenCoordinates coords = MavenCoordinates.guessFromFileName(j.getFileName().toString());
                        list.add(new ClasspathSource(currentOrder++, ClasspathSource.Kind.JAR,
                                j, null, coords, coords != null ? coords.version() : null));
                    }
                }
            } else if (isArchive(path)) {
                MavenCoordinates coords = inspectArchiveCoordinates(path);
                list.add(new ClasspathSource(currentOrder++, ClasspathSource.Kind.JAR,
                        path, null, coords, coords != null ? coords.version() : null));
            }
        }

        return list;
    }

    private static boolean looksLikeClassDirectory(Path dir) {
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".class")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private static List<Path> scanJarsInDirectory(Path dir) {
        List<Path> jars = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isArchive(file)) {
                        jars.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        jars.sort(Path::compareTo);
        return jars;
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear") || name.endsWith(".zip");
    }

    private static MavenCoordinates inspectArchiveCoordinates(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] bytes = ArchiveIO.readBounded(in, 64 * 1024, entry::getCompressedSize, 0);
                        MavenCoordinates coords = MavenCoordinates.fromPomProperties(bytes, name);
                        if (coords != null) {
                            return coords;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return MavenCoordinates.guessFromFileName(archive.getFileName().toString());
    }

    private static SourceIndex scanSource(ClasspathSource source, BooleanSupplier cancelled) {
        Map<String, RawClassEntry> entries = new HashMap<>();

        if (source.kind() == ClasspathSource.Kind.DIRECTORY) {
            scanDirectoryClasses(source.path(), entries, cancelled);
        } else {
            scanArchiveClasses(source.path(), entries, cancelled);
        }

        return new SourceIndex(source, entries);
    }

    private static void scanDirectoryClasses(Path root, Map<String, RawClassEntry> entries,
                                             BooleanSupplier cancelled) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isCancelled(cancelled)) {
                        return FileVisitResult.TERMINATE;
                    }
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".class") && !fileName.equals("module-info.class")) {
                        Path rel = root.relativize(file);
                        String className = rel.toString().replace('\\', '/');
                        if (className.endsWith(".class")) {
                            className = className.substring(0, className.length() - 6).replace('/', '.');
                            try {
                                byte[] bytes = Files.readAllBytes(file);
                                String sha256 = sha256(bytes);
                                entries.put(className, new RawClassEntry(className, bytes, sha256));
                            } catch (IOException ignored) {
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void scanArchiveClasses(Path archive, Map<String, RawClassEntry> entries,
                                           BooleanSupplier cancelled) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                if (isCancelled(cancelled)) {
                    break;
                }
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                    String cleanPath = name;
                    for (String prefix : new String[]{"BOOT-INF/classes/", "WEB-INF/classes/"}) {
                        if (cleanPath.startsWith(prefix)) {
                            cleanPath = cleanPath.substring(prefix.length());
                            break;
                        }
                    }
                    String className = cleanPath.substring(0, cleanPath.length() - 6).replace('/', '.');
                    if (className.startsWith(".")) {
                        className = className.substring(1);
                    }

                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] bytes = ArchiveIO.readBounded(in, MAX_CLASS_BYTES, entry::getCompressedSize, 0);
                        String sha256 = sha256(bytes);
                        entries.put(className, new RawClassEntry(className, bytes, sha256));
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Hex.toHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record EntryOccurrence(ClasspathSource source, RawClassEntry entry) {}

    private record SourceIndex(ClasspathSource source, Map<String, RawClassEntry> entries) {}

    private static final class RawClassEntry {
        final String className;
        final byte[] bytes;
        final String sha256;
        private volatile ClassAbiSignature resolvedAbi;

        RawClassEntry(String className, byte[] bytes, String sha256) {
            this.className = className;
            this.bytes = bytes;
            this.sha256 = sha256;
        }

        ClassAbiSignature resolveAbi() {
            if (resolvedAbi == null) {
                resolvedAbi = ClassAbiSignature.extract(className, bytes, sha256);
            }
            return resolvedAbi;
        }
    }
}
