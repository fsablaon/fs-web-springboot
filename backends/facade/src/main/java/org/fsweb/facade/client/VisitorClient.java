package org.fsweb.facade.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.OptionalLong;

/** Calls the visitor backend's REST API. */
@Slf4j
@Component
public class VisitorClient {

    private final RestClient restClient;

    public VisitorClient(RestClient visitorRestClient) {
        this.restClient = visitorRestClient;
    }

    /**
     * Increments the visit counter ({@code POST /api/visits}).
     *
     * @return the new count, or empty if the visitor backend could not be reached or answered
     *         with an error; the caller decides what to do without the count
     */
    public OptionalLong recordVisit() {
        try {
            VisitCount visitCount = restClient.post()
                    .uri("/api/visits")
                    .retrieve()
                    .body(VisitCount.class);
            long count = visitCount != null ? visitCount.count() : 0L;
            log.debug("Visitor backend recorded visit, count is now {}", count);
            return OptionalLong.of(count);
        } catch (RestClientException e) {
            log.warn("Could not record visit in the visitor backend: {}", e.getMessage());
            return OptionalLong.empty();
        }
    }
}
