package com.aqishi.toolbox.infra.kafka;

import com.aqishi.toolbox.infra.InfrastructureException;
import com.aqishi.toolbox.infra.messaging.KafkaResource;
import org.apache.kafka.clients.admin.AdminClient;

import java.util.Properties;

/**
 * Infrastructure factory for Kafka admin connections.
 *
 * <p>Only the long-lived {@code AdminClient} goes through this factory. The
 * short-lived producers and consumers used for browsing and publishing are still
 * created directly by {@code KafkaBrowserService}.</p>
 */
public final class KafkaClient {

    public KafkaResource connect(Properties properties) {
        if (properties == null || properties.getProperty("bootstrap.servers") == null
                || properties.getProperty("bootstrap.servers").trim().isEmpty()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "Kafka bootstrap.servers 不能为空");
        }
        try {
            return new KafkaResource(AdminClient.create(properties));
        } catch (RuntimeException error) {
            throw new InfrastructureException(InfrastructureException.Kind.CONNECTION,
                    error.getMessage(), error);
        }
    }

    public AdminClient adminClient(KafkaResource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("resource");
        }
        AutoCloseable client = resource.client();
        if (!(client instanceof AdminClient)) {
            throw new IllegalStateException("Kafka resource does not contain an AdminClient");
        }
        return (AdminClient) client;
    }
}
