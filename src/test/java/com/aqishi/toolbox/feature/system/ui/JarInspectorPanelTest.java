package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.application.ClassSearchService;
import com.aqishi.toolbox.feature.system.domain.ClassFileInfo;
import com.aqishi.toolbox.feature.system.domain.ClassFileParser;
import com.aqishi.toolbox.feature.system.domain.ClassFixtures;
import com.aqishi.toolbox.feature.system.domain.JarInspector;
import com.aqishi.toolbox.feature.system.domain.JarReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarInspectorPanelTest {

    @TempDir
    Path temp;

    @Test
    void buildsViewWithCatalogIdentity() throws Exception {
        JarInspectorPanel panel = new JarInspectorPanel();

        assertEquals("system", panel.getGroup());
        assertEquals("jar.inspector", panel.getName());
        SwingUtilities.invokeAndWait(() -> {
            assertNotNull(panel.getView());
            assertEquals(3, panel.tabs().getTabCount());
        });
        panel.closeResources();
        panel.closeResources();
    }

    @Test
    void showsClassJarAndSearchResults() throws Exception {
        byte[] classBytes = ClassFixtures.compileOne(temp, 17, "demo/Svc.java",
                "package demo; public class Svc { private int count; public String call(int a, String b) { return b; } }");
        ClassFileInfo info = new ClassFileParser().parse(classBytes);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("BOOT-INF/classes/demo/Svc.class", classBytes);
        entries.put("BOOT-INF/lib/legacy-lib-2.0.jar", ClassFixtures.libraryJar(temp, 8, "legacy", "Old", "",
                "com.example", "legacy-lib", "2.0"));
        Path jar = ClassFixtures.writeJar(temp.resolve("lib/app.jar"), "Manifest-Version: 1.0\r\n"
                + "Start-Class: demo.Svc\r\n\r\n", entries);
        JarReport report = new JarInspector().inspect(jar, null);
        ClassSearchService service = new ClassSearchService();
        ClassSearchService.SearchResult hits = service.search(List.of(temp.resolve("lib")), "Svc", null, null);
        ClassSearchService.ConflictReport conflicts = service.findDuplicates(List.of(temp.resolve("lib")), null, null);

        JarInspectorPanel panel = new JarInspectorPanel();
        SwingUtilities.invokeAndWait(() -> {
            panel.getView();
            ClassInfoView view = panel.classTab().view();
            view.show(info);
            assertEquals(1, view.fieldRows());
            assertEquals(2, view.methodRows(), "constructor and call");
            assertTrue(view.summaryText().contains("demo.Svc"));
            assertTrue(view.summaryText().contains("Java 17"));

            JarTab jarTab = panel.jarTab();
            jarTab.applyReport(report, jar);
            assertEquals(report.entryCount(), jarTab.entryTable().getRowCount());
            assertTrue(jarTab.tabs().getTitleAt(JarTab.SUB_LIBRARIES).endsWith("(1)"));
            assertTrue(JarText.report(report).contains("legacy-lib"));
            jarTab.applyReport(null, null);
            assertEquals(0, jarTab.entryTable().getRowCount());

            ClassSearchTab searchTab = panel.searchTab();
            searchTab.queryField().setText("**/logback.xml");
            assertFalse(searchTab.hintLabel().getText().isEmpty());
            searchTab.applySearch(hits);
            assertEquals(1, searchTab.hitModel().getRowCount());
            searchTab.applyConflicts(conflicts);
            assertEquals(0, searchTab.duplicateModel().getRowCount());
            assertTrue(SearchText.searchReport(hits).contains("demo.Svc"));
            assertNotNull(SearchText.conflictReport(conflicts));
        });
        panel.closeResources();
    }

    @Test
    void explainsVersionAndLocalizesErrors() throws Exception {
        byte[] classBytes = ClassFixtures.compileOne(temp, 17, "demo/V.java", "package demo; public class V {}");
        ClassFileInfo info = new ClassFileParser().parse(classBytes);
        assertNotNull(ClassText.explain(info));
        assertEquals("record", ClassText.kindKeyword(ClassFileInfo.Kind.RECORD));
        assertNotNull(ClassText.describeError(new com.aqishi.toolbox.feature.system.domain.ClassFileException(
                com.aqishi.toolbox.feature.system.domain.ClassFileException.Code.TRUNCATED, 12, "x")));
    }
}
