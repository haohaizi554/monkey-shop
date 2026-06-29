package com.example.monkey.infrastructure.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.infrastructure.privacy.PiiKeyMaterialProvider.PiiKeyMaterial;
import com.example.monkey.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PiiKeyMaterialProviderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void loadsEnvironmentKeyMaterial() {
        PiiKeyMaterialProvider provider =
                provider("env", base64(new byte[32]), base64(new byte[32]), "2026-06-01T00:00:00Z", true, "", "", "");

        PiiKeyMaterial material = provider.load(true);

        assertThat(material.aesKeyBytes()).hasSize(32);
        assertThat(material.hmacKey()).isNotNull();
    }

    @Test
    void optionalLoadReturnsEmptyMaterialWithoutReadingKeys() {
        PiiKeyMaterialProvider provider = provider("env", "", "", "", false, "", "", "");

        PiiKeyMaterial material = provider.load(false);

        assertThat(material.aesKeyBytes()).isNull();
        assertThat(material.hmacKey()).isNull();
    }

    @Test
    void environmentKeyMaterialRequiresValidBase64AndAesLength() {
        PiiKeyMaterialProvider missingAes = provider("env", "", base64(new byte[32]), "", false, "", "", "");
        PiiKeyMaterialProvider invalidBase64 =
                provider("env", "not-base64", base64(new byte[32]), "", false, "", "", "");
        PiiKeyMaterialProvider invalidAesLength =
                provider("env", base64(new byte[3]), base64(new byte[32]), "", false, "", "", "");
        PiiKeyMaterialProvider missingHmac = provider("env", base64(new byte[32]), "", "", false, "", "", "");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> missingAes.load(true))
                .withMessage("PII AES key is not configured");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> invalidBase64.load(true))
                .withMessage("PII AES key is not configured");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> invalidAesLength.load(true))
                .withMessage("PII AES key must be 128, 192, or 256 bits");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> missingHmac.load(true))
                .withMessage("PII HMAC key is not configured");
    }

    @Test
    void unsupportedProviderIsRejected() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> provider("kms", base64(new byte[32]), base64(new byte[32]), "", false, "", "", ""))
                .withMessageContaining("Unsupported PII key provider");
    }

    @Test
    void rotationEnforcementRejectsStaleKeyMaterial() {
        PiiKeyMaterialProvider provider =
                provider("env", base64(new byte[32]), base64(new byte[32]), "2026-01-01T00:00:00Z", true, "", "", "");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> provider.load(true))
                .withMessage("PII key rotation window exceeded");
    }

    @Test
    void unwrapsKeyMaterialWithVaultTransit() throws Exception {
        byte[] aesKey = filled(32, 7);
        byte[] hmacKey = filled(32, 9);
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> namespace = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/transit/decrypt/monkeyshop-pii", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            namespace.set(exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String plaintext = requestBody.contains("vault:v1:aes") ? base64(aesKey) : base64(hmacKey);
            byte[] response = ("{\"data\":{\"plaintext\":\"" + plaintext + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            PiiKeyMaterialProvider provider = provider(
                    "vault-transit",
                    "",
                    "",
                    "2026-06-01",
                    true,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "vault:v1:aes",
                    "vault:v1:hmac");

            PiiKeyMaterial material = provider.load(true);

            assertThat(material.aesKeyBytes()).containsExactly(aesKey);
            assertThat(material.hmacKey()).isNotNull();
            assertThat(token).hasValue("test-token");
            assertThat(namespace).hasValue("monkeyshop");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void vaultTransitFailureIsServiceUnavailable() {
        PiiKeyMaterialProvider provider = provider(
                "vault-transit", "", "", "2026-06-01", true, "http://127.0.0.1:1", "vault:v1:aes", "vault:v1:hmac");

        assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> provider.load(true));
    }

    @Test
    void vaultTransitRequiresConfigurationBeforeCallingVault() {
        PiiKeyMaterialProvider provider =
                provider("vault-transit", "", "", "2026-06-01", true, "", "vault:v1:aes", "vault:v1:hmac");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> provider.load(true))
                .withMessage("Vault Transit address is not configured");
    }

    @Test
    void vaultTransitRejectsNonSuccessAndMalformedResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/transit/decrypt/monkeyshop-pii", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (requestBody.contains("vault:v1:aes")) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                byte[] response = "{\"data\":{\"plaintext\":\"\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            PiiKeyMaterialProvider provider = provider(
                    "vault-transit",
                    "",
                    "",
                    "2026-06-01",
                    true,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "vault:v1:aes",
                    "vault:v1:hmac");

            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> provider.load(true))
                    .withMessage("PII AES key unwrap failed with Vault status 500");
        } finally {
            server.stop(0);
        }
    }

    private static PiiKeyMaterialProvider provider(
            String keyProvider,
            String aesKey,
            String hmacKey,
            String keyCreatedAt,
            boolean rotationEnforced,
            String vaultAddress,
            String vaultAesCiphertext,
            String vaultHmacCiphertext) {
        return new PiiKeyMaterialProvider(
                keyProvider,
                aesKey,
                hmacKey,
                keyCreatedAt,
                rotationEnforced,
                Duration.ofDays(90),
                vaultAddress,
                "test-token",
                "monkeyshop",
                "monkeyshop-pii",
                vaultAesCiphertext,
                vaultHmacCiphertext,
                Duration.ofSeconds(2),
                OBJECT_MAPPER,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                NOW);
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] filled(int size, int value) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) value;
        }
        return bytes;
    }
}
