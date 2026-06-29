package com.example.monkey.dto;

import java.time.Instant;

public record PresignedGetUrlResponseDto(String objectKey, String url, Instant expiresAt) {}
