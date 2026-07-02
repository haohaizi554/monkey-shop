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
        assertThat(workflow).contains("- 'codex/**'");
        assertThat(workflow).contains("python -m pip install --upgrade pip uv");
        assertThat(workflow).contains("choco install ripgrep -y --no-progress");
        assertThat(workflow).contains(".\\scripts\\bootstrap-ws1-tools.ps1");
        assertThat(workflow).contains("gitleaks version");
        assertThat(workflow).contains("trivy --version");
        assertThat(workflow).contains("uvx --version");
        assertThat(workflow).contains(".\\scripts\\verify-ws1-security.ps1 -SkipDependencyCheck");
        assertThat(workflow).contains("target/ws1-security/");
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
        assertThat(script).contains("\"--skip-check-update\"");
        assertThat(script).contains("\"--offline-scan\"");
        assertThat(script).contains("\"--skip-version-check\"");
        assertThat(script).contains("\"frontend/node_modules\"");
        assertThat(script).contains("\"frontend/lighthouse-report.json\"");
    }

    @Test
    void ws1ScriptRunsSecretHistorySemgrepAndFilesystemScans() throws IOException {
        String script = Files.readString(Path.of("scripts/verify-ws1-security.ps1"), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("\"gitleaks current tree\"")
                .contains("\"gitleaks-current.json\"")
                .contains("\"gitleaks git history\"")
                .contains("\"gitleaks-history.json\"")
                .contains("\"Semgrep OWASP and secrets\"")
                .contains("\"p/owasp-top-ten\"")
                .contains("\"p/secrets\"")
                .contains("\"--error\"")
                .contains("\"--no-git-ignore\"")
                .contains("\"semgrep.json\"")
                .contains("\"Trivy HIGH/CRITICAL filesystem scan\"")
                .contains("\"--scanners\", \"vuln,secret,misconfig\"")
                .contains("\"--severity\", \"HIGH,CRITICAL\"")
                .contains("\"--exit-code\", \"1\"")
                .contains("\"trivy.json\"");
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

        assertThat(script).contains("[switch]$SkipMaven");
        assertThat(script).contains("Maven verify (skipped; using a previously verified build)");
        assertThat(script).contains("[switch]$SkipDependencyCheckUpdate");
        assertThat(script).contains("\"-DautoUpdate=false\", \"clean\", \"verify\"");
        assertThat(script)
                .contains("(-not $SkipMaven) -and (-not $SkipDependencyCheck) -and (-not $SkipDependencyCheckUpdate)");
    }

    @Test
    void ws1BootstrapInstallsCachedScannerTools() throws IOException {
        String bootstrap = Files.readString(Path.of("scripts/bootstrap-ws1-tools.ps1"), StandardCharsets.UTF_8);
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        assertThat(bootstrap)
                .contains("gitleaks/gitleaks")
                .contains("aquasecurity/trivy")
                .contains("api.github.com/repos/$Repository/releases/latest")
                .contains("api.github.com/repos/$Repository/releases/tags/$tag")
                .contains(".cache\\codex-tools\\ws1-security")
                .contains("gitleaks_*_windows_x64.zip")
                .contains("trivy_*_windows-64bit.zip")
                .contains("-ExecutableName \"gitleaks\"")
                .contains("-ExecutableName \"trivy\"")
                .contains("\"$ExecutableName.exe\"")
                .contains("Invoke-WebRequest")
                .contains("Expand-Archive")
                .contains("Add-ToolDirectoryToPath")
                .contains("$env:PATH = $resolved")
                .contains("$env:GITHUB_PATH")
                .contains("Add-Content -LiteralPath $env:GITHUB_PATH")
                .contains("gitleaks version")
                .contains("trivy --version")
                .contains("[switch]$Force");

        assertThat(readme)
                .contains(".\\scripts\\bootstrap-ws1-tools.ps1")
                .contains("%USERPROFILE%\\.cache\\codex-tools\\ws1-security")
                .contains("both scanner directories on `PATH`")
                .contains("verify-ws1-security.ps1 -SkipMaven -SkipTrivyDbUpdate")
                .contains("target\\ws1-security-offline")
                .contains("--skip-check-update")
                .contains("--offline-scan")
                .contains("--skip-version-check");
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
