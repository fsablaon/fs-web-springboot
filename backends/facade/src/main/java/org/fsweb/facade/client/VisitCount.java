package org.fsweb.facade.client;

/** Response body of the visitor backend's {@code /api/visits} ({@code {"count": n}}). */
public record VisitCount(long count) {
}
