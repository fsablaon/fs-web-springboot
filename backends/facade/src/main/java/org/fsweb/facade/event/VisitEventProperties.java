package org.fsweb.facade.event;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Visit event settings ({@code facade.visits.*} in application.yml).
 *
 * @param topic  Kafka topic the visit events are published to; the visitor backend listens on the same name
 * @param source value of {@link VisitEvent#source()} and the record key
 */
@ConfigurationProperties("facade.visits")
public record VisitEventProperties(
        @DefaultValue("fs-web.visits") String topic,
        @DefaultValue("facade") String source) {
}
