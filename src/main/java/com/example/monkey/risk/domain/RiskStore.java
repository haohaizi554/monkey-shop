package com.example.monkey.risk.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RiskStore {

    RiskDeviceFingerprint saveDeviceFingerprint(RiskDeviceFingerprint fingerprint);

    long countDistinctUsersByDevice(String deviceFingerprintHash, LocalDateTime since);

    long countDistinctPhonesByDevice(String deviceFingerprintHash, LocalDateTime since);

    RiskScore saveRiskScore(RiskScore score);

    Optional<RiskScore> findLatestScore(Long userId);

    RiskReviewCase enqueueReview(RiskReviewCase reviewCase);

    List<RiskReviewCase> findOpenReviewCases(int limit);

    Optional<RiskReviewCase> findReviewCase(Long caseId);

    RiskReviewCase saveReviewCase(RiskReviewCase reviewCase);

    boolean unlistProductForPriceAnomaly(Long productId);
}
