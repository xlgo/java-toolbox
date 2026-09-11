package com.aqishi.toolbox.feature.data.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlSupportTest {

    @Test
    void buildsUrlForKnownDatabaseTypes() {
        assertEquals("jdbc:mysql://h:3306/db?useSSL=false&serverTimezone=UTC&characterEncoding=utf-8",
                JdbcUrlSupport.buildJdbcUrl("MySQL", "h", "3306", "db"));
        assertEquals("jdbc:postgresql://h:5432/db",
                JdbcUrlSupport.buildJdbcUrl("PostgreSQL", "h", "5432", "db"));
        assertEquals("jdbc:oracle:thin:@//h:1521/db",
                JdbcUrlSupport.buildJdbcUrl("Oracle", "h", "1521", "db"));
    }

    @Test
    void customAndUnknownTypesLeaveUrlUntouched() {
        assertNull(JdbcUrlSupport.buildJdbcUrl("Custom", "h", "1", "db"));
        assertNull(JdbcUrlSupport.buildJdbcUrl("DB2", "h", "1", "db"));
        assertNull(JdbcUrlSupport.buildJdbcUrl(null, "h", "1", "db"));
    }

    @Test
    void parsePortAcceptsValidRange() {
        assertEquals(1, JdbcUrlSupport.parsePort("1"));
        assertEquals(3306, JdbcUrlSupport.parsePort(" 3306 "));
        assertEquals(65535, JdbcUrlSupport.parsePort("65535"));
    }

    @Test
    void parsePortRejectsAnythingElse() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlSupport.parsePort("0"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlSupport.parsePort("65536"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlSupport.parsePort("abc"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlSupport.parsePort(""));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlSupport.parsePort(null));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlSupport.parsePort("33.5"));
    }

    @Test
    void replaceEndpointRewritesAuthorityHostOnly() {
        assertEquals("jdbc:mysql://127.0.0.1:15432/orders?useSSL=false",
                JdbcUrlSupport.replaceJdbcEndpoint(
                        "jdbc:mysql://db.internal:3306/orders?useSSL=false",
                        "db.internal", 3306, 15432));
    }

    @Test
    void replaceEndpointPreservesUserInfo() {
        assertEquals("jdbc:mysql://user:pw@127.0.0.1:15432/orders",
                JdbcUrlSupport.replaceJdbcEndpoint(
                        "jdbc:mysql://user:pw@db.internal:3306/orders",
                        "db.internal", 3306, 15432));
    }

    @Test
    void replaceEndpointFallsBackToStringReplaceForNonStandardUrls() {
        // Oracle thin 风格没有 "://" authority 段
        assertEquals("jdbc:oracle:thin:@//127.0.0.1:11521/orcl",
                JdbcUrlSupport.replaceJdbcEndpoint(
                        "jdbc:oracle:thin:@//db.host:1521/orcl",
                        "db.host", 1521, 11521));
    }

    @Test
    void replaceEndpointHandlesBracketedIpv6() {
        assertEquals("jdbc:postgresql://127.0.0.1:15432/db",
                JdbcUrlSupport.replaceJdbcEndpoint(
                        "jdbc:postgresql://[::1]:5432/db", "::1", 5432, 15432));
    }

    @Test
    void replaceEndpointLeavesUnrelatedUrlUnchanged() {
        String url = "jdbc:mysql://other.host:3306/orders";
        assertEquals(url, JdbcUrlSupport.replaceJdbcEndpoint(url, "db.internal", 3306, 15432));
    }
}
