package org.fsweb.facade.event;

import java.time.Instant;

/**
 * One visit, published to the Kafka topic {@code facade.visits.topic} as JSON:
 * <pre>{"id":"<uuid>","source":"facade","path":"/api/hello","occurredAt":"2026-10-30T20:15:30.123456Z"}</pre>
 * The visitor backend has its own copy of this record (same field names) and increments the
 * visit counter once per event. No type headers are sent, so the class name does not matter.
 *
 * @param id         unique per event (UUID), so a consumer can detect redelivery
 * @param source     service that recorded the visit, also the Kafka record key
 * @param path       request path that counted as a visit
 * @param occurredAt when the request was handled
 */
public record VisitEvent(String id, String source, String path, Instant occurredAt) {
}
