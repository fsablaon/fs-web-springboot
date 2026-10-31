package org.fsweb.facade.dto;

import org.fsweb.facade.client.Stock;

import java.util.List;

/**
 * Response body of {@code GET /api/overview}: one field per downstream service. A field is
 * {@code null} when that service could not be reached, so the client can show the rest.
 *
 * @param visits current visit count from the visitor backend, or {@code null} if unavailable
 * @param stocks stock list from the stocks backend, or {@code null} if unavailable
 */
public record Overview(Long visits, List<Stock> stocks) {
}
