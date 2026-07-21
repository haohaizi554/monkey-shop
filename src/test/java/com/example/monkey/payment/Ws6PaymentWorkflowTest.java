package com.example.monkey.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws6PaymentWorkflowTest {

    @Test
    void paymentArtifactsWireCallbacksRefundReconciliationAndFrontend() throws IOException {
        String docs = read("docs/payment/ws6.md");
        String paymentMigration = read("src/main/resources/db/migration/V30__payment_order.sql");
        String reconciliationMigration = read("src/main/resources/db/migration/V31__payment_reconciliation.sql");
        String service = read("src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java");
        String controller = read("src/main/java/com/example/monkey/payment/interfaces/PaymentController.java");
        String store = read("src/main/java/com/example/monkey/payment/infrastructure/JpaPaymentStore.java");
        String replayGuard =
                read("src/main/java/com/example/monkey/payment/infrastructure/RedisPaymentCallbackReplayGuard.java");
        String stateMachine = read("src/main/java/com/example/monkey/payment/domain/PaymentTransitionPolicy.java");
        String rateLimit = read("src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java");
        String audit = read("src/main/java/com/example/monkey/shared/application/observability/AuditService.java");
        String frontendApi = read("frontend/src/api/payments.ts");
        String paymentView = read("frontend/src/views/PaymentView.vue");
        String verifier = read("scripts/verify-ws6-payment.ps1");

        assertThat(docs).contains("callback replay", "TOTP", "bank-card PII", "daily reconciliation");
        assertThat(paymentMigration).contains("CREATE TABLE payment_order", "CREATE TABLE payment_ledger");
        assertThat(paymentMigration).contains("CREATE TABLE payment_callback_log", "bank_card_hmac");
        assertThat(reconciliationMigration).contains("CREATE TABLE payment_reconciliation_report");
        assertThat(service)
                .contains(
                        "@WithSpan(\"payment.create\")",
                        "verifySignature",
                        "requireCallbackSecret",
                        "APP_PAYMENT_CALLBACK_SECRET must be set",
                        "PaymentEvent.REFUND_PARTIAL");
        assertThat(service).contains("userMfaVerifier.verifyCode", "payment-query-timeout-orders");
        assertThat(controller).contains("/pay", "/callback", "/refund", "/reconciliation");
        assertThat(store).contains("piiCryptoService.encrypt", "piiCryptoService.blindIndex");
        assertThat(replayGuard)
                .contains("PaymentCallbackLogRepository", "callbackLogRepository.reserve", "publishAfterCommit")
                .contains("opsForValue().set")
                .doesNotContain("setIfAbsent");
        assertThat(stateMachine).contains("REFUND_PARTIAL", "SUSPEND");
        assertThat(rateLimit).contains("PAYMENT(\"payment\", 5");
        assertThat(audit).contains("PAYMENT_CREATED", "PAYMENT_RECONCILED");
        assertThat(frontendApi).contains("createPayment", "refundPayment", "reconcilePayment");
        assertThat(paymentView).contains("paymentsApi.createPayment", "paymentsApi.refundPayment");
        assertThat(verifier).contains("PaymentApplicationServiceTest", "Ws6PaymentWorkflowTest");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
