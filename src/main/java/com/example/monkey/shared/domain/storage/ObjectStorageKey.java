package com.example.monkey.shared.domain.storage;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;

public final class ObjectStorageKey {

    private ObjectStorageKey() {}

    public static String normalize(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "object key is required");
        }
        String normalized = objectKey.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("..") || normalized.endsWith("/")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "invalid object key");
        }
        return normalized;
    }
}
