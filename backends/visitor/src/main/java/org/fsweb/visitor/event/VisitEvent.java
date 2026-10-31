package org.fsweb.visitor.event;

import java.time.Instant;

/**
 * A visit recorded by another backend, read from the Kafka topic {@code visitor.visits.topic} as JSON:
 * <pre>{"id":"<uuid>","source":"facade","path":"/api/hello","occurredAt":"2026-10-30T20:15:30.123456Z"}</pre>
 * Mirrors the facade backend's {@code org.fsweb.facade.event.VisitEvent} (same field names); records
 * carry no type header, so the two classes are independent.
 *
 * @param id         unique per event (UUID)
 * @param source     service that recorded the visit (also the record key)
 * @param path       request path that counted as a visit
 * @param occurredAt when the request was handled
 */
public record VisitEvent(String id, String source, String path, Instant occurredAt) {
}
