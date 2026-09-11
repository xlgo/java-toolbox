package com.aqishi.toolbox.feature.data.domain;

import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.common.TopicPartition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主题订阅者聚合：把"当前有成员分配到该主题的活动组"与
 * "仅持有该主题位移的历史组"合并成统一视图。
 */
public final class KafkaSubscriberAnalysis {

    /** 一个订阅了目标主题的消费组。 */
    public record Subscriber(String groupId, String state, boolean active, int totalMembers) {
    }

    /** 聚合结果：订阅组列表 + 各活动组里分配到该主题的组成员。 */
    public record Result(List<Subscriber> subscribers,
                         Map<String, List<MemberDescription>> activeMembers) {
    }

    private KafkaSubscriberAnalysis() {
    }

    /**
     * @param topic        目标主题
     * @param descriptions 全部消费组的描述（可无）
     * @param offsetGroups 持有该主题位移的组（可无）
     */
    public static Result analyze(String topic,
                                 Map<String, ConsumerGroupDescription> descriptions,
                                 Set<String> offsetGroups) {
        Map<String, ConsumerGroupDescription> descs =
                descriptions == null ? Map.of() : descriptions;
        Set<String> withOffsets = offsetGroups == null ? Set.of() : offsetGroups;

        Map<String, List<MemberDescription>> activeMembers = new LinkedHashMap<>();
        for (Map.Entry<String, ConsumerGroupDescription> entry : descs.entrySet()) {
            List<MemberDescription> members = new ArrayList<>();
            for (MemberDescription member : entry.getValue().members()) {
                for (TopicPartition tp : member.assignment().topicPartitions()) {
                    if (tp.topic().equals(topic)) {
                        members.add(member);
                        break;
                    }
                }
            }
            if (!members.isEmpty()) {
                activeMembers.put(entry.getKey(), members);
            }
        }

        Set<String> allGroups = new LinkedHashSet<>(activeMembers.keySet());
        allGroups.addAll(withOffsets);

        List<Subscriber> subscribers = new ArrayList<>();
        for (String groupId : allGroups) {
            boolean active = activeMembers.containsKey(groupId);
            ConsumerGroupDescription desc = descs.get(groupId);
            String state = desc != null ? desc.state().toString() : "UNKNOWN";
            int totalMembers = desc != null ? desc.members().size() : 0;
            subscribers.add(new Subscriber(groupId, state, active, totalMembers));
        }
        subscribers.sort(Comparator.comparing(Subscriber::groupId));
        return new Result(subscribers, activeMembers);
    }
}
