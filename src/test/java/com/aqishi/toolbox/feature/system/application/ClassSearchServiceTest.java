package com.aqishi.toolbox.feature.system.application;

import com.aqishi.toolbox.feature.system.domain.ClassFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassSearchServiceTest {

    @TempDir
    static Path temp;

    private static Path root;
    private final ClassSearchService service = new ClassSearchService(
            ClassSearchService.Options.defaults().withThreads(3));

    /** 夹具要编译好几次源码，整个类只建一次；会改动树的用例只追加文件、且只断言不受影响的结果。 */
    @BeforeAll
    static void buildTree() throws Exception {
        root = Files.createDirectories(temp.resolve("tree"));
        Path work = Files.createDirectories(temp.resolve("work"));
        byte[] guava30 = ClassFixtures.libraryJar(work, 8, "com.google", "Common", "public int v() { return 30; }",
                "com.google.guava", "guava", "30.0");
        byte[] guava31 = ClassFixtures.libraryJar(work, 8, "com.google", "Common", "public int v() { return 31; }",
                "com.google.guava", "guava", "31.0");
        Files.createDirectories(root.resolve("lib"));
        Files.write(root.resolve("lib/guava-30.0.jar"), guava30);
        Files.write(root.resolve("lib/guava-31.0.jar"), guava31);

        byte[] same = ClassFixtures.compileOne(work, 11, "shared/Same.java", "package shared; public class Same {}");
        ClassFixtures.writeJar(root.resolve("lib/copy-a-1.0.jar"), null, Map.of("shared/Same.class", same));
        ClassFixtures.writeJar(root.resolve("lib/copy-b-1.0.jar"), null, Map.of("shared/Same.class", same));
        ClassFixtures.writeJar(root.resolve("lib/copy-a-1.0-sources.jar"), null,
                Map.of("shared/Same.class", same));

        Map<String, byte[]> app = new LinkedHashMap<>();
        app.put("BOOT-INF/classes/logback.xml", ClassFixtures.text("<configuration/>"));
        app.put("BOOT-INF/lib/guava-31.0.jar", guava31);
        ClassFixtures.writeJar(root.resolve("app/app.jar"), null, app);

        Path classes = Files.createDirectories(root.resolve("module/target/classes/com/foo"));
        Files.write(classes.resolve("UserService.class"), ClassFixtures.compileOne(work, 17,
                "com/foo/UserService.java", "package com.foo; public class UserService {}"));
        Files.write(root.resolve("module/target/classes/logback.xml"), ClassFixtures.text("<configuration/>"));
    }

    @ParameterizedTest
    @CsvSource({
            "com.google.Common, FQN",
            "Common, SIMPLE_NAME",
            "com.google., PACKAGE",
            "com.foo.*Service, CLASS_WILDCARD",
            "*Service, CLASS_WILDCARD",
            "logback.xml, RESOURCE_NAME",
            "*.properties, RESOURCE_NAME",
            "**/logback.xml, RESOURCE_GLOB",
            "META-INF/spring.factories, RESOURCE_GLOB",
            "com/foo/UserService.class, FQN"})
    void detectsQueryKind(String text, ClassSearchService.QueryKind kind) {
        assertEquals(kind, ClassSearchService.Query.parse(text).kind());
    }

    @Test
    void globsRespectSeparators() {
        ClassSearchService.Query wildcard = ClassSearchService.Query.parse("com.foo.*Service");
        assertTrue(wildcard.matchesClass("com.foo.UserService"));
        assertFalse(wildcard.matchesClass("com.foo.sub.UserService"));
        assertTrue(ClassSearchService.Query.parse("com.**.UserService").matchesClass("com.a.b.UserService"));
        ClassSearchService.Query glob = ClassSearchService.Query.parse("**/logback.xml");
        assertTrue(glob.matchesResource("logback.xml"));
        assertTrue(glob.matchesResource("config/logback.xml"));
        assertFalse(glob.matchesResource("logback.xml.bak"));
        assertTrue(ClassSearchService.Query.parse("Inner").matchesClass("com.foo.UserService$Inner"));
        assertThrows(IllegalArgumentException.class, () -> ClassSearchService.Query.parse("  "));
    }

    @Test
    void findsClassesByNameAcrossJarsNestedLibsAndDirectories() {
        AtomicInteger progress = new AtomicInteger();
        ClassSearchService.SearchResult fqn = service.search(List.of(root), "com.google.Common",
                (done, total, current) -> progress.incrementAndGet(), null);
        assertEquals(3, fqn.hits().size(), fqn.hits().toString());
        assertTrue(fqn.hits().stream().anyMatch(hit -> "BOOT-INF/lib/guava-31.0.jar".equals(hit.location().nestedPath())
                && "31.0".equals(hit.location().version())));
        assertTrue(fqn.hits().stream().anyMatch(hit -> "30.0".equals(hit.location().version())));
        assertTrue(progress.get() > 1);
        assertEquals(5, fqn.archivesScanned(), "sources jars are skipped");

        ClassSearchService.SearchResult simple = service.search(List.of(root), "UserService", null, null);
        assertEquals(1, simple.hits().size());
        ClassSearchService.Hit hit = simple.hits().get(0);
        assertEquals(ClassSearchService.LocationKind.DIRECTORY, hit.location().kind());
        assertTrue(hit.location().file().endsWith(Path.of("module", "target", "classes")), hit.location().toString());
        assertEquals("com/foo/UserService.class", hit.entryPath());

        assertEquals(1, service.search(List.of(root), "com.foo.*Service", null, null).hits().size());
        assertEquals(1, service.search(List.of(root), "com.foo.", null, null).hits().size());
        assertEquals(3, service.search(List.of(root), "com.google.", null, null).hits().size());
    }

    @Test
    void findsResourcesInJarsAndDirectories() {
        ClassSearchService.SearchResult glob = service.search(List.of(root), "**/logback.xml", null, null);
        assertEquals(2, glob.hits().size(), glob.hits().toString());
        assertTrue(glob.hits().stream().anyMatch(hit -> hit.entryPath().equals("BOOT-INF/classes/logback.xml")));
        assertEquals(2, service.search(List.of(root), "logback.xml", null, null).hits().size());
        assertEquals(1, service.search(List.of(root.resolve("app/app.jar")), "logback.xml", null, null).hits().size());
    }

    @Test
    void distinguishesIdenticalCopiesFromRealConflicts() {
        ClassSearchService.ConflictReport report = service.findDuplicates(List.of(root), null, null);

        ClassSearchService.DuplicateClass common = report.duplicates().stream()
                .filter(d -> d.className().equals("com.google.Common")).findFirst().orElseThrow();
        assertEquals(3, common.occurrences().size());
        assertEquals(2, common.variants());
        assertTrue(common.contentDiffers());

        ClassSearchService.DuplicateClass same = report.duplicates().stream()
                .filter(d -> d.className().equals("shared.Same")).findFirst().orElseThrow();
        assertEquals(2, same.occurrences().size());
        assertFalse(same.contentDiffers());

        assertEquals(1, report.conflictCount());
        assertEquals(2, report.duplicates().size());
        assertEquals("com.google.Common", report.duplicates().get(0).className(), "conflicts sort first");

        // guava-31 顶层与 app.jar 里嵌套的同版本：共享 1 个类、内容相同
        ClassSearchService.JarPair identical = report.pairs().stream()
                .filter(p -> p.differingClasses() == 0 && p.first().displayName().contains("guava")
                        || p.differingClasses() == 0 && p.second().displayName().contains("guava"))
                .findFirst().orElseThrow();
        assertEquals(1, identical.sharedClasses());
        assertEquals(2, report.pairs().stream().filter(p -> p.differingClasses() == 1).count());
        assertEquals(4, report.pairs().size());
    }

    @Test
    void cancelsAndReportsUnreadableArchives() throws Exception {
        assertThrows(CancellationException.class, () -> service.search(List.of(root), "Common", null, () -> true));
        assertThrows(CancellationException.class, () -> service.findDuplicates(List.of(root), null, () -> true));

        Path brokenDir = Files.createDirectories(temp.resolve("broken"));
        Files.writeString(brokenDir.resolve("broken.jar"), "not a zip");
        ClassSearchService.SearchResult result = service.search(List.of(root, brokenDir), "Common", null, null);
        assertEquals(3, result.hits().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == ClassSearchService.ScanWarning.Code.NOT_A_ZIP
                && w.path().endsWith("broken.jar")), result.warnings().toString());
    }
}
