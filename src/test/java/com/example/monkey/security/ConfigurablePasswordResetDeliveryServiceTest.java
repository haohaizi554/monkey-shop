package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConfigurablePasswordResetDeliveryServiceTest {

    @Test
    void disabledModeFailsClosed() {
        ConfigurablePasswordResetDeliveryService delivery = new ConfigurablePasswordResetDeliveryService(
                "disabled", "", "", "", Duration.ofSeconds(1), HttpClient.newHttpClient(), new ObjectMapper());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> delivery.sendSmsOtp("18888888888", "654321"))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void unknownModeFallsBackToDisabled() {
        ConfigurablePasswordResetDeliveryService delivery = new ConfigurablePasswordResetDeliveryService(
                "bogus", "", "", "", Duration.ofSeconds(1), HttpClient.newHttpClient(), new ObjectMapper());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> delivery.sendEmailToken("alice@example.com", "token"))
                .withMessage("password reset email delivery is disabled");
    }

    @Test
    void loggingModeMasksRecipientsWithoutThrowing() {
        ConfigurablePasswordResetDeliveryService delivery = new ConfigurablePasswordResetDeliveryService(
                "logging", "", "", "", Duration.ofSeconds(1), HttpClient.newHttpClient(), new ObjectMapper());

        delivery.sendSmsOtp("18888888888", "654321");
        delivery.sendSmsOtp("12", "654321");
        delivery.sendSmsOtp(" ", "654321");
        delivery.sendEmailToken("alice@example.com", "email-token");
        delivery.sendEmailToken("ab@example.com", "email-token");
        delivery.sendEmailToken("not-an-email", "email-token");

        assertThat(maskPhone("18888888888")).isEqualTo("188****8888");
        assertThat(maskPhone("12")).isEqualTo("***");
        assertThat(maskPhone(" ")).isEqualTo("unknown");
        assertThat(maskEmail("alice@example.com")).isEqualTo("al***@example.com");
        assertThat(maskEmail("ab@example.com")).isEqualTo("**@example.com");
        assertThat(maskEmail("not-an-email")).isEqualTo("unknown");
    }

    @Test
    void webhookModePostsSmsAndEmailResetChallenges() throws IOException {
        List<CapturedRequest> capturedRequests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.createContext("/deliver", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            String sharedKey = exchange.getRequestHeaders().getFirst("X-Password-Reset-Delivery-Secret");
            capturedRequests.add(new CapturedRequest(body, sharedKey));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.setExecutor(executor);
        server.start();
        try {
            String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/deliver";
            ConfigurablePasswordResetDeliveryService delivery = new ConfigurablePasswordResetDeliveryService(
                    "webhook",
                    webhookUrl,
                    webhookUrl,
                    "unit-test-shared-key",
                    Duration.ofSeconds(2),
                    HttpClient.newHttpClient(),
                    new ObjectMapper());

            delivery.sendSmsOtp("18888888888", "654321");
            delivery.sendEmailToken("alice@example.com", "email-token");

            assertThat(capturedRequests).hasSize(2);
            assertThat(capturedRequests)
                    .extracting(CapturedRequest::sharedKey)
                    .containsExactly("unit-test-shared-key", "unit-test-shared-key");
            assertThat(capturedRequests.get(0).body())
                    .contains("\"channel\":\"sms\"")
                    .contains("\"recipient\":\"18888888888\"")
                    .contains("\"code\":\"654321\"");
            assertThat(capturedRequests.get(1).body())
                    .contains("\"channel\":\"email\"")
                    .contains("\"recipient\":\"alice@example.com\"")
                    .contains("\"token\":\"email-token\"");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void webhookModeRejectsNonSuccessResponses() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/deliver", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/deliver";
            ConfigurablePasswordResetDeliveryService delivery = new ConfigurablePasswordResetDeliveryService(
                    "webhook",
                    webhookUrl,
                    webhookUrl,
                    "unit-test-shared-key",
                    Duration.ofSeconds(2),
                    HttpClient.newHttpClient(),
                    new ObjectMapper());

            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> delivery.sendSmsOtp("18888888888", "654321"))
                    .withMessage("password reset delivery webhook returned 503");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webhookModeRejectsInvalidWebhookUri() {
        ConfigurablePasswordResetDeliveryService delivery = new ConfigurablePasswordResetDeliveryService(
                "webhook",
                "://bad-url",
                "://bad-url",
                "unit-test-shared-key",
                Duration.ofSeconds(1),
                HttpClient.newHttpClient(),
                new ObjectMapper());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> delivery.sendSmsOtp("18888888888", "654321"))
                .withMessage("password reset delivery webhook is invalid");
    }

    @Test
    void webhookModeRequiresUrlAndSharedSecret() {
        ConfigurablePasswordResetDeliveryService delivery = new ConfigurablePasswordResetDeliveryService(
                "webhook", "", "", "", Duration.ofSeconds(1), HttpClient.newHttpClient(), new ObjectMapper());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> delivery.sendSmsOtp("18888888888", "654321"))
                .withMessage("password reset delivery webhook is not configured")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @SuppressWarnings("ConstantConditions")
    private static String maskPhone(String phone) {
        return ReflectionTestUtils.invokeMethod(ConfigurablePasswordResetDeliveryService.class, "maskPhone", phone);
    }

    @SuppressWarnings("ConstantConditions")
    private static String maskEmail(String email) {
        return ReflectionTestUtils.invokeMethod(ConfigurablePasswordResetDeliveryService.class, "maskEmail", email);
    }

    private record CapturedRequest(String body, String sharedKey) {}
}
