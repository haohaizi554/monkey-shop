package com.example.monkey.shared.interfaces.storage.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignedGetUrlRequestDto(
        @NotBlank(message = "objectKey is required") String objectKey) {

    public boolean avatarObject() {
        return normalizedObjectKey().startsWith("avatar/");
    }

    public boolean productObject() {
        return normalizedObjectKey().startsWith("product/");
    }

    private String normalizedObjectKey() {
        if (objectKey == null) {
            return "";
        }
        String normalized = objectKey.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
