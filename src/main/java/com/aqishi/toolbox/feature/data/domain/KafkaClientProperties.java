package com.aqishi.toolbox.feature.data.domain;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

/**
 * Kafka 客户端属性的纯组装逻辑：从面板文本解析、合并超时默认值，
 * 并强制以 UI 选择的 bootstrap 地址为准（自定义属性粘贴不得绕过 SSH 隧道）。
 */
public final class KafkaClientProperties {

    private KafkaClientProperties() {
    }

    /**
     * 解析多行 {@code key=value} 文本。语法错误抛 {@link IOException}，
     * 由调用方决定是提示用户还是忽略。
     */
    public static Properties parseText(String text) throws IOException {
        Properties properties = new Properties();
        if (text == null || text.trim().isEmpty()) {
            return properties;
        }
        properties.load(new StringReader(text.trim()));
        return properties;
    }

    /**
     * 在自定义属性之上套用连接默认值；bootstrap servers 永远以参数为准，
     * 防止自定义属性里的同名键悄悄改走别的集群或绕过隧道。
     */
    public static Properties withDefaults(String bootstrapServers, Properties custom) {
        Properties props = new Properties();
        setDefault(props, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "15000");
        setDefault(props, AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000");
        setDefault(props, "socket.connection.setup.timeout.ms", "10000");
        setDefault(props, "socket.connection.setup.timeout.max.ms", "30000");
        if (custom != null) {
            for (String key : custom.stringPropertyNames()) {
                props.setProperty(key, custom.getProperty(key));
            }
        }
        props.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return props;
    }

    private static void setDefault(Properties properties, String key, String value) {
        if (!properties.containsKey(key)) {
            properties.setProperty(key, value);
        }
    }

    /**
     * 消息查看用的临时 consumer 属性：独立临时组、不提交位移、从头可读的
     * 字节数组反序列化。自定义属性最后合并（bootstrap 除外，见上）。
     */
    public static Properties consumerProperties(String bootstrapServers, String tempGroupId,
                                                Properties custom) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, tempGroupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        mergeCustom(props, custom);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return props;
    }

    /** 消息发布用的 producer 属性：字符串序列化，自定义属性最后合并。 */
    public static Properties producerProperties(String bootstrapServers, Properties custom) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        mergeCustom(props, custom);
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return props;
    }

    private static void mergeCustom(Properties props, Properties custom) {
        if (custom != null) {
            for (String key : custom.stringPropertyNames()) {
                props.put(key, custom.getProperty(key));
            }
        }
    }
}
