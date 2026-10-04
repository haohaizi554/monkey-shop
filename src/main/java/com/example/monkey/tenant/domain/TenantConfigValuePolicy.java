package com.example.monkey.tenant.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Defines the wire contract for tenant settings without making the setting names an authority boundary.
 *
 * <p>Only the small set of explicitly non-sensitive metadata keys is returned to an administrator. Every other
 * value is represented by {@link #MASKED_VALUE}; the presence of that reserved value means that a persisted value
 * exists and can be preserved by a subsequent update.
 */
public final class TenantConfigValuePolicy {

    public static final String MASKED_VALUE = "****";
    public static final int MAX_SETTINGS_ENTRIES = 64;
    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_VALUE_LENGTH = 4096;
    public static final Set<String> SAFE_METADATA_KEYS = Set.of("merchantId", "revision", "canaryWeight");

    private TenantConfigValuePolicy() {}

    public static Map<String, String> validateAndCopy(Map<String, String> settings) {
        if (settings == null) {
            throw invalid("Tenant settings are required");
        }
        if (settings.size() > MAX_SETTINGS_ENTRIES) {
            throw invalid("Tenant settings contain too many entries");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
                throw invalid("Tenant setting key is invalid");
            }
            if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
                throw invalid("Tenant setting value is invalid");
            }
            copy.put(key, value);
        }
        return copy;
    }

    /**
     * Resolves the reserved wire mask using the currently persisted values. A missing key is rejected so a client
     * cannot use the mask as a way to invent an unset secret.
     */
    public static Map<String, String> mergeMaskedValues(
            Map<String, String> requestedSettings, Map<String, String> persistedSettings) {
        Map<String, String> requested = validateAndCopy(requestedSettings);
        Map<String, String> persisted = persistedSettings == null ? Map.of() : persistedSettings;
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            if (!MASKED_VALUE.equals(entry.getValue())) {
                merged.put(entry.getKey(), entry.getValue());
                continue;
            }
            String persistedValue = persisted.get(entry.getKey());
            if (persistedValue == null || persistedValue.isBlank()) {
                throw invalid("Masked tenant setting does not exist");
            }
            merged.put(entry.getKey(), persistedValue);
        }
        return merged;
    }

    public static Map<String, String> maskForApi(Map<String, String> settings) {
        if (settings == null || settings.isEmpty()) {
            return Map.of();
        }
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            masked.put(
                    entry.getKey(),
                    SAFE_METADATA_KEYS.contains(entry.getKey()) ? entry.getValue() : MASKED_VALUE);
        }
        return Collections.unmodifiableMap(masked);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
