package org.fsweb.stocks.exception;

public class StockNotFoundException extends RuntimeException {

    public StockNotFoundException(String symbol) {
        super("Stock not found: " + symbol);
    }
}
