package com.example.monkey.risk.domain;

import java.time.LocalDateTime;
import java.util.List;

public record RiskScore(
        Long id,
        Long userId,
        String deviceFingerprintHash,
        String phoneHmac,
        int score,
        RiskDecision decision,
        List<RiskSignal> signals,
        LocalDateTime assessedAt,
        LocalDateTime expiresAt,
        long version) {
    public RiskScore {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }
}
