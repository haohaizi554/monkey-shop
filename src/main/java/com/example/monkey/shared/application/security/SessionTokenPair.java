package com.example.monkey.shared.application.security;

public record SessionTokenPair(
        String accessToken,
        String refreshToken,
        String accessTokenId,
        String refreshTokenId,
        long accessTtlSeconds,
        long refreshTtlSeconds) {}
