package com.aqishi.toolbox.feature.data.domain;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class KafkaClientPropertiesTest {

    @Test
    void parseTextHandlesEmptyAndNull() throws IOException {
        assertTrue(KafkaClientProperties.parseText(null).isEmpty());
        assertTrue(KafkaClientProperties.parseText("  \n ").isEmpty());
    }

    @Test
    void parseTextReadsStandardPropertiesSyntax() throws IOException {
        Properties props = KafkaClientProperties.parseText(
                "security.protocol=SASL_SSL\nsasl.mechanism=PLAIN\n# 注释行\n");
        assertEquals("SASL_SSL", props.getProperty("security.protocol"));
        assertEquals("PLAIN", props.getProperty("sasl.mechanism"));
        assertEquals(2, props.size());
    }

    @Test
    void parseTextRejectsMalformedEscapes() {
        // 非法 unicode 转义必须抛给调用方，而不是静默用半截配置去连集群
        // （Properties.load 对语法错误抛的是未受检的 IllegalArgumentException）
        assertThrows(IllegalArgumentException.class,
                () -> KafkaClientProperties.parseText("key=bad\\u12 escape"));
    }

    @Test
    void withDefaultsSuppliesTimeoutDefaults() {
        Properties props = KafkaClientProperties.withDefaults("localhost:9092", null);
        assertEquals("15000", props.getProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG));
        assertEquals("30000", props.getProperty(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG));
        assertEquals("10000", props.getProperty("socket.connection.setup.timeout.ms"));
        assertEquals("30000", props.getProperty("socket.connection.setup.timeout.max.ms"));
        assertEquals("localhost:9092",
                props.getProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    @Test
    void withDefaultsLetsCustomPropertiesOverrideTimeouts() {
        Properties custom = new Properties();
        custom.setProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "60000");
        custom.setProperty("security.protocol", "SSL");

        Properties props = KafkaClientProperties.withDefaults("localhost:9092", custom);
        // 自定义值在默认值之后写入，自定义优先
        assertEquals("60000", props.getProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG));
        assertEquals("SSL", props.getProperty("security.protocol"));
    }

    @Test
    void bootstrapServersAlwaysComeFromUiSelection() {
        // 安全约束：自定义属性粘贴不得把连接引向别的集群或绕过 SSH 隧道
        Properties custom = new Properties();
        custom.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "evil.example.com:9092");

        Properties props = KafkaClientProperties.withDefaults("127.0.0.1:54321", custom);
        assertEquals("127.0.0.1:54321",
                props.getProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    @Test
    void consumerPropertiesSetTempGroupAndByteDeserializers() {
        Properties props = KafkaClientProperties.consumerProperties(
                "localhost:9092", "tmp-group-1", null);
        assertEquals("localhost:9092", props.getProperty("bootstrap.servers"));
        assertEquals("tmp-group-1", props.getProperty("group.id"));
        assertEquals("false", props.getProperty("enable.auto.commit"));
        assertEquals("earliest", props.getProperty("auto.offset.reset"));
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer",
                props.getProperty("key.deserializer"));
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer",
                props.getProperty("value.deserializer"));
    }

    @Test
    void consumerAndProducerPropertiesCannotOverrideBootstrapViaCustom() {
        Properties custom = new Properties();
        custom.setProperty("bootstrap.servers", "evil.example.com:9092");
        custom.setProperty("security.protocol", "SSL");

        Properties consumer = KafkaClientProperties.consumerProperties(
                "127.0.0.1:54321", "g", custom);
        assertEquals("127.0.0.1:54321", consumer.getProperty("bootstrap.servers"));
        assertEquals("SSL", consumer.getProperty("security.protocol"));

        Properties producer = KafkaClientProperties.producerProperties("127.0.0.1:54321", custom);
        assertEquals("127.0.0.1:54321", producer.getProperty("bootstrap.servers"));
        assertEquals("org.apache.kafka.common.serialization.StringSerializer",
                producer.getProperty("key.serializer"));
        assertEquals("org.apache.kafka.common.serialization.StringSerializer",
                producer.getProperty("value.serializer"));
    }
}
