package org.fsweb.facade.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Where the facade finds the visitor backend ({@code facade.visitor.*} in application.yml).
 *
 * @param baseUrl        visitor backend base URL, e.g. {@code http://localhost:8081}
 * @param connectTimeout how long to wait for a TCP connection
 * @param readTimeout    how long to wait for the response; kept short because /api/hello waits for it
 */
@ConfigurationProperties("facade.visitor")
public record VisitorProperties(
        @DefaultValue("http://localhost:8081") URI baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout) {
}
