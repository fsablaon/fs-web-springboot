package org.fsweb.visitor.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fsweb.visitor.service.VisitCounterService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the visit events the facade backend publishes and increments the visit counter once per
 * event. Records that cannot be deserialized (see {@code ErrorHandlingDeserializer} in
 * application.yml) are logged and skipped by Spring Kafka's default error handler, so one bad record
 * does not block the partition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitEventListener {

    private final VisitCounterService visitCounterService;

    @KafkaListener(topics = "${visitor.visits.topic}", groupId = "${visitor.visits.group-id}")
    public void onVisit(VisitEvent event) {
        long count = visitCounterService.increment();
        log.debug("Visit event {} from {} ({} at {}) counted, count is now {}",
                event.id(), event.source(), event.path(), event.occurredAt(), count);
    }
}
