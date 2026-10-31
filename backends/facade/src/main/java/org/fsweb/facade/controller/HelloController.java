package org.fsweb.facade.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fsweb.facade.event.VisitEventPublisher;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HelloController {

    private final VisitEventPublisher visitEventPublisher;

    /**
     * Greets {@code name}, or the signed-in user ({@code preferred_username} from the access
     * token) when no name is given. Requires a bearer token (SecurityConfig).
     */
    @GetMapping("/hello")
    public String hello(@RequestParam(required = false) String name, @AuthenticationPrincipal Jwt jwt) {
        String greeted = StringUtils.hasText(name) ? name : usernameOf(jwt);
        log.debug("Hello endpoint called with name: {} (user: {})", name, jwt != null ? jwt.getSubject() : null);
        // Each greeting counts as a visit: publish an event for the visitor backend.
        // The greeting is returned even if Kafka is down (the publisher only logs a warning).
        visitEventPublisher.publish("/api/hello");
        return "Hello, " + greeted + "!";
    }

    private static String usernameOf(Jwt jwt) {
        String username = jwt != null ? jwt.getClaimAsString("preferred_username") : null;
        return StringUtils.hasText(username) ? username : "World";
    }

}
