package com.aqishi.toolbox.feature.codec.application;

import com.aqishi.toolbox.feature.codec.domain.EncodingDetector;
import com.aqishi.toolbox.feature.codec.domain.TextFileConverter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 目录级的编码扫描与批量转换。
 *
 * <p>分两阶段：{@link #scan} 只读不写，给出每个文件的探测结果与计划动作（先按当前选项演算一遍转换）；
 * {@link #convert} 只处理用户勾选的行，逐个文件「备份 → 同目录临时文件 → 原子替换 → 回读校验」。
 * 置信度低于阈值的行不会被自动转换，必须由用户确认或指定源编码。</p>
 *
 * <p>取消只在文件之间生效：单个文件要么完整替换、要么原样不动，不会留下写了一半的文件。</p>
 */
public class EncodingBatchService {

    public static final List<String> DEFAULT_INCLUDES = List.of(
            "*.java", "*.properties", "*.xml", "*.yml", "*.yaml", "*.txt", "*.md", "*.sql", "*.csv",
            "*.json", "*.html", "*.js", "*.ts", "*.css", "*.vue", "*.c", "*.h", "*.cpp", "*.py",
            "*.sh", "*.bat", "*.cmd");
    public static final List<String> DEFAULT_EXCLUDED_DIRS = List.of(
            ".git", ".svn", ".idea", "target", "build", "out", "node_modules", "dist", ".gradle");
    public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.6;
    public static final int DEFAULT_MAX_FILES = 20_000;
    /** 同名备份已存在时最多尝试的序号，避免覆盖更早的原始备份。 */
    private static final int MAX_BACKUP_SUFFIX = 999;

    /** 扫描阶段为每个文件计划的动作。 */
    public enum Action {
        /** 将被转换。 */
        CONVERT,
        /** 转换结果与原文件逐字节相同，无需写入。 */
        UNCHANGED,
        /** 探测置信度不足，需用户确认源编码。 */
        NEEDS_CONFIRMATION,
        /** 不处理（二进制、过大、符号链接、读取失败）。 */
        SKIP,
        /** 按当前选项转换必然失败（非法字节或目标编码无法表示的字符）。 */
        WILL_FAIL
    }

    /** 转换阶段的结论。 */
    public enum Outcome { CONVERTED, UNCHANGED, SKIPPED, FAILED }

    /** 原因码；界面按名称映射文案。 */
    public enum Code {
        NONE, BINARY, TOO_LARGE, SYMLINK, READ_FAILED, LOW_CONFIDENCE, MALFORMED_INPUT, UNMAPPABLE,
        FILE_CHANGED, NOT_FOUND, BACKUP_FAILED, WRITE_FAILED, VERIFY_FAILED
    }

    /** 备份方式：不备份、同目录 {@code <文件>.bak}、镜像到单独的备份目录。 */
    public enum BackupMode { NONE, SIBLING, MIRROR }

    /** 进度回调；扫描阶段总数未知时 {@code total} 为 -1。 */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int done, int total, Path current);

        ProgressListener NONE = (done, total, current) -> {
        };
    }

    /**
     * 扫描选项。
     *
     * @param includes     文件名通配符（大小写不敏感）；为空表示全部文件
     * @param excludedDirs 跳过的目录名或目录名通配符
     * @param conversion   用于演算计划动作的转换选项
     */
    public record ScanOptions(Path root, List<String> includes, List<String> excludedDirs, long maxBytes,
                              int maxFiles, double threshold, TextFileConverter.Options conversion) {
        public ScanOptions {
            Objects.requireNonNull(root, "root");
            includes = List.copyOf(includes);
            excludedDirs = List.copyOf(excludedDirs);
            Objects.requireNonNull(conversion, "conversion");
        }

        public static ScanOptions defaults(Path root, TextFileConverter.Options conversion) {
            return new ScanOptions(root, DEFAULT_INCLUDES, DEFAULT_EXCLUDED_DIRS, DEFAULT_MAX_BYTES,
                    DEFAULT_MAX_FILES, DEFAULT_CONFIDENCE_THRESHOLD, conversion);
        }
    }

    /**
     * 扫描出的一行。
     *
     * @param relativePath   相对扫描根目录的路径，统一用 {@code /} 分隔
     * @param lastModified   扫描时的修改时间（毫秒），转换前据此判断文件是否已被改动
     * @param detection      探测结果；跳过的文件为 null
     * @param lineStats      换行统计；无法解码时为 null
     * @param errorOffset    计划失败时的字节偏移 / 字符下标，否则 -1
     * @param errorLine      计划失败时的行号，否则 0
     * @param errorCodePoint 无法映射的字符，否则 -1
     */
    public record ScanRow(Path path, String relativePath, long size, long lastModified,
                          EncodingDetector.Result detection, TextFileConverter.LineStats lineStats,
                          Action action, Code code, long errorOffset, int errorLine, int errorCodePoint) {
    }

    public record ScanResult(Path root, List<ScanRow> rows, boolean cancelled, boolean limitReached) {
        public ScanResult {
            rows = List.copyOf(rows);
        }
    }

    /** 转换请求：一行扫描结果，加上用户指定的源编码（null 表示采用探测结果）。 */
    public record ConvertRequest(ScanRow row, Charset sourceOverride) {
        public ConvertRequest {
            Objects.requireNonNull(row, "row");
        }
    }

    /**
     * 转换选项。
     *
     * @param scanRoot             扫描根目录，镜像备份据此计算相对路径
     * @param backupRoot           镜像备份目录（仅 MIRROR 使用）
     * @param preserveLastModified 保留原文件的修改时间
     */
    public record ConvertOptions(TextFileConverter.Options conversion, BackupMode backup, Path scanRoot,
                                 Path backupRoot, boolean preserveLastModified, long maxBytes) {
        public ConvertOptions {
            Objects.requireNonNull(conversion, "conversion");
            Objects.requireNonNull(backup, "backup");
            if (backup == BackupMode.MIRROR) {
                Objects.requireNonNull(scanRoot, "scanRoot");
                Objects.requireNonNull(backupRoot, "backupRoot");
            }
        }
    }

    /**
     * 单个文件的转换结果。
     *
     * @param backup 备份文件位置；未备份时为 null
     * @param detail 失败时的底层异常说明（英文，仅供排查），否则为 null
     */
    public record FileResult(ScanRow row, Outcome outcome, Code code, Path backup, long errorOffset,
                             int errorLine, int errorCodePoint, int replacements, String detail) {
    }

    public record ConvertSummary(List<FileResult> results, int converted, int unchanged, int skipped,
                                 int failed, boolean cancelled) {
        public ConvertSummary {
            results = List.copyOf(results);
        }
    }

    private final EncodingDetector detector;

    public EncodingBatchService() {
        this(new EncodingDetector());
    }

    public EncodingBatchService(EncodingDetector detector) {
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    // ==========================================
    // 阶段一：扫描（只读）
    // ==========================================
    public ScanResult scan(ScanOptions options, BooleanSupplier cancelled, ProgressListener progress)
            throws IOException {
        Objects.requireNonNull(options, "options");
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        ProgressListener listener = progress == null ? ProgressListener.NONE : progress;
        Path root = options.root().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new NoSuchFileException(root.toString());
        }
        List<Pattern> includes = compile(options.includes());
        List<Pattern> excludes = compile(options.excludedDirs());
        List<ScanRow> rows = new ArrayList<>();
        boolean[] flags = new boolean[2]; // [0] 已取消，[1] 达到上限

        // 不传 FOLLOW_LINKS：符号链接不跟随，指向目录的链接也不会被展开
        Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (stop.getAsBoolean()) {
                            flags[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        if (!dir.equals(root) && dir.getFileName() != null
                                && matchesAny(excludes, dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (stop.getAsBoolean()) {
                            flags[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        String name = file.getFileName() == null ? "" : file.getFileName().toString();
                        if (!includes.isEmpty() && !matchesAny(includes, name)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (!attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (rows.size() >= options.maxFiles()) {
                            flags[1] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        rows.add(analyze(root, file, attrs, options));
                        listener.onProgress(rows.size(), -1, file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        String name = file.getFileName() == null ? "" : file.getFileName().toString();
                        if ((includes.isEmpty() || matchesAny(includes, name)) && rows.size() < options.maxFiles()) {
                            rows.add(skipRow(root, file, 0, 0, Code.READ_FAILED));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return new ScanResult(root, rows, flags[0], flags[1]);
    }

    /** 分析单个文件：探测编码并按转换选项演算一遍，得出计划动作。 */
    ScanRow analyze(Path root, Path file, BasicFileAttributes attrs, ScanOptions options) {
        long size = attrs.size();
        long modified = attrs.lastModifiedTime().toMillis();
        if (attrs.isSymbolicLink()) {
            return skipRow(root, file, size, modified, Code.SYMLINK);
        }
        if (size > options.maxBytes()) {
            return skipRow(root, file, size, modified, Code.TOO_LARGE);
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException error) {
            return skipRow(root, file, size, modified, Code.READ_FAILED);
        }
        if (data.length > options.maxBytes()) {
            return skipRow(root, file, data.length, modified, Code.TOO_LARGE);
        }
        EncodingDetector.Result detection = detector.detect(data);
        String relative = relativize(root, file);
        if (detection.binary()) {
            return new ScanRow(file, relative, data.length, modified, detection, null, Action.SKIP, Code.BINARY,
                    -1, 0, -1);
        }
        TextFileConverter.Result plan = TextFileConverter.convert(data, detection.charset(), options.conversion());
        TextFileConverter.LineStats lines = plan.found();
        if (plan.status() == TextFileConverter.Status.UNCHANGED) {
            return new ScanRow(file, relative, data.length, modified, detection, lines, Action.UNCHANGED,
                    Code.NONE, -1, 0, -1);
        }
        if (detection.confidence() < options.threshold()) {
            return new ScanRow(file, relative, data.length, modified, detection, lines,
                    Action.NEEDS_CONFIRMATION, Code.LOW_CONFIDENCE, -1, 0, -1);
        }
        if (plan.status() == TextFileConverter.Status.FAILED) {
            Code code = plan.failure() == TextFileConverter.Failure.MALFORMED_INPUT
                    ? Code.MALFORMED_INPUT : Code.UNMAPPABLE;
            return new ScanRow(file, relative, data.length, modified, detection, lines, Action.WILL_FAIL, code,
                    plan.errorOffset(), plan.errorLine(), plan.errorCodePoint());
        }
        return new ScanRow(file, relative, data.length, modified, detection, lines, Action.CONVERT, Code.NONE,
                -1, 0, -1);
    }

    private static ScanRow skipRow(Path root, Path file, long size, long modified, Code code) {
        return new ScanRow(file, relativize(root, file), size, modified, null, null, Action.SKIP, code,
                -1, 0, -1);
    }

    // ==========================================
    // 阶段二：转换
    // ==========================================
    public ConvertSummary convert(List<ConvertRequest> requests, ConvertOptions options,
                                  BooleanSupplier cancelled, ProgressListener progress) {
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(options, "options");
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        ProgressListener listener = progress == null ? ProgressListener.NONE : progress;
        List<FileResult> results = new ArrayList<>(requests.size());
        int converted = 0;
        int unchanged = 0;
        int skipped = 0;
        int failed = 0;
        boolean wasCancelled = false;
        for (int i = 0; i < requests.size(); i++) {
            if (stop.getAsBoolean()) {
                wasCancelled = true;
                break;
            }
            ConvertRequest request = requests.get(i);
            FileResult result = convertOne(request, options);
            results.add(result);
            switch (result.outcome()) {
                case CONVERTED:
                    converted++;
                    break;
                case UNCHANGED:
                    unchanged++;
                    break;
                case SKIPPED:
                    skipped++;
                    break;
                default:
                    failed++;
                    break;
            }
            listener.onProgress(i + 1, requests.size(), request.row().path());
        }
        return new ConvertSummary(results, converted, unchanged, skipped, failed, wasCancelled);
    }

    FileResult convertOne(ConvertRequest request, ConvertOptions options) {
        ScanRow row = request.row();
        if (row.action() == Action.SKIP) {
            return skipped(row, row.code());
        }
        if (request.sourceOverride() == null && row.action() == Action.NEEDS_CONFIRMATION) {
            return skipped(row, Code.LOW_CONFIDENCE);
        }
        Charset source = request.sourceOverride() != null ? request.sourceOverride()
                : row.detection() == null ? null : row.detection().charset();
        if (source == null) {
            return skipped(row, Code.BINARY);
        }
        Path file = row.path();
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return failed(row, Code.NOT_FOUND, null, missing);
        } catch (IOException error) {
            return failed(row, Code.READ_FAILED, null, error);
        }
        if (attrs.isSymbolicLink()) {
            return skipped(row, Code.SYMLINK);
        }
        if (attrs.size() > options.maxBytes()) {
            return skipped(row, Code.TOO_LARGE);
        }
        // 扫描之后被别人改过的文件，探测结论已不可信：宁可让用户重新扫描，也不按过期的判断覆盖它
        if (attrs.size() != row.size() || attrs.lastModifiedTime().toMillis() != row.lastModified()) {
            return failed(row, Code.FILE_CHANGED, null, null);
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException error) {
            return failed(row, Code.READ_FAILED, null, error);
        }
        TextFileConverter.Result conversion = TextFileConverter.convert(data, source, options.conversion());
        if (conversion.status() == TextFileConverter.Status.FAILED) {
            Code code = conversion.failure() == TextFileConverter.Failure.MALFORMED_INPUT
                    ? Code.MALFORMED_INPUT : Code.UNMAPPABLE;
            return new FileResult(row, Outcome.FAILED, code, null, conversion.errorOffset(),
                    conversion.errorLine(), conversion.errorCodePoint(), 0, null);
        }
        if (conversion.status() == TextFileConverter.Status.UNCHANGED) {
            return new FileResult(row, Outcome.UNCHANGED, Code.NONE, null, -1, 0, -1, 0, null);
        }

        Path backup = null;
        if (options.backup() != BackupMode.NONE) {
            try {
                backup = backup(file, options);
            } catch (IOException | RuntimeException error) {
                return failed(row, Code.BACKUP_FAILED, null, error);
            }
        }
        byte[] output = conversion.output();
        try {
            writeAtomically(file, output);
        } catch (IOException error) {
            return failed(row, Code.WRITE_FAILED, backup, error);
        }
        if (options.preserveLastModified()) {
            try {
                Files.setLastModifiedTime(file, attrs.lastModifiedTime());
            } catch (IOException ignored) {
                // 修改时间只是锦上添花：内容已正确写入，不因它把整个文件判为失败
            }
        }
        try {
            byte[] written = Files.readAllBytes(file);
            if (!Arrays.equals(written, output)
                    || !TextFileConverter.decode(written, conversion.target()).ok()) {
                return failed(row, Code.VERIFY_FAILED, backup, null);
            }
        } catch (IOException error) {
            return failed(row, Code.VERIFY_FAILED, backup, error);
        }
        return new FileResult(row, Outcome.CONVERTED, Code.NONE, backup, -1, 0, -1,
                conversion.replacements(), null);
    }

    /** 先备份原文件；已有同名备份时顺延序号，绝不覆盖更早的备份。 */
    Path backup(Path file, ConvertOptions options) throws IOException {
        Path target;
        if (options.backup() == BackupMode.MIRROR) {
            Path root = options.scanRoot().toAbsolutePath().normalize();
            Path absolute = file.toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) {
                throw new IOException("File is outside the scan root: " + absolute);
            }
            Path backupRoot = options.backupRoot().toAbsolutePath().normalize();
            target = backupRoot.resolve(root.relativize(absolute).toString()).normalize();
            if (!target.startsWith(backupRoot)) {
                throw new IOException("Backup path escapes the backup directory: " + target);
            }
            Files.createDirectories(target.getParent());
        } else {
            target = file.resolveSibling(file.getFileName() + ".bak");
        }
        Path candidate = target;
        for (int suffix = 1; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); suffix++) {
            if (suffix > MAX_BACKUP_SUFFIX) {
                throw new IOException("Too many existing backups for " + file);
            }
            candidate = target.resolveSibling(target.getFileName() + "." + suffix);
        }
        Files.copy(file, candidate, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        return candidate;
    }

    /** 同目录临时文件 + 原子替换：进程中途被杀也不会留下写了一半的目标文件。 */
    static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Path temporary = file.resolveSibling("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            copyPosixPermissions(file, temporary);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** 新建的临时文件带的是默认权限；替换前把原文件的权限（例如脚本的可执行位）抄过去。 */
    private static void copyPosixPermissions(Path from, Path to) {
        PosixFileAttributeView source = Files.getFileAttributeView(from, PosixFileAttributeView.class);
        PosixFileAttributeView target = Files.getFileAttributeView(to, PosixFileAttributeView.class);
        if (source == null || target == null) {
            return;
        }
        try {
            target.setPermissions(source.readAttributes().permissions());
        } catch (IOException | UnsupportedOperationException ignored) {
            // 权限拷贝失败时沿用默认权限，内容替换照常进行
        }
    }

    // ==========================================
    // 工具
    // ==========================================
    /** 逗号 / 分号 / 换行分隔的通配符列表。 */
    public static List<String> splitPatterns(String text) {
        List<String> patterns = new ArrayList<>();
        if (text == null) {
            return patterns;
        }
        for (String part : text.split("[,;\\r\\n]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        return patterns;
    }

    /** 简单通配符（{@code *}、{@code ?}）转正则，大小写不敏感——Windows 上 {@code *.JAVA} 也应当匹配。 */
    static List<Pattern> compile(List<String> globs) {
        List<Pattern> patterns = new ArrayList<>();
        for (String glob : globs) {
            StringBuilder regex = new StringBuilder();
            for (char c : glob.toCharArray()) {
                if (c == '*') {
                    regex.append(".*");
                } else if (c == '?') {
                    regex.append('.');
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            patterns.add(Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return patterns;
    }

    static boolean matchesAny(List<Pattern> patterns, String name) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException differentRoot) {
            return file.toString();
        }
    }

    private static FileResult skipped(ScanRow row, Code code) {
        return new FileResult(row, Outcome.SKIPPED, code, null, -1, 0, -1, 0, null);
    }

    private static FileResult failed(ScanRow row, Code code, Path backup, Throwable error) {
        String detail = error == null ? null
                : error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return new FileResult(row, Outcome.FAILED, code, backup, -1, 0, -1, 0, detail);
    }
}
