package org.fsweb.facade.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fsweb.facade.client.StocksClient;
import org.fsweb.facade.client.VisitorClient;
import org.fsweb.facade.dto.Overview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.OptionalLong;

/**
 * {@code GET /api/overview}: the visit count and the stock list in one response, read from the
 * visitor and stocks backends through their HTTP service clients. Each downstream is optional;
 * an unreachable one shows up as {@code null} and the endpoint still answers 200.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OverviewController {

    private final VisitorClient visitorClient;
    private final StocksClient stocksClient;

    @GetMapping("/overview")
    public Overview overview() {
        log.debug("Overview endpoint called");
        OptionalLong visits = visitorClient.currentCount();
        return new Overview(visits.isPresent() ? visits.getAsLong() : null, stocksClient.all().orElse(null));
    }
}
