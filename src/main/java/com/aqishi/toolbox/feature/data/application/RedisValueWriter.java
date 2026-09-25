package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.RedisValueEdit;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把一次 {@link RedisValueEdit} 写回 Redis。
 *
 * <p>list / set / zset 没有"整体替换"命令，只能先 {@code DEL} 再重建。这里把删除、重建和
 * 恢复过期时间放进同一个 {@code MULTI/EXEC}：其他客户端不会看到被清空的中间态，
 * 也不会因为 {@code DEL} 或 {@code SET} 顺带清掉 TTL 而把一个临时 key 变成永久 key。</p>
 *
 * <p>本类不做线程约束，调用方须保证同一个 {@link Jedis} 不被并发使用。</p>
 */
public final class RedisValueWriter {

    /**
     * 写入编辑结果。集合类型内容为空时，结果等同于删除该 key——Redis 不允许空集合存在。
     */
    public void save(Jedis jedis, RedisValueEdit edit) {
        String key = edit.key();
        // PTTL：-1 永不过期，-2 key 不存在，其余为剩余毫秒。只有正数需要在重建后补回。
        long ttlMillis = jedis.pttl(key);

        if (edit.type() == RedisValueEdit.Type.HASH) {
            saveHash(jedis, edit, key);
            return;
        }

        Transaction transaction = jedis.multi();
        switch (edit.type()) {
            case STRING:
                transaction.set(key, edit.text());
                break;
            case LIST:
                transaction.del(key);
                if (!edit.items().isEmpty()) {
                    transaction.rpush(key, edit.items().toArray(new String[0]));
                }
                break;
            case SET:
                transaction.del(key);
                if (!edit.items().isEmpty()) {
                    transaction.sadd(key, edit.items().toArray(new String[0]));
                }
                break;
            case ZSET:
                transaction.del(key);
                if (!edit.scores().isEmpty()) {
                    transaction.zadd(key, edit.scores());
                }
                break;
            default:
                transaction.discard();
                throw new IllegalArgumentException("Unsupported Redis type: " + edit.type());
        }
        if (ttlMillis > 0) {
            transaction.pexpire(key, ttlMillis);
        }
        transaction.exec();
    }

    /**
     * 哈希按差异更新：只写表格里的字段、只删表格里被移除的字段，不整体重建，
     * 因此不会清掉 TTL，也不会在其他客户端眼里短暂消失。
     */
    private void saveHash(Jedis jedis, RedisValueEdit edit, String key) {
        Map<String, String> current = jedis.hgetAll(key);
        Set<String> removed = new HashSet<>(current.keySet());
        removed.removeAll(edit.fields().keySet());

        Transaction transaction = jedis.multi();
        if (!edit.fields().isEmpty()) {
            transaction.hset(key, edit.fields());
        }
        if (!removed.isEmpty()) {
            transaction.hdel(key, removed.toArray(new String[0]));
        }
        transaction.exec();
    }
}
