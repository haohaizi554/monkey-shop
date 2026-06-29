package com.example.monkey.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ObservabilityConfigTest {

    @Test
    void logbackUsesJsonEncodingWithTraceAndUserMdcAndRetention() throws IOException {
        String logback = Files.readString(Path.of("src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);

        assertThat(logback).contains("net.logstash.logback.encoder.LogstashEncoder");
        assertThat(logback).contains("<includeMdcKeyName>traceId</includeMdcKeyName>");
        assertThat(logback).contains("<includeMdcKeyName>userId</includeMdcKeyName>");
        assertThat(logback).contains("<maxFileSize>100MB</maxFileSize>");
        assertThat(logback).contains("<maxHistory>30</maxHistory>");
        assertThat(logback).contains("<totalSizeCap>10GB</totalSizeCap>");
        assertThat(logback).contains("MaskingJsonGeneratorDecorator");
    }

    @Test
    void applicationExposesPrometheusAndKeepsObservabilityPiiOffByDefault() throws IOException {
        String application = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);

        assertThat(application).contains("include: health,prometheus,loggers");
        assertThat(application).contains("show-values: NEVER");
        assertThat(application).contains("send-default-pii: false");
        assertThat(application).contains("exporter: ${OTEL_TRACES_EXPORTER:none}");
        assertThat(application).contains("retention-days: ${APP_AUDIT_RETENTION_DAYS:180}");
        assertThat(prod).contains("exporter: ${OTEL_TRACES_EXPORTER:otlp}");
        assertThat(prod).contains("send-default-pii: false");
    }

    @Test
    void asyncExecutorIsExternalized() {
        ObservabilityConfig config = new ObservabilityConfig();

        assertThat(config.observabilityTaskExecutor(1, 2, 3)).isNotNull();
    }
}
