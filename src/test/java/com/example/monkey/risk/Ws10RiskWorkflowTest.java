package com.example.monkey.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws10RiskWorkflowTest {

    @Test
    void riskArtifactsWireAntiFraudReviewCacheAndFrontend() throws IOException {
        String docs = read("docs/risk/ws10.md");
        String deviceMigration = read("src/main/resources/db/migration/V39__risk_device_fingerprint.sql");
        String scoreMigration = read("src/main/resources/db/migration/V40__risk_score.sql");
        String queueMigration = read("src/main/resources/db/migration/V41__risk_audit_queue.sql");
        String policy = read("src/main/java/com/example/monkey/risk/domain/RiskPolicy.java");
        String service = read("src/main/java/com/example/monkey/risk/application/RiskApplicationService.java");
        String blindIndex = read("src/main/java/com/example/monkey/risk/infrastructure/PiiRiskBlindIndexService.java");
        String controller = read("src/main/java/com/example/monkey/risk/interfaces/RiskController.java");
        String store = read("src/main/java/com/example/monkey/risk/infrastructure/JpaRiskStore.java");
        String cache = read("src/main/java/com/example/monkey/risk/infrastructure/RedisRiskCache.java");
        String filter = read("src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java");
        String securityConfig =
                read("src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java");
        String metrics =
                read("src/main/java/com/example/monkey/order/application/observability/BusinessMetricsService.java");
        String prometheusRule = read("helm/monkeyshop/templates/prometheusrule.yaml");
        String frontendApi = read("frontend/src/api/risk.ts");
        String riskView = read("frontend/src/views/RiskReviewView.vue");
        String script = read("scripts/verify-ws10-risk.ps1");

        assertThat(docs).contains("Device fingerprint", "price anomalies", "manual review");
        assertThat(deviceMigration).contains("risk_device_fingerprint", "phone_hmac", "expires_at");
        assertThat(scoreMigration).contains("risk_score", "signals_json", "decision");
        assertThat(queueMigration).contains("risk_audit_queue", "RISK_REVIEW", "handler_user_id");
        assertThat(policy).contains("PRICE_ANOMALY_RATE", "SECKILL_SCALPER", "TOTP_REQUIRED");
        assertThat(service)
                .contains(
                        "@WithSpan(\"risk.assess\")",
                        "RiskBlindIndexService",
                        "revokeUserTokens",
                        "userMfaVerifier.verifyCode");
        assertThat(blindIndex).contains("PiiCryptoService", "blindIndexPhone");
        assertThat(controller).contains("/api/risk", "/assess", "/reviews/{caseId}/resolve");
        assertThat(store).contains("UNLISTED", "RECYCLED", "RiskStore", "app.risk.store");
        assertThat(cache).contains("risk:device:", "risk:score:user:", "risk:seckill:");
        assertThat(filter).contains("/api/risk/internal/probe", "ApiRateLimitOperation.RISK");
        assertThat(securityConfig).contains("/risk", "RISK_WRITE", "RISK_REVIEW");
        assertThat(metrics).contains("risk.high_score", "recordRiskDecision");
        assertThat(prometheusRule).contains("MonkeyShopRiskHighScoreSpike", "risk_price_anomaly_total");
        assertThat(frontendApi).contains("assessRisk", "riskReviews", "resolveRiskReview");
        assertThat(riskView)
                .contains("riskApi.assessRisk", "RiskReviewView", "t('risk.title')")
                .contains("useRouteQueryState", "saveDecision", "risk.totpRequiredForBlock");
        assertThat(script).contains("RiskPolicyTest", "WS10 risk verification completed successfully");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
