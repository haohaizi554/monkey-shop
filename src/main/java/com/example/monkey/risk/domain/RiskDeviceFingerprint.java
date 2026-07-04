package com.example.monkey.risk.domain;

import java.time.LocalDateTime;

public record RiskDeviceFingerprint(
        Long id,
        Long userId,
        String deviceFingerprintHash,
        String clientIp,
        String phoneHmac,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        LocalDateTime expiresAt) {}
