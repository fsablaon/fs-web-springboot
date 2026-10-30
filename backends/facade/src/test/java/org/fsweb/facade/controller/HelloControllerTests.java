package org.fsweb.facade.controller;

import org.fsweb.facade.client.VisitorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** HelloController with a real VisitorClient whose HTTP calls go to a mock visitor backend. */
class HelloControllerTests {

    private MockRestServiceServer visitorBackend;
    private VisitorClient visitorClient;
    private HelloController controller;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://visitor.test");
        visitorBackend = MockRestServiceServer.bindTo(builder).build();
        visitorClient = new VisitorClient(builder.build());
        controller = new HelloController(visitorClient);
    }

    @Test
    void helloRecordsOneVisit() {
        visitorBackend.expect(requestTo("http://visitor.test/api/visits"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"count\":42}", MediaType.APPLICATION_JSON));

        assertThat(controller.hello("France")).isEqualTo("Hello, France!");

        visitorBackend.verify();
    }

    @Test
    void helloStillAnswersWhenVisitorBackendFails() {
        visitorBackend.expect(requestTo("http://visitor.test/api/visits"))
                .andRespond(withServerError());

        assertThat(controller.hello("World")).isEqualTo("Hello, World!");

        visitorBackend.verify();
    }

    @Test
    void recordVisitReturnsNewCount() {
        visitorBackend.expect(requestTo("http://visitor.test/api/visits"))
                .andRespond(withSuccess("{\"count\":7}", MediaType.APPLICATION_JSON));

        assertThat(visitorClient.recordVisit()).hasValue(7L);
    }

    @Test
    void recordVisitReturnsEmptyOnError() {
        visitorBackend.expect(requestTo("http://visitor.test/api/visits"))
                .andRespond(withServerError());

        assertThat(visitorClient.recordVisit()).isEmpty();
    }
}
