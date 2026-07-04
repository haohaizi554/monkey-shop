package com.example.monkey.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.risk.application.dto.RiskAssessmentRequestDto;
import com.example.monkey.risk.application.dto.RiskReviewResolveRequestDto;
import com.example.monkey.risk.domain.RiskBlindIndexService;
import com.example.monkey.risk.domain.RiskCache;
import com.example.monkey.risk.domain.RiskDecision;
import com.example.monkey.risk.domain.RiskDeviceFingerprint;
import com.example.monkey.risk.domain.RiskReviewCase;
import com.example.monkey.risk.domain.RiskReviewStatus;
import com.example.monkey.risk.domain.RiskScore;
import com.example.monkey.risk.domain.RiskStore;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.domain.SessionTokenService;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskApplicationServiceTest {

    private final FakeRiskStore riskStore = new FakeRiskStore();
    private final FakeRiskCache riskCache = new FakeRiskCache();
    private final RiskBlindIndexService riskBlindIndexService = mock(RiskBlindIndexService.class);
    private final UserAccountStore userAccountStore = mock(UserAccountStore.class);
    private final UserMfaVerifier userMfaVerifier = mock(UserMfaVerifier.class);
    private final SessionTokenService sessionTokenService = mock(SessionTokenService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BusinessMetricsService businessMetricsService = mock(BusinessMetricsService.class);
    private final RiskApplicationService service = new RiskApplicationService(
            riskStore,
            riskCache,
            riskBlindIndexService,
            userAccountStore,
            userMfaVerifier,
            sessionTokenService,
            new IncrementingIdGenerator(),
            auditService,
            businessMetricsService,
            Clock.fixed(java.time.Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC),
            Duration.ofDays(30),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30));

    @BeforeEach
    void setUp() {
        when(riskBlindIndexService.blindIndex("browser-a")).thenReturn("device-hmac");
        when(riskBlindIndexService.phoneBlindIndex(anyString()))
                .thenAnswer(invocation -> "phone-" + invocation.getArgument(0, String.class));
        for (long userId = 1; userId <= 9; userId++) {
            when(userAccountStore.findById(userId)).thenReturn(Optional.of(account(userId, false)));
        }
    }

    @Test
    void woolPartyMultiAccountDetectionUsesPhoneBlindIndexAndQueuesReview() {
        assess(1L, "13800000001", null, null, null);
        assess(2L, "13800000002", null, null, null);
        assess(3L, "13800000003", null, null, null);

        var result = assess(4L, "13800000004", null, null, null);

        assertThat(result.decision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(result.score()).isGreaterThanOrEqualTo(60);
        assertThat(result.reviewCaseId()).isNotNull();
        assertThat(riskStore.reviews).hasSize(1);
        verify(riskBlindIndexService).phoneBlindIndex("13800000004");
    }

    @Test
    void seckillScalperBlocksAndRevokesCurrentUserTokens() {
        assess(1L, "13800000001", 99L, 20L, null);
        assess(2L, "13800000002", 99L, 20L, null);

        var result = assess(3L, "13800000003", 99L, 20L, null);

        assertThat(result.decision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(result.userTokensRevoked()).isTrue();
        verify(sessionTokenService).revokeUserTokens(3L);
        verify(businessMetricsService).recordRiskDecision(result.score(), false, true);
    }

    @Test
    void priceAnomalyAutoUnlistsProductAndQueuesReview() {
        var result = service.assess(
                new SessionUser(7L, "USER"),
                new RiskAssessmentRequestDto(
                        "13800000007",
                        "browser-a",
                        null,
                        20L,
                        30L,
                        null,
                        null,
                        new BigDecimal("100.00"),
                        new BigDecimal("170.00"),
                        null),
                "203.0.113.7");

        assertThat(result.productAutoUnlisted()).isTrue();
        assertThat(result.decision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(result.reviewCaseId()).isNotNull();
        assertThat(riskStore.unlistedProductIds).containsExactly(20L);
    }

    @Test
    void requireAllowedRejectsRateLimitDecisionBeforeBusinessAction() {
        assess(1L, "13800000001", null, null, null);
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(account(7L, true)));
        when(userMfaVerifier.verifyCode("secret-7", "739421")).thenReturn(true);

        assertThatThrownBy(() -> service.requireAllowed(
                        new SessionUser(7L, "USER"),
                        new RiskAssessmentRequestDto(
                                "13800000007",
                                "browser-a",
                                null,
                                20L,
                                30L,
                                null,
                                null,
                                new BigDecimal("100.00"),
                                new BigDecimal("170.00"),
                                "739421"),
                        "203.0.113.7",
                        "order.create"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));
    }

    @Test
    void blockingManualReviewRequiresAdminTotpAndRevokesReviewedUserTokens() {
        RiskReviewCase queued = riskStore.enqueueReview(new RiskReviewCase(
                100L,
                8L,
                30L,
                20L,
                com.example.monkey.risk.domain.RiskSignalType.SECKILL_SCALPER,
                90,
                RiskReviewStatus.PENDING,
                "scalper",
                LocalDateTime.now(),
                null,
                null,
                null));
        when(userAccountStore.findById(9L)).thenReturn(Optional.of(account(9L, true)));
        when(userMfaVerifier.verifyCode("secret-9", "654321")).thenReturn(true);

        var resolved = service.resolveReview(
                new SessionUser(9L, "ADMIN"),
                queued.id(),
                new RiskReviewResolveRequestDto(RiskReviewStatus.BLOCKED, "confirmed", "654321"),
                "203.0.113.9");

        assertThat(resolved.status()).isEqualTo(RiskReviewStatus.BLOCKED);
        verify(sessionTokenService).revokeUserTokens(8L);
        verify(userMfaVerifier).verifyCode("secret-9", "654321");
    }

    private com.example.monkey.risk.application.dto.RiskAssessmentResponseDto assess(
            Long userId, String phone, Long seckillActivityId, Long productId, Long sellerUserId) {
        return service.assess(
                new SessionUser(userId, "USER"),
                new RiskAssessmentRequestDto(
                        phone, "browser-a", null, productId, null, seckillActivityId, sellerUserId, null, null, null),
                "203.0.113.1");
    }

    private static UserAccount account(Long userId, boolean mfaEnabled) {
        return new UserAccount(
                userId,
                "user" + userId,
                "hash",
                "1380000000" + userId,
                null,
                null,
                mfaEnabled ? "ADMIN" : "USER",
                null,
                LocalDateTime.now(),
                false,
                "secret-" + userId,
                mfaEnabled,
                List.of("RISK_WRITE", "RISK_REVIEW"));
    }

    private static final class IncrementingIdGenerator implements IdGenerator {
        private final AtomicLong next = new AtomicLong(1000);

        @Override
        public long nextId() {
            return next.incrementAndGet();
        }
    }

    private static final class FakeRiskStore implements RiskStore {
        private final List<RiskDeviceFingerprint> fingerprints = new ArrayList<>();
        private final List<RiskScore> scores = new ArrayList<>();
        private final List<RiskReviewCase> reviews = new ArrayList<>();
        private final List<Long> unlistedProductIds = new ArrayList<>();

        @Override
        public RiskDeviceFingerprint saveDeviceFingerprint(RiskDeviceFingerprint fingerprint) {
            fingerprints.add(fingerprint);
            return fingerprint;
        }

        @Override
        public long countDistinctUsersByDevice(String deviceFingerprintHash, LocalDateTime since) {
            return fingerprints.stream()
                    .filter(item -> item.deviceFingerprintHash().equals(deviceFingerprintHash))
                    .filter(item -> !item.lastSeenAt().isBefore(since))
                    .map(RiskDeviceFingerprint::userId)
                    .distinct()
                    .count();
        }

        @Override
        public long countDistinctPhonesByDevice(String deviceFingerprintHash, LocalDateTime since) {
            return fingerprints.stream()
                    .filter(item -> item.deviceFingerprintHash().equals(deviceFingerprintHash))
                    .filter(item -> item.phoneHmac() != null)
                    .filter(item -> !item.lastSeenAt().isBefore(since))
                    .map(RiskDeviceFingerprint::phoneHmac)
                    .distinct()
                    .count();
        }

        @Override
        public RiskScore saveRiskScore(RiskScore score) {
            scores.add(score);
            return score;
        }

        @Override
        public Optional<RiskScore> findLatestScore(Long userId) {
            return scores.stream()
                    .filter(score -> score.userId().equals(userId))
                    .reduce((first, second) -> second);
        }

        @Override
        public RiskReviewCase enqueueReview(RiskReviewCase reviewCase) {
            reviews.add(reviewCase);
            return reviewCase;
        }

        @Override
        public List<RiskReviewCase> findOpenReviewCases(int limit) {
            return reviews.stream()
                    .filter(review -> review.status() == RiskReviewStatus.PENDING)
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<RiskReviewCase> findReviewCase(Long caseId) {
            return reviews.stream().filter(review -> review.id().equals(caseId)).findFirst();
        }

        @Override
        public RiskReviewCase saveReviewCase(RiskReviewCase reviewCase) {
            reviews.removeIf(existing -> existing.id().equals(reviewCase.id()));
            reviews.add(reviewCase);
            return reviewCase;
        }

        @Override
        public boolean unlistProductForPriceAnomaly(Long productId) {
            unlistedProductIds.add(productId);
            return true;
        }
    }

    private static final class FakeRiskCache implements RiskCache {
        private final Map<String, List<String>> usersByDevice = new LinkedHashMap<>();
        private final Map<String, List<String>> phonesByDevice = new LinkedHashMap<>();
        private final Map<String, List<String>> seckillUsers = new LinkedHashMap<>();
        private final Map<Long, RiskScore> scores = new LinkedHashMap<>();

        @Override
        public void rememberDeviceFingerprint(
                String deviceFingerprintHash, Long userId, String phoneHmac, Duration ttl) {
            usersByDevice
                    .computeIfAbsent(deviceFingerprintHash, ignored -> new ArrayList<>())
                    .add(userId.toString());
            phonesByDevice
                    .computeIfAbsent(deviceFingerprintHash, ignored -> new ArrayList<>())
                    .add(phoneHmac);
        }

        @Override
        public long countUsersForDevice(String deviceFingerprintHash) {
            return usersByDevice.getOrDefault(deviceFingerprintHash, List.of()).stream()
                    .distinct()
                    .count();
        }

        @Override
        public long countPhonesForDevice(String deviceFingerprintHash) {
            return phonesByDevice.getOrDefault(deviceFingerprintHash, List.of()).stream()
                    .distinct()
                    .count();
        }

        @Override
        public long recordSeckillAttempt(
                Long activityId, Long productId, String deviceFingerprintHash, Long userId, Duration ttl) {
            String key = activityId + ":" + productId + ":" + deviceFingerprintHash;
            seckillUsers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(userId.toString());
            return seckillUsers.get(key).stream().distinct().count();
        }

        @Override
        public void cacheScore(RiskScore score, Duration ttl) {
            scores.put(score.userId(), score);
        }

        @Override
        public Optional<RiskScore> findScore(Long userId) {
            return Optional.ofNullable(scores.get(userId));
        }
    }
}
