package org.fsweb.stocks.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.fsweb.stocks.dto.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.RestClient;

/** Starts the app on a random port and calls /api/stocks over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.opentelemetry.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
class StockControllerTest {

    @Autowired
    private Environment environment;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port"))
                .defaultStatusHandler(status -> true, (request, response) -> { })   // assert on status in the tests
                .build();
    }

    @Test
    void listsAllStocks() {
        var response = client.get().uri("/api/stocks").retrieve()
                .toEntity(new ParameterizedTypeReference<List<Stock>>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(Stock::symbol).contains("AAPL", "MSFT");
    }

    @Test
    void returnsOneStockBySymbol() {
        var response = client.get().uri("/api/stocks/msft").retrieve().toEntity(Stock.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new Stock("MSFT", "Microsoft Corporation", new java.math.BigDecimal("512.40")));
    }

    @Test
    void unknownSymbolIs404Problem() {
        var response = client.get().uri("/api/stocks/NOPE").retrieve().toEntity(ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Stock not found: NOPE");
    }
}
