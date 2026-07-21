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
                .contains("order_total")
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
                .contains("must render business metric dashboard panels")
                .contains("APP_PII_VAULT_PREVIOUS_AES_CIPHERTEXTS")
                .contains("must source staging ExternalSecret data from the staging secret path")
                .contains("must render ExternalSecret remoteRefs for the target environment")
                .contains("must keep ExternalSecret data entries as separate YAML list items")
                .contains("must copy the executable jar into the runtime stage used by ENTRYPOINT");
    }

    @Test
    void ws7VerifierDockerfileLineChecksAreCrlfTolerant() throws IOException {
        String script = read("scripts/verify-ws7-devops.ps1");

        assertThat(script)
                .contains("maven:3\\.9-eclipse-temurin-21\\s+AS\\s+build\\r?$")
                .contains("eclipse-temurin:21-jre-jammy\\s+AS\\s+extract\\r?$")
                .contains("^USER\\s+app\\r?$");
    }

    @Test
    void ciRequiresHelmRenderedManifestEvidenceForWs7() throws IOException {
        String workflow = read(".github/workflows/ci.yaml");
        String script = read("scripts/verify-ws7-devops.ps1");

        assertThat(workflow)
                .contains(".\\scripts\\verify-ws7-devops.ps1 -RequireHelm -DownloadHelmIfMissing")
                .contains("Verify Kyverno supply-chain policies")
                .contains("ws7-rendered-manifests")
                .contains("target/ws7-devops/");
        assertThat(script)
                .contains("Helm is required but was not found.")
                .contains("helm lint")
                .contains("foreach ($environment in @(\"dev\", \"staging\", \"prod\"))")
                .contains("helm template $environment")
                .contains("monkeyshop-$environment.yaml")
                .contains("prod must render a digest-pinned image");
    }

    @Test
    void prodGitOpsDoesNotUseFloatingRevisionsOrDigestPlaceholders() throws IOException {
        String prod = read("helm/monkeyshop/values-prod.yaml");
        String podTemplate = read("helm/monkeyshop/templates/_pod.tpl");
        String workflow = read(".github/workflows/ci.yaml");
        String script = read("scripts/verify-ws7-devops.ps1");
        String prodApplication = read("deploy/argocd/applications/monkeyshop-prod.yaml");

        assertThat(prod)
                .contains("digest: \"\"")
                .contains("busybox@sha256:9532d8c39891ca2ecde4d30d7710e01fb739c87a8b9299685c63704296b16028")
                .doesNotContain("sha256:0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(podTemplate)
                .contains("image.digest is required for prod releases")
                .contains("^sha256:[a-f0-9]{64}$")
                .contains("^sha256:0{64}$");
        assertThat(workflow)
                .contains("contents: write")
                .contains("IMAGE_REPOSITORY: ${{ steps.image.outputs.uri }}")
                .contains("Validate production release inputs")
                .contains("Update production release manifests")
                .contains("sed -i -E")
                .contains("release_revision=\"$(git rev-parse HEAD)\"")
                .contains("deploy/argocd/applications/monkeyshop-prod.yaml")
                .contains("git push origin HEAD:main")
                .contains("[skip ci]");
        assertThat(script)
                .contains("ProductionImageDigestFixture")
                .contains("must not use all-zero digest placeholders")
                .contains("must reject an all-zero production app digest")
                .contains("targetRevision:\\s+[a-f0-9]{40}")
                .contains("targetRevision:\\s+(?:HEAD|main)");

        assertThat(prodApplication)
                .containsPattern("(?m)^\\s*targetRevision:\\s+[a-f0-9]{40}\\s*$")
                .doesNotContain("targetRevision: HEAD")
                .doesNotContain("targetRevision: main")
                .doesNotContain("targetRevision: 0000000000000000000000000000000000000000");

        for (String environment : new String[] {"dev", "staging"}) {
            String application = read("deploy/argocd/applications/monkeyshop-" + environment + ".yaml");
            assertThat(application).contains("targetRevision: main").doesNotContain("targetRevision: HEAD");
        }
    }

    @Test
    void runtimeSmokeVerifierCoversDeployedHealthHeadersTraceAndMetrics() throws IOException {
        String script = read("scripts/verify-runtime-smoke.ps1");
        String readme = read("README.md");

        assertThat(script)
                .contains("/actuator/health")
                .contains("/actuator/health/liveness")
                .contains("/actuator/health/readiness")
                .contains("/actuator/prometheus")
                .contains("X-Trace-Id")
                .contains("Content-Security-Policy")
                .contains("X-Frame-Options")
                .contains("Permissions-Policy")
                .contains("Cross-Origin-Opener-Policy")
                .contains("Cross-Origin-Resource-Policy")
                .contains("X-Permitted-Cross-Domain-Policies")
                .contains("Strict-Transport-Security")
                .contains("jvm_memory_used_bytes")
                .contains("http_server_requests_seconds_count")
                .contains("-RequireHttps")
                .contains("Runtime smoke gate completed successfully");
        assertThat(readme)
                .contains("verify-runtime-smoke.ps1")
                .contains("-BaseUrl http://localhost:8888")
                .contains("-RequireHttps");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
