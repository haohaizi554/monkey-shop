package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws8SecurityWorkflowTest {

    @Test
    void verifierCoversAntiAbusePiiRetentionAndCompliancePosture() throws IOException {
        String script = read("scripts/verify-ws8-security.ps1");

        assertThat(script)
                .contains("RateLimitPolicy.java")
                .contains("must charge IP rate-limit dimension")
                .contains("must emit Retry-After on 429 responses")
                .contains("must verify Turnstile tokens through Siteverify")
                .contains("must bind tokens to expected actions")
                .contains("CaptchaHttp.java")
                .contains("must advertise Turnstile metadata")
                .contains("must advertise Turnstile site keys")
                .contains("must use Tink-backed encryption")
                .contains("must release PII keys through Vault Transit")
                .contains("must encrypt user phone")
                .contains("must anonymize completed orders")
                .contains("must document production database TDE")
                .contains("must run the WS8 security gate in CI");
    }

    @Test
    void ciRunsWs8SecurityGate() throws IOException {
        String workflow = read(".github/workflows/ci.yaml");

        assertThat(workflow).contains("Verify WS8 security posture").contains(".\\scripts\\verify-ws8-security.ps1");
    }

    @Test
    void ws8DocsDescribeComplianceAuditTraceLookup() throws IOException {
        String docs = read("docs/security/ws8.md");

        assertThat(docs)
                .contains("PIPL/GDPR")
                .contains("GET /api/stats/audit-trace?traceId=<traceId>")
                .contains("sanitized audit events")
                .contains("Loki logs")
                .contains("Tempo spans");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
