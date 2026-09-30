package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.RedisValueEdit;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisDataException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test the command sequence without a running Redis or any connection to user data. */
class RedisValueWriterTest {
    @Test void zeroRemainingTtlMustNotTurnIntoPermanentKey() {
        var jedis = new FakeJedis();
        jedis.ttl = 0;
        new RedisValueWriter().save(jedis, RedisValueEdit.string("k", "value"));
        assertEquals(List.of("set", "expire:0", "exec"), jedis.transaction.commands);
    }
    @Test void preservesPositiveTtlAndDoesNotExpirePersistentKeys() {
        var jedis = new FakeJedis();
        jedis.ttl = 15000;
        new RedisValueWriter().save(jedis, RedisValueEdit.string("k", "value"));
        assertEquals(List.of("set", "expire:15000", "exec"), jedis.transaction.commands);
        jedis.transaction.commands.clear();
        jedis.ttl = -1;
        new RedisValueWriter().save(jedis, RedisValueEdit.string("k", "value"));
        assertEquals(List.of("set", "exec"), jedis.transaction.commands);
    }
    @Test void commandErrorsInExecResultsAreNotReportedAsSuccess() {
        var jedis = new FakeJedis();
        var expected = new JedisDataException("WRONGTYPE");
        jedis.transaction.results = List.of(expected);
        assertSame(expected, assertThrows(JedisDataException.class,
                () -> new RedisValueWriter().save(jedis, RedisValueEdit.string("k", "v"))));
    }
    @Test void abortedTransactionIsReported() {
        var jedis = new FakeJedis();
        jedis.transaction.results = null;
        assertThrows(IllegalStateException.class,
                () -> new RedisValueWriter().save(jedis, RedisValueEdit.string("k", "v")));
    }
    private static class FakeJedis extends Jedis {
        long ttl = -1;
        final RecordingTransaction transaction = new RecordingTransaction();
        @Override public Long pttl(String key) { return ttl; }
        @Override public Transaction multi() { return transaction; }
    }
    private static class RecordingTransaction extends Transaction {
        final List<String> commands = new ArrayList<>();
        List<Object> results = List.of("OK");
        @Override public Response<String> set(String key, String value) { commands.add("set"); return null; }
        @Override public Response<Long> pexpire(String key, long ttl) { commands.add("expire:" + ttl); return null; }
        @Override public List<Object> exec() { commands.add("exec"); return results; }
    }
}
