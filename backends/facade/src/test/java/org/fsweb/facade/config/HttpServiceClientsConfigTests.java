package org.fsweb.facade.config;

import org.fsweb.facade.client.StocksApi;
import org.fsweb.facade.client.StocksClient;
import org.fsweb.facade.client.VisitorApi;
import org.fsweb.facade.client.VisitorClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code visitor} and {@code stocks} groups are registered and each picks up its own block in
 * application.yml. Nothing is called over HTTP here; that is covered by OverviewControllerTests.
 */
@SpringBootTest(properties = {
        "management.opentelemetry.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
class HttpServiceClientsConfigTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private HttpServiceClientProperties serviceClientProperties;

    @Test
    void groupInterfacesAreBeansAndInjectedIntoTheirClients() {
        assertThat(context.getBean(VisitorApi.class)).isNotNull();
        assertThat(context.getBean(StocksApi.class)).isNotNull();
        assertThat(context.getBean(VisitorClient.class)).isNotNull();
        assertThat(context.getBean(StocksClient.class)).isNotNull();
    }

    @Test
    void eachGroupBindsItsOwnSettingsFromApplicationYml() {
        var visitor = serviceClientProperties.get("visitor");
        var stocks = serviceClientProperties.get("stocks");

        assertThat(visitor.getBaseUrl()).isEqualTo("http://localhost:8081");
        assertThat(visitor.getReadTimeout()).isEqualTo(Duration.ofSeconds(1));

        assertThat(stocks.getBaseUrl()).isEqualTo("http://localhost:8082");
        assertThat(stocks.getReadTimeout()).isEqualTo(Duration.ofSeconds(3));
    }
}
