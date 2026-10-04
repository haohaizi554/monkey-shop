package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    void sharedEnvironmentsUseRedisForDistributedImageReferences() throws IOException {
        String staging = read("helm/monkeyshop/values-staging.yaml");
        String prod = read("helm/monkeyshop/values-prod.yaml");
        String stagingApplication = read("src/main/resources/application-staging.yml");
        String prodApplication = read("src/main/resources/application-prod.yml");
        String configMap = read("helm/monkeyshop/templates/configmap.yaml");
        String verifier = read("scripts/verify-ws7-devops.ps1");

        assertThat(prod)
                .containsPattern("(?m)^replicaCount:\\s*[2-9]\\d*\\s*$")
                .containsPattern("(?m)^\\s+APP_IMAGE_REFERENCE_PROVIDER:\\s+redis\\s*$")
                .containsPattern("(?m)^\\s+APP_RISK_REQUIRE_REDIS_STATE:\\s+\"?true\"?\\s*$")
                .doesNotContainPattern("(?m)^\\s+APP_IMAGE_REFERENCE_PROVIDER:\\s+memory\\s*$");
        assertThat(staging)
                .containsPattern("(?m)^\\s+APP_IMAGE_REFERENCE_PROVIDER:\\s+redis\\s*$")
                .containsPattern("(?m)^\\s+APP_RISK_REQUIRE_REDIS_STATE:\\s+\"?true\"?\\s*$")
                .doesNotContainPattern("(?m)^\\s+APP_IMAGE_REFERENCE_PROVIDER:\\s+memory\\s*$");
        assertThat(prodApplication)
                .containsPattern("(?s)image-reference:\\s*\\n\\s+provider:\\s+\\$\\{APP_IMAGE_REFERENCE_PROVIDER:redis}")
                .doesNotContain("APP_IMAGE_REFERENCE_PROVIDER:memory");
        assertThat(stagingApplication)
                .containsPattern("(?s)image-reference:\\s*\\n\\s+provider:\\s+\\$\\{APP_IMAGE_REFERENCE_PROVIDER:redis}")
                .doesNotContain("APP_IMAGE_REFERENCE_PROVIDER:memory");
        assertThat(prodApplication)
                .containsPattern("(?s)risk:\\s*\\n\\s+require-redis-state:\\s+\\$\\{APP_RISK_REQUIRE_REDIS_STATE:true}");
        assertThat(stagingApplication)
                .containsPattern("(?s)risk:\\s*\\n\\s+require-redis-state:\\s+\\$\\{APP_RISK_REQUIRE_REDIS_STATE:true}");
        assertThat(configMap)
                .contains("range $key, $value := .Values.config")
                .contains("{{ $key }}: {{ $value | quote }}");
        assertThat(verifier)
                .contains("must route multi-replica prod image-reference state through Redis")
                .contains("must not fall back to an in-memory image-reference provider")
                .contains("must route staging image-reference state through Redis")
                .contains("must require shared Redis for risk state in prod")
                .contains("must require shared Redis for risk state in staging")
                .contains("must render shared Redis risk state in shared environments");
    }

    @Test
    void imageReferenceMigrationUsesRecreateDeploymentAndDisablesCanarySource() throws IOException {
        String values = read("helm/monkeyshop/values.yaml");
        String staging = read("helm/monkeyshop/values-staging.yaml");
        String prod = read("helm/monkeyshop/values-prod.yaml");
        String deployment = read("helm/monkeyshop/templates/deployment.yaml");
        String rollout = read("helm/monkeyshop/templates/rollout.yaml");
        String hpa = read("helm/monkeyshop/templates/hpa.yaml");

        assertThat(values).containsPattern("(?s)imageReferenceProtocol:\\s*\\n\\s+migrationMode:\\s+false");
        assertThat(staging).containsPattern("(?s)imageReferenceProtocol:\\s*\\n\\s+migrationMode:\\s+true");
        assertThat(prod).containsPattern("(?s)imageReferenceProtocol:\\s*\\n\\s+migrationMode:\\s+true");
        assertThat(deployment)
                .contains(".Values.imageReferenceProtocol.migrationMode")
                .containsPattern("(?s)strategy:\\s*\\n\\s+type:\\s+Recreate")
                .containsPattern("(?s)revisionHistoryLimit:\\s+0");
        assertThat(rollout)
                .contains("not .Values.imageReferenceProtocol.migrationMode")
                .contains("kind: Rollout");
        assertThat(hpa)
                .contains("not .Values.imageReferenceProtocol.migrationMode")
                .containsPattern("apiVersion:\\s+apps/v1")
                .containsPattern("kind:\\s+Deployment");
    }

    @Test
    void imageReferenceMigrationRendersDeploymentForStagingAndProdWhenHelmIsAvailable() throws Exception {
        String helm = findHelm();
        assumeTrue(helm != null, "Helm is required for the rendered migration contract");

        for (String environment : new String[] {"staging", "prod"}) {
            String migrationManifest = renderHelm(helm, environment, null);
            assertThat(migrationManifest)
                    .containsPattern("(?s)kind:\\s+Deployment[\\s\\S]+strategy:\\s*\\r?\\n\\s+type:\\s+Recreate")
                    .containsPattern("(?s)kind:\\s+Deployment[\\s\\S]+revisionHistoryLimit:\\s+0")
                    .containsPattern(
                            "(?s)scaleTargetRef:\\s*\\r?\\n\\s+apiVersion:\\s+apps/v1\\s*\\r?\\n\\s+kind:\\s+Deployment")
                    .containsPattern("(?m)^\\s+APP_IMAGE_REFERENCE_PROVIDER:\\s+\\\"?redis\\\"?\\s*$")
                    .doesNotContain("kind: Rollout")
                    .doesNotContain("canary:");

            String normalManifest = renderHelm(helm, environment, false);
            assertThat(normalManifest)
                    .contains("kind: Rollout")
                    .contains("strategy:")
                    .contains("canary:")
                    .containsPattern(
                            "(?s)scaleTargetRef:\\s*\\r?\\n\\s+apiVersion:\\s+argoproj.io/v1alpha1\\s*\\r?\\n\\s+kind:\\s+Rollout");
        }
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
    void ws7VerifierClearsExpectedNativeFailureExitCodeOnSuccess() throws IOException {
        String script = read("scripts/verify-ws7-devops.ps1");

        assertThat(script)
                .contains("Assert-HelmFailure")
                .contains("WS7 DevOps gate completed successfully.")
                .contains("$global:LASTEXITCODE = 0");
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
                .contains("prod must render the committed GHCR image by digest");
    }

    @Test
    void ws7VerifierFailsClosedUnlessStaticOnlyIsExplicit() throws IOException {
        String script = read("scripts/verify-ws7-devops.ps1");

        assertThat(script)
                .contains("[switch]$StaticOnly")
                .contains("if ($StaticOnly)")
                .contains("StaticOnly requested; rendered manifest checks were skipped.")
                .doesNotContain("Static WS7 checks ran; rendered manifest checks were skipped.");
    }

    @Test
    void missingHelmFailsClosedWithoutDownload() throws Exception {
        String powershell = findPowerShell();
        assumeTrue(powershell != null, "PowerShell is required for the executable WS7 Helm check");

        Path missingHelm = Path.of("target", "ws7-missing-helm-" + System.nanoTime() + ".exe")
                .toAbsolutePath();
        assertThat(Files.exists(missingHelm)).isFalse();
        Process process = new ProcessBuilder(
                        powershell,
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        Path.of("scripts/verify-ws7-devops.ps1").toAbsolutePath().toString(),
                        "-HelmPath",
                        missingHelm.toString())
                .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("WS7 DevOps verifier timed out");
        }
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(process.exitValue()).as(output).isNotZero();
        assertThat(output)
                .contains("Helm is required but was not found.")
                .doesNotContain("Download Helm");
    }

    @Test
    void prodGitOpsDoesNotUseFloatingRevisionsOrDigestPlaceholders() throws IOException {
        String prod = read("helm/monkeyshop/values-prod.yaml");
        String podTemplate = read("helm/monkeyshop/templates/_pod.tpl");
        String workflow = read(".github/workflows/ci.yaml");
        String script = read("scripts/verify-ws7-devops.ps1");
        String prodApplication = read("deploy/argocd/applications/monkeyshop-prod.yaml");

        assertThat(prod)
                .contains("repository: ghcr.io/haohaizi554/monkey-shop")
                .containsPattern("(?m)^  digest: sha256:[a-f0-9]{64}$")
                .contains("busybox@sha256:9532d8c39891ca2ecde4d30d7710e01fb739c87a8b9299685c63704296b16028")
                .doesNotContain("digest: \"\"")
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
                .contains("ProductionImageRepository")
                .contains("must require a committed non-placeholder production app digest")
                .contains("must not use all-zero digest placeholders")
                .contains("must reject an all-zero production app digest")
                .doesNotContain("ProductionImageDigestFixture")
                .doesNotContain("harbor.example.com/monkeyshop/monkeyshop")
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

    private static String findPowerShell() throws Exception {
        for (String candidate : new String[] {"pwsh", "powershell"}) {
            try {
                Process process = new ProcessBuilder(candidate, "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                        .redirectErrorStream(true)
                        .start();
                if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return candidate;
                }
                process.destroyForcibly();
            } catch (IOException ignored) {
                // Try the other platform name before allowing the test to be skipped.
            }
        }
        return null;
    }

    private static String findHelm() throws Exception {
        for (String candidate : new String[] {"helm", "target/tools/helm-v3.21.2/windows-amd64/helm.exe"}) {
            try {
                Process process = new ProcessBuilder(candidate, "version", "--short")
                        .redirectErrorStream(true)
                        .start();
                if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return candidate;
                }
                process.destroyForcibly();
            } catch (IOException ignored) {
                // Helm is optional for source-only verification.
            }
        }
        return null;
    }

    private static String renderHelm(String helm, String environment, Boolean migrationMode) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(helm);
        command.add("template");
        command.add("monkeyshop");
        command.add("helm/monkeyshop");
        command.add("--namespace");
        command.add("monkeyshop-" + environment);
        command.add("-f");
        command.add("helm/monkeyshop/values-" + environment + ".yaml");
        if (migrationMode != null) {
            command.add("--set");
            command.add("imageReferenceProtocol.migrationMode=" + migrationMode);
        }

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            try (var input = process.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read Helm output", exception);
            }
        });
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Helm template timed out for " + environment);
        }
        String output = outputFuture.join();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }
}
