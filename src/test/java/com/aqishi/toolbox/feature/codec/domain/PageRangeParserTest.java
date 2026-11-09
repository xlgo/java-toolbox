package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageRangeParserTest {

    @Test
    void parseEmptyOrAll() {
        assertEquals(List.of(1, 2, 3), PageRangeParser.parse("", 3));
        assertEquals(List.of(1, 2, 3), PageRangeParser.parse(null, 3));
        assertEquals(List.of(1, 2, 3), PageRangeParser.parse("全部", 3));
        assertEquals(List.of(1, 2, 3), PageRangeParser.parse("all", 3));
        assertTrue(PageRangeParser.parse("all", 0).isEmpty());
    }

    @Test
    void parseSinglePages() {
        assertEquals(List.of(2), PageRangeParser.parse("2", 5));
        assertEquals(List.of(1, 3, 5), PageRangeParser.parse("1, 3, 5", 5));
        assertEquals(List.of(1, 3, 5), PageRangeParser.parse("1， 3， 5", 5)); // 中文逗号
    }

    @Test
    void parseRanges() {
        assertEquals(List.of(1, 2, 3), PageRangeParser.parse("1-3", 5));
        assertEquals(List.of(1, 2, 4, 5), PageRangeParser.parse("1-2, 4-5", 5));
        assertEquals(List.of(3, 4, 5), PageRangeParser.parse("3-", 5));
        assertEquals(List.of(1, 2), PageRangeParser.parse("-2", 5));
    }

    @Test
    void parseReverseRange() {
        assertEquals(List.of(5, 4, 3), PageRangeParser.parse("5-3", 5));
    }

    @Test
    void parseOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class, () -> PageRangeParser.parse("0", 5));
        assertThrows(IllegalArgumentException.class, () -> PageRangeParser.parse("6", 5));
        assertThrows(IllegalArgumentException.class, () -> PageRangeParser.parse("1-6", 5));
    }

    @Test
    void parseInvalidFormatThrows() {
        assertThrows(IllegalArgumentException.class, () -> PageRangeParser.parse("abc", 5));
    }

    @Test
    void keepDuplicatePages() {
        assertEquals(List.of(1, 1, 2), PageRangeParser.parse("1, 1, 2", 5));
    }

    @Test
    void formatCompactRanges() {
        assertEquals("1-3, 5", PageRangeParser.format(List.of(1, 2, 3, 5)));
        assertEquals("5-3", PageRangeParser.format(List.of(5, 4, 3)));
        assertEquals("1, 1, 2", PageRangeParser.format(List.of(1, 1, 2)));
        assertEquals("", PageRangeParser.format(List.of()));
    }

    @Test
    void formatRoundTrips() {
        for (List<Integer> pages : List.of(
                List.of(1, 2, 3, 5),
                List.of(5, 4, 3),
                List.of(1, 1, 2),
                List.of(2, 4, 6),
                List.of(1, 2, 3, 4, 5))) {
            assertEquals(pages, PageRangeParser.parse(PageRangeParser.format(pages), 6));
        }
    }

    @Test
    void allExpressionIsLocaleNeutral() {
        assertTrue(PageRangeParser.isAllExpression(null));
        assertTrue(PageRangeParser.isAllExpression("  "));
        assertTrue(PageRangeParser.isAllExpression("全部"));
        assertTrue(PageRangeParser.isAllExpression("ALL"));
        assertFalse(PageRangeParser.isAllExpression("1-3"));
    }
}
