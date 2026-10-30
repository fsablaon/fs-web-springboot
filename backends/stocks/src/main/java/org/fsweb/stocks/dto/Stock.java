package org.fsweb.stocks.dto;

import java.math.BigDecimal;

/** A stock as returned by /api/stocks. */
public record Stock(String symbol, String name, BigDecimal price) {
}
