package org.fsweb.facade.controller;

import org.fsweb.facade.client.StocksApi;
import org.fsweb.facade.client.StocksClient;
import org.fsweb.facade.client.VisitorApi;
import org.fsweb.facade.client.VisitorClient;
import org.fsweb.facade.dto.Overview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OverviewController with real clients and real {@link VisitorApi} / {@link StocksApi} proxies
 * whose HTTP calls go to two mock backends, one per group. The proxies are built the same way
 * Spring builds them at startup (RestClient + HttpServiceProxyFactory), without Boot's property
 * binding; that part is covered by HttpServiceClientsConfigTests.
 */
class OverviewControllerTests {

    private static final String STOCKS_JSON = """
            [{"symbol":"AAPL","name":"Apple Inc.","price":189.5}]
            """;

    private MockRestServiceServer visitorBackend;
    private MockRestServiceServer stocksBackend;
    private OverviewController controller;

    @BeforeEach
    void setUp() {
        RestClient.Builder visitorBuilder = RestClient.builder().baseUrl("http://visitor.test");
        visitorBackend = MockRestServiceServer.bindTo(visitorBuilder).build();
        VisitorApi visitorApi = proxy(visitorBuilder, VisitorApi.class);

        RestClient.Builder stocksBuilder = RestClient.builder().baseUrl("http://stocks.test");
        stocksBackend = MockRestServiceServer.bindTo(stocksBuilder).build();
        StocksApi stocksApi = proxy(stocksBuilder, StocksApi.class);

        controller = new OverviewController(new VisitorClient(visitorApi), new StocksClient(stocksApi));
    }

    private static <T> T proxy(RestClient.Builder builder, Class<T> api) {
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(api);
    }

    @Test
    void overviewCombinesBothBackends() {
        visitorBackend.expect(requestTo("http://visitor.test/api/visits"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"count\":42}", MediaType.APPLICATION_JSON));
        stocksBackend.expect(requestTo("http://stocks.test/api/stocks"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(STOCKS_JSON, MediaType.APPLICATION_JSON));

        Overview overview = controller.overview();

        assertThat(overview.visits()).isEqualTo(42L);
        assertThat(overview.stocks()).hasSize(1);
        assertThat(overview.stocks().getFirst().symbol()).isEqualTo("AAPL");
        assertThat(overview.stocks().getFirst().price()).isEqualByComparingTo(new BigDecimal("189.5"));
        visitorBackend.verify();
        stocksBackend.verify();
    }

    @Test
    void visitorDownLeavesVisitsNullAndKeepsStocks() {
        visitorBackend.expect(requestTo("http://visitor.test/api/visits")).andRespond(withServerError());
        stocksBackend.expect(requestTo("http://stocks.test/api/stocks"))
                .andRespond(withSuccess(STOCKS_JSON, MediaType.APPLICATION_JSON));

        Overview overview = controller.overview();

        assertThat(overview.visits()).isNull();
        assertThat(overview.stocks()).hasSize(1);
    }

    @Test
    void stocksDownLeavesStocksNullAndKeepsVisits() {
        visitorBackend.expect(requestTo("http://visitor.test/api/visits"))
                .andRespond(withSuccess("{\"count\":7}", MediaType.APPLICATION_JSON));
        stocksBackend.expect(requestTo("http://stocks.test/api/stocks")).andRespond(withServerError());

        Overview overview = controller.overview();

        assertThat(overview.visits()).isEqualTo(7L);
        assertThat(overview.stocks()).isNull();
    }

    @Test
    void bothDownStillAnswers() {
        visitorBackend.expect(requestTo("http://visitor.test/api/visits")).andRespond(withServerError());
        stocksBackend.expect(requestTo("http://stocks.test/api/stocks")).andRespond(withServerError());

        Overview overview = controller.overview();

        assertThat(overview.visits()).isNull();
        assertThat(overview.stocks()).isNull();
    }
}
