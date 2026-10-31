package org.fsweb.facade.controller;

import org.fsweb.facade.event.VisitEvent;
import org.fsweb.facade.event.VisitEventProperties;
import org.fsweb.facade.event.VisitEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HelloController with a real VisitEventPublisher whose KafkaTemplate is a mock. */
@ExtendWith(MockitoExtension.class)
class HelloControllerTests {

    private static final String TOPIC = "visits-test";

    @Mock
    private KafkaTemplate<String, VisitEvent> kafkaTemplate;

    private HelloController controller;

    @BeforeEach
    void setUp() {
        VisitEventPublisher publisher = new VisitEventPublisher(kafkaTemplate, new VisitEventProperties(TOPIC, "facade"));
        controller = new HelloController(publisher);
    }

    @Test
    void helloPublishesOneVisitEvent() {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), any(VisitEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));

        assertThat(controller.hello("France")).isEqualTo("Hello, France!");

        ArgumentCaptor<VisitEvent> event = ArgumentCaptor.forClass(VisitEvent.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("facade"), event.capture());
        assertThat(event.getValue().id()).isNotBlank();
        assertThat(event.getValue().source()).isEqualTo("facade");
        assertThat(event.getValue().path()).isEqualTo("/api/hello");
        assertThat(event.getValue().occurredAt()).isNotNull();
    }

    @Test
    void helloStillAnswersWhenTheSendFails() {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), any(VisitEvent.class)))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        assertThat(controller.hello("World")).isEqualTo("Hello, World!");
    }

    @Test
    void helloStillAnswersWhenTheProducerThrows() {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), any(VisitEvent.class)))
                .thenThrow(new KafkaException("no producer"));

        assertThat(controller.hello("World")).isEqualTo("Hello, World!");
    }
}
