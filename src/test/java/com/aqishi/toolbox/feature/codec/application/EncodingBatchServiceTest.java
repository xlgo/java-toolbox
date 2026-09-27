package com.aqishi.toolbox.feature.codec.application;

import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.Action;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.BackupMode;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.Code;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.ConvertOptions;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.ConvertRequest;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.ConvertSummary;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.Outcome;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.ScanOptions;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.ScanResult;
import com.aqishi.toolbox.feature.codec.application.EncodingBatchService.ScanRow;
import com.aqishi.toolbox.feature.codec.domain.TextFileConverter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodingBatchServiceTest {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String CHINESE = "编码转换测试：这是一段足够长的中文内容，用于让探测器给出高置信度的判断。\r\n"
            + "第二行继续补充一些常用汉字和标点符号，确保统计样本足够。\r\n";
    private static final FileTime OLD_TIME = FileTime.fromMillis(1_600_000_000_000L);

    @TempDir
    Path root;

    private final EncodingBatchService service = new EncodingBatchService();

    @BeforeEach
    void createTree() throws IOException {
        write("src/Main.java", CHINESE.getBytes(GBK));
        write("src/readme.txt", CHINESE.getBytes(StandardCharsets.UTF_8));
        write("src/ascii.md", "plain ascii\n".getBytes(StandardCharsets.US_ASCII));
        write("src/short.txt", "中文".getBytes(GBK));
        write("src/image.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0});
        write("src/fake.txt", new byte[]{'a', 0, 0, 1, 2, 3, 0, 0, (byte) 0xFF, 0, 7, 0, 0, 0});
        write("target/Generated.java", CHINESE.getBytes(GBK));
        write("node_modules/lib/index.js", CHINESE.getBytes(GBK));
        write("docs/big.txt", CHINESE.repeat(20).getBytes(GBK));
        for (Path file : allFiles()) {
            Files.setLastModifiedTime(file, OLD_TIME);
        }
    }

    private Path write(String relative, byte[] bytes) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return file;
    }

    private List<Path> allFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
    }

    private Map<Path, byte[]> snapshot() throws IOException {
        Map<Path, byte[]> contents = new HashMap<>();
        for (Path file : allFiles()) {
            contents.put(file, Files.readAllBytes(file));
        }
        return contents;
    }

    private ScanOptions options(long maxBytes) {
        return new ScanOptions(root, EncodingBatchService.DEFAULT_INCLUDES,
                EncodingBatchService.DEFAULT_EXCLUDED_DIRS, maxBytes, 1000, 0.6,
                TextFileConverter.Options.utf8());
    }

    private static Map<String, ScanRow> byPath(ScanResult result) {
        Map<String, ScanRow> rows = new HashMap<>();
        for (ScanRow row : result.rows()) {
            rows.put(row.relativePath(), row);
        }
        return rows;
    }

    @Test
    void scanAppliesFiltersAndPlansActionsWithoutTouchingFiles() throws IOException {
        Map<Path, byte[]> before = snapshot();
        ScanResult result = service.scan(options(1024), () -> false, null);
        Map<String, ScanRow> rows = byPath(result);

        // 排除目录与未包含的扩展名不出现
        assertFalse(rows.containsKey("target/Generated.java"));
        assertFalse(rows.containsKey("node_modules/lib/index.js"));
        assertFalse(rows.containsKey("src/image.png"));

        assertEquals(Action.CONVERT, rows.get("src/Main.java").action());
        assertEquals(GBK, rows.get("src/Main.java").detection().charset());
        assertEquals(2, rows.get("src/Main.java").lineStats().crlf());
        assertEquals(Action.UNCHANGED, rows.get("src/readme.txt").action());
        assertEquals(Action.UNCHANGED, rows.get("src/ascii.md").action());
        assertEquals(Action.NEEDS_CONFIRMATION, rows.get("src/short.txt").action());
        assertEquals(Code.LOW_CONFIDENCE, rows.get("src/short.txt").code());
        assertEquals(Action.SKIP, rows.get("src/fake.txt").action());
        assertEquals(Code.BINARY, rows.get("src/fake.txt").code());
        assertEquals(Action.SKIP, rows.get("docs/big.txt").action());
        assertEquals(Code.TOO_LARGE, rows.get("docs/big.txt").code());
        assertFalse(result.cancelled());

        // 扫描是只读的
        Map<Path, byte[]> after = snapshot();
        assertEquals(before.keySet(), after.keySet());
        for (Map.Entry<Path, byte[]> entry : before.entrySet()) {
            assertArrayEquals(entry.getValue(), after.get(entry.getKey()), entry.getKey().toString());
            assertEquals(OLD_TIME, Files.getLastModifiedTime(entry.getKey()));
        }
    }

    @Test
    void includePatternsAreCaseInsensitiveAndEmptyMeansAll() throws IOException {
        write("src/UPPER.TXT", "x".getBytes(StandardCharsets.US_ASCII));
        ScanResult defaults = service.scan(options(1 << 20), null, null);
        assertTrue(byPath(defaults).containsKey("src/UPPER.TXT"));

        ScanOptions everything = new ScanOptions(root, List.of(), List.of(), 1 << 20, 1000, 0.6,
                TextFileConverter.Options.utf8());
        Map<String, ScanRow> all = byPath(service.scan(everything, null, null));
        assertTrue(all.containsKey("src/image.png"));
        assertTrue(all.containsKey("target/Generated.java"));
        assertEquals(Code.BINARY, all.get("src/image.png").code());
    }

    @Test
    void wildcardExcludesAndFileLimit() throws IOException {
        ScanOptions options = new ScanOptions(root, List.of("*"), List.of("tar*", "node_*", "do?s"), 1 << 20, 3,
                0.6, TextFileConverter.Options.utf8());
        ScanResult result = service.scan(options, null, null);
        assertEquals(3, result.rows().size());
        assertTrue(result.limitReached());
        for (ScanRow row : result.rows()) {
            assertFalse(row.relativePath().startsWith("target/"));
            assertFalse(row.relativePath().startsWith("docs/"));
        }
    }

    @Test
    void convertWritesAtomicallyWithSiblingBackupAndVerifies() throws IOException {
        ScanResult scan = service.scan(options(1024), null, null);
        Map<String, ScanRow> rows = byPath(scan);
        Path main = root.resolve("src/Main.java");
        byte[] original = Files.readAllBytes(main);

        List<ConvertRequest> requests = List.of(new ConvertRequest(rows.get("src/Main.java"), null),
                new ConvertRequest(rows.get("src/readme.txt"), null),
                new ConvertRequest(rows.get("src/fake.txt"), null));
        List<Integer> progress = new ArrayList<>();
        ConvertSummary summary = service.convert(requests, convertOptions(BackupMode.SIBLING, null, false),
                () -> false, (done, total, current) -> progress.add(done));

        assertEquals(1, summary.converted());
        assertEquals(1, summary.unchanged());
        assertEquals(1, summary.skipped());
        assertEquals(0, summary.failed());
        assertEquals(List.of(1, 2, 3), progress);

        assertArrayEquals(CHINESE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(main));
        Path backup = summary.results().get(0).backup();
        assertEquals(root.resolve("src/Main.java.bak"), backup);
        assertArrayEquals(original, Files.readAllBytes(backup));
        // 未改动的文件不写、不备份
        assertNull(summary.results().get(1).backup());
        assertFalse(Files.exists(root.resolve("src/readme.txt.bak")));
        // 没有残留的临时文件
        try (Stream<Path> files = Files.list(root.resolve("src"))) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
        // 未要求保留时间：修改时间已更新
        assertFalse(OLD_TIME.equals(Files.getLastModifiedTime(main)));
    }

    @Test
    void existingBackupIsNeverOverwritten() throws IOException {
        Path oldBackup = write("src/Main.java.bak", "older backup".getBytes(StandardCharsets.US_ASCII));
        ScanRow row = byPath(service.scan(options(1024), null, null)).get("src/Main.java");
        ConvertSummary summary = service.convert(List.of(new ConvertRequest(row, null)),
                convertOptions(BackupMode.SIBLING, null, true), null, null);
        assertEquals(root.resolve("src/Main.java.bak.1"), summary.results().get(0).backup());
        assertArrayEquals("older backup".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(oldBackup));
        assertEquals(OLD_TIME, Files.getLastModifiedTime(root.resolve("src/Main.java")));
    }

    @Test
    void mirrorBackupKeepsRelativeLayout(@TempDir Path backups) throws IOException {
        ScanRow row = byPath(service.scan(options(1024), null, null)).get("src/Main.java");
        byte[] original = Files.readAllBytes(row.path());
        ConvertSummary summary = service.convert(List.of(new ConvertRequest(row, null)),
                convertOptions(BackupMode.MIRROR, backups, false), null, null);
        assertEquals(Outcome.CONVERTED, summary.results().get(0).outcome());
        Path expected = backups.resolve("src").resolve("Main.java");
        assertEquals(expected, summary.results().get(0).backup());
        assertArrayEquals(original, Files.readAllBytes(expected));
    }

    @Test
    void lowConfidenceRowsNeedAnExplicitSourceCharset() throws IOException {
        ScanRow row = byPath(service.scan(options(1024), null, null)).get("src/short.txt");
        Path file = row.path();
        byte[] original = Files.readAllBytes(file);

        ConvertSummary skipped = service.convert(List.of(new ConvertRequest(row, null)),
                convertOptions(BackupMode.NONE, null, false), null, null);
        assertEquals(Outcome.SKIPPED, skipped.results().get(0).outcome());
        assertEquals(Code.LOW_CONFIDENCE, skipped.results().get(0).code());
        assertArrayEquals(original, Files.readAllBytes(file));

        ConvertSummary confirmed = service.convert(List.of(new ConvertRequest(row, GBK)),
                convertOptions(BackupMode.NONE, null, false), null, null);
        assertEquals(Outcome.CONVERTED, confirmed.results().get(0).outcome());
        assertEquals("中文", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void wrongSourceOverrideFailsWithoutWriting() throws IOException {
        ScanRow row = byPath(service.scan(options(1024), null, null)).get("src/Main.java");
        byte[] original = Files.readAllBytes(row.path());
        ConvertSummary summary = service.convert(List.of(new ConvertRequest(row, StandardCharsets.UTF_8)),
                convertOptions(BackupMode.SIBLING, null, false), null, null);
        EncodingBatchService.FileResult result = summary.results().get(0);
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals(Code.MALFORMED_INPUT, result.code());
        assertTrue(result.errorOffset() >= 0);
        assertArrayEquals(original, Files.readAllBytes(row.path()));
        assertFalse(Files.exists(root.resolve("src/Main.java.bak")), "no backup when nothing is written");
    }

    @Test
    void unmappableCharactersArePlannedAsFailures() throws IOException {
        write("src/emoji.txt", ("smile " + new String(Character.toChars(0x1F600)) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        ScanOptions toGbk = new ScanOptions(root, EncodingBatchService.DEFAULT_INCLUDES,
                EncodingBatchService.DEFAULT_EXCLUDED_DIRS, 1 << 20, 1000, 0.6,
                new TextFileConverter.Options(GBK, TextFileConverter.BomMode.REMOVE,
                        TextFileConverter.LineEnding.KEEP, false));
        ScanRow row = byPath(service.scan(toGbk, null, null)).get("src/emoji.txt");
        assertEquals(Action.WILL_FAIL, row.action());
        assertEquals(Code.UNMAPPABLE, row.code());
        assertEquals(0x1F600, row.errorCodePoint());
        assertEquals(1, row.errorLine());
    }

    @Test
    void filesChangedAfterScanAreNotOverwritten() throws IOException {
        ScanRow row = byPath(service.scan(options(1024), null, null)).get("src/Main.java");
        Files.write(row.path(), "someone else edited this file".getBytes(StandardCharsets.US_ASCII));
        ConvertSummary summary = service.convert(List.of(new ConvertRequest(row, null)),
                convertOptions(BackupMode.NONE, null, false), null, null);
        assertEquals(Code.FILE_CHANGED, summary.results().get(0).code());
        assertEquals("someone else edited this file", Files.readString(row.path(), StandardCharsets.US_ASCII));

        Files.delete(row.path());
        ConvertSummary missing = service.convert(List.of(new ConvertRequest(row, null)),
                convertOptions(BackupMode.NONE, null, false), null, null);
        assertEquals(Code.NOT_FOUND, missing.results().get(0).code());
    }

    @Test
    void convertStopsBetweenFilesWhenCancelled() throws IOException {
        write("src/Second.java", CHINESE.getBytes(GBK));
        write("src/Third.java", CHINESE.getBytes(GBK));
        Map<String, ScanRow> rows = byPath(service.scan(options(1024), null, null));
        List<ConvertRequest> requests = List.of(new ConvertRequest(rows.get("src/Main.java"), null),
                new ConvertRequest(rows.get("src/Second.java"), null),
                new ConvertRequest(rows.get("src/Third.java"), null));
        AtomicInteger processed = new AtomicInteger();
        ConvertSummary summary = service.convert(requests, convertOptions(BackupMode.NONE, null, false),
                () -> processed.get() >= 1, (done, total, current) -> processed.set(done));
        assertTrue(summary.cancelled());
        assertEquals(1, summary.results().size());
        assertEquals(1, summary.converted());
        assertArrayEquals(CHINESE.getBytes(GBK), Files.readAllBytes(root.resolve("src/Second.java")));
        assertArrayEquals(CHINESE.getBytes(GBK), Files.readAllBytes(root.resolve("src/Third.java")));
    }

    @Test
    void scanCanBeCancelled() throws IOException {
        AtomicInteger seen = new AtomicInteger();
        ScanResult result = service.scan(options(1024), () -> seen.get() >= 2,
                (done, total, current) -> {
                    seen.set(done);
                    assertEquals(-1, total);
                    assertNotNull(current);
                });
        assertTrue(result.cancelled());
        assertEquals(2, result.rows().size());
    }

    @Test
    void scanRejectsMissingRoot() {
        ScanOptions missing = new ScanOptions(root.resolve("nope"), List.of(), List.of(), 1024, 10, 0.6,
                TextFileConverter.Options.utf8());
        assertThrows(IOException.class, () -> service.scan(missing, null, null));
    }

    @Test
    void symlinksAreNotFollowed() throws IOException {
        Path link = root.resolve("src/link.txt");
        try {
            Files.createSymbolicLink(link, root.resolve("src/Main.java"));
        } catch (UnsupportedOperationException | FileSystemException notPermitted) {
            Assumptions.abort("symbolic links are not available: " + notPermitted.getMessage());
        }
        ScanRow row = byPath(service.scan(options(1024), null, null)).get("src/link.txt");
        assertEquals(Action.SKIP, row.action());
        assertEquals(Code.SYMLINK, row.code());
    }

    @Test
    void splitsPatternLists() {
        assertEquals(List.of("*.java", "*.txt", "Makefile"),
                EncodingBatchService.splitPatterns(" *.java, *.txt ;\nMakefile ,"));
        assertTrue(EncodingBatchService.splitPatterns(null).isEmpty());
    }

    private ConvertOptions convertOptions(BackupMode backup, Path backupRoot, boolean preserveTime) {
        return new ConvertOptions(TextFileConverter.Options.utf8(), backup, root, backupRoot, preserveTime,
                EncodingBatchService.DEFAULT_MAX_BYTES);
    }
}
