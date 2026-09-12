package com.aqishi.toolbox.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonFormatter 美化 / 压缩 round-trip 测试（语义等价，不依赖字符串逐字）。
 */
class JsonFormatterTest {

    private JsonNode parse(String json) throws Exception {
        return Json.mapper().readTree(json);
    }

    @Test
    void prettyPreservesStructure() throws Exception {
        String raw = "{\"name\":\"阿启视\",\"items\":[1,2,{\"x\":true}],\"nested\":{\"a\":\"b\\\"c\"}}";
        String pretty = JsonFormatter.pretty(raw);
        assertEquals(parse(raw), parse(pretty));
        // 美化后应有缩进换行
        assertTrue(pretty.contains("\n"));
    }

    @Test
    void compactRemovesInsignificantWhitespace() throws Exception {
        String raw = "{ \"a\" : 1 , \"b\" : [ 2 , 3 ] }";
        String compact = JsonFormatter.compact(raw);
        assertEquals(parse(raw), parse(compact));
        assertFalse(compact.contains("\n"));
        assertFalse(compact.contains(" "));
    }

    @Test
    void prettyThenCompactEqualsCompact() throws Exception {
        String raw = "{\"users\":[{\"id\":1,\"name\":\"张帅\"},{\"id\":2,\"name\":\"arges\"}]}";
        String pretty = JsonFormatter.pretty(raw);
        String round = JsonFormatter.compact(pretty);
        assertEquals(JsonFormatter.compact(raw), round);
        assertEquals(parse(raw), parse(round));
    }

    @Test
    void escapesInsideStringsArePreserved() throws Exception {
        String raw = "{\"path\":\"C:\\\\tmp\\\\a\\\"b.json\",\"quote\":\"他说:\\\"hi\\\"\"}";
        assertEquals(parse(raw), parse(JsonFormatter.pretty(raw)));
        assertEquals(parse(raw), parse(JsonFormatter.compact(raw)));
    }

    @Test
    void unicodeIsPreservedWithoutEscapingAway() throws Exception {
        String raw = "{\"品牌\":\"阿启视\",\"emoji\":\"具身智能\"}";
        JsonNode node = parse(JsonFormatter.pretty(raw));
        assertEquals("阿启视", node.get("品牌").asText());
    }

    @Test
    void nullInputYieldsEmptyString() {
        assertEquals("", JsonFormatter.pretty(null));
        assertEquals("", JsonFormatter.compact(null));
    }

    @Test
    void emptyObjectRoundTrips() throws Exception {
        String raw = "{}";
        assertEquals(parse(raw), parse(JsonFormatter.pretty(raw)));
        assertEquals(parse(raw), parse(JsonFormatter.compact(raw)));
    }
}
