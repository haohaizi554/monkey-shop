package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws7DevOpsWorkflowTest {

    @Test
    void helmChartDefinesPrometheusRulesAndGrafanaDashboard() throws IOException {
        String values = read("helm/monkeyshop/values.yaml");
        String staging = read("helm/monkeyshop/values-staging.yaml");
        String prod = read("helm/monkeyshop/values-prod.yaml");

        assertThat(values)
                .contains("prometheusRule:")
                .contains("errorRate: 0.02")
                .contains("p99LatencySeconds: 1.5")
                .contains("grafanaDashboard:")
                .contains("sidecarLabel: grafana_dashboard");
        assertThat(staging).contains("prometheusRule:\n  enabled: true").contains("grafanaDashboard:\n  enabled: true");
        assertThat(prod).contains("prometheusRule:\n  enabled: true").contains("grafanaDashboard:\n  enabled: true");
    }

    @Test
    void prometheusRuleCoversGoldenSignalsAndBusinessMetrics() throws IOException {
        String prometheusRule = read("helm/monkeyshop/templates/prometheusrule.yaml");

        assertThat(prometheusRule)
                .contains("kind: PrometheusRule")
                .contains("MonkeyShopHighErrorRate")
                .contains("http_server_requests_seconds_count")
                .contains("http_server_requests_seconds_bucket")
                .contains("hikaricp_connections_active")
                .contains("hikaricp_connections_max")
                .contains("stock_deduct_fail_total")
                .contains("order_pending");
    }

    @Test
    void grafanaDashboardConnectsMetricsLogsAndTraces() throws IOException {
        String dashboard = read("helm/monkeyshop/templates/grafana-dashboard.yaml");

        assertThat(dashboard)
                .contains("kind: ConfigMap")
                .contains("Values.grafanaDashboard.sidecarLabel")
                .contains("HTTP RPS")
                .contains("HTTP P99 Latency")
                .contains("HTTP 5xx Error Rate")
                .contains("HikariCP Saturation")
                .contains("jvm_memory_used_bytes")
                .contains("order_created_total")
                .contains("stock_deduct_fail_total")
                .contains("order_create_seconds_bucket")
                .contains("order_pending")
                .contains("Audit trace API")
                .contains("/api/stats/audit-trace?traceId=${traceId}")
                .contains("Audit Events By TraceId")
                .contains("Tempo Trace Drilldown");
    }

    @Test
    void ws7VerifierRequiresRenderedMonitoringArtifacts() throws IOException {
        String script = read("scripts/verify-ws7-devops.ps1");

        assertThat(script)
                .contains("templates/prometheusrule.yaml")
                .contains("templates/grafana-dashboard.yaml")
                .contains("must render Prometheus alert rules")
                .contains("must render the Grafana dashboard ConfigMap")
                .contains("must render high error rate alerts")
                .contains("must render business metric dashboard panels");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
