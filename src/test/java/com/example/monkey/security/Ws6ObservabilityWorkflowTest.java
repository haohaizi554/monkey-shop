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

    @Test
    void localObservabilityStackIsDockerlessPinnedAndVerifiable() throws IOException {
        List<String> requiredFiles = List.of(
                "scripts/bootstrap-local-observability.ps1",
                "scripts/start-local-observability.ps1",
                "scripts/status-local-observability.ps1",
                "scripts/stop-local-observability.ps1",
                "scripts/verify-local-observability.ps1",
                "ops/local/observability/otel-collector.yml",
                "ops/local/observability/prometheus.yml",
                "ops/local/observability/loki.yml",
                "ops/local/observability/tempo.yml",
                "ops/local/observability/grafana/provisioning/datasources/monkeyshop.yml",
                "ops/local/observability/grafana/provisioning/dashboards/monkeyshop.yml");

        assertThat(requiredFiles).allSatisfy(path -> assertThat(Path.of(path)).exists());

        String bootstrap = read("scripts/bootstrap-local-observability.ps1");
        String start = read("scripts/start-local-observability.ps1");
        String startLocal = read("scripts/start-local.ps1");
        String status = read("scripts/status-local-observability.ps1");
        String stop = read("scripts/stop-local-observability.ps1");
        String stopLocal = read("scripts/stop-local.ps1");
        String verify = read("scripts/verify-local-observability.ps1");
        String collector = read("ops/local/observability/otel-collector.yml");
        String prometheus = read("ops/local/observability/prometheus.yml");
        String loki = read("ops/local/observability/loki.yml");
        String tempo = read("ops/local/observability/tempo.yml");
        String dataSources = read("ops/local/observability/grafana/provisioning/datasources/monkeyshop.yml");

        assertThat(bootstrap)
                .contains("0.153.0")
                .contains("3.12.0")
                .contains("3.7.2")
                .contains("2.10.5")
                .contains("13.1.1")
                .contains("opentelemetry-collector-releases_otelcol-contrib_windows_checksums.txt")
                .contains("Get-FileHash")
                .contains("SHA256")
                .contains("\\s+")
                .contains("\\b[0-9a-f]{64}\\b")
                .doesNotContain("\\\\s")
                .doesNotContain("\\\\b")
                .contains("Expand-Archive")
                .doesNotContain("docker");
        assertThat(start)
                .contains("otelcol-contrib.exe")
                .contains("prometheus.exe")
                .contains("loki-windows-amd64.exe")
                .contains("tempo.exe")
                .contains("grafana.exe")
                .contains("New-LocalRuntimeServiceRecord")
                .contains("Save-LocalObservabilityState")
                .contains("RandomNumberGenerator]::Create()")
                .contains("GetBytes($bytes)")
                .contains("$process.HasExited")
                .contains("exited before becoming ready")
                .contains("GF_ANALYTICS_CHECK_FOR_UPDATES")
                .contains("GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES")
                .contains("GF_PLUGINS_PREINSTALL_DISABLED")
                .contains("GF_PLUGINS_PLUGIN_ADMIN_ENABLED")
                .contains("alerting", "plugins")
                .contains("--web.enable-remote-write-receiver")
                .doesNotContain("RandomNumberGenerator]::Fill")
                .doesNotContain("grafana-server.exe")
                .doesNotContain("docker");
        assertThat(startLocal)
                .contains("[switch]$WithObservability")
                .contains("start-local-observability.ps1")
                .contains("OTEL_TRACES_EXPORTER = \"otlp\"")
                .contains("OTEL_EXPORTER_OTLP_ENDPOINT = \"http://127.0.0.1:4318\"")
                .contains("OTEL_TRACES_EXPORTER = \"none\"");
        assertThat(startLocal.indexOf("$requiredEnvironment"))
                .isLessThan(startLocal.indexOf("start-local-observability.ps1"));
        assertThat(status)
                .contains("http://127.0.0.1:13133/")
                .contains("http://127.0.0.1:9090/-/ready")
                .contains("http://127.0.0.1:3100/ready")
                .contains("http://127.0.0.1:3200/ready")
                .contains("http://127.0.0.1:3000/api/health");
        assertThat(stop)
                .contains("grafana", "prometheus", "otelCollector", "loki", "tempo")
                .contains("Test-LocalRuntimeProcessIdentity")
                .contains("AddSeconds(10)")
                .contains("Start-Sleep -Milliseconds 200");
        assertThat(stopLocal).contains("AddSeconds(10)").contains("Start-Sleep -Milliseconds 200");
        assertThat(verify)
                .contains("api/v1/targets")
                .contains("api/v1/query")
                .contains("loki/api/v1/query_range")
                .contains("api/search")
                .contains("trace:id")
                .contains("traces_spanmetrics_calls_total")
                .contains("api/datasources")
                .contains("X-Trace-Id")
                .contains("traceId")
                .contains("traceparent")
                .contains("http.server.request")
                .contains("+ $traceId +")
                .doesNotContain("-f $traceId")
                .doesNotContain("/v1/traces");
        assertThat(collector)
                .contains("otlp:")
                .contains("filelog/monkeyshop:")
                .contains("otlp/tempo:")
                .contains("otlphttp/loki:")
                .contains("health_check:")
                .contains("metrics:\n      readers:")
                .contains("host: 127.0.0.1")
                .contains("port: 18888")
                .doesNotContain("port: 8888");
        assertThat(prometheus)
                .contains("127.0.0.1:8888")
                .contains("/actuator/prometheus")
                .contains("127.0.0.1:8889");
        assertThat(loki)
                .contains("auth_enabled: false")
                .contains("instance_addr: 127.0.0.1")
                .contains("retention_period: 4320h")
                .contains("allow_structured_metadata: true");
        assertThat(tempo)
                .contains("http:\n          endpoint: 127.0.0.1:14318")
                .contains("grpc:\n          endpoint: 127.0.0.1:14317")
                .doesNotContain("http: 127.0.0.1:14318")
                .doesNotContain("grpc: 127.0.0.1:14317")
                .contains("backend: local")
                .contains("metrics_generator:")
                .contains("processors: [service-graphs, span-metrics]")
                .contains("api/v1/write");
        assertThat(dataSources)
                .contains("type: prometheus")
                .contains("type: loki")
                .contains("type: tempo")
                .contains("derivedFields:")
                .contains("tracesToLogsV2:")
                .contains("tracesToMetrics:");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String normalized(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
