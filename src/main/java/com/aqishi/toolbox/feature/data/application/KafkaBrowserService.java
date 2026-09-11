package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.KafkaClientProperties;
import com.aqishi.toolbox.feature.data.domain.KafkaConsumerLag;
import com.aqishi.toolbox.feature.data.domain.KafkaFetchWindow;
import com.aqishi.toolbox.feature.data.domain.KafkaMessageFormat;
import com.aqishi.toolbox.feature.data.domain.KafkaSubscriberAnalysis;
import com.aqishi.toolbox.feature.network.ssh.infra.KafkaTunnelSupport;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Kafka 浏览工作台的编排服务：主题/消费组列表、分区、消费滞后、
 * 订阅者分析、消息拉取与发布。面板只保留 Swing 装配与状态展示。
 *
 * <p>实例在连接建立后创建，持有该连接生效的 bootstrap 地址与自定义属性；
 * SSH 隧道的 broker 主机集合按调用传入，避免服务反向依赖面板状态。</p>
 */
public final class KafkaBrowserService {

    private final AdminClient adminClient;
    private final String bootstrapServers;
    private final Properties customProperties;

    public KafkaBrowserService(AdminClient adminClient, String bootstrapServers,
                               Properties customProperties) {
        if (adminClient == null) {
            throw new NullPointerException("adminClient");
        }
        this.adminClient = adminClient;
        this.bootstrapServers = bootstrapServers;
        this.customProperties = customProperties;
    }

    public Set<String> listTopics() throws Exception {
        return adminClient.listTopics().names().get();
    }

    /** 消费组 ID 列表，按字典序排序。 */
    public List<String> listGroupIds() throws Exception {
        Collection<ConsumerGroupListing> groups = adminClient.listConsumerGroups().all().get();
        List<String> ids = groups.stream().map(ConsumerGroupListing::groupId)
                .collect(Collectors.toList());
        Collections.sort(ids);
        return ids;
    }

    /** 主题的分区编号列表。 */
    public List<Integer> partitionsOf(String topic) throws Exception {
        TopicDescription details = adminClient
                .describeTopics(Collections.singletonList(topic))
                .allTopicNames().get().get(topic);
        return details.partitions().stream()
                .map(TopicPartitionInfo::partition).collect(Collectors.toList());
    }

    /** 消费组在各分区上的滞后视图。 */
    public List<KafkaConsumerLag> lagOf(String groupId) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committed =
                adminClient.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
        if (committed.isEmpty()) {
            return List.of();
        }
        Map<TopicPartition, OffsetSpec> specs = new HashMap<>();
        for (TopicPartition tp : committed.keySet()) {
            specs.put(tp, OffsetSpec.latest());
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                adminClient.listOffsets(specs).all().get();
        return KafkaConsumerLag.assemble(committed, endOffsets);
    }

    /**
     * 主题的订阅者聚合：活动组（有成员分配到该主题）与历史组（仅持有位移）。
     * 单组位移查询超时不影响整体结果，经 {@code diagnostics} 上报。
     */
    public KafkaSubscriberAnalysis.Result subscribersOf(String topic,
                                                        Consumer<String> diagnostics) throws Exception {
        Collection<ConsumerGroupListing> groups = adminClient.listConsumerGroups().all().get();
        List<String> groupIds = groups.stream().map(ConsumerGroupListing::groupId)
                .collect(Collectors.toList());
        if (groupIds.isEmpty()) {
            return KafkaSubscriberAnalysis.analyze(topic, Map.of(), Set.of());
        }

        Map<String, ConsumerGroupDescription> descriptions =
                adminClient.describeConsumerGroups(groupIds).all().get();

        Set<String> offsetGroups = new LinkedHashSet<>();
        try {
            Map<String, org.apache.kafka.common.KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> futures =
                    new HashMap<>();
            ListConsumerGroupOffsetsOptions options = new ListConsumerGroupOffsetsOptions().timeoutMs(5000);
            for (String groupId : groupIds) {
                futures.put(groupId, adminClient.listConsumerGroupOffsets(groupId, options)
                        .partitionsToOffsetAndMetadata());
            }
            for (Map.Entry<String, org.apache.kafka.common.KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>>
                    entry : futures.entrySet()) {
                try {
                    Map<TopicPartition, OffsetAndMetadata> offsets =
                            entry.getValue().get(3, TimeUnit.SECONDS);
                    if (offsets != null) {
                        for (TopicPartition tp : offsets.keySet()) {
                            if (tp.topic().equals(topic)) {
                                offsetGroups.add(entry.getKey());
                                break;
                            }
                        }
                    }
                } catch (java.util.concurrent.TimeoutException timeout) {
                    diagnostics.accept("查询消费组 " + entry.getKey() + " Offset 超时");
                } catch (Exception ignored) {
                    // 单个消费组查询失败不影响其他组的结果
                }
            }
        } catch (Exception error) {
            diagnostics.accept("查询消费组 Offset 失败: " + error.getMessage());
        }
        return KafkaSubscriberAnalysis.analyze(topic, descriptions, offsetGroups);
    }

