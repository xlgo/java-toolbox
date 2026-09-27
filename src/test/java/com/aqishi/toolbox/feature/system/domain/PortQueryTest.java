package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortQueryTest {

    @Test
    void blankMatchesEverything() {
        assertTrue(PortQuery.parse("  ").isAny());
        assertTrue(PortQuery.parse(null).matches(12345));
    }

    @Test
    void parsesSinglePortsRangesAndLists() {
        PortQuery query = PortQuery.parse("80, 443;8000-8100 9090");
        assertTrue(query.matches(80));
        assertTrue(query.matches(443));
        assertTrue(query.matches(8000));
        assertTrue(query.matches(8050));
        assertTrue(query.matches(8100));
        assertTrue(query.matches(9090));
        assertFalse(query.matches(8101));
        assertFalse(query.matches(81));
        assertEquals(OptionalInt.empty(), query.singlePort());
        assertEquals(OptionalInt.of(8080), PortQuery.parse(" 8080 ").singlePort());
        assertEquals(OptionalInt.of(8080), PortQuery.parse("8080-8080").singlePort());
    }

    @Test
    void reportsErrorCategories() {
        assertEquals(PortQuery.Error.NOT_A_NUMBER,
                assertThrows(PortQuery.InvalidException.class, () -> PortQuery.parse("http")).error());
        assertEquals(PortQuery.Error.OUT_OF_RANGE,
                assertThrows(PortQuery.InvalidException.class, () -> PortQuery.parse("70000")).error());
        PortQuery.InvalidException reversed =
                assertThrows(PortQuery.InvalidException.class, () -> PortQuery.parse("80, 9000-8000"));
        assertEquals(PortQuery.Error.REVERSED_RANGE, reversed.error());
        assertEquals("9000-8000", reversed.fragment());
        assertEquals(PortQuery.Error.NOT_A_NUMBER,
                assertThrows(PortQuery.InvalidException.class, () -> PortQuery.parse("-5")).error());
        assertEquals(PortQuery.Error.NOT_A_NUMBER,
                assertThrows(PortQuery.InvalidException.class, () -> PortQuery.parse("80-")).error());
    }
}
