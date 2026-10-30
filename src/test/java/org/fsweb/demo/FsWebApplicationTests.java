package org.fsweb.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Docker Compose support is skipped in tests by default, so no containers are started.
 * OpenTelemetry and OTLP metrics export are turned off so the test does not try to
 * reach a collector. Redis connects lazily, so the context loads without a Redis server.
 */
@SpringBootTest(properties = {
        "management.opentelemetry.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
class FsWebApplicationTests {

    @Test
    void contextLoads() {
    }

}
