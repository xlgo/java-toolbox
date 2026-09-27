package com.aqishi.toolbox.feature.system.application;

import com.aqishi.toolbox.feature.system.domain.ArchiveIO;
import com.aqishi.toolbox.feature.system.domain.ClassFileParser;
import com.aqishi.toolbox.feature.system.domain.JarInspector;
import com.aqishi.toolbox.feature.system.domain.MavenCoordinates;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.util.Hex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 跨 JAR 找类与重复类（依赖冲突）检测。
 *
 * <p>输入是一组根：目录（项目的 lib 目录、{@code target/classes}、{@code ~/.m2/repository}）
 * 或单个归档。目录会递归遍历，其中的 .jar/.war/.ear 逐个扫描（含 {@code BOOT-INF/lib}、
 * {@code WEB-INF/lib} 里的嵌套库，最多两层），散落的 .class 按“展开的类目录”处理。</p>
 *
 * <p>重复类检测对每个类条目计算 SHA-256：同名且字节完全相同的只是重复打包（通常无害，
 * 例如同一个依赖既在 fat jar 里又在 lib 目录里）；同名但内容不同才是真正的冲突——
 * 类加载顺序决定哪个生效，典型症状就是 {@code NoSuchMethodError}、{@code ClassCastException}。</p>
 *
 * <p>扫描在有界线程池里并行进行；每个条目检查一次取消标志；读不了的归档记一条警告后跳过。</p>
 */
public final class ClassSearchService {

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", ".svn", ".hg", ".idea", "node_modules");

    /** 视为资源而非类名的扩展名（查询 {@code logback.xml} 时不该当成包 logback 下的类 xml）。 */
    private static final Set<String> RESOURCE_EXTENSIONS = Set.of(
            "xml", "properties", "yml", "yaml", "json", "txt", "conf", "cfg", "ini", "toml", "csv",
            "xsd", "dtd", "wsdl", "xsl", "xslt", "tld", "sql", "mf", "sf", "factories", "imports", "idx",
            "handlers", "schemas", "list", "so", "dll", "dylib", "jnilib", "png", "jpg", "jpeg", "gif", "svg",
            "ico", "html", "htm", "js", "css", "ftl", "vm", "sh", "bat", "cmd", "jks", "p12", "pem", "crt",
            "cer", "key", "proto", "graphql", "groovy", "kts", "jar", "war", "ear", "zip", "md", "java");

    /**
     * 扫描参数。
     *
     * @param threads           并行扫描的线程数
     * @param maxHits           搜索结果条数上限
     * @param maxIndexedClasses 重复检测最多索引多少个不同类名
     * @param maxFiles          目录遍历最多访问多少个文件
     * @param limits            单个归档的安全上限（条目数、单条字节数、压缩比、嵌套深度）
     */
    public record Options(int threads, int maxHits, int maxIndexedClasses, int maxFiles, JarInspector.Limits limits) {
        public Options {
            if (threads < 1 || maxHits < 1 || maxIndexedClasses < 1 || maxFiles < 1) {
                throw new IllegalArgumentException("invalid options");
            }
            Objects.requireNonNull(limits, "limits");
        }

        public static Options defaults() {
            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
            return new Options(threads, 20_000, 3_000_000, 2_000_000, JarInspector.Limits.defaults());
        }

        public Options withThreads(int value) {
            return new Options(value, maxHits, maxIndexedClasses, maxFiles, limits);
        }

        public Options withMaxHits(int value) {
            return new Options(threads, value, maxIndexedClasses, maxFiles, limits);
        }

        public Options withLimits(JarInspector.Limits value) {
            return new Options(threads, maxHits, maxIndexedClasses, maxFiles, value);
        }
    }

