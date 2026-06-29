package com.example.monkey.dto;

import java.time.Instant;
import java.util.Map;

public record PresignedUploadResponseDto(
        String objectKey, String uploadUrl, Map<String, String> formData, String publicUrl, Instant expiresAt) {}
