package com.example.monkey.shared.application.storage.dto;

import java.time.Instant;

public record PresignedGetUrlResponseDto(String objectKey, String url, Instant expiresAt) {}
