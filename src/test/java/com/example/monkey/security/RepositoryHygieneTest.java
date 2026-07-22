package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RepositoryHygieneTest {

    private static final List<String> REQUIRED_GITIGNORE_PATTERNS = List.of(
            "uploads/",
            "app.jar",
            "code.txt",
            "*.jar",
            ".env",
            ".env.*",
            "*.pem",
            "*.key",
            "application.properties",
            "application-*.properties",
            ".trae/",
            "secrets/*.yaml",
            "secrets/*.dec.*");

    private static final List<String> REQUIRED_DOCKERIGNORE_PATTERNS = List.of(
            ".git",
            "target",
            "uploads",
            "app.jar",
            "code.txt",
            "*.jar",
            ".env",
            ".env.*",
            "*.pem",
            "*.key",
            "application.properties",
            "application-*.properties",
            "secrets/*.yaml",
            "secrets/*.dec.*",
            ".trae");

    private static final List<String> FORBIDDEN_TRACKED_PATH_FRAGMENTS = List.of(
            "code.txt",
            "app.jar",
            ".pem",
            ".key",
            "uploads/",
            ".trae/",
            "application.properties",
            "application-dev.properties",
            "application-staging.properties",
            "application-prod.properties");

    private static final List<Path> DELIVERY_WORKFLOWS = List.of(
            Path.of(".github/workflows/ci.yaml"),
            Path.of(".github/workflows/codeql.yml"),
            Path.of(".github/workflows/snyk.yml"),
            Path.of(".github/workflows/sonarqube.yml"),
            Path.of(".github/workflows/ws1-security.yml"));

    @Test
    void gitignoreCoversSensitiveLocalArtifacts() throws IOException {
        String gitignore = Files.readString(Path.of(".gitignore"), StandardCharsets.UTF_8);

        for (String pattern : REQUIRED_GITIGNORE_PATTERNS) {
            assertThat(gitignore).contains(pattern);
        }
    }

    @Test
    void dockerignoreKeepsSensitiveFilesOutOfBuildContext() throws IOException {
        String dockerignore = Files.readString(Path.of(".dockerignore"), StandardCharsets.UTF_8);

        for (String pattern : REQUIRED_DOCKERIGNORE_PATTERNS) {
            assertThat(dockerignore).contains(pattern);
        }
    }

    @Test
    void sensitiveArtifactsAreNotTrackedByGit() throws Exception {
        assumeTrue(Files.isDirectory(Path.of(".git")), "git metadata is required for tracked artifact audit");

        Process process = new ProcessBuilder("git", "ls-files", "-z")
                .redirectErrorStream(true)
                .start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String trackedFiles =
                output.get(5, TimeUnit.SECONDS).replace('\0', '\n').replace('\\', '/');

        assertThat(finished).as("git ls-files timed out").isTrue();
        assertThat(process.exitValue()).as(trackedFiles).isZero();
        for (String forbiddenPath : FORBIDDEN_TRACKED_PATH_FRAGMENTS) {
            assertThat(trackedFiles).doesNotContain(forbiddenPath);
        }
        assertThat(trackedFiles
                        .lines()
                        .filter(RepositoryHygieneTest::isTrackedEnvironmentSecret)
                        .toList())
                .as("tracked environment secret files")
                .isEmpty();
    }

    @Test
    void secretHistoryCleanupIsDocumentedAsReleaseBlocker() throws IOException {
        String runbook = Files.readString(Path.of("docs/security/ws1-history-cleanup.md"), StandardCharsets.UTF_8);
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        String requiredChecks = Files.readString(Path.of(".github/required-checks.yml"), StandardCharsets.UTF_8);

        assertThat(runbook)
                .contains("fresh disposable clone")
                .contains("git filter-repo")
                .contains("--path code.txt")
                .contains("--path app.jar")
                .contains("--invert-paths")
                .contains(".\\scripts\\verify-ws1-security.ps1")
                .contains("target/ws1-security/gitleaks-history.json")
                .contains("release blocker")
                .contains("git push --force-with-lease --all")
                .contains("credential rotation");

        assertThat(readme)
                .contains("docs/security/ws1-history-cleanup.md")
                .contains("target/ws1-security/gitleaks-history.json")
                .contains("release-blocking");

        assertThat(requiredChecks)
                .contains("release_blockers:")
                .contains("Secret History Rewrite Attestation")
                .contains("docs/security/ws1-history-cleanup.md")
                .contains("target/ws1-security/gitleaks-history.json")
                .contains("credential rotation ticket references");
    }

    @Test
    void monkeyImageDownloaderIsPortableAndPreservesAttribution() throws IOException {
        String script = Files.readString(Path.of("scripts/download-monkey-images.ps1"), StandardCharsets.UTF_8);
        String ci = Files.readString(Path.of(".github/workflows/ci.yaml"), StandardCharsets.UTF_8);

        assertThat(script.toLowerCase()).doesNotContain("d:\\desktop\\project");
        assertThat(script)
                .contains("$PSScriptRoot")
                .contains("New-Item")
                .contains("Creator")
                .contains("License")
                .contains("LicenseUrl")
                .contains("ForeignLandingUrl")
                .contains("Get-FileHash")
                .contains("attribution.json");
        assertThat(ci).contains(".\\scripts\\tests\\download-monkey-images.Tests.ps1");
    }

    @Test
    void deliveryWorkflowsRunForArchitectureUpgradeBranches() throws IOException {
        for (Path workflow : DELIVERY_WORKFLOWS) {
            assertThat(Files.readString(workflow, StandardCharsets.UTF_8))
                    .as("push trigger for %s", workflow)
                    .contains("- '*Architecture-Upgrade'");
        }
    }

    @Test
    void frontendAcceptanceBypassesConfiguredProxyForLoopbackServers() throws IOException {
        String script = Files.readString(Path.of("scripts/verify-ws5-frontend.ps1"), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("$env:NO_PROXY")
                .contains("$env:no_proxy")
                .contains("127.0.0.1")
                .contains("localhost")
                .contains("::1");
    }

    private static String readProcessOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isTrackedEnvironmentSecret(String path) {
        String fileName = Path.of(path).getFileName().toString();
        return fileName.equals(".env") || (fileName.startsWith(".env.") && !fileName.endsWith(".example"));
    }
}
