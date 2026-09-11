package com.aqishi.toolbox.feature.data.domain;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KafkaFetchWindowTest {

    @Test
    void latestStartBacksOffFromEnd() {
        assertEquals(900, KafkaFetchWindow.latestStart(0, 1000, 100));
        assertEquals(995, KafkaFetchWindow.latestStart(0, 1000, 5));
    }

    @Test
    void latestStartNeverGoesBeforePartitionBeginning() {
        // 分区里剩余消息不足 N 条时从起点读
        assertEquals(0, KafkaFetchWindow.latestStart(0, 50, 100));
        // 保留策略裁掉了开头：不能越过当前起点
        assertEquals(10, KafkaFetchWindow.latestStart(10, 40, 100));
    }

    @Test
    void latestFirstSortsByTimestampDescending() {
        ConsumerRecord<String, String> older = record(1000L);
        ConsumerRecord<String, String> newer = record(2000L);
        List<ConsumerRecord<String, String>> records = new ArrayList<>(List.of(older, newer));

        records.sort(KafkaFetchWindow.LATEST_FIRST);
        assertEquals(List.of(newer, older), records);
    }

    @SuppressWarnings("deprecation")
    private static ConsumerRecord<String, String> record(long timestamp) {
        return new ConsumerRecord<>("t", 0, 0L, timestamp, TimestampType.CREATE_TIME,
                -1, -1, null, null, new RecordHeaders(), Optional.empty());
    }
}
