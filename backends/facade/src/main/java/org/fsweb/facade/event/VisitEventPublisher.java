package org.fsweb.facade.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Publishes one {@link VisitEvent} per visit to the Kafka topic the visitor backend consumes. */
@Slf4j
@Component
public class VisitEventPublisher {

    private final KafkaTemplate<String, VisitEvent> kafkaTemplate;
    private final VisitEventProperties properties;
    private final Clock clock;

    @Autowired
    public VisitEventPublisher(KafkaTemplate<String, VisitEvent> kafkaTemplate, VisitEventProperties properties) {
        this(kafkaTemplate, properties, Clock.systemUTC());
    }

    /** For tests that need a fixed clock. */
    VisitEventPublisher(KafkaTemplate<String, VisitEvent> kafkaTemplate, VisitEventProperties properties, Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Publishes a visit for {@code path}. The send is asynchronous: the caller does not wait for the
     * broker. A failure (broker down, timeout) is logged at WARN and swallowed, so the caller's
     * response does not depend on Kafka. The producer blocks at most {@code max.block.ms}
     * ({@code spring.kafka.producer.properties.max.block.ms}) when the broker is unreachable.
     *
     * @return the event that was handed to the producer
     */
    public VisitEvent publish(String path) {
        VisitEvent event = new VisitEvent(UUID.randomUUID().toString(), properties.source(), path, Instant.now(clock));
        try {
            CompletableFuture<?> future = kafkaTemplate.send(properties.topic(), event.source(), event);
            future.whenComplete((result, failure) -> {
                if (failure == null) {
                    log.debug("Published visit event {} to topic {}", event.id(), properties.topic());
                } else {
                    logFailure(event, failure);
                }
            });
        } catch (RuntimeException e) {
            // Immediate failures are thrown, not returned in the future: no broker within max.block.ms
            // (TimeoutException), no producer, serialization error
            logFailure(event, e);
        }
        return event;
    }

    private void logFailure(VisitEvent event, Throwable failure) {
        // KafkaTemplate wraps the producer error as KafkaException("Send failed", cause); the cause says why
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        log.warn("Could not publish visit event {} to topic {}: {}", event.id(), properties.topic(), cause.getMessage());
    }
}
