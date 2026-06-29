package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupplyChainAutomationTest {

    @Test
    void dependabotMaintainsRuntimeBuildAndCiDependencies() throws IOException {
        String dependabot = Files.readString(Path.of(".github/dependabot.yml"), StandardCharsets.UTF_8);

        assertThat(dependabot)
                .contains("version: 2")
                .contains("package-ecosystem: \"maven\"")
                .contains("directory: \"/\"")
                .contains("package-ecosystem: \"npm\"")
                .contains("directory: \"/frontend\"")
                .contains("package-ecosystem: \"github-actions\"")
                .contains("package-ecosystem: \"docker\"")
                .contains("timezone: \"Asia/Shanghai\"")
                .contains("labels:")
                .contains("- \"security\"")
                .contains("open-pull-requests-limit: 5")
                .contains("commit-message:")
                .contains("include: \"scope\"");
    }

    @Test
    void codeqlScansBackendAndFrontendSources() throws IOException {
        String codeql = Files.readString(Path.of(".github/workflows/codeql.yml"), StandardCharsets.UTF_8);

        assertThat(codeql)
                .contains("security-events: write")
                .contains("- 'codex/**'")
                .contains("language: java-kotlin")
                .contains("language: javascript-typescript")
                .contains("build-mode: manual")
                .contains("build-mode: none")
                .contains("npm run build")
                .contains("-Ddependency-check.skip=true package")
                .contains("github/codeql-action/init@v4")
                .contains("github/codeql-action/analyze@v4");
    }

    @Test
    void snykGateScansMavenAndFrontendDependencies() throws IOException {
        String snyk = Files.readString(Path.of(".github/workflows/snyk.yml"), StandardCharsets.UTF_8);

        assertThat(snyk)
                .contains("name: Snyk Security Gate")
                .contains("- 'codex/**'")
                .contains("SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}")
                .contains("SNYK_TOKEN secret is required")
                .contains("npm install --global snyk")
                .contains("snyk test --file=pom.xml --package-manager=maven --severity-threshold=high")
                .contains("working-directory: frontend")
                .contains("npm ci")
                .contains("snyk test --file=package-lock.json --package-manager=npm --severity-threshold=high");
    }

    @Test
    void readmeDocumentsSupplyChainGatesAndRequiredSecrets() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        assertThat(readme)
                .contains("### Supply-chain gates")
                .contains(".github/dependabot.yml")
                .contains("Maven, frontend npm, GitHub Actions, and Docker")
                .contains(".github/workflows/codeql.yml")
                .contains("Java/Kotlin and JavaScript/TypeScript")
                .contains(".github/workflows/snyk.yml")
                .contains("pom.xml")
                .contains("frontend/package-lock.json")
                .contains("SNYK_TOKEN");
    }

    @Test
    void requiredChecksDocumentBranchProtectionContract() throws IOException {
        String requiredChecks = Files.readString(Path.of(".github/required-checks.yml"), StandardCharsets.UTF_8);
        String ci = Files.readString(Path.of(".github/workflows/ci.yaml"), StandardCharsets.UTF_8);
        String ws1 = Files.readString(Path.of(".github/workflows/ws1-security.yml"), StandardCharsets.UTF_8);
        String codeql = Files.readString(Path.of(".github/workflows/codeql.yml"), StandardCharsets.UTF_8);
        String snyk = Files.readString(Path.of(".github/workflows/snyk.yml"), StandardCharsets.UTF_8);

        assertThat(requiredChecks)
                .contains("branch: main")
                .contains("require_pull_request_before_merging: true")
                .contains("- NVD_API_KEY")
                .contains("- SNYK_TOKEN")
                .contains("- Maven Verify")
                .contains("- Frontend Verify")
                .contains("- DevOps Manifest Verify")
                .contains("- Image Build Scan Sign")
                .contains("- Fast WS1 Security Gate")
                .contains("- Full OWASP Dependency Check")
                .contains("- CodeQL Analyze (java-kotlin)")
                .contains("- CodeQL Analyze (javascript-typescript)")
                .contains("- Snyk Dependency Scan");

        assertThat(ci)
                .contains("name: Maven Verify")
                .contains("name: Frontend Verify")
                .contains("name: DevOps Manifest Verify")
                .contains("name: Image Build Scan Sign");
        assertThat(ws1).contains("name: Fast WS1 Security Gate").contains("name: Full OWASP Dependency Check");
        assertThat(codeql).contains("name: CodeQL Analyze (${{ matrix.language }})");
        assertThat(snyk).contains("name: Snyk Dependency Scan");
    }
}
