package org.fsweb.facade.client;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * The visitor backend's REST API as an HTTP service interface. Spring builds the implementation
 * at startup; it is registered by {@code HttpServiceClientsConfig} in the {@code visitor} group
 * and uses the {@code spring.http.serviceclient.visitor.*} settings (base URL, timeouts).
 *
 * <p>Methods throw {@link org.springframework.web.client.RestClientException} on transport or
 * HTTP errors. {@link VisitorClient} decides what the facade does when that happens.
 */
@HttpExchange("/api/visits")
public interface VisitorApi {

    /** Reads the current visit count ({@code GET /api/visits}) without changing it. */
    @GetExchange
    VisitCount current();
}
