package com.aqishi.toolbox.feature.system.application;

import com.aqishi.toolbox.feature.system.domain.ClassConflict;
import com.aqishi.toolbox.feature.system.domain.ClassFixtures;
import com.aqishi.toolbox.feature.system.domain.ClasspathDiagnosticReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathDiagnosticServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("diagnose should detect NoSuchMethodError breaking risk when older jar shadows newer jar")
    void testDiagnoseBreakingConflict() throws IOException {
        // 版本 1：只有 oldMethod
        Map<String, byte[]> v1Classes = ClassFixtures.compile(tempDir, 11, Map.of(
                "com/example/Worker.java",
                "package com.example;\n" +
                        "public class Worker {\n" +
                        "    public void oldMethod() {}\n" +
                        "}\n"
        ));
        Path jar1 = ClassFixtures.writeJar(tempDir.resolve("worker-1.0.jar"), null, v1Classes);

        // 版本 2：新增 newMethod 和 public int count 字段
        Map<String, byte[]> v2Classes = ClassFixtures.compile(tempDir, 11, Map.of(
                "com/example/Worker.java",
                "package com.example;\n" +
                        "public class Worker {\n" +
                        "    public int count = 10;\n" +
                        "    public void oldMethod() {}\n" +
                        "    public void newMethod(String msg) {}\n" +
                        "}\n"
        ));
        Path jar2 = ClassFixtures.writeJar(tempDir.resolve("worker-2.0.jar"), null, v2Classes);

        ClasspathDiagnosticService service = new ClasspathDiagnosticService();
        AtomicInteger progressCount = new AtomicInteger();

        // 场景 A: jar1 (老版本) 排在前面遮蔽了 jar2 (新版本) -> 必定破坏性冲突 (BREAKING)
        ClasspathDiagnosticReport reportA = service.diagnose(
                List.of(jar1, jar2),
                (done, total, cur) -> progressCount.incrementAndGet(),
                () -> false
        );

        assertEquals(2, reportA.sources().size());
        assertEquals(1, reportA.conflicts().size());
        ClassConflict conflictA = reportA.conflicts().get(0);
        assertEquals("com.example.Worker", conflictA.className());
        assertEquals(ClassConflict.RiskLevel.BREAKING, conflictA.worstRiskLevel());
        assertEquals(1, conflictA.maskedOccurrences().size());

        ClassConflict.MaskedOccurrence maskedA = conflictA.maskedOccurrences().get(0);
        assertEquals(1, maskedA.missingMethods().size());
        assertEquals("newMethod", maskedA.missingMethods().get(0).name());
        assertEquals(1, maskedA.missingFields().size());
        assertEquals("count", maskedA.missingFields().get(0).name());

        // 场景 B: jar2 (新版本) 排在前面胜出 -> 拥有全部方法，不会有缺失方法 (SAFE)
        ClasspathDiagnosticReport reportB = service.diagnose(
                List.of(jar2, jar1),
                null,
                () -> false
        );
        assertEquals(1, reportB.conflicts().size());
        ClassConflict conflictB = reportB.conflicts().get(0);
        assertEquals(ClassConflict.RiskLevel.SAFE, conflictB.worstRiskLevel());
        ClassConflict.MaskedOccurrence maskedB = conflictB.maskedOccurrences().get(0);
        assertEquals(0, maskedB.missingMethods().size());
        assertEquals(0, maskedB.missingFields().size());
        assertEquals(1, maskedB.winnerExclusiveMethods().size());
    }

    @Test
    @DisplayName("diagnose should respect cancellation flag")
    void testDiagnoseCancellation() throws IOException {
        Map<String, byte[]> classes = ClassFixtures.compile(tempDir, 11, Map.of(
                "com/example/Empty.java", "package com.example; public class Empty {}"
        ));
        Path jar = ClassFixtures.writeJar(tempDir.resolve("test.jar"), null, classes);

        ClasspathDiagnosticService service = new ClasspathDiagnosticService();
        ClasspathDiagnosticReport report = service.diagnose(List.of(jar), null, () -> true);

        assertNotNull(report);
        assertTrue(report.conflicts().isEmpty());
    }
}
