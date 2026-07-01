package com.example.monkey.shared.domain.security;

public record JwtTokenPair(
        String accessToken,
        String refreshToken,
        String accessTokenId,
        String refreshTokenId,
        long accessTtlSeconds,
        long refreshTtlSeconds) {}
