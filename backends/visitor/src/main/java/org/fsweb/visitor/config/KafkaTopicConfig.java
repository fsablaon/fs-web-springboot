package org.fsweb.visitor.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.fsweb.visitor.event.VisitEventProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties(VisitEventProperties.class)
public class KafkaTopicConfig {

    /**
     * The visit events topic. Spring Boot's auto-configured {@code KafkaAdmin} creates every
     * {@code NewTopic} bean at startup if it does not exist yet (and leaves existing topics alone).
     * The visitor backend owns the topic because it owns the visit counter; the single-node dev
     * broker also auto-creates topics on first write, so the facade can start first.
     */
    @Bean
    NewTopic visitsTopic(VisitEventProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(1)
                .build();
    }
}
