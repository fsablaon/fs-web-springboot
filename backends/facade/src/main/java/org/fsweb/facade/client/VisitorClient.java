package org.fsweb.facade.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.OptionalLong;

/**
 * Calls the visitor backend through {@link VisitorApi} and owns the facade's failure policy for
 * it: the count is informational, so errors are logged and reported as "unknown", not thrown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitorClient {

    private final VisitorApi visitorApi;

    /**
     * Reads the current visit count ({@code GET /api/visits}).
     *
     * @return the count, or empty if the visitor backend could not be reached or answered with
     *         an error
     */
    public OptionalLong currentCount() {
        try {
            VisitCount visitCount = visitorApi.current();
            return visitCount != null ? OptionalLong.of(visitCount.count()) : OptionalLong.empty();
        } catch (RestClientException e) {
            log.warn("Could not read the visit count from the visitor backend: {}", e.getMessage());
            return OptionalLong.empty();
        }
    }
}
