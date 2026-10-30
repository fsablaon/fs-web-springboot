package org.fsweb.stocks.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.fsweb.stocks.dto.Stock;
import org.junit.jupiter.api.Test;

class StockServiceTest {

    private final StockService service = new StockService();

    @Test
    void findAllIsSortedBySymbol() {
        var symbols = service.findAll().stream().map(Stock::symbol).toList();
        assertThat(symbols).isNotEmpty().isSorted().doesNotHaveDuplicates();
    }

    @Test
    void findBySymbolIgnoresCaseAndWhitespace() {
        assertThat(service.findBySymbol(" aapl ")).map(Stock::name).contains("Apple Inc.");
    }

    @Test
    void findBySymbolIsEmptyForUnknownSymbol() {
        assertThat(service.findBySymbol("NOPE")).isEmpty();
    }
}
