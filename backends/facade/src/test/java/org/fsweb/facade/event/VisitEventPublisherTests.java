package org.fsweb.facade.event;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publishes through the real, auto-configured KafkaTemplate to an embedded Kafka broker and reads
 * the record back as raw JSON. This pins down the wire format the visitor backend relies on.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "facade.visits.topic=" + VisitEventPublisherTests.TOPIC,
        "management.opentelemetry.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
@EmbeddedKafka(partitions = 1, topics = VisitEventPublisherTests.TOPIC)
class VisitEventPublisherTests {

    static final String TOPIC = "fs-web.visits.test";

    @Autowired
    private VisitEventPublisher publisher;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Test
    void publishesJsonVisitEventKeyedBySource() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(embeddedKafka, "publisher-test", false);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(consumerProps,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, TOPIC);

            VisitEvent published = publisher.publish("/api/hello");

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
            assertThat(record.key()).isEqualTo("facade");
            assertThat(record.value())
                    .contains("\"id\":\"" + published.id() + "\"")
                    .contains("\"source\":\"facade\"")
                    .contains("\"path\":\"/api/hello\"")
                    .contains("\"occurredAt\":\"" + published.occurredAt() + "\"");
            // No Java type header: the visitor backend deserializes into its own VisitEvent class
            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        }
    }
}
