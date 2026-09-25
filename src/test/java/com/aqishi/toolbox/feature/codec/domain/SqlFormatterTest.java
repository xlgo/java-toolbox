package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlFormatterTest {

    @Test
    void uppercasesKeywordsAndBreaksClauses() {
        assertEquals("SELECT id, name\nFROM users\nWHERE age > 18\n  AND active = 1\nORDER BY id",
                SqlFormatter.format("select id,name from users where age>18 and active=1 order by id"));
    }

    @Test
    void keepsJoinQualifierOnSameLine() {
        assertEquals("SELECT *\nFROM a\nLEFT JOIN b ON a.id = b.id",
                SqlFormatter.format("select * from a left join b on a.id=b.id"));
    }

    /** 回归：旧实现把 >= 拆成两个 token，格式化后变成 "> ="，SQL 直接无法执行。 */
    @Test
    void keepsMultiCharacterOperatorsTogether() {
        assertEquals("SELECT *\nFROM t\nWHERE a >= 1\n  AND b <= 2\n  AND c <> 3\n  AND d != 4",
                SqlFormatter.format("select * from t where a>=1 and b<=2 and c<>3 and d!=4"));
    }

    @Test
    void keepsQualifiedNamesTight() {
        assertEquals("SELECT u.id\nFROM app.users u",
                SqlFormatter.format("select u.id from app.users u"));
    }

    /** 回归：旧实现先压缩全部空白再切词，字符串里的连续空格被改掉，查询语义随之改变。 */
    @Test
    void preservesWhitespaceInsideStringLiterals() {
        String formatted = SqlFormatter.format("select * from t where name = 'a   b'");

        assertTrue(formatted.contains("'a   b'"), formatted);
    }

    @Test
    void keepsEscapedQuotesInsideLiterals() {
        String formatted = SqlFormatter.format("select * from t where name = 'it''s  here'");

        assertTrue(formatted.contains("'it''s  here'"), formatted);
    }

    /** 回归：行注释被并到同一行后，会把后面整段 SQL 一起注释掉。 */
    @Test
    void lineCommentDoesNotSwallowFollowingSql() {
        String formatted = SqlFormatter.format("select id -- primary key\nfrom users");

        String[] lines = formatted.split("\n");
        String commentLine = null;
        for (String line : lines) {
            if (line.contains("--")) {
                commentLine = line;
            }
        }
        assertTrue(commentLine != null && commentLine.trim().endsWith("-- primary key"), formatted);
        assertTrue(formatted.contains("\nFROM users"), formatted);
    }

    @Test
    void keepsBlockCommentsAndQuotedIdentifiersIntact() {
        List<String> tokens = SqlFormatter.tokenize("select /* a  b */ `my col`, \"Other  Col\" from t");

        assertTrue(tokens.contains("/* a  b */"), tokens.toString());
        assertTrue(tokens.contains("`my col`"), tokens.toString());
        assertTrue(tokens.contains("\"Other  Col\""), tokens.toString());
    }

    @Test
    void compressJoinsTokensOnOneLine() {
        assertEquals("select a.id, count(*) from t where x >= 1",
                SqlFormatter.compress("select a.id ,\n  count( * )\nfrom t\n where x>=1"));
    }

    @Test
    void compressKeepsLiteralsIntact() {
        assertEquals("select * from t where name = 'a   b'",
                SqlFormatter.compress("select *\nfrom t\nwhere name = 'a   b'"));
    }

    /** 回归：旧的压缩把行注释并进同一行，后面的 SQL 全被注释掉。 */
    @Test
    void compressTurnsLineCommentsIntoBlockComments() {
        assertEquals("select id /* primary key */ from users",
                SqlFormatter.compress("select id -- primary key\nfrom users"));
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertEquals("", SqlFormatter.compress(" "));
        assertEquals("", SqlFormatter.format("   "));
        assertEquals("", SqlFormatter.format(null));
    }
}
