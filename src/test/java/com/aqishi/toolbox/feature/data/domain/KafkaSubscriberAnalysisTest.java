package com.aqishi.toolbox.feature.data.domain;

import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberAssignment;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KafkaSubscriberAnalysisTest {

    private static MemberDescription member(String id, String... topics) {
        Set<TopicPartition> partitions = new java.util.LinkedHashSet<>();
        for (String topic : topics) {
            partitions.add(new TopicPartition(topic, 0));
        }
        return new MemberDescription(id, "client-" + id, "/127.0.0.1",
                new MemberAssignment(partitions));
    }

    private static ConsumerGroupDescription group(String id, MemberDescription... members) {
        return new ConsumerGroupDescription(id, false, List.of(members),
                "range", ConsumerGroupState.STABLE, new Node(1, "broker", 9092));
    }

    @Test
    void activeGroupsAreThoseWithMembersAssignedToTopic() {
        Map<String, ConsumerGroupDescription> descriptions = Map.of(
                "g1", group("g1", member("m1", "orders"), member("m2", "other")),
                "g2", group("g2", member("m3", "other")));

        KafkaSubscriberAnalysis.Result result =
                KafkaSubscriberAnalysis.analyze("orders", descriptions, Set.of());

        assertEquals(1, result.subscribers().size());
        KafkaSubscriberAnalysis.Subscriber subscriber = result.subscribers().get(0);
        assertEquals("g1", subscriber.groupId());
        assertTrue(subscriber.active());
        assertEquals("Stable", subscriber.state());
        assertEquals(2, subscriber.totalMembers());
        // 只有真正分配到该主题的成员进入 activeMembers
        assertEquals(List.of("m1"),
                result.activeMembers().get("g1").stream()
                        .map(MemberDescription::consumerId).toList());
    }

    @Test
    void offsetOnlyGroupsShowAsInactive() {
        Map<String, ConsumerGroupDescription> descriptions = Map.of(
                "g1", group("g1", member("m1", "other")));

        KafkaSubscriberAnalysis.Result result =
                KafkaSubscriberAnalysis.analyze("orders", descriptions, Set.of("g1"));

        assertEquals(1, result.subscribers().size());
        assertFalse(result.subscribers().get(0).active());
        assertTrue(result.activeMembers().isEmpty());
    }

    @Test
    void offsetGroupWithoutDescriptionGetsUnknownState() {
        KafkaSubscriberAnalysis.Result result =
                KafkaSubscriberAnalysis.analyze("orders", Map.of(), Set.of("ghost"));

        assertEquals(1, result.subscribers().size());
        KafkaSubscriberAnalysis.Subscriber subscriber = result.subscribers().get(0);
        assertEquals("ghost", subscriber.groupId());
        assertEquals("UNKNOWN", subscriber.state());
        assertEquals(0, subscriber.totalMembers());
    }

    @Test
    void subscribersAreSortedByGroupId() {
        Map<String, ConsumerGroupDescription> descriptions = Map.of(
                "zeta", group("zeta", member("m1", "orders")),
                "alpha", group("alpha", member("m2", "orders")));

        KafkaSubscriberAnalysis.Result result =
                KafkaSubscriberAnalysis.analyze("orders", descriptions, Set.of("mid"));
        assertEquals(List.of("alpha", "mid", "zeta"),
                result.subscribers().stream()
                        .map(KafkaSubscriberAnalysis.Subscriber::groupId).toList());
    }

    @Test
    void emptyInputsYieldEmptyReport() {
        KafkaSubscriberAnalysis.Result result =
                KafkaSubscriberAnalysis.analyze("orders", null, null);
        assertTrue(result.subscribers().isEmpty());
        assertTrue(result.activeMembers().isEmpty());
    }
}
