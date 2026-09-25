package com.aqishi.toolbox.feature.data.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisValueEditTest {

    @Test
    void parsesRedisTypeNames() {
        assertEquals(RedisValueEdit.Type.ZSET, RedisValueEdit.Type.of("zset"));
        assertEquals(RedisValueEdit.Type.HASH, RedisValueEdit.Type.of(" Hash "));
        assertThrows(IllegalArgumentException.class, () -> RedisValueEdit.Type.of("stream"));
        assertThrows(IllegalArgumentException.class, () -> RedisValueEdit.Type.of(null));
    }

    @Test
    void hashSkipsBlankFieldsAndLastDuplicateWins() {
        RedisValueEdit edit = RedisValueEdit.hash("user:1", Arrays.asList(
                new String[]{"name", "alice"},
                new String[]{" ", "ignored"},
                new String[]{null, "ignored"},
                new String[]{"name", "bob"},
                new String[]{"age", null}));

        assertEquals(Map.of("name", "bob", "age", ""), edit.fields());
    }

    /** 列表里空串是合法元素，只有 null（尚未填写的新行）才跳过；顺序必须保留。 */
    @Test
    void listKeepsEmptyStringsAndOrder() {
        RedisValueEdit edit = RedisValueEdit.list("queue", Arrays.asList("b", "", null, "a"));

        assertEquals(List.of("b", "", "a"), edit.items());
    }

    @Test
    void setSkipsBlankMembers() {
        RedisValueEdit edit = RedisValueEdit.set("tags", Arrays.asList("x", " ", null, "y"));

        assertEquals(List.of("x", "y"), edit.items());
    }

    @Test
    void zsetParsesScoresAndSkipsRowsWithoutMember() {
        RedisValueEdit edit = RedisValueEdit.zset("rank", Arrays.asList(
                new String[]{"1.5", "a"},
                new String[]{" 2 ", "b"},
                new String[]{"oops", ""}));

        assertEquals(Map.of("a", 1.5, "b", 2.0), edit.scores());
    }

    /** 分值写错必须在构建时就失败——写入阶段再失败会留下一个已经被 DEL 清空的 key。 */
    @Test
    void zsetRejectsBadScoreBeforeAnyWrite() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RedisValueEdit.zset("rank", Arrays.asList(
                        new String[]{"1", "a"},
                        new String[]{"abc", "b"})));

        assertTrue(error.getMessage().contains("Row 2"), error.getMessage());
    }

    @Test
    void requiresKey() {
        assertThrows(IllegalArgumentException.class, () -> RedisValueEdit.string("", "v"));
        assertThrows(IllegalArgumentException.class, () -> RedisValueEdit.string(null, "v"));
    }
}
