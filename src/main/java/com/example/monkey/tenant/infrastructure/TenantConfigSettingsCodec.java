package com.example.monkey.tenant.infrastructure;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.tenant.domain.TenantConfigValuePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.util.StringUtils;

/**
 * Encodes tenant settings as a JSON object whose values are individually authenticated ciphertexts.
 *
 * <p>The object shape deliberately remains {@code Map<String, String>} so the existing JSON column and older pods
 * can still parse it. Legacy plaintext values are accepted only while decoding or reconciling existing rows; every
 * new value is required to come back from {@link PiiCryptoService#encrypt(String)} as ciphertext.
 */
public final class TenantConfigSettingsCodec {

    private static final TypeReference<Map<String, String>> SETTINGS_TYPE = new TypeReference<>() {};

    private final PiiCryptoService piiCryptoService;
    private final ObjectMapper objectMapper;

    public TenantConfigSettingsCodec(PiiCryptoService piiCryptoService, ObjectMapper objectMapper) {
        this.piiCryptoService = Objects.requireNonNull(piiCryptoService, "piiCryptoService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String encodeForWrite(Map<String, String> requestedSettings, String existingSettingsJson) {
        return encodeForWrite(requestedSettings, decode(existingSettingsJson));
    }

    public String encodeForWrite(Map<String, String> requestedSettings, Map<String, String> persistedSettings) {
        requireEncryption();
        Map<String, String> merged = TenantConfigValuePolicy.mergeMaskedValues(requestedSettings, persistedSettings);
        return writeEncrypted(merged);
    }

    public Map<String, String> decode(String settingsJson) {
        requireEncryption();
        Map<String, String> stored = parse(settingsJson);
        Map<String, String> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                throw invalid("Tenant setting value is invalid");
            }
            decoded.put(entry.getKey(), piiCryptoService.decrypt(value));
        }
        return decoded;
    }

    /**
     * Re-encrypts only values that are not already accepted ciphertext. The returned boolean allows a caller to avoid
     * an unnecessary write and makes a compare-and-set reconciliation idempotent.
     */
    public ReconciledJson reconcile(String settingsJson) {
        requireEncryption();
        Map<String, String> stored = parse(settingsJson);
        Map<String, String> rewritten = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                throw invalid("Tenant setting value is invalid");
            }
            if (!StringUtils.hasText(value)) {
                // Blank values were never accepted by the new contract; drop malformed legacy entries rather than
                // carrying a non-ciphertext value forward forever.
                changed = true;
                continue;
            }
            String next = encryptForReconciliation(value);
            rewritten.put(entry.getKey(), next);
            changed |= !Objects.equals(value, next);
        }
        return new ReconciledJson(changed ? writeJson(rewritten) : settingsJson, changed);
    }

    private String writeEncrypted(Map<String, String> settings) {
        Map<String, String> encrypted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            encrypted.put(entry.getKey(), encryptRequired(entry.getValue()));
        }
        return writeJson(encrypted);
    }

    private String encryptRequired(String plaintext) {
        String ciphertext = piiCryptoService.encrypt(plaintext);
        if (!StringUtils.hasText(ciphertext) || Objects.equals(ciphertext, plaintext)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Tenant settings encryption failed");
        }
        return ciphertext;
    }

    private String encryptForReconciliation(String value) {
        String ciphertext = piiCryptoService.encrypt(value);
        if (!StringUtils.hasText(ciphertext)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Tenant settings encryption failed");
        }
        if (!Objects.equals(ciphertext, value)) {
            return ciphertext;
        }
        if (value.startsWith("enc:")) {
            piiCryptoService.decrypt(value);
            return value;
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Tenant settings encryption failed");
    }

    private Map<String, String> parse(String settingsJson) {
        if (!StringUtils.hasText(settingsJson)) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(settingsJson, SETTINGS_TYPE);
            if (parsed == null || parsed.size() > TenantConfigValuePolicy.MAX_SETTINGS_ENTRIES) {
                throw invalid("Tenant settings contain too many entries");
            }
            validateStoredKeys(parsed);
            return new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException exception) {
            throw invalid("Tenant settings cannot be deserialized");
        }
    }

    private static void validateStoredKeys(Map<String, String> settings) {
        for (String key : settings.keySet()) {
            if (key == null || key.isBlank() || key.length() > TenantConfigValuePolicy.MAX_KEY_LENGTH) {
                throw invalid("Tenant setting key is invalid");
            }
        }
    }

    private String writeJson(Map<String, String> settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tenant settings cannot be serialized", exception);
        }
    }

    void requireEncryption() {
        if (!piiCryptoService.encryptionEnabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Tenant settings encryption is required");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    public record ReconciledJson(String json, boolean changed) {}
}
