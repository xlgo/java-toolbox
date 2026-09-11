package com.aqishi.toolbox.feature.data.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlTextSupportTest {

    @Test
    void safeIdentifierAcceptsPlainIdentifiers() {
        assertEquals("orders", SqlTextSupport.safeIdentifier("orders"));
        assertEquals("_t", SqlTextSupport.safeIdentifier("_t"));
        assertEquals("t$1", SqlTextSupport.safeIdentifier("t$1"));
        assertEquals("T#2", SqlTextSupport.safeIdentifier("T#2"));
        assertEquals("A1", SqlTextSupport.safeIdentifier("A1"));
    }

    @Test
    void safeIdentifierRejectsHostileOrInvalidNames() {
        assertThrows(IllegalArgumentException.class, () -> SqlTextSupport.safeIdentifier(null));
        assertThrows(IllegalArgumentException.class, () -> SqlTextSupport.safeIdentifier(""));
        assertThrows(IllegalArgumentException.class, () -> SqlTextSupport.safeIdentifier("1abc"));
        assertThrows(IllegalArgumentException.class, () -> SqlTextSupport.safeIdentifier("a b"));
        assertThrows(IllegalArgumentException.class, () -> SqlTextSupport.safeIdentifier("a;b"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlTextSupport.safeIdentifier("x\" OR '1'='1"));
        assertThrows(IllegalArgumentException.class, () -> SqlTextSupport.safeIdentifier("表"));
    }

    @Test
    void extractTableNameFindsLastDmlTarget() {
        assertEquals("orders", SqlTextSupport.extractTableName("SELECT * FROM orders"));
        assertEquals("b", SqlTextSupport.extractTableName(
                "select * from a join b on a.id = b.id"));
        assertEquals("t", SqlTextSupport.extractTableName("UPDATE t SET x = 1"));
        assertEquals("logs", SqlTextSupport.extractTableName("INSERT INTO logs VALUES (1)"));
        assertEquals("orders", SqlTextSupport.extractTableName("select *\nfrom\n  orders"));
    }

    @Test
    void extractTableNameReturnsNullWhenNoMatch() {
        assertNull(SqlTextSupport.extractTableName("SELECT 1"));
        assertNull(SqlTextSupport.extractTableName(null));
        // 词边界要求：INFORM 里的 FROM 不算
        assertNull(SqlTextSupport.extractTableName("SELECT informant"));
    }
}
