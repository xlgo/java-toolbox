package com.aqishi.toolbox.feature.system.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MavenDependencyTreeTest {
    @Test
    void standardTreePreservesPathsAndOmittedVersions() throws Exception {
        String text =
                "[INFO] g:app:jar:1\n"
                    + "[INFO] +- g:a:jar:2:compile\n"
                    + "[INFO] |  \\- x:lib:jar:3:compile\n"
                    + "[INFO] \\- g:b:jar:1:compile\n"
                    + "[INFO]    \\- (x:lib:jar:2:compile - omitted for conflict with 3)";
        var service = new MavenDependencyTree();
        var values = service.parse(text);
        assertEquals(5, values.size());
        assertEquals(3, values.get(4).path().size());
        assertEquals("2", values.get(4).version());
        assertTrue(values.get(4).note().contains("omitted"));
        String report = service.report(values, "x:lib");
        assertTrue(report.contains("g:a:jar:2"));
        assertTrue(report.contains("g:b:jar:1"));
        assertTrue(report.contains("<artifactId>lib</artifactId>"));
    }

    @Test
    void classifiersScopesAndMultipleRoots() throws Exception {
        var entries =
                new MavenDependencyTree()
                        .parse(
                                "g:app:jar:1\n"
                                    + "\\- g:lib:test-jar:tests:2:test\n"
                                    + "g:other:pom:1\n"
                                    + "\\- g:lib:jar:3:runtime");
        assertEquals(4, entries.size());
        assertEquals("tests", entries.get(1).classifier());
        assertEquals("test", entries.get(1).scope());
        assertEquals("g:other:pom:1", entries.get(3).path().get(0));
    }

    @Test
    void jsonTreeSupportsNestedChildren() throws Exception {
        var values =
                new MavenDependencyTree()
                        .parse(
                                "{\"groupId\":\"g\",\"artifactId\":\"app\",\"version\":\"1\",\"children\":[{\"groupId\":\"g\",\"artifactId\":\"lib\",\"version\":\"2\",\"scope\":\"compile\",\"children\":[]}]}");
        assertEquals(2, values.size());
        assertEquals(2, values.get(1).path().size());
    }

    @Test
    void invalidDepthOrNonTreeInputFails() {
        assertThrows(Exception.class, () -> new MavenDependencyTree().parse("noise only"));
        assertThrows(
                Exception.class,
                () -> new MavenDependencyTree().parse("g:app:jar:1\n      \\- g:a:jar:2:compile"));
    }
}
