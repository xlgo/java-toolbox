package com.aqishi.toolbox.feature.data.domain;

import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 一个分区的消费滞后视图；{@link #assemble} 把已提交位移与末端位移
 * 合成按主题、分区排序的列表。
 */
public record KafkaConsumerLag(String topic, int partition,
                               long committedOffset, long latestOffset, long lag) {

    /**
     * 合成滞后列表。已提交位移为 {@code null} 按 0 计；末端位移缺失按 0 计；
     * lag 不允许为负（末端可能因保留策略落后于已提交位移）。
     */
    public static List<KafkaConsumerLag> assemble(
            Map<TopicPartition, OffsetAndMetadata> committed,
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets) {
        List<KafkaConsumerLag> list = new ArrayList<>();
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
            TopicPartition tp = entry.getKey();
            long committedOffset = entry.getValue() != null ? entry.getValue().offset() : 0;
            long latestOffset = endOffsets.containsKey(tp) ? endOffsets.get(tp).offset() : 0;
            long lag = Math.max(0, latestOffset - committedOffset);
            list.add(new KafkaConsumerLag(tp.topic(), tp.partition(),
                    committedOffset, latestOffset, lag));
        }
        list.sort(Comparator.comparing(KafkaConsumerLag::topic)
                .thenComparing(KafkaConsumerLag::partition));
        return list;
    }
}
