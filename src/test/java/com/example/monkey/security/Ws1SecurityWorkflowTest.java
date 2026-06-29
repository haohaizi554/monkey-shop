package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Ws1SecurityWorkflowTest {

    private static final List<String> FORBIDDEN_LITERAL_RISK_PATTERNS = List.of(
            "123" + "456",
            "monkey" + "pass",
            "root" + "password",
            "anystrong" + "password",
            "useSSL" + "=false",
            "ddl-auto" + "=update",
            "show-sql" + "=true",
            "SPRING" + "_DATASOURCE_",
            "csrf(" + "AbstractHttpConfigurer::disable)",
            "anyRequest()" + ".permitAll()",
            "print" + "StackTrace",
            "System" + ".out",
            "System" + ".err");

    @Test
    void workflowRunsFastGateAndFullDependencyCheck() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/ws1-security.yml"), StandardCharsets.UTF_8);

        assertThat(workflow).contains("fetch-depth: 0");
        assertThat(workflow).contains(".\\scripts\\verify-ws1-security.ps1 -SkipDependencyCheck");
        assertThat(workflow).contains("NVD_API_KEY: ${{ secrets.NVD_API_KEY }}");
        assertThat(workflow).contains("Set repository secret NVD_API_KEY");
        assertThat(workflow).contains("mvn --batch-mode clean verify");
    }

    @Test
    void ws1ScriptRunsTrivyWithMirrorFallbackAndExplicitCachedDbMode() throws IOException {
        String script = Files.readString(Path.of("scripts/verify-ws1-security.ps1"), StandardCharsets.UTF_8);

        assertThat(script).contains("[switch]$SkipTrivyDbUpdate");
        assertThat(script).contains("mirror.gcr.io/aquasec/trivy-db:2");
        assertThat(script).contains("ghcr.io/aquasecurity/trivy-db:2");
        assertThat(script).contains("mirror.gcr.io/aquasec/trivy-java-db:1");
        assertThat(script).contains("ghcr.io/aquasecurity/trivy-java-db:1");
        assertThat(script).contains("\"--no-progress\"");
        assertThat(script).contains("\"--skip-db-update\"");
        assertThat(script).contains("\"frontend/node_modules\"");
        assertThat(script).contains("\"frontend/lighthouse-report.json\"");
    }

    @Test
    void ws1ScriptAssertsSecurityHeaderPosture() throws IOException {
        String script = Files.readString(Path.of("scripts/verify-ws1-security.ps1"), StandardCharsets.UTF_8);

        assertThat(script).contains("Invoke-SecurityHeaderPostureScan");
        assertThat(script).contains("Content-Security-Policy");
        assertThat(script).contains("https://challenges.cloudflare.com");
        assertThat(script).contains("frame-src https://challenges.cloudflare.com");
        assertThat(script).contains("upgrade-insecure-requests");
        assertThat(script).contains("Strict-Transport-Security");
        assertThat(script).contains("add_header Content-Security-Policy");
        assertThat(script).contains("proxy_hide_header Content-Security-Policy");
    }

    @Test
    void ws1ScriptCanRunDependencyCheckAgainstHydratedLocalCache() throws IOException {
        String script = Files.readString(Path.of("scripts/verify-ws1-security.ps1"), StandardCharsets.UTF_8);

        assertThat(script).contains("[switch]$SkipDependencyCheckUpdate");
        assertThat(script).contains("\"-DautoUpdate=false\", \"clean\", \"verify\"");
        assertThat(script).contains("(-not $SkipDependencyCheckUpdate) -and (-not $env:NVD_API_KEY)");
    }

    @Test
    void repositoryDoesNotContainLiteralWs1RiskPatterns() throws IOException {
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(Path.of("."))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!isRiskScannedTextFile(path)) {
                    continue;
                }
                String content = Files.readString(path, StandardCharsets.ISO_8859_1);
                for (String pattern : FORBIDDEN_LITERAL_RISK_PATTERNS) {
                    if (content.contains(pattern)) {
                        violations.add(normalized(path) + " contains " + pattern);
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    private static boolean isRiskScannedTextFile(Path path) {
        String normalized = normalized(path);
        if (normalized.startsWith(".git/")
                || normalized.startsWith("target/")
                || normalized.startsWith("frontend/node_modules/")
                || normalized.startsWith("frontend/dist/")
                || normalized.startsWith("frontend/dist-ssr/")
                || normalized.startsWith("frontend/test-results/")
                || normalized.startsWith("frontend/playwright-report/")
                || normalized.startsWith("frontend/coverage/")
                || normalized.equals("frontend/lighthouse-report.json")
                || normalized.startsWith("uploads/")
                || normalized.startsWith(".trae/")) {
            return false;
        }
        String lower = normalized.toLowerCase();
        return !lower.endsWith(".jpg")
                && !lower.endsWith(".jpeg")
                && !lower.endsWith(".png")
                && !lower.endsWith(".gif")
                && !lower.endsWith(".ico")
                && !lower.endsWith(".jar")
                && !lower.endsWith(".class");
    }

    private static String normalized(Path path) {
        String normalized = path.normalize().toString().replace('\\', '/');
        return normalized.startsWith("./") ? normalized.substring(2) : normalized;
    }
}
