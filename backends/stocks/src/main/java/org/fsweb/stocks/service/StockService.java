package org.fsweb.stocks.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import org.fsweb.stocks.dto.Stock;
import org.springframework.stereotype.Service;

/**
 * In-memory stock list. A placeholder for a real data source (market-data API or a database):
 * replace the map with a repository/client and keep the public methods.
 */
@Slf4j
@Service
public class StockService {

    private final Map<String, Stock> stocks = new ConcurrentHashMap<>();

    public StockService() {
        Stream.of(
                new Stock("AAPL", "Apple Inc.", new BigDecimal("255.10")),
                new Stock("MSFT", "Microsoft Corporation", new BigDecimal("512.40")),
                new Stock("GOOGL", "Alphabet Inc.", new BigDecimal("248.75")),
                new Stock("AMZN", "Amazon.com, Inc.", new BigDecimal("224.30")),
                new Stock("NVDA", "NVIDIA Corporation", new BigDecimal("187.60")),
                new Stock("TSLA", "Tesla, Inc.", new BigDecimal("434.20"))
        ).forEach(stock -> stocks.put(stock.symbol(), stock));
        log.info("Loaded {} in-memory stocks", stocks.size());
    }

    /** All stocks, ordered by symbol. */
    public List<Stock> findAll() {
        return stocks.values().stream()
                .sorted(Comparator.comparing(Stock::symbol))
                .toList();
    }

    /** One stock by symbol (case-insensitive), or empty if unknown. */
    public Optional<Stock> findBySymbol(String symbol) {
        return Optional.ofNullable(stocks.get(normalize(symbol)));
    }

    static String normalize(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
