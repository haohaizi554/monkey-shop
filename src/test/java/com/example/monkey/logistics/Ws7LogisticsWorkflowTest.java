package com.example.monkey.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws7LogisticsWorkflowTest {

    @Test
    void logisticsArtifactsWireTrackingFreightAddressWebhookAndFrontend() throws IOException {
        String docs = read("docs/logistics/ws7.md");
        String trackingMigration = read("src/main/resources/db/migration/V32__logistics_tracking.sql");
        String freightMigration = read("src/main/resources/db/migration/V33__logistics_freight_template.sql");
        String service =
                read("src/main/java/com/example/monkey/logistics/application/LogisticsApplicationService.java");
        String controller = read("src/main/java/com/example/monkey/logistics/interfaces/LogisticsController.java");
        String store = read("src/main/java/com/example/monkey/logistics/infrastructure/JpaLogisticsStore.java");
        String replayGuard =
                read("src/main/java/com/example/monkey/logistics/infrastructure/RedisLogisticsWebhookReplayGuard.java");
        String stateMachine = read("src/main/java/com/example/monkey/logistics/domain/LogisticsTransitionPolicy.java");
        String addressParser =
                read("src/main/java/com/example/monkey/logistics/infrastructure/RuleBasedAddressParser.java");
        String rateLimit = read("src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java");
        String audit = read("src/main/java/com/example/monkey/shared/application/observability/AuditService.java");
        String frontendApi = read("frontend/src/api/logistics.ts");
        String logisticsView = read("frontend/src/views/LogisticsView.vue");
        String verifier = read("scripts/verify-ws7-logistics.ps1");

        assertThat(docs).contains("tracking state machine", "freight", "webhook replay", "PII");
        assertThat(trackingMigration).contains("CREATE TABLE logistics_tracking", "CREATE TABLE logistics_webhook_log");
        assertThat(trackingMigration).contains("recipient_phone_hmac", "version BIGINT");
        assertThat(freightMigration).contains("CREATE TABLE logistics_freight_template", "SF", "ZTO", "YTO");
        assertThat(service)
                .contains("@WithSpan(\"logistics.create\")", "@WithSpan(\"logistics.webhook\")", "idGenerator.nextId");
        assertThat(controller).contains("/shipments", "/freight/quote", "/address/parse", "/webhook");
        assertThat(store).contains("piiCryptoService.encrypt", "piiCryptoService.blindIndex");
        assertThat(replayGuard).contains("setIfAbsent", "LogisticsWebhookLogRepository");
        assertThat(stateMachine).contains("PICKUP", "DISPATCH", "SIGN");
        assertThat(addressParser).contains("Hangzou", "Hangzhou", "ParsedAddress");
        assertThat(rateLimit).contains("LOGISTICS(\"logistics\", 20");
        assertThat(audit).contains("LOGISTICS_SHIPMENT_CREATED", "LOGISTICS_WEBHOOK_ACCEPTED");
        assertThat(frontendApi).contains("createShipment", "quoteFreight", "pushWebhook");
        assertThat(logisticsView).contains("logisticsApi.createShipment", "logisticsApi.pushWebhook");
        assertThat(verifier).contains("LogisticsApplicationServiceTest", "Ws7LogisticsWorkflowTest");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