    /** 进度回调：在扫描线程上调用，实现方自行切回 EDT。 */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int done, int total, String current);
    }

    // ==========================================
    // 查询
    // ==========================================
    /** 按输入形态自动判定的查询方式。 */
    public enum QueryKind {
        /** 全限定类名：{@code com.foo.Bar}（内部类也可写成 {@code com.foo.Outer.Inner}） */
        FQN,
        /** 简单类名：{@code Bar}，同时匹配内部类 {@code Outer$Bar} */
        SIMPLE_NAME,
        /** 包前缀：以点结尾，{@code com.foo.} 匹配其下所有类（含子包） */
        PACKAGE,
        /** 类名通配：{@code com.foo.*Service}、{@code *Mapper}、{@code com.**.Util} */
        CLASS_WILDCARD,
        /** 资源文件名：{@code logback.xml}、{@code *.properties}（任意目录） */
        RESOURCE_NAME,
        /** 资源路径通配：{@code **}{@code /logback.xml}、{@code META-INF/spring.factories} */
        RESOURCE_GLOB
    }

    /** 解析后的查询。 */
    public record Query(QueryKind kind, String text, Pattern pattern) {

        /**
         * 自动判定查询方式；输入为空时抛 {@link IllegalArgumentException}。
         *
         * <p>判定顺序：{@code .class} 结尾的路径先转成类名；含 {@code /} 为资源路径；
         * 末段是常见资源扩展名为资源文件名；含通配符为类名通配；点结尾为包前缀；含点为全限定名；
         * 其余为简单类名。</p>
         */
        public static Query parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("empty query");
            }
            String text = raw.trim().replace('\\', '/');
            if (text.endsWith(".class") && text.length() > ".class".length()) {
                String body = text.substring(0, text.length() - ".class".length());
                while (body.startsWith("/")) {
                    body = body.substring(1);
                }
                text = body.replace('/', '.');
            }
            boolean wildcard = text.indexOf('*') >= 0 || text.indexOf('?') >= 0;
            if (text.indexOf('/') >= 0) {
                while (text.startsWith("/")) {
                    text = text.substring(1);
                }
                return new Query(QueryKind.RESOURCE_GLOB, text, Pattern.compile(glob(text, '/')));
            }
            if (looksLikeResource(text)) {
                return new Query(QueryKind.RESOURCE_NAME, text,
                        Pattern.compile(wildcard ? glob(text, '/') : Pattern.quote(text)));
            }
            if (wildcard) {
                return new Query(QueryKind.CLASS_WILDCARD, text, Pattern.compile(glob(text, '.')));
            }
            if (text.endsWith(".")) {
                return new Query(QueryKind.PACKAGE, text, null);
            }
            if (text.indexOf('.') >= 0) {
                return new Query(QueryKind.FQN, text, null);
            }
            return new Query(QueryKind.SIMPLE_NAME, text, null);
        }

        public boolean targetsClasses() {
            return kind != QueryKind.RESOURCE_NAME && kind != QueryKind.RESOURCE_GLOB;
        }

        /** @param fqn 点分类名，内部类用 {@code $} */
        public boolean matchesClass(String fqn) {
            switch (kind) {
                case FQN:
                    return fqn.equals(text) || fqn.replace('$', '.').equals(text);
                case SIMPLE_NAME: {
                    String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                    return simple.equals(text) || simple.endsWith("$" + text);
                }
                case PACKAGE:
                    return fqn.startsWith(text);
                case CLASS_WILDCARD: {
                    if (text.indexOf('.') >= 0) {
                        return pattern.matcher(fqn).matches() || pattern.matcher(fqn.replace('$', '.')).matches();
                    }
                    String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                    if (pattern.matcher(simple).matches()) {
                        return true;
                    }
                    int dollar = simple.lastIndexOf('$');
                    return dollar >= 0 && pattern.matcher(simple.substring(dollar + 1)).matches();
                }
                default:
                    return false;
            }
        }

        /** @param path 类路径视角下的资源路径（已去掉 BOOT-INF/classes/ 等前缀），用 {@code /} 分隔 */
        public boolean matchesResource(String path) {
            if (kind == QueryKind.RESOURCE_GLOB) {
                return pattern.matcher(path).matches();
            }
            if (kind == QueryKind.RESOURCE_NAME) {
                return pattern.matcher(path.substring(path.lastIndexOf('/') + 1)).matches();
            }
            return false;
        }

        private static boolean looksLikeResource(String text) {
            int dot = text.lastIndexOf('.');
            if (dot <= 0 || dot == text.length() - 1) {
                return false;
            }
            return RESOURCE_EXTENSIONS.contains(text.substring(dot + 1));
        }

        /** 通配符转正则：{@code **} 跨越分隔符，{@code *}/{@code ?} 不跨越。 */
        static String glob(String text, char separator) {
            String sep = Pattern.quote(String.valueOf(separator));
            String notSep = "[^" + (separator == '.' ? "." : "/") + "]";
            StringBuilder regex = new StringBuilder();
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '*' || c == '?') {
                    if (literal.length() > 0) {
                        regex.append(Pattern.quote(literal.toString()));
                        literal.setLength(0);
                    }
                    if (c == '?') {
                        regex.append(notSep);
                    } else if (i + 1 < text.length() && text.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < text.length() && text.charAt(i + 1) == separator) {
                            i++;
                            regex.append("(?:.*").append(sep).append(")?");
                        } else {
                            regex.append(".*");
                        }
                    } else {
                        regex.append(notSep).append('*');
                    }
                } else {
                    literal.append(c);
                }
            }
            if (literal.length() > 0) {
                regex.append(Pattern.quote(literal.toString()));
            }
            return regex.toString();
        }
    }

    // ==========================================
    // 结果模型
    // ==========================================
    public enum LocationKind { JAR, NESTED_JAR, DIRECTORY }

    /**
     * 类或资源所在的位置：一个归档、归档里的嵌套库，或一个展开的类目录。
     *
     * @param nestedPath 嵌套库在外层归档中的路径（多层以 {@code !/} 连接），顶层为 {@code null}
     * @param version    最能代表该位置的版本号（pom.properties → MANIFEST → 文件名），未知为 {@code null}
     */
    public record Location(LocationKind kind, Path file, String nestedPath, MavenCoordinates coordinates,
                           String version) {

        /** 简短名：{@code app.jar!/BOOT-INF/lib/guava-31.1-jre.jar}；目录为完整路径。 */
        public String displayName() {
            if (kind == LocationKind.DIRECTORY) {
                return file.toString();
            }
            String base = file.getFileName() == null ? file.toString() : file.getFileName().toString();
            return nestedPath == null ? base : base + "!/" + nestedPath;
        }

        /** 完整路径：{@code D:\lib\app.jar!/BOOT-INF/lib/x.jar}。 */
        public String fullPath() {
            return nestedPath == null ? file.toString() : file + "!/" + nestedPath;
        }
    }

    /** 一个命中。{@code entryPath} 为归档内（或相对类目录）的原始路径。 */
    public record Hit(String name, boolean classEntry, Location location, String entryPath, long size) {
    }

    /** 扫描过程中跳过的内容。 */
    public record ScanWarning(Code code, String path, String detail) {
        public enum Code {
            /** 文件或目录无法读取 */
            UNREADABLE,
            /** 不是有效的 zip */
            NOT_A_ZIP,
            /** 归档条目数超过上限，已截断 */
            ENTRY_LIMIT,
            /** 条目超过读取上限或压缩比过高，已跳过 */
            GUARD_TRIPPED,
            /** 目录遍历文件数超过上限，已停止 */
            FILE_LIMIT,
            /** 重复检测索引的类名数超过上限，已停止索引 */
            INDEX_LIMIT,
            /** 散落的 class 文件损坏，按路径推断类名 */
            CORRUPT_CLASS
        }
    }

    public record SearchResult(Query query, List<Hit> hits, boolean truncated, int archivesScanned,
                               int directoriesScanned, List<ScanWarning> warnings, long elapsedMillis) {
        public SearchResult {
            hits = List.copyOf(hits);
            warnings = List.copyOf(warnings);
        }
    }

    /** 某个类在某个位置的一份拷贝。{@code sha256} 为条目字节的 SHA-256（十六进制）。 */
    public record Occurrence(Location location, String entryPath, String sha256) {
    }

    /** 出现在多个位置的类。 */
    public record DuplicateClass(String className, List<Occurrence> occurrences, int variants) {
        public DuplicateClass {
            occurrences = List.copyOf(occurrences);
        }

        /** 同名但字节不同：真正的冲突。 */
        public boolean contentDiffers() {
            return variants > 1;
        }
    }

    /** 两个位置之间共享的类统计。 */
    public record JarPair(Location first, Location second, int sharedClasses, int differingClasses) {
    }

    public record ConflictReport(List<DuplicateClass> duplicates, List<JarPair> pairs, int archivesScanned,
                                 int locations, int classesIndexed, boolean truncated,
                                 List<ScanWarning> warnings, long elapsedMillis) {
        public ConflictReport {
            duplicates = List.copyOf(duplicates);
            pairs = List.copyOf(pairs);
            warnings = List.copyOf(warnings);
        }

        public int conflictCount() {
            int count = 0;
            for (DuplicateClass duplicate : duplicates) {
                if (duplicate.contentDiffers()) {
                    count++;
                }
            }
            return count;
        }
    }

    // ==========================================
    // 入口
    // ==========================================
    private final Options options;

    public ClassSearchService() {
        this(Options.defaults());
    }

    public ClassSearchService(Options options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * 在所有根下查找类或资源。
     *
     * @throws CancellationException 被取消
     * @throws IllegalArgumentException 查询为空或没有根
     */
    public SearchResult search(List<Path> roots, String queryText, ProgressListener listener,
                               BooleanSupplier cancelled) {
        Query query = Query.parse(queryText);
        long start = System.nanoTime();
        Run run = new Run(query, false, listener, cancelled);
        List<Unit> units = run.execute(roots);

        List<Hit> hits = new ArrayList<>();
        boolean truncated = run.hitCount.get() > options.maxHits();
        for (Unit unit : units) {
            Location location = unit.location();
            for (RawHit raw : unit.hits) {
                if (hits.size() >= options.maxHits()) {
                    truncated = true;
                    break;
                }
                hits.add(new Hit(raw.name(), raw.classEntry(), location, raw.entryPath(), raw.size()));
            }
        }
        hits.sort(Comparator.comparing(Hit::name).thenComparing(hit -> hit.location().fullPath())
                .thenComparing(Hit::entryPath));
        return new SearchResult(query, hits, truncated, run.archives.get(), run.directories.get(),
                run.warnings(), elapsed(start));
    }

    /**
     * 找出出现在多个位置的类，并按字节内容区分“相同拷贝”与“内容不同的冲突”。
     *
     * @throws CancellationException 被取消
     */
    public ConflictReport findDuplicates(List<Path> roots, ProgressListener listener, BooleanSupplier cancelled) {
        long start = System.nanoTime();
        Run run = new Run(null, true, listener, cancelled);
        List<Unit> units = run.execute(roots);

        List<Location> locations = new ArrayList<>(units.size());
        Map<String, List<int[]>> index = new HashMap<>();
        // 每个 int[] = {位置下标, 该位置里的类序号}；类序号指向 unit.classes 的列表，省去额外对象。
        List<List<RawClass>> classLists = new ArrayList<>(units.size());
        boolean truncated = false;
        int indexed = 0;
        for (int u = 0; u < units.size(); u++) {
            Unit unit = units.get(u);
            locations.add(unit.location());
            List<RawClass> classes = new ArrayList<>(unit.classes.values());
            classLists.add(classes);
            for (int c = 0; c < classes.size(); c++) {
                String name = classes.get(c).name();
                List<int[]> occurrences = index.get(name);
                if (occurrences == null) {
                    if (index.size() >= options.maxIndexedClasses()) {
                        truncated = true;
                        continue;
                    }
                    occurrences = new ArrayList<>(1);
                    index.put(name, occurrences);
                }
                occurrences.add(new int[]{u, c});
                indexed++;
            }
        }
        if (truncated) {
            run.warn(ScanWarning.Code.INDEX_LIMIT, "", String.valueOf(options.maxIndexedClasses()));
        }

        List<DuplicateClass> duplicates = new ArrayList<>();
        Map<Long, int[]> pairStats = new HashMap<>();
        for (Map.Entry<String, List<int[]>> entry : index.entrySet()) {
            List<int[]> refs = entry.getValue();
            if (refs.size() < 2) {
                continue;
            }
            List<Occurrence> occurrences = new ArrayList<>(refs.size());
            List<Integer> unitIndexes = new ArrayList<>(refs.size());
            List<byte[]> hashes = new ArrayList<>(refs.size());
            Set<String> variants = new HashSet<>();
            for (int[] ref : refs) {
                RawClass raw = classLists.get(ref[0]).get(ref[1]);
                String hex = Hex.toHex(raw.sha256());
                variants.add(hex);
                occurrences.add(new Occurrence(locations.get(ref[0]), raw.entryPath(), hex));
                unitIndexes.add(ref[0]);
                hashes.add(raw.sha256());
            }
            duplicates.add(new DuplicateClass(entry.getKey(), occurrences, variants.size()));
            for (int i = 0; i < unitIndexes.size(); i++) {
                for (int j = i + 1; j < unitIndexes.size(); j++) {
                    int a = Math.min(unitIndexes.get(i), unitIndexes.get(j));
                    int b = Math.max(unitIndexes.get(i), unitIndexes.get(j));
                    int[] stats = pairStats.computeIfAbsent(((long) a << 32) | b, key -> new int[2]);
                    stats[0]++;
                    if (!Arrays.equals(hashes.get(i), hashes.get(j))) {
                        stats[1]++;
                    }
                }
            }
        }
        duplicates.sort(Comparator.comparing((DuplicateClass d) -> !d.contentDiffers())
                .thenComparing(d -> -d.occurrences().size())
                .thenComparing(DuplicateClass::className));

        List<JarPair> pairs = new ArrayList<>(pairStats.size());
        pairStats.forEach((key, stats) -> pairs.add(new JarPair(locations.get((int) (key >>> 32)),
                locations.get((int) (key & 0xFFFFFFFFL)), stats[0], stats[1])));
        pairs.sort(Comparator.comparing((JarPair p) -> -p.differingClasses())
                .thenComparing(p -> -p.sharedClasses())
                .thenComparing(p -> p.first().fullPath()));

        return new ConflictReport(duplicates, pairs, run.archives.get(), units.size(), indexed, truncated,
                run.warnings(), elapsed(start));
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    // ==========================================
    // 一次扫描
    // ==========================================
    private record RawHit(String name, boolean classEntry, String entryPath, long size) {
    }

    private record RawClass(String name, String entryPath, byte[] sha256) {
    }

    private record LooseFile(Path file, Path walkRoot) {
    }

    /** 扫描产出的一个位置及其内容。位置信息（坐标、版本）要扫完才知道，所以先攒原始数据。 */
    private static final class Unit {
        final LocationKind kind;
        final Path file;
        final String nestedPath;
        final List<MavenCoordinates> coordinates = new ArrayList<>();
        String manifestVersion;
        final List<RawHit> hits = new ArrayList<>();
        final Map<String, RawClass> classes = new LinkedHashMap<>();
        private Location location;

        Unit(LocationKind kind, Path file, String nestedPath) {
            this.kind = kind;
            this.file = file;
            this.nestedPath = nestedPath;
        }

        Location location() {
            if (location == null) {
                String fileName = nestedPath != null ? nestedPath
                        : file.getFileName() == null ? file.toString() : file.getFileName().toString();
                MavenCoordinates primary = primaryCoordinates(coordinates, fileName);
                String version = primary != null && primary.version() != null ? primary.version() : manifestVersion;
                if (version == null && kind != LocationKind.DIRECTORY) {
                    MavenCoordinates guess = MavenCoordinates.guessFromFileName(fileName);
                    if (guess != null) {
                        version = guess.version();
                        if (primary == null) {
                            primary = guess;
                        }
                    }
                }
                location = new Location(kind, file, nestedPath, primary, version);
            }
            return location;
        }

        String sortKey() {
            return nestedPath == null ? file.toString() : file + "!/" + nestedPath;
        }
    }

    static MavenCoordinates primaryCoordinates(List<MavenCoordinates> coordinates, String fileName) {
        if (coordinates.isEmpty()) {
            return null;
        }
        if (coordinates.size() > 1) {
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

    private final class Run {
        private final Query query;
        private final boolean hashing;
        private final ProgressListener listener;
        private final BooleanSupplier cancelled;
        private final List<ScanWarning> warnings = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger hitCount = new AtomicInteger();
        final AtomicInteger archives = new AtomicInteger();
        final AtomicInteger directories = new AtomicInteger();

        Run(Query query, boolean hashing, ProgressListener listener, BooleanSupplier cancelled) {
            this.query = query;
            this.hashing = hashing;
            this.listener = listener == null ? (done, total, current) -> { } : listener;
            this.cancelled = cancelled == null ? () -> false : cancelled;
        }

        List<ScanWarning> warnings() {
            synchronized (warnings) {
                return new ArrayList<>(warnings);
            }
        }

        void warn(ScanWarning.Code code, String path, String detail) {
            synchronized (warnings) {
                if (warnings.size() < 1000) {
                    warnings.add(new ScanWarning(code, path, detail));
                }
            }
        }

        private void checkCancelled() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Search cancelled");
            }
        }

        List<Unit> execute(List<Path> roots) {
            if (roots == null || roots.isEmpty()) {
                throw new IllegalArgumentException("no roots");
            }
            List<Path> archiveFiles = new ArrayList<>();
            Map<Path, List<LooseFile>> looseByRoot = new LinkedHashMap<>();
            collectTargets(roots, archiveFiles, looseByRoot);

            List<Task> tasks = new ArrayList<>();
            for (Path archive : archiveFiles) {
                tasks.add(new Task(archive.toString(), () -> scanArchive(archive)));
            }
            looseByRoot.forEach((root, files) -> tasks.add(new Task(root.toString(), () -> scanLoose(files))));
            listener.onProgress(0, tasks.size(), "");

            List<Unit> units = new ArrayList<>();
            if (tasks.isEmpty()) {
                return units;
            }
            ExecutorService pool = DaemonThreads.fixed("class-search", Math.min(options.threads(), tasks.size()));
            try {
                CompletionService<List<Unit>> completion = new ExecutorCompletionService<>(pool);
                Map<Future<List<Unit>>, Task> submitted = new HashMap<>();
                for (Task task : tasks) {
                    submitted.put(completion.submit(task.work()), task);
                }
                int done = 0;
                while (done < tasks.size()) {
                    checkCancelled();
                    Future<List<Unit>> future;
                    try {
                        future = completion.poll(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Search interrupted");
                    }
                    if (future == null) {
                        continue;
                    }
                    done++;
                    Task task = submitted.get(future);
                    try {
                        units.addAll(future.get());
                    } catch (ExecutionException failed) {
                        Throwable cause = failed.getCause();
                        if (cause instanceof CancellationException) {
                            throw (CancellationException) cause;
                        }
                        warn(ScanWarning.Code.UNREADABLE, task.name(), String.valueOf(cause));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Search interrupted");
                    }
                    listener.onProgress(done, tasks.size(), task.name());
                }
            } finally {
                pool.shutdownNow();
            }
            units.sort(Comparator.comparing(Unit::sortKey));
            return units;
        }

        // ------------------------------------------
        // 遍历
        // ------------------------------------------
        private void collectTargets(List<Path> roots, List<Path> archiveFiles, Map<Path, List<LooseFile>> loose) {
            Set<Path> seen = new LinkedHashSet<>();
            AtomicInteger visited = new AtomicInteger();
            for (Path rawRoot : roots) {
                checkCancelled();
                Path root = rawRoot.toAbsolutePath().normalize();
                if (Files.isRegularFile(root)) {
                    if (seen.add(root)) {
                        archiveFiles.add(root);
                    }
                    continue;
                }
                if (!Files.isDirectory(root)) {
                    warn(ScanWarning.Code.UNREADABLE, root.toString(), "not found");
                    continue;
                }
                try {
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            checkCancelled();
                            Path name = dir.getFileName();
                            if (!dir.equals(root) && name != null && SKIPPED_DIRECTORIES.contains(name.toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (visited.incrementAndGet() > options.maxFiles()) {
                                warn(ScanWarning.Code.FILE_LIMIT, root.toString(), String.valueOf(options.maxFiles()));
                                return FileVisitResult.TERMINATE;
                            }
                            if (!attrs.isRegularFile()) {
                                return FileVisitResult.CONTINUE;
                            }
                            String fileName = file.getFileName().toString();
                            String lower = fileName.toLowerCase(Locale.ROOT);
                            if (ArchiveIO.isArchiveName(fileName)) {
                                // 源码包与文档包不含 class，扫描它们只会浪费时间。
                                if (!lower.endsWith("-sources.jar") && !lower.endsWith("-javadoc.jar")
                                        && seen.add(file.toAbsolutePath().normalize())) {
                                    archiveFiles.add(file);
                                }
                                if (query != null && !query.targetsClasses()) {
                                    loose.computeIfAbsent(root, key -> new ArrayList<>()).add(new LooseFile(file, root));
                                }
                            } else if (lower.endsWith(".class")
                                    || (query != null && !query.targetsClasses())) {
                                if (seen.add(file.toAbsolutePath().normalize())) {
                                    loose.computeIfAbsent(root, key -> new ArrayList<>()).add(new LooseFile(file, root));
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException error) {
                            warn(ScanWarning.Code.UNREADABLE, file.toString(), error.getClass().getSimpleName());
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException error) {
                    warn(ScanWarning.Code.UNREADABLE, root.toString(), error.getMessage());
                }
            }
        }

        // ------------------------------------------
        // 归档
        // ------------------------------------------
        private List<Unit> scanArchive(Path file) {
            List<Unit> out = new ArrayList<>();
            try (ZipFile zip = new ZipFile(file.toFile())) {
                archives.incrementAndGet();
                Unit unit = new Unit(LocationKind.JAR, file, null);
                out.add(unit);
                try (ArchiveIO.EntrySource source = ArchiveIO.zipFileSource(zip)) {
                    scanSource(unit, source, 0, out);
                }
            } catch (ZipException notZip) {
                warn(ScanWarning.Code.NOT_A_ZIP, file.toString(), notZip.getMessage());
                return List.of();
            } catch (IOException unreadable) {
                warn(ScanWarning.Code.UNREADABLE, file.toString(), unreadable.getMessage());
                return out;
            }
            return out;
        }

        private void scanSource(Unit unit, ArchiveIO.EntrySource source, int depth, List<Unit> out)
                throws IOException {
            JarInspector.Limits limits = options.limits();
            int count = 0;
            ArchiveIO.ArchiveEntry entry;
            while ((entry = source.next()) != null) {
                checkCancelled();
                if (++count > limits.maxEntries()) {
                    warn(ScanWarning.Code.ENTRY_LIMIT, describe(unit), String.valueOf(limits.maxEntries()));
                    return;
                }
                if (entry.directory()) {
                    continue;
                }
                String name = entry.name();
                try {
                    if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                        unit.manifestVersion = manifestVersion(read(entry, 1024 * 1024));
                    } else if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                        MavenCoordinates coordinates = MavenCoordinates.fromPomProperties(read(entry, 64 * 1024), name);
                        if (coordinates != null) {
                            unit.coordinates.add(coordinates);
                        }
                    }
                    if (name.endsWith(".class")) {
                        handleClassEntry(unit, entry, name);
                    } else {
                        if (query != null && !query.targetsClasses()
                                && query.matchesResource(ArchiveIO.classPath(name))) {
                            addHit(unit, new RawHit(ArchiveIO.classPath(name), false, name, entry.size()));
                        }
                        if (ArchiveIO.isArchiveName(name) && depth + 1 <= limits.maxDepth()) {
                            scanNested(unit, entry, name, depth + 1, out);
                        }
                    }
                } catch (ArchiveIO.EntryTooLargeException | ArchiveIO.CompressionRatioException guard) {
                    warn(ScanWarning.Code.GUARD_TRIPPED, describe(unit) + "!/" + name, guard.getMessage());
                }
            }
        }

        private void handleClassEntry(Unit unit, ArchiveIO.ArchiveEntry entry, String name) throws IOException {
            String fqn = ArchiveIO.className(name);
            if (query != null && query.targetsClasses() && query.matchesClass(fqn)) {
                addHit(unit, new RawHit(fqn, true, name, entry.size()));
            }
            // 多版本条目与 module-info 在同一个 JAR 里天然“重复”，不参与冲突检测。
            if (hashing && ArchiveIO.versionOf(name) == 0 && !fqn.endsWith("module-info")
                    && !unit.classes.containsKey(fqn)) {
                byte[] bytes = read(entry, Math.min(options.limits().maxEntryBytes(), ClassFileParser.MAX_CLASS_BYTES));
                unit.classes.put(fqn, new RawClass(fqn, name, sha256(bytes)));
            }
        }

        private void scanNested(Unit parent, ArchiveIO.ArchiveEntry entry, String name, int depth, List<Unit> out)
                throws IOException {
            JarInspector.Limits limits = options.limits();
            String nestedPath = parent.nestedPath == null ? name : parent.nestedPath + "!/" + name;
            Unit child = new Unit(LocationKind.NESTED_JAR, parent.file, nestedPath);
            try (InputStream in = entry.open()) {
                ArchiveIO.GuardedInputStream guarded = new ArchiveIO.GuardedInputStream(in, limits.maxNestedBytes(),
                        entry.compressedCounter(), limits.maxCompressionRatio());
                try (ArchiveIO.EntrySource source = ArchiveIO.streamSource(guarded, limits.maxEntryBytes(),
                        limits.maxCompressionRatio())) {
                    List<Unit> nestedOut = new ArrayList<>();
                    scanSource(child, source, depth, nestedOut);
                    out.add(child);
                    out.addAll(nestedOut);
                }
            } catch (ArchiveIO.EntryTooLargeException | ArchiveIO.CompressionRatioException guard) {
                warn(ScanWarning.Code.GUARD_TRIPPED, parent.file + "!/" + nestedPath, guard.getMessage());
            } catch (ZipException corrupt) {
                warn(ScanWarning.Code.NOT_A_ZIP, parent.file + "!/" + nestedPath, corrupt.getMessage());
            } catch (IOException unreadable) {
                warn(ScanWarning.Code.UNREADABLE, parent.file + "!/" + nestedPath, unreadable.getMessage());
            }
        }

        private byte[] read(ArchiveIO.ArchiveEntry entry, int maxBytes) throws IOException {
            try (InputStream in = entry.open()) {
                return ArchiveIO.readBounded(in, maxBytes, entry.compressedCounter(),
                        options.limits().maxCompressionRatio());
            }
        }

        private void addHit(Unit unit, RawHit hit) {
            if (hitCount.incrementAndGet() <= options.maxHits() + 1) {
                unit.hits.add(hit);
            }
        }

        private String describe(Unit unit) {
            return unit.nestedPath == null ? unit.file.toString() : unit.file + "!/" + unit.nestedPath;
        }

        // ------------------------------------------
        // 散落文件（展开的类目录、资源）
        // ------------------------------------------
        private List<Unit> scanLoose(List<LooseFile> files) {
            Map<Path, Unit> units = new LinkedHashMap<>();
            boolean countedDirectory = false;
            ClassFileParser parser = new ClassFileParser();
            for (LooseFile loose : files) {
                checkCancelled();
                Path file = loose.file();
                String relative = relativePath(loose.walkRoot(), file);
                try {
                    if (relative.endsWith(".class")) {
                        if (!countedDirectory) {
                            directories.incrementAndGet();
                            countedDirectory = true;
                        }
                        handleLooseClass(units, loose, relative, parser);
                    } else if (query != null && !query.targetsClasses() && matchesLooseResource(relative)) {
                        Unit unit = units.computeIfAbsent(loose.walkRoot(),
                                root -> new Unit(LocationKind.DIRECTORY, root, null));
                        addHit(unit, new RawHit(relative, false, relative, Files.size(file)));
                    }
                } catch (IOException unreadable) {
                    warn(ScanWarning.Code.UNREADABLE, file.toString(), unreadable.getMessage());
                }
            }
            return new ArrayList<>(units.values());
        }

        private void handleLooseClass(Map<Path, Unit> units, LooseFile loose, String relative,
                                      ClassFileParser parser) throws IOException {
            Path file = loose.file();
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes(ClassFileParser.MAX_CLASS_BYTES + 1);
            }
            if (bytes.length > ClassFileParser.MAX_CLASS_BYTES) {
                warn(ScanWarning.Code.GUARD_TRIPPED, file.toString(), String.valueOf(ClassFileParser.MAX_CLASS_BYTES));
                return;
            }
            // 类名以 class 文件自己声明的为准，由它反推类目录根（target/classes、out/production/x…）。
            String fqn = null;
            Path classRoot = loose.walkRoot();
            try {
                fqn = parser.parse(bytes).className();
                String suffix = fqn.replace('.', '/') + ".class";
                String normalized = relative.replace('\\', '/');
                if (normalized.equals(suffix)) {
                    classRoot = loose.walkRoot();
                } else if (normalized.endsWith("/" + suffix)) {
                    classRoot = loose.walkRoot().resolve(normalized.substring(0, normalized.length() - suffix.length() - 1));
                } else {
                    fqn = null;
                }
            } catch (IOException corrupt) {
                warn(ScanWarning.Code.CORRUPT_CLASS, file.toString(), corrupt.getMessage());
            }
            if (fqn == null) {
                fqn = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
            }
            String entryPath = relativePath(classRoot, file);
            Unit unit = units.computeIfAbsent(classRoot, root -> new Unit(LocationKind.DIRECTORY, root, null));
            if (query != null && query.targetsClasses() && query.matchesClass(fqn)) {
                addHit(unit, new RawHit(fqn, true, entryPath, bytes.length));
            }
            if (hashing && !fqn.endsWith("module-info") && !unit.classes.containsKey(fqn)) {
                unit.classes.put(fqn, new RawClass(fqn, entryPath, sha256(bytes)));
            }
        }

        /** 展开目录里的资源：相对遍历根的路径，以及最后一个 classes/ 之后的路径，任一命中即可。 */
        private boolean matchesLooseResource(String relative) {
            if (query.matchesResource(relative)) {
                return true;
            }
            int classes = relative.lastIndexOf("classes/");
            return classes >= 0 && (classes == 0 || relative.charAt(classes - 1) == '/')
                    && query.matchesResource(relative.substring(classes + "classes/".length()));
        }
    }

    private record Task(String name, java.util.concurrent.Callable<List<Unit>> work) {
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** MANIFEST 里的版本：Implementation-Version 优先，其次 Bundle-Version。 */
    static String manifestVersion(byte[] bytes) {
        String implementation = null;
        String bundle = null;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\r\n|\r|\n")) {
            if (line.isEmpty()) {
                break;
            }
            if (line.startsWith("Implementation-Version:")) {
                implementation = line.substring("Implementation-Version:".length()).trim();
            } else if (line.startsWith("Bundle-Version:")) {
                bundle = line.substring("Bundle-Version:".length()).trim();
            }
        }
        if (implementation != null && !implementation.isEmpty()) {
            return implementation;
        }
        return bundle == null || bundle.isEmpty() ? null : bundle;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
