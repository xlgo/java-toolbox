package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarInspectorTest {

    @TempDir
    Path temp;

    private final JarInspector inspector = new JarInspector();

    @Test
    void inspectsPlainLibraryJar() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/", null);
        entries.put("demo/Util.class", ClassFixtures.compileOne(temp, 8, "demo/Util.java",
                "package demo; public class Util {}"));
        entries.put("demo/sub/Helper.class", ClassFixtures.compileOne(temp, 8, "demo/sub/Helper.java",
                "package demo.sub; public class Helper {}"));
        entries.put("META-INF/versions/11/demo/Util.class", ClassFixtures.compileOne(temp, 11, "demo/Util.java",
                "package demo; public class Util { int x() { var v = 1; return v; } }"));
        entries.put("META-INF/maven/com.example/demo-lib/pom.properties",
                ClassFixtures.pomProperties("com.example", "demo-lib", "1.2.3"));
        entries.put("META-INF/services/demo.Spi", ClassFixtures.text("# providers\ndemo.Util\n\ndemo.sub.Helper # second\n"));
        entries.put("META-INF/APP.SF", ClassFixtures.text("Signature-Version: 1.0\n"));
        entries.put("META-INF/APP.RSA", new byte[]{1, 2, 3});
        String manifest = "Manifest-Version: 1.0\r\nMain-Class: demo.Util\r\nAutomatic-Module-Name: com.example.demo\r\n"
                + "Multi-Release: true\r\nCreated-By: Maven JAR Plugin 3.3.0\r\nBuild-Jdk-Spec: 17\r\n"
                + "Implementation-Title: demo-lib\r\nImplementation-Version: 1.2.3\r\nImplementation-Vendor: Example\r\n"
                + "Bundle-SymbolicName: com.example.demo\r\nBundle-Version: 1.2.3\r\n"
                + "Class-Path: a.jar b.jar\r\n  c.jar\r\n\r\nName: demo/Util.class\r\nX-Ignored: yes\r\n\r\n";
        Path jar = ClassFixtures.writeJar(temp.resolve("demo-lib-1.2.3.jar"), manifest, entries);

        JarReport report = inspector.inspect(jar, null);

        assertEquals(JarReport.Layout.PLAIN, report.layout());
        assertEquals("demo.Util", report.manifestValue("Main-Class"));
        assertEquals("com.example.demo", report.manifestValue("Automatic-Module-Name"));
        assertEquals("17", report.manifestValue("Build-Jdk-Spec"));
        assertEquals("a.jar b.jar c.jar", report.manifestValue("Class-Path"));
        assertNull(report.manifestValue("X-Ignored"), "only the main section is reported");
        assertEquals(List.of("Manifest-Version", "Main-Class"), List.copyOf(report.manifest().keySet()).subList(0, 2));
        assertEquals("com.example:demo-lib:1.2.3", report.coordinates().get(0).gav());
        assertEquals(List.of("demo.Util", "demo.sub.Helper"), report.services().get(0).providers());
        assertTrue(report.signed());
        assertEquals(List.of("META-INF/APP.SF", "META-INF/APP.RSA"), report.signatureFiles());
        assertTrue(report.multiRelease());
        // 基线只有 Java 8；多版本目录单独统计
        assertEquals(Map.of(52, 2), report.majorHistogram());
        assertEquals(8, report.requiredRelease());
        assertEquals(55, report.versionedClasses().get(11).maxMajor());
        assertEquals(1, report.versionedClasses().get(11).classCount());
        assertEquals(List.of(new JarReport.PackageStats("demo", 1), new JarReport.PackageStats("demo.sub", 1)),
                report.packages());
        assertEquals(entries.size() + 1, report.entryCount());
        assertTrue(report.entries().stream().anyMatch(e -> e.name().equals("demo/") && e.directory()));
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());

        ClassFileInfo util = inspector.readClass(jar, "demo/Util.class");
        assertEquals("demo.Util", util.className());
        assertEquals(JarInspector.InspectionException.Code.ENTRY_NOT_FOUND,
                assertThrows(JarInspector.InspectionException.class,
                        () -> inspector.readClass(jar, "missing.class")).code());
    }

    @Test
    void readsModuleInfoFromJar() throws Exception {
        Map<String, byte[]> classes = ClassFixtures.compile(temp, 11, Map.of(
                "module-info.java", "module demo.mod { exports demo.mod; }",
                "demo/mod/A.java", "package demo.mod; public class A {}"));
        Path jar = ClassFixtures.writeJar(temp.resolve("mod.jar"), null, classes);

        JarReport report = inspector.inspect(jar, null);

        assertEquals("demo.mod", report.module().name());
        assertEquals("module-info.class", report.moduleEntry());
        assertEquals("demo.mod", report.module().exports().get(0).packageName());
        assertEquals(1, report.classCount(), "module-info is not part of the baseline");
    }

    @Test
    void inspectsSpringBootFatJarWithNestedLibraries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("org/springframework/boot/loader/launch/JarLauncher.class",
                ClassFixtures.compileOne(temp, 8, "l/JarLauncher.java", "package l; public class JarLauncher {}"));
        entries.put("BOOT-INF/classes/app/Main.class",
                ClassFixtures.compileOne(temp, 11, "app/Main.java", "package app; public class Main {}"));
        entries.put("BOOT-INF/classes/application.yml", ClassFixtures.text("server.port: 8080\n"));
        entries.put("BOOT-INF/lib/modern-lib-1.0.jar", ClassFixtures.libraryJar(temp, 17, "modern", "Api", "",
                "com.example", "modern-lib", "1.0"));
        entries.put("BOOT-INF/lib/legacy-lib-2.0.jar", ClassFixtures.libraryJar(temp, 8, "legacy", "Old", "",
                "com.example", "legacy-lib", "2.0"));
        entries.put("BOOT-INF/classpath.idx", ClassFixtures.text(
                "- \"BOOT-INF/lib/modern-lib-1.0.jar\"\n- \"BOOT-INF/lib/legacy-lib-2.0.jar\"\n"));
        entries.put("BOOT-INF/layers.idx", ClassFixtures.text(
                "- \"dependencies\":\n  - \"BOOT-INF/lib/\"\n- \"application\":\n  - \"BOOT-INF/classes/\"\n"));
        String manifest = "Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\r\n"
                + "Start-Class: app.Main\r\nSpring-Boot-Version: 3.2.0\r\n\r\n";
        Path jar = ClassFixtures.writeJar(temp.resolve("app.jar"), manifest, entries);

        JarReport report = inspector.inspect(jar, null);

        assertEquals(JarReport.Layout.SPRING_BOOT_JAR, report.layout());
        assertEquals("app.Main", report.springBoot().startClass());
        assertEquals("3.2.0", report.springBoot().version());
        assertEquals(1, report.springBoot().loaderClassCount());
        assertEquals(2, report.springBoot().classpathIndex().size());
        assertEquals(List.of("dependencies", "application"), report.springBoot().layers());
        assertEquals(55, report.maxMajor(), "own code: BOOT-INF/classes compiled for 11");
        assertEquals(61, report.maxMajorIncludingLibraries(), "a nested library needs 17");
        assertTrue(report.packages().stream().anyMatch(p -> p.name().equals("app")));

        assertEquals(2, report.libraries().size());
        JarReport.NestedLibrary modern = report.libraries().get(0);
        assertEquals("BOOT-INF/lib/modern-lib-1.0.jar", modern.path());
        assertEquals("com.example:modern-lib:1.0", modern.bestCoordinates().gav());
        assertEquals(17, modern.report().requiredRelease());
        assertTrue(modern.report().entries().isEmpty(), "nested reports skip the entry list");
        assertEquals(8, report.libraries().get(1).report().requiredRelease());
    }

    @Test
    void inspectsWarAndEarLayoutsWithDepthLimit() throws Exception {
        Map<String, byte[]> war = new LinkedHashMap<>();
        war.put("WEB-INF/classes/web/Home.class",
                ClassFixtures.compileOne(temp, 11, "web/Home.java", "package web; public class Home {}"));
        war.put("WEB-INF/lib/legacy-lib-2.0.jar", ClassFixtures.libraryJar(temp, 8, "legacy", "Old", "",
                "com.example", "legacy-lib", "2.0"));
        war.put("WEB-INF/web.xml", ClassFixtures.text("<web-app/>"));
        byte[] warBytes = ClassFixtures.jarBytes(null, war);
        Path warFile = temp.resolve("web.war");
        Files.write(warFile, warBytes);

        JarReport warReport = inspector.inspect(warFile, null);
        assertEquals(JarReport.Layout.WAR, warReport.layout());
        assertEquals(1, warReport.libraries().size());
        assertEquals("com.example:legacy-lib:2.0", warReport.libraries().get(0).bestCoordinates().gav());

        Path ear = ClassFixtures.writeJar(temp.resolve("app.ear"), null, Map.of("web.war", warBytes,
                "META-INF/application.xml", ClassFixtures.text("<application/>")));
        JarReport earReport = inspector.inspect(ear, null);
        assertEquals(JarReport.Layout.EAR, earReport.layout());
        JarReport nestedWar = earReport.libraries().get(0).report();
        assertEquals(JarReport.Layout.WAR, nestedWar.layout());
        assertEquals(8, nestedWar.libraries().get(0).report().requiredRelease(), "EAR -> WAR -> lib is depth 2");

        JarReport shallow = new JarInspector(JarInspector.Limits.defaults().withMaxDepth(1)).inspect(ear, null);
        JarReport.NestedLibrary tooDeep = shallow.libraries().get(0).report().libraries().get(0);
        assertNull(tooDeep.report());
        assertEquals(JarReport.Warning.Code.NESTED_TOO_DEEP,
                shallow.libraries().get(0).report().warnings().get(0).code());
    }

    @Test
    void guardsAgainstZipBombs() throws Exception {
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            many.put("r" + i + ".txt", new byte[]{1});
        }
        Path manyFile = ClassFixtures.writeJar(temp.resolve("many.jar"), null, many);
        JarInspector strict = new JarInspector(JarInspector.Limits.defaults().withMaxEntries(10));
        assertEquals(JarInspector.InspectionException.Code.TOO_MANY_ENTRIES,
                assertThrows(JarInspector.InspectionException.class, () -> strict.inspect(manyFile, null)).code());

        // 8 MB 的 0 压缩成几 KB：压缩比远超 200
        byte[] zeros = new byte[8 * 1024 * 1024];
        byte[] nestedBomb = ClassFixtures.jarBytes(null, Map.of("META-INF/services/x.Bomb", zeros));
        byte[] random = new byte[2 * 1024 * 1024];
        new java.util.Random(7).nextBytes(random);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", zeros);
        entries.put("META-INF/services/demo.Bomb", zeros);
        entries.put("META-INF/maven/g/a/pom.properties", random);
        entries.put("BOOT-INF/lib/bomb-1.0.jar", nestedBomb);
        Path bomb = ClassFixtures.writeJar(temp.resolve("bomb.jar"), null, entries);

        JarReport report = inspector.inspect(bomb, null);
        List<JarReport.Warning.Code> codes = report.warnings().stream().map(JarReport.Warning::code).toList();
        assertTrue(codes.contains(JarReport.Warning.Code.COMPRESSION_RATIO), codes.toString());
        assertTrue(codes.contains(JarReport.Warning.Code.ENTRY_TOO_LARGE), codes.toString());
        assertTrue(report.services().isEmpty());
        assertTrue(report.manifest().isEmpty());
        assertNull(report.libraries().get(0).report(), "nested bomb is abandoned");

        Path notZip = Files.writeString(temp.resolve("fake.jar"), "not a zip at all");
        assertEquals(JarInspector.InspectionException.Code.NOT_A_ZIP,
                assertThrows(JarInspector.InspectionException.class, () -> inspector.inspect(notZip, null)).code());
    }

    @Test
    void honoursCancellationAndCorruptClasses() throws Exception {
        Path jar = ClassFixtures.writeJar(temp.resolve("c.jar"), null, Map.of(
                "bad/Broken.class", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9},
                "ok.txt", new byte[]{1}));
        assertThrows(CancellationException.class, () -> inspector.inspect(jar, () -> true));

        JarReport report = inspector.inspect(jar, () -> false);
        assertEquals(JarReport.Warning.Code.CORRUPT_CLASS, report.warnings().get(0).code());
        assertEquals(0, report.classCount());
        assertFalse(report.signed());
        assertNotNull(report.entries());
    }
}