    /**
     * 拉取消息。{@code partition} 为 {@code null} 表示全部分区；
     * {@code fromBeginning} 为真时从头读，否则取每分区最新 {@code limit} 条。
     * 最多等待 6 秒，已有结果且连续两次空轮询则提前结束。
     */
    public List<ConsumerRecord<byte[], byte[]>> fetchMessages(String topic, Integer partition,
                                                              boolean fromBeginning, int limit,
                                                              Collection<String> tunnelBrokerHosts)
            throws Exception {
        List<ConsumerRecord<byte[], byte[]>> list = new ArrayList<>();
        Properties props = KafkaClientProperties.consumerProperties(bootstrapServers,
                "java-toolbox-temp-group-" + UUID.randomUUID(), customProperties);

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            if (tunnelBrokerHosts != null && !tunnelBrokerHosts.isEmpty()) {
                KafkaTunnelSupport.configure(consumer, tunnelBrokerHosts);
            }
            List<TopicPartition> partitions = new ArrayList<>();
            if (partition == null) {
                List<PartitionInfo> infos = consumer.partitionsFor(topic);
                if (infos != null) {
                    for (PartitionInfo info : infos) {
                        partitions.add(new TopicPartition(topic, info.partition()));
                    }
                }
            } else {
                partitions.add(new TopicPartition(topic, partition));
            }
            if (partitions.isEmpty()) {
                return list;
            }

            consumer.assign(partitions);
            if (fromBeginning) {
                consumer.seekToBeginning(partitions);
            } else {
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
                Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
                for (TopicPartition tp : partitions) {
                    consumer.seek(tp, KafkaFetchWindow.latestStart(
                            beginningOffsets.getOrDefault(tp, 0L),
                            endOffsets.getOrDefault(tp, 0L), limit));
                }
            }

            long deadline = System.currentTimeMillis() + 6000;
            int emptyPollCount = 0;
            while (System.currentTimeMillis() < deadline && list.size() < limit) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(300));
                if (records.isEmpty()) {
                    emptyPollCount++;
                    if (emptyPollCount >= 2 && !list.isEmpty()) {
                        break;
                    }
                } else {
                    emptyPollCount = 0;
                    for (ConsumerRecord<byte[], byte[]> record : records) {
                        list.add(record);
                        if (list.size() >= limit) {
                            break;
                        }
                    }
                }
            }
        }
        list.sort(KafkaFetchWindow.LATEST_FIRST);
        return list;
    }

    /** 发布一条字符串消息，头文本按 {@code key=value}/{@code key: value} 逐行解析。 */
    public RecordMetadata produce(String topic, String key, String headersText, String value,
                                  Collection<String> tunnelBrokerHosts) throws Exception {
        Properties props = KafkaClientProperties.producerProperties(bootstrapServers, customProperties);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            if (tunnelBrokerHosts != null && !tunnelBrokerHosts.isEmpty()) {
                KafkaTunnelSupport.configure(producer, tunnelBrokerHosts);
            }
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, key == null || key.isEmpty() ? null : key, value);
            for (KafkaMessageFormat.Header header : KafkaMessageFormat.parseHeaders(headersText)) {
                record.headers().add(header.key(), KafkaMessageFormat.headerValueBytes(header));
            }
            return producer.send(record).get();
        }
    }
}
