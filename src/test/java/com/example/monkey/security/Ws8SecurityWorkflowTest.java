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
                .contains("must default honeypot WAF blocks to 24 hours")
                .contains("must keep honeypot WAF block duration at 24 hours")
                .contains("must prove honeypot WAF blocks are written with a 24 hour TTL")
                .contains("must verify Turnstile tokens through Siteverify")
                .contains("must bind tokens to expected actions")
                .contains("CaptchaHttp.java")
                .contains("must advertise Turnstile metadata")
                .contains("must advertise Turnstile site keys")
                .contains("must use Tink-backed encryption")
                .contains("must release PII keys through Vault Transit")
                .contains("must allow historical AES keys for PII key rotation")
                .contains("must prove rotated deployments read old ciphertext and write with the active key")
                .contains("must prove Vault Transit historical AES key unwrapping")
                .contains("must source wrapped historical AES keys through ExternalSecret")
                .contains("must encrypt user phone")
                .contains("must encrypt user email")
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

    @Test
    void runtimeApiSecuritySmokeCoversAnonymousReadAuthzCaptchaHoneypotAndOptionalRateLimit() throws IOException {
        String script = read("scripts/verify-runtime-api-security.ps1");
        String readme = read("README.md");

        assertThat(script)
                .contains("/api/monkeys?page=0&size=1")
                .contains("/api/auth/captcha/config")
                .contains("/api/orders/my")
                .contains("/api/.env")
                .contains("application/problem+json")
                .contains("UNAUTHORIZED")
                .contains("FORBIDDEN")
                .contains("RATE_LIMIT")
                .contains("Retry-After")
                .contains("X-Forwarded-For")
                .contains("$script:IssuedProbeIps")
                .contains("192.0.2")
                .contains("203.0.113")
                .contains("Invoke-AnonymousOrdersProbe")
                .contains("already blocked; retrying anonymous auth probe")
                .contains("Invoke-UnblockedApiProbe")
                .contains("already blocked; retrying unblocked API probe")
                .contains("Invoke-RateLimitProbe")
                .contains("already blocked; retrying rate-limit probe")
                .contains("-Exclude @($honeypotIp)")
                .contains("-RunRateLimitProbe")
                .contains("Runtime API security smoke gate completed successfully");
        assertThat(readme)
                .contains("verify-runtime-api-security.ps1")
                .contains("-BaseUrl http://localhost:8888")
                .contains("-RunRateLimitProbe");
    }

    @Test
    void runtimeDataProtectionVerifierCoversComposeFlagsCiphertextBlindIndexesAndStrictMode() throws IOException {
        String script = read("scripts/verify-runtime-data-protection.ps1");
        String bashScript = read("scripts/verify-runtime-data-protection.sh");
        String compose = read("docker-compose.yml");
        String readme = read("README.md");
        String application = read("src/main/resources/application.yml");
        String devApplication = read("src/main/resources/application-dev.yml");

        assertThat(script)
                .contains("APP_PII_ENCRYPTION_ENABLED")
                .contains("APP_PII_ALLOW_PLAINTEXT_READ")
                .contains("APP_PII_BACKFILL_ENABLED")
                .contains("PiiCiphertextAuditCli")
                .contains("PropertiesLauncher")
                .contains("Authenticated PII ciphertext audit completed")
                .contains("flyway.version")
                .contains("^[0-9a-f]{64}$")
                .contains("user.phone_hmac")
                .contains("user.totp_secret")
                .contains("address.phone_hmac")
                .contains("orders.receiver_phone_hmac")
                .contains("order_review.content")
                .contains("RequirePopulatedPii")
                .contains("Runtime data protection gate completed successfully");
        assertThat(bashScript)
                .contains("APP_PII_ENCRYPTION_ENABLED")
                .contains("APP_PII_ALLOW_PLAINTEXT_READ")
                .contains("APP_PII_BACKFILL_ENABLED")
                .contains("PiiCiphertextAuditCli")
                .contains("PropertiesLauncher")
                .contains("Authenticated PII ciphertext audit completed")
                .contains("flyway.version")
                .contains("^[0-9a-f]{64}$")
                .contains("user.totp_secret")
                .contains("order_review.content")
                .contains("--require-populated-pii")
                .contains("Runtime data protection gate completed successfully");
        assertThat(compose)
                .contains("APP_PII_ENCRYPTION_ENABLED: ${APP_PII_ENCRYPTION_ENABLED:-true}")
                .contains("APP_PII_AES_KEY_BASE64: ${APP_PII_AES_KEY_BASE64:?set APP_PII_AES_KEY_BASE64}")
                .contains("APP_PII_HMAC_KEY_BASE64: ${APP_PII_HMAC_KEY_BASE64:?set APP_PII_HMAC_KEY_BASE64}")
                .contains("APP_PII_PREVIOUS_AES_KEYS_BASE64: ${APP_PII_PREVIOUS_AES_KEYS_BASE64:-}")
                .contains("APP_PII_ALLOW_PLAINTEXT_READ: ${APP_PII_ALLOW_PLAINTEXT_READ:-false}")
                .contains("APP_PII_BACKFILL_ENABLED: ${APP_PII_BACKFILL_ENABLED:-false}");
        assertThat(application)
                .contains("enabled: ${APP_PII_ENCRYPTION_ENABLED:true}")
                .contains("allow-plaintext-read: ${APP_PII_ALLOW_PLAINTEXT_READ:false}");
        assertThat(application)
                .contains("batch-size: ${APP_PII_BACKFILL_BATCH_SIZE:500}")
                .contains("batch-size: ${APP_PII_CIPHERTEXT_AUDIT_BATCH_SIZE:500}");
        assertThat(devApplication)
                .contains("enabled: ${APP_PII_ENCRYPTION_ENABLED:true}")
                .contains("allow-plaintext-read: ${APP_PII_ALLOW_PLAINTEXT_READ:false}");
        assertThat(readme)
                .contains("verify-runtime-data-protection.ps1")
                .contains("verify-runtime-data-protection.sh")
                .contains("RequirePopulatedPii")
                .contains("--require-populated-pii")
                .contains("enc:v1:")
                .contains("without printing secrets or raw PII");
    }

    @Test
    void localRuntimeGateRunsStrictAuthenticatedPiiAudit() throws IOException {
        String verifier = read("scripts/verify-local-data-protection.ps1");
        String runtimeGate = read("scripts/verify-local-runtime.ps1");

        assertThat(verifier)
                .contains("Import-LocalRuntimeEnvironment")
                .contains("APP_PII_ENCRYPTION_ENABLED")
                .contains("APP_PII_ALLOW_PLAINTEXT_READ")
                .contains("APP_PII_BACKFILL_ENABLED")
                .contains("PiiCiphertextAuditCli")
                .contains("app.pii.ciphertext-audit.require-populated")
                .contains("Authenticated PII ciphertext audit completed")
                .contains("Local runtime data-protection gate completed successfully");
        assertThat(runtimeGate).contains("verify-local-data-protection.ps1").contains("RequirePopulatedPii");
    }

    @Test
    void ws8GateRejectsMicrok8sPiiEncryptionBypasses() throws IOException {
        String gate = read("scripts/verify-ws8-security.ps1");

        assertThat(gate)
                .contains("scripts/verify-microk8s-dev-runtime.ps1")
                .contains("scripts/verify-argocd-microk8s-gitops.ps1")
                .contains("APP_PII_ENCRYPTION_ENABLED")
                .contains("APP_PII_AES_KEY_BASE64")
                .contains("APP_PII_HMAC_KEY_BASE64")
                .contains("printenv APP_PII_ENCRYPTION_ENABLED");
    }

    @Test
    void composePublishesLocalDependencyPortsForIdeAcceptance() throws IOException {
        String compose = read("docker-compose.yml");
        String readme = read("README.md");

        assertThat(compose)
                .contains("${MYSQL_PORT:-3307}:3306")
                .contains("${REDIS_PORT:-6379}:6379")
                .contains("${CLAMAV_PORT:-3310}:3310");
        assertThat(readme).contains("$env:APP_PII_AES_KEY_BASE64").contains("$env:APP_PII_HMAC_KEY_BASE64");
    }

    @Test
    void runtimePiiBackfillWrapperDefaultsToDryRunAndRequiresExplicitApproval() throws IOException {
        String script = read("scripts/run-pii-backfill-compose.ps1");
        String readme = read("README.md");
        String docs = read("docs/security/ws8.md");

        assertThat(script)
                .contains("AcknowledgeDataRewrite")
                .contains("I understand this rewrites PII data")
                .contains("Dry run complete")
                .contains("GenerateMissingKeys")
                .contains("mysqldump")
                .contains("APP_PII_BACKFILL_ENABLED=true")
                .contains("APP_PII_ALLOW_PLAINTEXT_READ=false")
                .contains("PII plaintext backfill completed")
                .contains("verify-runtime-data-protection.ps1")
                .contains("secrets/raw PII were not printed");
        assertThat(readme)
                .contains("run-pii-backfill-compose.ps1")
                .contains("dry-run")
                .contains("AcknowledgeDataRewrite")
                .contains("I understand this rewrites PII data")
                .contains("mysqldump")
                .contains("data-protection verifier");
        assertThat(docs)
                .contains("scripts/run-pii-backfill-compose.ps1")
                .contains("default mode is a dry-run")
                .contains("AcknowledgeDataRewrite")
                .contains("verify-runtime-data-protection.ps1");
    }

    @Test
    void ws8DocsDescribeRuntimePiiMigrationRunbook() throws IOException {
        String docs = read("docs/security/ws8.md");
        String readme = read("README.md");

        assertThat(docs)
                .contains("Runtime Compose PII Migration Runbook")
                .contains("requires explicit operator approval")
                .contains("mysqldump")
                .contains("APP_PII_AES_KEY_BASE64")
                .contains("APP_PII_HMAC_KEY_BASE64")
                .contains("APP_PII_PREVIOUS_AES_KEYS_BASE64")
                .contains("APP_PII_ALLOW_PLAINTEXT_READ=true")
                .contains("APP_PII_BACKFILL_ENABLED=true")
                .contains("PII plaintext backfill completed")
                .contains("APP_PII_ALLOW_PLAINTEXT_READ=false")
                .contains("user.totp_secret")
                .contains("order_review.content")
                .contains("verify-runtime-data-protection.ps1")
                .contains("without printing secrets or raw PII");
        assertThat(readme)
                .contains("APP_PII_AES_KEY_BASE64")
                .contains("APP_PII_HMAC_KEY_BASE64")
                .contains("APP_PII_PREVIOUS_AES_KEYS_BASE64")
                .contains("APP_PII_ALLOW_PLAINTEXT_READ")
                .contains("APP_PII_BACKFILL_ENABLED")
                .contains("APP_PII_KEY_VERSION")
                .contains("APP_PII_KEY_CREATED_AT")
                .contains("APP_PII_VAULT_PREVIOUS_AES_CIPHERTEXTS");
    }

    @Test
    void localProductionSupportUsesPinnedLoopbackServicesAndFailClosedStartup() throws IOException {
        String runtimeCommon = read("scripts/local-runtime-common.ps1");
        String common = read("scripts/local-support-common.ps1");
        String bootstrap = read("scripts/bootstrap-local-support.ps1");
        String startSupport = read("scripts/start-local-support.ps1");
        String statusLocal = read("scripts/status-local.ps1");
        String statusSupport = read("scripts/status-local-support.ps1");
        String stopSupport = read("scripts/stop-local-support.ps1");
        String verifySupport = read("scripts/verify-local-support.ps1");
        String startLocal = read("scripts/start-local.ps1");
        String application = read("src/main/resources/application.yml");

        assertThat(runtimeCommon)
                .contains("function Add-LocalRuntimeNoProxy")
                .contains("@(\"127.0.0.1\", \"localhost\", \"::1\")")
                .contains("function Assert-LocalRuntimeLoopbackListener");
        assertThat(common)
                .contains("local-runtime-common.ps1")
                .contains("LocalSupportRoot")
                .contains("local-support-state.json")
                .contains("application.env")
                .contains("Protect-LocalSupportSecret");
        assertThat(bootstrap)
                .contains("2.0.3")
                .contains("4.29")
                .contains("1.5.3")
                .contains("02da9f383256606db9717d29f2d26d0aafd9af951d51263bdee38dd98d38cbaa")
                .contains("a5a343f2e2249b4e709842b846e596330a316e064b15f9d77899581ea545cb9b")
                .contains("e998b3b98c2812726ca7f4db06bf89c4b52a7eb7160ab93403c3ec790a9be6b6")
                .contains("http://127.0.0.1:7890")
                .contains("Get-FileHash");
        assertThat(startSupport)
                .contains("Add-LocalRuntimeNoProxy")
                .doesNotContain("$env:HTTPS_PROXY = $ProxyUri")
                .doesNotContain("$env:HTTP_PROXY = $ProxyUri")
                .contains("[switch]$AdoptEnvironmentPiiKeys")
                .contains("127.0.0.1:8200")
                .contains("storage \"file\"")
                .contains("transit/keys/monkeyshop-pii")
                .contains("transit/decrypt/monkeyshop-pii")
                .contains("capabilities = [\"update\"]")
                .contains("Invoke-VaultKeyWrap")
                .contains("piiKeySource")
                .contains("APP_PII_AES_KEY_BASE64")
                .contains("APP_PII_HMAC_KEY_BASE64")
                .contains("RandomNumberGenerator")
                .contains("APP_PII_KEY_PROVIDER=vault-transit")
                .contains("-ip.bind=127.0.0.1")
                .contains("-s3.ip.bind=127.0.0.1")
                .contains("-filer.port=8887")
                .contains("-s3.port=8333")
                .contains("-s3.port.iceberg=0")
                .contains("-master.telemetry=false")
                .contains("TCPAddr 127.0.0.1")
                .contains("TCPSocket 3310")
                .contains("Stop-LocalRuntimeProcessTree")
                .doesNotContain("-admin.")
                .doesNotContain("verify-local-support.ps1");
        assertThat(statusLocal)
                .contains("Add-LocalRuntimeNoProxy")
                .contains("Assert-LocalRuntimeLoopbackListener")
                .contains("MySQL", "Redis", "Backend", "Frontend");
        assertThat(statusSupport)
                .contains("Add-LocalRuntimeNoProxy", "Assert-LocalRuntimeLoopbackListener")
                .contains("Vault", "SeaweedFS S3", "ClamAV");
        assertThat(stopSupport).contains("clamav", "seaweedfs", "vault").contains("Test-LocalRuntimeProcessIdentity");
        assertThat(verifySupport)
                .contains("Add-LocalRuntimeNoProxy")
                .contains("LocalProductionSupportAcceptanceTest")
                .contains("MONKEYSHOP_LOCAL_SUPPORT_ACCEPTANCE")
                .contains("APP_PII_VAULT_TOKEN")
                .contains("APP_STORAGE_MINIO_ENDPOINT");
        assertThat(startLocal)
                .contains("Add-LocalRuntimeNoProxy")
                .contains("[switch]$WithProductionSupport")
                .contains("start-local-support.ps1")
                .contains("-AdoptEnvironmentPiiKeys")
                .contains("Remove-Item Env:APP_PII_AES_KEY_BASE64")
                .contains("Remove-Item Env:APP_PII_HMAC_KEY_BASE64")
                .contains("$env:SERVER_ADDRESS = \"127.0.0.1\"")
                .contains("APP_INTEGRATIONS_STARTUP_READINESS_REQUIRED")
                .contains("APP_INTEGRATIONS_STARTUP_CREATE_STORAGE_BUCKET")
                .contains("APP_STORAGE_PROVIDER = \"minio\"")
                .contains("APP_UPLOAD_VIRUS_SCAN_ENABLED = \"true\"")
                .contains("$effectiveProductionSupportEnabled")
                .contains("productionSupportEnabled = $effectiveProductionSupportEnabled")
                .contains("Assert-LocalRuntimeLoopbackListener -Name \"MySQL\"")
                .contains("Assert-LocalRuntimeLoopbackListener -Name \"Redis\"")
                .contains("Assert-LocalRuntimeLoopbackListener -Name \"Backend\"")
                .contains("Assert-LocalRuntimeLoopbackListener -Name \"Frontend\"");
        assertThat(startLocal.indexOf("$previousState = Read-LocalRuntimeState"))
                .isLessThan(startLocal.indexOf("start-local-support.ps1"));
        assertThat(application)
                .contains("startup-readiness-required: ${APP_INTEGRATIONS_STARTUP_READINESS_REQUIRED:false}")
                .contains("startup-create-storage-bucket: ${APP_INTEGRATIONS_STARTUP_CREATE_STORAGE_BUCKET:false}");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
