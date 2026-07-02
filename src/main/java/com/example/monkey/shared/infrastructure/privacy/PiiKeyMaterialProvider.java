package com.example.monkey.shared.infrastructure.privacy;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PiiKeyMaterialProvider {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final KeyProvider keyProvider;
    private final String aesKeyBase64;
    private final String hmacKeyBase64;
    private final String previousAesKeysBase64;
    private final boolean rotationEnforced;
    private final Instant keyCreatedAt;
    private final Duration rotationMaxAge;
    private final String vaultAddress;
    private final String vaultToken;
    private final String vaultNamespace;
    private final String vaultTransitKeyName;
    private final String vaultAesCiphertext;
    private final String vaultHmacCiphertext;
    private final String vaultPreviousAesCiphertexts;
    private final Duration vaultTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public PiiKeyMaterialProvider(
            @Value("${app.pii.encryption.key-provider:env}") String keyProvider,
            @Value("${app.pii.encryption.aes-key-base64:}") String aesKeyBase64,
            @Value("${app.pii.encryption.hmac-key-base64:}") String hmacKeyBase64,
            @Value("${app.pii.encryption.previous-aes-keys-base64:}") String previousAesKeysBase64,
            @Value("${app.pii.encryption.key-created-at:}") String keyCreatedAt,
            @Value("${app.pii.encryption.rotation.enforce:false}") boolean rotationEnforced,
            @Value("${app.pii.encryption.rotation.max-age:PT2160H}") Duration rotationMaxAge,
            @Value("${app.pii.encryption.vault-transit.address:}") String vaultAddress,
            @Value("${app.pii.encryption.vault-transit.token:}") String vaultToken,
            @Value("${app.pii.encryption.vault-transit.namespace:}") String vaultNamespace,
            @Value("${app.pii.encryption.vault-transit.key-name:monkeyshop-pii}") String vaultTransitKeyName,
            @Value("${app.pii.encryption.vault-transit.aes-ciphertext:}") String vaultAesCiphertext,
            @Value("${app.pii.encryption.vault-transit.hmac-ciphertext:}") String vaultHmacCiphertext,
            @Value("${app.pii.encryption.vault-transit.previous-aes-ciphertexts:}") String vaultPreviousAesCiphertexts,
            @Value("${app.pii.encryption.vault-transit.timeout:PT3S}") Duration vaultTimeout,
            ObjectMapper objectMapper) {
        this(
                keyProvider,
                aesKeyBase64,
                hmacKeyBase64,
                previousAesKeysBase64,
                keyCreatedAt,
                rotationEnforced,
                rotationMaxAge,
                vaultAddress,
                vaultToken,
                vaultNamespace,
                vaultTransitKeyName,
                vaultAesCiphertext,
                vaultHmacCiphertext,
                vaultPreviousAesCiphertexts,
                vaultTimeout,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(vaultTimeout).build(),
                Clock.systemUTC());
    }

    PiiKeyMaterialProvider(
            String keyProvider,
            String aesKeyBase64,
            String hmacKeyBase64,
            String previousAesKeysBase64,
            String keyCreatedAt,
            boolean rotationEnforced,
            Duration rotationMaxAge,
            String vaultAddress,
            String vaultToken,
            String vaultNamespace,
            String vaultTransitKeyName,
            String vaultAesCiphertext,
            String vaultHmacCiphertext,
            String vaultPreviousAesCiphertexts,
            Duration vaultTimeout,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock) {
        this.keyProvider = KeyProvider.parse(keyProvider);
        this.aesKeyBase64 = aesKeyBase64;
        this.hmacKeyBase64 = hmacKeyBase64;
        this.previousAesKeysBase64 = previousAesKeysBase64;
        this.keyCreatedAt = parseKeyCreatedAt(keyCreatedAt);
        this.rotationEnforced = rotationEnforced;
        this.rotationMaxAge = rotationMaxAge;
        this.vaultAddress = vaultAddress;
        this.vaultToken = vaultToken;
        this.vaultNamespace = vaultNamespace;
        this.vaultTransitKeyName = vaultTransitKeyName;
        this.vaultAesCiphertext = vaultAesCiphertext;
        this.vaultHmacCiphertext = vaultHmacCiphertext;
        this.vaultPreviousAesCiphertexts = vaultPreviousAesCiphertexts;
        this.vaultTimeout = vaultTimeout;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public PiiKeyMaterial load(boolean required) {
        if (!required) {
            return PiiKeyMaterial.empty();
        }
        enforceRotationWindow();
        return switch (keyProvider) {
            case ENV -> fromEnvironment();
            case VAULT_TRANSIT -> fromVaultTransit();
        };
    }

    private PiiKeyMaterial fromEnvironment() {
        byte[] aes = decodeBase64(aesKeyBase64, "PII AES key is not configured");
        byte[] hmac = decodeBase64(hmacKeyBase64, "PII HMAC key is not configured");
        validateAesKey(aes);
        Map<String, byte[]> previousAesKeys = parseVersionedBase64Keys(previousAesKeysBase64);
        return new PiiKeyMaterial(aes, hmacSecret(hmac), previousAesKeys);
    }

    private PiiKeyMaterial fromVaultTransit() {
        requireText(vaultAddress, "Vault Transit address is not configured");
        requireText(vaultTransitKeyName, "Vault Transit key name is not configured");
        requireText(vaultAesCiphertext, "Vault Transit AES ciphertext is not configured");
        requireText(vaultHmacCiphertext, "Vault Transit HMAC ciphertext is not configured");

        byte[] aes = decryptVaultTransit(vaultAesCiphertext, "PII AES key");
        byte[] hmac = decryptVaultTransit(vaultHmacCiphertext, "PII HMAC key");
        validateAesKey(aes);
        Map<String, byte[]> previousAesKeys = decryptVersionedVaultTransitKeys(vaultPreviousAesCiphertexts);
        return new PiiKeyMaterial(aes, hmacSecret(hmac), previousAesKeys);
    }

    private byte[] decryptVaultTransit(String ciphertext, String label) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("ciphertext", ciphertext));
            HttpRequest.Builder builder = HttpRequest.newBuilder(vaultTransitUri())
                    .timeout(vaultTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (StringUtils.hasText(vaultToken)) {
                builder.header("X-Vault-Token", vaultToken);
            }
            if (StringUtils.hasText(vaultNamespace)) {
                builder.header("X-Vault-Namespace", vaultNamespace);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(label + " unwrap failed with Vault status " + response.statusCode());
            }
            JsonNode plaintext =
                    objectMapper.readTree(response.body()).path("data").path("plaintext");
            if (!plaintext.isTextual() || !StringUtils.hasText(plaintext.asText())) {
                throw new IllegalStateException(label + " unwrap response did not contain data.plaintext");
            }
            return decodeBase64(plaintext.asText(), label + " unwrap response was not valid base64");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, label + " unwrap failed");
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private URI vaultTransitUri() {
        String normalizedAddress = vaultAddress.replaceAll("/+$", "");
        return URI.create(normalizedAddress + "/v1/transit/decrypt/" + vaultTransitKeyName);
    }

    private Map<String, byte[]> decryptVersionedVaultTransitKeys(String configuredCiphertexts) {
        Map<String, String> entries = parseVersionedEntries(configuredCiphertexts, "PII previous Vault AES ciphertext");
        if (entries.isEmpty()) {
            return Map.of();
        }
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            byte[] key = decryptVaultTransit(entry.getValue(), "PII previous AES key " + entry.getKey());
            validateAesKey(key);
            keys.put(entry.getKey(), key);
        }
        return Map.copyOf(keys);
    }

    private void enforceRotationWindow() {
        if (!rotationEnforced) {
            return;
        }
        if (keyCreatedAt == null) {
            throw new IllegalStateException(
                    "PII key creation timestamp is required when rotation enforcement is enabled");
        }
        if (clock.instant().isAfter(keyCreatedAt.plus(rotationMaxAge))) {
            throw new IllegalStateException("PII key rotation window exceeded");
        }
    }

    private static SecretKey hmacSecret(byte[] hmacBytes) {
        if (hmacBytes.length == 0) {
            throw new IllegalStateException("PII HMAC key is not configured");
        }
        return new SecretKeySpec(Arrays.copyOf(hmacBytes, hmacBytes.length), HMAC_SHA256);
    }

    private static byte[] decodeBase64(String keyBase64, String message) {
        requireText(keyBase64, message);
        try {
            byte[] decoded = Base64.getDecoder().decode(keyBase64);
            return Arrays.copyOf(decoded, decoded.length);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private static Map<String, byte[]> parseVersionedBase64Keys(String configuredKeys) {
        Map<String, String> entries = parseVersionedEntries(configuredKeys, "PII previous AES key");
        if (entries.isEmpty()) {
            return Map.of();
        }
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            byte[] key = decodeBase64(entry.getValue(), "PII previous AES key " + entry.getKey() + " is invalid");
            validateAesKey(key);
            keys.put(entry.getKey(), key);
        }
        return Map.copyOf(keys);
    }

    private static Map<String, String> parseVersionedEntries(String configuredEntries, String label) {
        if (!StringUtils.hasText(configuredEntries)) {
            return Map.of();
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (String rawEntry : configuredEntries.split("[,;]")) {
            if (!StringUtils.hasText(rawEntry)) {
                continue;
            }
            int separator = rawEntry.indexOf('=');
            if (separator <= 0 || separator == rawEntry.length() - 1) {
                throw new IllegalStateException(label + " entries must use version=value");
            }
            String version = rawEntry.substring(0, separator).trim();
            String value = rawEntry.substring(separator + 1).trim();
            requireText(version, label + " version is not configured");
            requireText(value, label + " value is not configured");
            if (entries.putIfAbsent(version, value) != null) {
                throw new IllegalStateException(label + " version is duplicated: " + version);
            }
        }
        return Map.copyOf(entries);
    }

    private static void validateAesKey(byte[] keyBytes) {
        if (!(keyBytes.length == 16 || keyBytes.length == 24 || keyBytes.length == 32)) {
            throw new IllegalStateException("PII AES key must be 128, 192, or 256 bits");
        }
    }

    private static void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
    }

    private static Instant parseKeyCreatedAt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return LocalDate.parse(value.trim()).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
    }

    public record PiiKeyMaterial(byte[] aesKeyBytes, SecretKey hmacKey, Map<String, byte[]> previousAesKeys) {

        public PiiKeyMaterial(byte[] aesKeyBytes, SecretKey hmacKey) {
            this(aesKeyBytes, hmacKey, Map.of());
        }

        public PiiKeyMaterial {
            aesKeyBytes = aesKeyBytes == null ? null : Arrays.copyOf(aesKeyBytes, aesKeyBytes.length);
            previousAesKeys = copyVersionedKeys(previousAesKeys);
        }

        static PiiKeyMaterial empty() {
            return new PiiKeyMaterial(null, null, Map.of());
        }

        @Override
        public byte[] aesKeyBytes() {
            return aesKeyBytes == null ? null : Arrays.copyOf(aesKeyBytes, aesKeyBytes.length);
        }

        @Override
        public Map<String, byte[]> previousAesKeys() {
            return copyVersionedKeys(previousAesKeys);
        }

        private static Map<String, byte[]> copyVersionedKeys(Map<String, byte[]> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : source.entrySet()) {
                copy.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
            }
            return Map.copyOf(copy);
        }
    }

    private enum KeyProvider {
        ENV,
        VAULT_TRANSIT;

        static KeyProvider parse(String value) {
            String normalized = StringUtils.hasText(value) ? value.trim().replace('-', '_') : "env";
            try {
                return KeyProvider.valueOf(normalized.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Unsupported PII key provider: " + value, exception);
            }
        }
    }
}
