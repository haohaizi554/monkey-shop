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
                .containsPattern("github/codeql-action/init@[0-9a-f]{40}")
                .containsPattern("github/codeql-action/analyze@[0-9a-f]{40}");
    }

    @Test
    void snykGateScansMavenAndFrontendDependencies() throws IOException {
        String snyk = Files.readString(Path.of(".github/workflows/snyk.yml"), StandardCharsets.UTF_8);

        assertThat(snyk)
                .contains("name: Snyk Security Gate")
                .contains("- 'codex/**'")
                .contains("SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}")
                .contains("EXTERNAL_SECURITY_GATES_REQUIRED")
                .contains("id: snyk-policy")
                .contains("::notice title=Snyk gate deferred::")
                .contains("if: steps.snyk-policy.outputs.available == 'true'")
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
                .contains("external_security_gate_policy:")
                .contains("repository_variable: EXTERNAL_SECURITY_GATES_REQUIRED")
                .contains("default: false")
                .contains("required_secrets_when_enabled:")
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

    @Test
    void verifyKyvernoSupplyChainGateIsWiredIntoCi() throws IOException {
        String ci = Files.readString(Path.of(".github/workflows/ci.yaml"), StandardCharsets.UTF_8);
        String script = Files.readString(Path.of("scripts/verify-kyverno-supply-chain.ps1"), StandardCharsets.UTF_8);

        assertThat(ci)
                .contains("Verify WS7 manifests")
                .contains("Verify Kyverno supply-chain policies")
                .contains(".\\scripts\\verify-kyverno-supply-chain.ps1")
                .contains("Verify WS8 security posture");
        assertThat(script)
                .contains("monkeyshop-image-policy.yaml")
                .contains("monkeyshop-pod-security.yaml")
                .contains("validationFailureAction:\\s+Enforce")
                .contains("verifyImages:")
                .contains("Assert-ProdImagesDigestPinned")
                .contains("must render the prod app image by immutable digest")
                .contains("must not use an all-zero digest placeholder")
                .contains("Kyverno supply-chain gate completed successfully");
    }

    @Test
    void runtimeImageSupplyChainGateScansExportedImageTarWithoutDockerSocket() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        String ci = Files.readString(Path.of(".github/workflows/ci.yaml"), StandardCharsets.UTF_8);
        String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
        String script =
                Files.readString(Path.of("scripts/verify-runtime-image-supply-chain.ps1"), StandardCharsets.UTF_8);

        assertThat(readme)
                .contains("verify-runtime-image-supply-chain.ps1")
                .contains("-SshTarget user@host")
                .contains("never mounts `/var/run/docker.sock`")
                .contains("does not store passwords");
        assertThat(ci)
                .contains("Trivy runtime image JSON gate")
                .contains("target/runtime-supply-chain/trivy-runtime-image.json")
                .contains("trivy-runtime-image-json")
                .contains("Trivy image scan");
        assertThat(dockerfile)
                .contains("apt-get install -y --only-upgrade")
                .contains("libssl3")
                .contains("openssl");
        assertThat(script)
                .contains("docker save")
                .contains("scp")
                .contains("--input")
                .contains("--severity")
                .contains("--pkg-types")
                .contains("HIGH,CRITICAL")
                .contains("--exit-code")
                .contains("--skip-java-db-update")
                .contains("\"1\"")
                .contains("vuln,secret,misconfig")
                .doesNotContain("/var/run/docker.sock")
                .doesNotContain("12" + "3456");
    }

    @Test
    void sonarQualityGateBlocksMerges() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/sonarqube.yml"), StandardCharsets.UTF_8);
        String script = Files.readString(Path.of("scripts/verify-sonarqube-quality-gate.ps1"), StandardCharsets.UTF_8);
        String properties = Files.readString(Path.of("sonar-project.properties"), StandardCharsets.UTF_8);
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        assertThat(workflow)
                .contains("name: SonarQube Quality Gate")
                .contains("SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}")
                .contains("EXTERNAL_SECURITY_GATES_REQUIRED")
                .contains("id: sonar-policy")
                .contains("::notice title=SonarQube gate deferred::")
                .contains("if: steps.sonar-policy.outputs.available == 'true'")
                .contains("SONAR_PROJECT_KEY: ${{ vars.SONAR_PROJECT_KEY }}")
                .contains("SONAR_ORGANIZATION: ${{ vars.SONAR_ORGANIZATION }}")
                .contains("sonar.qualitygate.wait=true")
                .contains("target/site/jacoco/jacoco.xml")
                .contains("target/spotbugsXml.xml")
                .contains("mvn --batch-mode -Ddependency-check.skip=true test jacoco:report spotbugs:spotbugs")
                .contains("SONAR_MAVEN_PLUGIN_VERSION: 5.7.0.6970")
                .contains("org.sonarsource.scanner.maven:sonar-maven-plugin:${SONAR_MAVEN_PLUGIN_VERSION}:sonar")
                .contains("fetch-depth: 0");
        assertThat(script)
                .contains("SONAR_TOKEN is required")
                .contains("SONAR_PROJECT_KEY is required")
                .contains("SONAR_ORGANIZATION is required")
                .contains("sonar.qualitygate.wait=true")
                .contains("target/site/jacoco/jacoco.xml")
                .contains("target/spotbugsXml.xml")
                .contains("org.sonarsource.scanner.maven:sonar-maven-plugin:$SonarMavenPluginVersion`:sonar")
                .contains("SonarQube Quality Gate completed successfully");
        assertThat(pom)
                .contains("<sonar-maven-plugin.version>5.7.0.6970</sonar-maven-plugin.version>")
                .contains("<groupId>org.sonarsource.scanner.maven</groupId>")
                .contains("<artifactId>sonar-maven-plugin</artifactId>")
                .contains("<version>${sonar-maven-plugin.version}</version>");

        assertThat(properties)
                .contains("sonar.qualitygate.wait=true")
                .contains("sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml")
                .contains("sonar.java.spotbugs.reportPaths=target/spotbugsXml.xml")
                .contains("frontend/src")
                .contains("frontend/dist/**")
                .contains("target/**");
        assertThat(readme)
                .contains(".github/workflows/sonarqube.yml")
                .contains("verify-sonarqube-quality-gate.ps1")
                .contains("SONAR_TOKEN")
                .contains("SONAR_PROJECT_KEY")
                .contains("SonarQube Quality Gate");
    }

    @Test
    void ciKeepsLocalSecurityBlockingWhenExternalCredentialsAreUnavailable() throws IOException {
        String ci = Files.readString(Path.of(".github/workflows/ci.yaml"), StandardCharsets.UTF_8);
        String ws1 = Files.readString(Path.of(".github/workflows/ws1-security.yml"), StandardCharsets.UTF_8);
        String snyk = Files.readString(Path.of(".github/workflows/snyk.yml"), StandardCharsets.UTF_8);
        String sonar = Files.readString(Path.of(".github/workflows/sonarqube.yml"), StandardCharsets.UTF_8);

        assertThat(ci)
                .contains("EXTERNAL_SECURITY_GATES_REQUIRED")
                .contains("id: nvd-policy")
                .contains("::notice title=OWASP dependency-check deferred::")
                .contains("-Ddependency-check.skip=true clean verify")
                .contains("npm run audit")
                .contains("Trivy runtime image JSON gate");
        assertThat(ws1)
                .contains("Fast WS1 Security Gate")
                .contains("id: nvd-policy")
                .contains("::notice title=OWASP dependency-check deferred::");
        assertThat(snyk)
                .contains("id: snyk-policy")
                .contains("::notice title=Snyk gate deferred::")
                .contains("if: steps.snyk-policy.outputs.available == 'true'");
        assertThat(sonar)
                .contains("id: sonar-policy")
                .contains("::notice title=SonarQube gate deferred::")
                .contains("if: steps.sonar-policy.outputs.available == 'true'");
    }

    @Test
    void umbrellaAcceptanceGateOrchestratesLocalRuntimeAndExternalProof() throws IOException {
        String script = Files.readString(Path.of("scripts/verify-ws1-ws8-acceptance.ps1"), StandardCharsets.UTF_8);
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        String evidence = Files.readString(Path.of("docs/acceptance/ws1-ws8-evidence.md"), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("verify-quality-reports.ps1")
                .contains("verify-ws1-security.ps1")
                .contains("verify-ws5-frontend.ps1")
                .contains("verify-ws6-observability.ps1")
                .contains("verify-ws7-devops.ps1")
                .contains("verify-ws8-security.ps1")
                .contains("verify-kyverno-supply-chain.ps1")
                .contains("verify-runtime-smoke.ps1")
                .contains("verify-runtime-api-security.ps1")
                .contains("verify-microk8s-dev-runtime.ps1")
                .contains("verify-argocd-microk8s-gitops.ps1")
                .contains("verify-runtime-image-supply-chain.ps1")
                .contains("verify-runtime-data-protection.sh")
                .contains("verify-local-data-protection.ps1")
                .contains("RequirePopulatedPii")
                .contains("verify-public-edge-security.ps1")
                .contains("verify-sonarqube-quality-gate.ps1")
                .contains("TimeoutSeconds = 30")
                .contains("Open proof not executed in this run")
                .doesNotContain("12" + "3456");

        assertThat(readme)
                .contains("verify-ws1-ws8-acceptance.ps1")
                .contains("-IncludeVmRuntime")
                .contains("-IncludePublicEdge")
                .contains("-IncludeSonar");
        assertThat(evidence)
                .contains("Umbrella Acceptance Entry Point")
                .contains("scripts/verify-ws1-ws8-acceptance.ps1")
                .contains("-IncludeRuntimeDataProtection");
    }

    @Test
    void microk8sRuntimeGatesUseInClusterRedisForApplicationState() throws IOException {
        String directRuntime =
                Files.readString(Path.of("scripts/verify-microk8s-dev-runtime.ps1"), StandardCharsets.UTF_8);
        String gitopsRuntime =
                Files.readString(Path.of("scripts/verify-argocd-microk8s-gitops.ps1"), StandardCharsets.UTF_8);

        assertThat(directRuntime)
                .contains("SPRING_DATA_REDIS_HOST: \"redis.$DataNamespace.svc.cluster.local\"")
                .contains("APP_AUTH_REQUIRE_REDIS_STATE: \"true\"")
                .contains("APP_RATE_LIMIT_REQUIRE_REDIS_STATE: \"true\"")
                .contains("APP_JWT_REQUIRE_REDIS_TOKEN_STORE: \"true\"")
                .contains("image: redis:7-alpine")
                .contains("rollout status deployment/redis");
        assertThat(gitopsRuntime)
                .contains("SPRING_DATA_REDIS_HOST: \"redis.$DataNamespace.svc.cluster.local\"")
                .contains("APP_AUTH_REQUIRE_REDIS_STATE: \"true\"")
                .contains("APP_RATE_LIMIT_REQUIRE_REDIS_STATE: \"true\"")
                .contains("APP_JWT_REQUIRE_REDIS_TOKEN_STORE: \"true\"")
                .contains("deployment/argocd-redis")
                .contains("delete endpoints argocd-redis")
                .contains("imagePullPolicy: IfNotPresent")
                .contains("git daemon did not serve")
                .contains("image: redis:7-alpine")
                .contains("rollout status deployment/redis")
                .doesNotContain("scale deployment argocd-redis --replicas=0");
    }

    @Test
    void devRuntimeRequiresEncryptedMysqlTransport() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yml"), StandardCharsets.UTF_8);
        String devProfile = Files.readString(Path.of("src/main/resources/application-dev.yml"), StandardCharsets.UTF_8);

        assertThat(compose)
                .contains("sslMode=REQUIRED")
                .doesNotContain("requireSSL=false")
                .doesNotContain("verifyServerCertificate=false");
        assertThat(devProfile)
                .contains("sslMode=REQUIRED")
                .contains("username: ${DB_USERNAME:monkeyuser}")
                .doesNotContain("requireSSL=false")
                .doesNotContain("verifyServerCertificate=false");
    }
}
