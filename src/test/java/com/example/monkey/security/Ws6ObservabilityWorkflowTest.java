package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Ws6ObservabilityWorkflowTest {

    @Test
    void productionJavaUsesStructuredLoggingOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> forbiddenPatterns = List.of("System" + ".out", "System" + ".err", "print" + "StackTrace");

        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                for (String forbiddenPattern : forbiddenPatterns) {
                    if (content.contains(forbiddenPattern)) {
                        violations.add(normalized(path) + " contains " + forbiddenPattern);
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void runtimeConfigExposesMetricsTracingJsonLogsAndPiiSafeErrorCollection() throws IOException {
        String pom = read("pom.xml");
        String application = read("src/main/resources/application.yml");
        String prod = read("src/main/resources/application-prod.yml");
        String logback = read("src/main/resources/logback-spring.xml");

        assertThat(pom)
                .contains("spring-boot-starter-actuator")
                .contains("micrometer-registry-prometheus")
                .contains("logstash-logback-encoder")
                .contains("opentelemetry-spring-boot-starter")
                .contains("opentelemetry-instrumentation-annotations")
                .contains("sentry-spring-boot-starter-jakarta");
        assertThat(application)
                .contains("include: health,prometheus,loggers")
                .contains("show-values: NEVER")
                .contains("exporter: ${OTEL_TRACES_EXPORTER:none}")
                .contains("send-default-pii: false")
                .contains("retention-days: ${APP_AUDIT_RETENTION_DAYS:180}");
        assertThat(prod)
                .contains("show-sql: false")
                .contains("org.hibernate.SQL: WARN")
                .contains("show-values: NEVER")
                .contains("exporter: ${OTEL_TRACES_EXPORTER:otlp}")
                .contains("send-default-pii: false");
        assertThat(logback)
                .contains("net.logstash.logback.encoder.LogstashEncoder")
                .contains("<includeMdcKeyName>traceId</includeMdcKeyName>")
                .contains("<includeMdcKeyName>userId</includeMdcKeyName>")
                .contains("<maxFileSize>100MB</maxFileSize>")
                .contains("<maxHistory>30</maxHistory>")
                .contains("<totalSizeCap>10GB</totalSizeCap>")
                .contains("MaskingJsonGeneratorDecorator")
                .contains("<path>password</path>")
                .contains("<path>phone</path>")
                .contains("<path>addressSnapshot</path>");
    }

    @Test
    void orderFlowConnectsSpansMetricsAuditAndTraceLookup() throws IOException {
        String orderService = read("src/main/java/com/example/monkey/order/application/OrderService.java");
        String metricsService =
                read("src/main/java/com/example/monkey/order/application/observability/BusinessMetricsService.java");
        String auditService =
                read("src/main/java/com/example/monkey/shared/application/observability/AuditService.java");
        String statsController = read("src/main/java/com/example/monkey/admin/interfaces/StatsController.java");

        assertThat(orderService)
                .contains("@WithSpan(\"order.create\")")
                .contains("businessMetricsService.recordOrderCreate")
                .contains("businessMetricsService.recordOrderCreated")
                .contains("businessMetricsService.recordStockDeductFailure")
                .contains("AuditService.ORDER_CREATED")
                .contains("AuditService.ORDER_CREATE_FAILURE")
                .contains("AuditService.ORDER_RETURN_REQUESTED")
                .contains("AuditService.ORDER_REFUNDED")
                .contains("AuditService.OUTCOME_SUCCESS")
                .contains("AuditService.OUTCOME_FAILURE");
        assertThat(metricsService)
                .contains("Timer.builder(\"order.create\")")
                .contains("Counter.builder(\"order.created\")")
                .contains("Counter.builder(\"stock.deduct.fail\")")
                .contains("Gauge.builder(\"order.pending\"");
        assertThat(auditService)
                .contains("ORDER_CREATE_FAILURE")
                .contains("ORDER_RETURN_SHIPPED")
                .contains("ORDER_REFUNDED")
                .contains("findFirst50ByTraceId");
        assertThat(statsController)
                .contains("@GetMapping(\"/audit-trace\")")
                .contains("@PreAuthorize(\"hasAuthority('ADMIN_DASHBOARD_READ')\")")
                .contains("auditService.findByTraceId(request.traceId())");
    }

    @Test
    void auditTrailIsAsyncRetainedAndSanitized() throws IOException {
        String auditService =
                read("src/main/java/com/example/monkey/shared/application/observability/AuditService.java");
        String auditLog = read("src/main/java/com/example/monkey/shared/infrastructure/observability/AuditLog.java");

        assertThat(auditService)
                .contains("@Async(\"observabilityTaskExecutor\")")
                .contains("@Value(\"${app.audit.retention-days:180}\")")
                .contains("DEFAULT_RETENTION_DAYS = 180")
                .contains("SENSITIVE_DETAIL_PATTERN")
                .contains("hashSubject(subject)")
                .contains("sanitizeDetail(detail)")
                .contains("purgeExpiredAuditLogs()");
        assertThat(auditLog)
                .contains("@Table(name = \"audit_log\"")
                .contains("trace_id")
                .contains("subject_hash")
                .doesNotContain("subject_name");
    }

    @Test
    void metricConfigurationPublishesEveryHistogramQueriedByGrafana() throws IOException {
        String application = read("src/main/resources/application.yml");
        String grafanaDashboard = read("helm/monkeyshop/templates/grafana-dashboard.yaml");

        assertThat(application)
                .contains("percentiles-histogram:")
                .contains("http.server.requests: true")
                .contains("order.create: true");
        assertThat(grafanaDashboard)
                .contains("http_server_requests_seconds_bucket")
                .contains("order_create_seconds_bucket")
                .contains("order_total")
                .doesNotContain("order_created_total");
    }

    @Test
    void helmObservabilityDefinesAvailabilitySloBurnAlertsAndDashboard() throws IOException {
        String values = read("helm/monkeyshop/values.yaml");
        String prometheusRule = read("helm/monkeyshop/templates/prometheusrule.yaml");
        String grafanaDashboard = read("helm/monkeyshop/templates/grafana-dashboard.yaml");
        String docs = read("docs/observability/ws6.md");
        String readme = read("README.md");

        assertThat(values)
                .contains("targetAvailability: 0.999")
                .contains("errorBudgetRatio: 0.001")
                .contains("window: 30d")
                .contains("fast: 14.4")
                .contains("slow: 6")
                .contains("sloFastBurn: 2m")
                .contains("sloSlowBurn: 15m");
        assertThat(prometheusRule)
                .contains("MonkeyShopSloFastBurn")
                .contains("MonkeyShopSloSlowBurn")
                .contains("[5m]")
                .contains("[1h]")
                .contains("[30m]")
                .contains("[6h]")
                .contains(".Values.prometheusRule.slo.errorBudgetRatio")
                .contains(".Values.prometheusRule.slo.burnRates.fast")
                .contains(".Values.prometheusRule.slo.burnRates.slow")
                .contains("severity: critical")
                .contains("slo: availability");
        assertThat(grafanaDashboard)
                .contains("SLO Availability 30d")
                .contains("Error Budget Burn Rate")
                .contains("[30d]")
                .contains("5m burn rate")
                .contains("1h burn rate")
                .contains(".Values.prometheusRule.slo.targetAvailability")
                .contains(".Values.prometheusRule.slo.errorBudgetRatio");
        assertThat(docs)
                .contains("99.9% Availability SLO")
                .contains("0.001")
                .contains("MonkeyShopSloFastBurn")
                .contains("MonkeyShopSloSlowBurn");
        assertThat(readme).contains("99.9% over 30 days").contains("fast and slow burn-rate alerts");
    }

    @Test
    void ws6VerifierIsDocumentedAndRunsInCi() throws IOException {
        String verifier = read("scripts/verify-ws6-observability.ps1");
        String workflow = read(".github/workflows/ci.yaml");
        String readme = read("README.md");

        assertThat(verifier)
                .contains("spring-boot-starter-actuator")
                .contains("micrometer-registry-prometheus")
                .contains("logstash-logback-encoder")
                .contains("opentelemetry-spring-boot-starter")
                .contains("sentry-spring-boot-starter-jakarta")
                .contains("Assert-NoConsoleLogging")
                .contains("TraceIdFilter.java")
                .contains("UserMdcFilter.java")
                .contains("ObservabilityConfig.java")
                .contains("AuditService.java")
                .contains("BusinessMetricsService.java")
                .contains("servicemonitor.yaml")
                .contains("prometheusrule.yaml")
                .contains("grafana-dashboard.yaml")
                .contains("Maven WS6 tests");
        assertThat(workflow).contains(".\\scripts\\verify-ws6-observability.ps1");
        assertThat(readme).contains(".\\scripts\\verify-ws6-observability.ps1");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String normalized(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
