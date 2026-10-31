package org.fsweb.visitor.event;

import org.apache.kafka.common.serialization.StringSerializer;
import org.fsweb.visitor.service.VisitCounterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sends records to an embedded Kafka broker the way the facade backend does (plain JSON, key = source,
 * no type header) and checks that the listener counts them. Redis is replaced by a mock of
 * {@link VisitCounterService}.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "visitor.visits.topic=" + VisitEventListenerTests.TOPIC,
        "management.opentelemetry.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
@EmbeddedKafka(partitions = 1, topics = VisitEventListenerTests.TOPIC)
class VisitEventListenerTests {

    static final String TOPIC = "fs-web.visits.test";

    private static final String FACADE_JSON = """
            {"id":"%s","source":"facade","path":"/api/hello","occurredAt":"2026-10-30T20:15:30.123456Z"}""";

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @MockitoBean
    private VisitCounterService visitCounterService;

    private KafkaTemplate<String, String> facade;

    @BeforeEach
    void setUp() {
        // Wait until the listener has its partition, so the records below are not consumed before it is ready
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafka);
        facade = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps,
                new StringSerializer(), new StringSerializer()));
    }

    @AfterEach
    void tearDown() {
        facade.destroy();
    }

    @Test
    void countsOneVisitPerEvent() {
        when(visitCounterService.increment()).thenReturn(1L, 2L, 3L);

        facade.send(TOPIC, "facade", FACADE_JSON.formatted("e1"));
        facade.send(TOPIC, "facade", FACADE_JSON.formatted("e2"));
        facade.send(TOPIC, "facade", FACADE_JSON.formatted("e3"));

        verify(visitCounterService, timeout(15_000).times(3)).increment();
    }

    @Test
    void skipsRecordsThatAreNotValidJson() {
        when(visitCounterService.increment()).thenReturn(1L);

        facade.send(TOPIC, "facade", "this is not json");
        facade.send(TOPIC, "facade", FACADE_JSON.formatted("e4"));

        // The bad record is logged and skipped; the next one is still counted
        verify(visitCounterService, timeout(15_000).times(1)).increment();
    }
}
