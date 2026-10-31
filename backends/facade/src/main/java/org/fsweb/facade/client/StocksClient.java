package org.fsweb.facade.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * Calls the stocks backend through {@link StocksApi} and owns the facade's failure policy for
 * it: a missing stock list is reported as empty, never as an exception to the caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StocksClient {

    private final StocksApi stocksApi;

    /**
     * Reads the stock list ({@code GET /api/stocks}).
     *
     * @return the list, or empty if the stocks backend could not be reached or answered with an
     *         error. An empty {@code Optional} is "unknown"; an empty list inside it is "none".
     */
    public Optional<List<Stock>> all() {
        try {
            List<Stock> stocks = stocksApi.all();
            return Optional.of(stocks != null ? stocks : List.of());
        } catch (RestClientException e) {
            log.warn("Could not read the stock list from the stocks backend: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
