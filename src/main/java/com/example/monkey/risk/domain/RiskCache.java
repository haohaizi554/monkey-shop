package com.example.monkey.risk.domain;

import java.time.Duration;
import java.util.Optional;

public interface RiskCache {

    void rememberDeviceFingerprint(String deviceFingerprintHash, Long userId, String phoneHmac, Duration ttl);

    long countUsersForDevice(String deviceFingerprintHash);

    long countPhonesForDevice(String deviceFingerprintHash);

    long recordSeckillAttempt(Long activityId, Long productId, String deviceFingerprintHash, Long userId, Duration ttl);

    void cacheScore(RiskScore score, Duration ttl);

    Optional<RiskScore> findScore(Long userId);
}
