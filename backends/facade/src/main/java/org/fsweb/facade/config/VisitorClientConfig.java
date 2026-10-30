package org.fsweb.facade.config;

import org.fsweb.facade.client.VisitorProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(VisitorProperties.class)
public class VisitorClientConfig {

    /**
     * RestClient for the visitor backend. Built from Spring Boot's RestClient.Builder, which has
     * observation (tracing) already applied, so the trace context is propagated to the visitor
     * backend and both spans show up in one trace in Grafana.
     */
    @Bean
    RestClient visitorRestClient(RestClient.Builder builder, VisitorProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
