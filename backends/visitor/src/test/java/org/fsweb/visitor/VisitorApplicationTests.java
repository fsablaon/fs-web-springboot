package org.fsweb.visitor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Docker Compose support is skipped in tests by default, so no containers are started.
 * OpenTelemetry and OTLP metrics export are turned off so the test does not try to
 * reach a collector. Redis connects lazily, so the context loads without a Redis server.
 * The Kafka listener and topic creation are turned off so the context does not wait for a broker;
 * {@code VisitEventListenerTests} covers them with an embedded broker.
 */
@SpringBootTest(properties = {
        "management.opentelemetry.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
class VisitorApplicationTests {

    @Test
    void contextLoads() {
    }

}
