package org.fsweb.facade.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.List;

/**
 * The stocks backend's REST API as an HTTP service interface. Registered by
 * {@code HttpServiceClientsConfig} in the {@code stocks} group and configured from
 * {@code spring.http.serviceclient.stocks.*}.
 *
 * <p>Methods throw {@link org.springframework.web.client.RestClientException} on transport or
 * HTTP errors (a {@code 404} for an unknown symbol included). {@link StocksClient} decides what
 * the facade does when that happens.
 */
@HttpExchange("/api/stocks")
public interface StocksApi {

    /** All stocks ({@code GET /api/stocks}). */
    @GetExchange
    List<Stock> all();

    /** One stock by symbol ({@code GET /api/stocks/{symbol}}). */
    @GetExchange("/{symbol}")
    Stock bySymbol(@PathVariable String symbol);
}
