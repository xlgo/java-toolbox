package com.aqishi.toolbox.feature.codec.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class StructuredDiffTest {
    private final StructuredDiff service = new StructuredDiff();

    @Test
    void ignoresKeyOrderAndNumericRepresentation() throws Exception {
        assertTrue(
                service.compare("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1.0}", false, Set.of(), "")
                        .isEmpty());
    }

    @Test
    void matchesIdsAcrossOrderAndIgnoresPaths() throws Exception {
        var result =
                service.compare(
                        "{\"time\":1,\"a\":[{\"id\":1,\"v\":\"x\"},{\"id\":2,\"v\":\"y\"}]}",
                        "{\"time\":2,\"a\":[{\"id\":2,\"v\":\"z\"},{\"id\":1,\"v\":\"x\"}]}",
                        false,
                        Set.of("$.time"),
                        "id");
        assertEquals(1, result.size());
        assertEquals("$.a[id=2].v", result.get(0).path());
        assertEquals("\"y\"", result.get(0).before());
    }

    @Test
    void distinguishesMissingFromNull() throws Exception {
        var changes = service.compare("{\"a\":null}", "{\"b\":null}", false, Set.of(), "");
        assertEquals(
                List.of("removed", "added"),
                changes.stream().map(StructuredDiff.Change::kind).toList());
    }

    @Test
    void yamlAndEscapedKeys() throws Exception {
        var result =
                service.compare(
                        "'a.b': 1\nname: Alice", "'a.b': 2\nname: Alice", true, Set.of(), "");
        assertEquals("$['a.b']", result.get(0).path());
    }

    @Test
    void rejectsDuplicateOrMissingArrayIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.compare("[{\"id\":1},{\"id\":1}]", "[]", false, Set.of(), "id"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.compare("[{}]", "[]", false, Set.of(), "id"));
    }

    @Test
    void rejectsAmbiguousDuplicateObjectKeysAndTrailingDocuments() {
        assertThrows(
                Exception.class,
                () -> service.compare("{\"a\":1,\"a\":2}", "{}", false, Set.of(), ""));
        assertThrows(
                Exception.class,
                () -> service.compare("a: 1\n---\na: 2", "a: 1", true, Set.of(), ""));
    }
}
