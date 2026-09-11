package com.aqishi.toolbox.feature.data.domain;

import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KafkaConsumerLagTest {

    private static ListOffsetsResult.ListOffsetsResultInfo end(long offset) {
        return new ListOffsetsResult.ListOffsetsResultInfo(offset, -1L, Optional.empty());
    }

    @Test
    void assemblesLagSortedByTopicAndPartition() {
        Map<TopicPartition, OffsetAndMetadata> committed = Map.of(
                new TopicPartition("b", 0), new OffsetAndMetadata(50),
                new TopicPartition("a", 1), new OffsetAndMetadata(100),
                new TopicPartition("a", 0), new OffsetAndMetadata(7));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = Map.of(
                new TopicPartition("b", 0), end(80),
                new TopicPartition("a", 1), end(160),
                new TopicPartition("a", 0), end(7));

        List<KafkaConsumerLag> lags = KafkaConsumerLag.assemble(committed, endOffsets);
        assertEquals(List.of(
                new KafkaConsumerLag("a", 0, 7, 7, 0),
                new KafkaConsumerLag("a", 1, 100, 160, 60),
                new KafkaConsumerLag("b", 0, 50, 80, 30)), lags);
    }

    @Test
    void lagNeverGoesNegative() {
        // 末端位移可能因保留策略落后于已提交位移
        Map<TopicPartition, OffsetAndMetadata> committed = Map.of(
                new TopicPartition("t", 0), new OffsetAndMetadata(500));
        List<KafkaConsumerLag> lags = KafkaConsumerLag.assemble(committed,
                Map.of(new TopicPartition("t", 0), end(400)));
        assertEquals(0, lags.get(0).lag());
        assertEquals(400, lags.get(0).latestOffset());
    }

    @Test
    void nullCommittedOffsetCountsAsZero() {
        Map<TopicPartition, OffsetAndMetadata> committed =
                new java.util.HashMap<>();
        committed.put(new TopicPartition("t", 0), null);
        List<KafkaConsumerLag> lags = KafkaConsumerLag.assemble(committed,
                Map.of(new TopicPartition("t", 0), end(42)));
        assertEquals(0, lags.get(0).committedOffset());
        assertEquals(42, lags.get(0).lag());
    }

    @Test
    void missingEndOffsetCountsAsZero() {
        Map<TopicPartition, OffsetAndMetadata> committed = Map.of(
                new TopicPartition("t", 0), new OffsetAndMetadata(10));
        List<KafkaConsumerLag> lags = KafkaConsumerLag.assemble(committed, Map.of());
        assertEquals(0, lags.get(0).latestOffset());
        assertEquals(0, lags.get(0).lag());
    }
}
