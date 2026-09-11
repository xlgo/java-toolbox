package com.aqishi.toolbox.feature.data.domain;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.Comparator;

/**
 * 消息拉取的窗口计算与排序：从末端向前回退 N 条的起点、按时间倒序的比较器。
 */
public final class KafkaFetchWindow {

    /** 最新记录排最前的排序。 */
    public static final Comparator<ConsumerRecord<?, ?>> LATEST_FIRST =
            (left, right) -> Long.compare(right.timestamp(), left.timestamp());

    private KafkaFetchWindow() {
    }

    /**
     * "最新 N 条"策略的分区起点：从末端回退 {@code limit} 条，
     * 但不越过分区起点（消息可能因保留策略已不足 N 条）。
     */
    public static long latestStart(long beginningOffset, long endOffset, int limit) {
        return Math.max(beginningOffset, endOffset - limit);
    }
}
