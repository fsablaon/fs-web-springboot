package org.fsweb.facade.client;

import java.math.BigDecimal;

/** A stock as returned by the stocks backend's {@code /api/stocks}. */
public record Stock(String symbol, String name, BigDecimal price) {
}
