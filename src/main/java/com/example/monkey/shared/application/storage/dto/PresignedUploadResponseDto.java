package com.example.monkey.shared.application.storage.dto;

import java.time.Instant;
import java.util.Map;

public record PresignedUploadResponseDto(
        String objectKey, String uploadUrl, Map<String, String> formData, String publicUrl, Instant expiresAt) {}
