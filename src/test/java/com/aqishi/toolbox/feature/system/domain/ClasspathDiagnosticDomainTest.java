package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathDiagnosticDomainTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ClasspathParser should extract and resolve paths from various input formats")
    void testClasspathParser() throws IOException {
        Path jar1 = Files.createFile(tempDir.resolve("alpha.jar"));
        Path jar2 = Files.createFile(tempDir.resolve("beta.jar"));
        Path subDir = Files.createDirectories(tempDir.resolve("lib"));
        Path jar3 = Files.createFile(subDir.resolve("charlie.jar"));

        // 1. 模拟命令行输入 java -cp "..."
        String cmd = "java -cp \"" + jar1 + ";" + jar2 + "\" com.example.Main";
        List<Path> parsed = ClasspathParser.parse(cmd);
        assertEquals(2, parsed.size());
        assertEquals(jar1, parsed.get(0));
        assertEquals(jar2, parsed.get(1));

        // 2. 通配符展开 lib/*
        String wildcardInput = subDir.toString() + "/*";
        List<Path> wildcardParsed = ClasspathParser.parse(wildcardInput);
        assertEquals(1, wildcardParsed.size());
        assertEquals(jar3, wildcardParsed.get(0));

        // 3. 多行与空行注释
        String multiline = "# Comments\n" + jar1 + "\n\n" + jar2;
        List<Path> multilineParsed = ClasspathParser.parse(multiline);
        assertEquals(2, multilineParsed.size());
    }

    @Test
    @DisplayName("ClassConflict comparison should detect missing methods and incompatible modifiers")
    void testClassConflictComparison() {
        ClasspathSource src1 = new ClasspathSource(1, ClasspathSource.Kind.JAR, tempDir.resolve("w.jar"), null, null, "1.0");
        ClasspathSource src2 = new ClasspathSource(2, ClasspathSource.Kind.JAR, tempDir.resolve("m.jar"), null, null, "2.0");

        ClassAbiSignature.MethodSignature m1 = new ClassAbiSignature.MethodSignature(
                "execute", "()V", ClassFileInfo.ACC_PUBLIC, "void execute()", false, false, false);
        ClassAbiSignature.MethodSignature m2New = new ClassAbiSignature.MethodSignature(
                "query", "(Ljava/lang/String;)Ljava/lang/String;", ClassFileInfo.ACC_PUBLIC,
                "String query(String)", false, false, false);
        ClassAbiSignature.MethodSignature mStaticChange = new ClassAbiSignature.MethodSignature(
                "helper", "()V", ClassFileInfo.ACC_PUBLIC | ClassFileInfo.ACC_STATIC,
                "static void helper()", true, false, false);
        ClassAbiSignature.MethodSignature mInstanceChange = new ClassAbiSignature.MethodSignature(
                "helper", "()V", ClassFileInfo.ACC_PUBLIC,
                "void helper()", false, false, false);

        // Winner 仅有 execute 和 static helper
        ClassAbiSignature winnerAbi = new ClassAbiSignature(
                "com.example.Service", 61, ClassFileInfo.ACC_PUBLIC, "java.lang.Object",
                List.of(), Map.of(m1.key(), m1, mStaticChange.key(), mStaticChange),
                Map.of(), "sha-winner", 1000, true, null);

        // Masked 拥有 execute, new query 方法，且 helper 为非 static
        ClassAbiSignature maskedAbi = new ClassAbiSignature(
                "com.example.Service", 61, ClassFileInfo.ACC_PUBLIC, "java.lang.Object",
                List.of(), Map.of(m1.key(), m1, m2New.key(), m2New, mInstanceChange.key(), mInstanceChange),
                Map.of(), "sha-masked", 1200, true, null);

        ClassConflict.MaskedOccurrence occurrence = ClassConflict.compare(src2, maskedAbi, src1, winnerAbi);

        assertEquals(ClassConflict.RiskLevel.BREAKING, occurrence.riskLevel());
        assertEquals(1, occurrence.missingMethods().size());
        assertEquals("query", occurrence.missingMethods().get(0).name());
        assertEquals(1, occurrence.incompatibleMethods().size());
        assertEquals("INSTANCE_TO_STATIC", occurrence.incompatibleMethods().get(0).reason());
        assertFalse(occurrence.identicalBytes());
    }

    @Test
    @DisplayName("KnownConflictDetector should catch slf4j and servlet conflicts")
    void testKnownConflictDetector() {
        KnownConflictDetector detector = new KnownConflictDetector();
        ClasspathSource s1 = new ClasspathSource(1, ClasspathSource.Kind.JAR, tempDir.resolve("slf4j-log4j12-1.7.30.jar"), null, null, "1.7.30");
        ClasspathSource s2 = new ClasspathSource(2, ClasspathSource.Kind.JAR, tempDir.resolve("logback-classic-1.2.3.jar"), null, null, "1.2.3");
        ClasspathSource s3 = new ClasspathSource(3, ClasspathSource.Kind.JAR, tempDir.resolve("guava-30.0-jre.jar"), null, null, "30.0");

        List<KnownConflictDetector.Match> matches = detector.detect(List.of(s1, s2, s3));
        assertEquals(1, matches.size());
        assertEquals("slf4j-binding", matches.get(0).rule().id());
    }

    @Test
    @DisplayName("ClasspathDiagnosticReport should produce structured markdown")
    void testReportMarkdown() {
        ClasspathSource s1 = new ClasspathSource(1, ClasspathSource.Kind.JAR, tempDir.resolve("a.jar"), null, null, "1.0");
        ClasspathSource s2 = new ClasspathSource(2, ClasspathSource.Kind.JAR, tempDir.resolve("b.jar"), null, null, "2.0");

        ClassAbiSignature.MethodSignature missing = new ClassAbiSignature.MethodSignature(
                "doWork", "()V", ClassFileInfo.ACC_PUBLIC, "void doWork()", false, false, false);
        ClassAbiSignature wAbi = new ClassAbiSignature("pkg.Foo", 52, 1, "java.lang.Object",
                List.of(), Map.of(), Map.of(), "h1", 100, true, null);
        ClassAbiSignature mAbi = new ClassAbiSignature("pkg.Foo", 52, 1, "java.lang.Object",
                List.of(), Map.of(missing.key(), missing), Map.of(), "h2", 100, true, null);

        ClassConflict.MaskedOccurrence occ = new ClassConflict.MaskedOccurrence(
                s2, mAbi, ClassConflict.RiskLevel.BREAKING, List.of(missing),
                List.of(), List.of(), List.of(), List.of(), false, false);
        ClassConflict conflict = new ClassConflict("pkg.Foo", s1, wAbi, List.of(occ));

        ClasspathDiagnosticReport report = new ClasspathDiagnosticReport(
                List.of(s1, s2), List.of(conflict), List.of(), 2, 50);

        assertEquals(1, report.breakingCount());
        String md = report.toMarkdown();
        assertTrue(md.contains("# Classpath Diagnostic Report"));
        assertTrue(md.contains("pkg.Foo"));
        assertTrue(md.contains("doWork()"));
    }
}
