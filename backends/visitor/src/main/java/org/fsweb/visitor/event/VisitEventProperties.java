package org.fsweb.visitor.event;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Visit event settings ({@code visitor.visits.*} in application.yml).
 *
 * @param topic      Kafka topic the facade backend publishes visit events to; created at startup if missing
 * @param groupId    consumer group of the listener; one group, so each event is counted once
 *                   however many visitor instances run
 * @param partitions partitions of the topic when this backend creates it
 */
@ConfigurationProperties("visitor.visits")
public record VisitEventProperties(
        @DefaultValue("fs-web.visits") String topic,
        @DefaultValue("visitor") String groupId,
        @DefaultValue("1") int partitions) {
}
