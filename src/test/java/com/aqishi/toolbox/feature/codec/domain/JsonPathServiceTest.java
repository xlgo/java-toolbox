package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathServiceTest {

    private JsonPathService service;

    @BeforeEach
    void setUp() {
        service = new JsonPathService();
    }

    @Test
    void testExtractAuthors() {
        JsonPathService.EvaluationResult result = service.evaluate(
                JsonPathService.SAMPLE_JSON,
                "$.store.book[*].author",
                false,
                true
        );

        assertTrue(result.isSuccess());
        assertEquals(4, result.getMatchCount());
        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().contains("Nigel Rees"));
        assertTrue(result.getOutput().contains("Evelyn Waugh"));
    }

    @Test
    void testPredicateFilter() {
        JsonPathService.EvaluationResult result = service.evaluate(
                JsonPathService.SAMPLE_JSON,
                "$.store.book[?(@.price < 10)]",
                false,
                true
        );

        assertTrue(result.isSuccess());
        assertEquals(2, result.getMatchCount());
        assertTrue(result.getOutput().contains("Sayings of the Century"));
        assertTrue(result.getOutput().contains("Moby Dick"));
        assertFalse(result.getOutput().contains("The Lord of the Rings"));
    }

    @Test
    void testSlice() {
        JsonPathService.EvaluationResult result = service.evaluate(
                JsonPathService.SAMPLE_JSON,
                "$.store.book[0:2]",
                false,
                false
        );

        assertTrue(result.isSuccess());
        assertEquals(2, result.getMatchCount());
    }

    @Test
    void testAsPathList() {
        JsonPathService.EvaluationResult result = service.evaluate(
                JsonPathService.SAMPLE_JSON,
                "$.store.book[?(@.price < 10)]",
                true,
                false
        );

        assertTrue(result.isSuccess());
        assertEquals(2, result.getMatchCount());
        assertTrue(result.getOutput().contains("$['store']['book'][0]"));
        assertTrue(result.getOutput().contains("$['store']['book'][2]"));
    }

    @Test
    void testInvalidJson() {
        JsonPathService.EvaluationResult result = service.evaluate(
                "{invalid_json}",
                "$.store",
                false,
                true
        );

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("JSON 格式错误"));
    }

    @Test
    void testInvalidExpression() {
        JsonPathService.EvaluationResult result = service.evaluate(
                JsonPathService.SAMPLE_JSON,
                "$.[invalid(...",
                false,
                true
        );

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("JSONPath 语法错误"));
    }

    @Test
    void testPathNotFound() {
        JsonPathService.EvaluationResult result = service.evaluate(
                JsonPathService.SAMPLE_JSON,
                "$.store.not_exist_key",
                false,
                true
        );

        assertTrue(result.isSuccess());
        assertEquals(0, result.getMatchCount());
        assertEquals("null", result.getOutput());
    }

    @Test
    void testEmptyInputs() {
        JsonPathService.EvaluationResult r1 = service.evaluate("", "$.a", false, true);
        assertFalse(r1.isSuccess());

        JsonPathService.EvaluationResult r2 = service.evaluate("{}", "", false, true);
        assertFalse(r2.isSuccess());
    }
}
