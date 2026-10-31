package org.fsweb.facade.config;

import org.fsweb.facade.client.StocksApi;
import org.fsweb.facade.client.VisitorApi;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Registers one HTTP service client group per downstream service the facade calls.
 *
 * <p>Each group gets its own {@code RestClient} (own request factory, connection pool and
 * timeouts), configured from {@code spring.http.serviceclient.<group>.*} in application.yml;
 * {@code spring.http.clients.*} supplies the defaults every group starts from. The builder each
 * group uses goes through Spring Boot's {@code RestClientCustomizer}s, so observation (tracing)
 * is applied the same way as for the auto-configured {@code RestClient.Builder}.
 *
 * <p>To call another service: add an {@code @HttpExchange} interface in {@code client/}, add an
 * {@code @ImportHttpServices} line here with a new group name, and add a
 * {@code spring.http.serviceclient.<group>} block to application.yml (and the {@code prod}
 * profile). Settings a property cannot express (interceptors, custom error handling, headers
 * computed at runtime) go in a {@code RestClientHttpServiceGroupConfigurer} bean keyed by group.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "visitor", types = VisitorApi.class)
@ImportHttpServices(group = "stocks", types = StocksApi.class)
public class HttpServiceClientsConfig {
}
