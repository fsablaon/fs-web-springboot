package org.fsweb.demo.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Connects the Logback {@code OTEL} appender (see logback-spring.xml) to the
 * auto-configured OpenTelemetry SDK so log events are exported over OTLP.
 */
@Component
class OpenTelemetryLogbackConfig implements InitializingBean {

    private final ObjectProvider<OpenTelemetry> openTelemetry;

    OpenTelemetryLogbackConfig(ObjectProvider<OpenTelemetry> openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        openTelemetry.ifAvailable(OpenTelemetryAppender::install);
    }
}
