package com.example.monkey.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.product.infrastructure.ProductSpu;
import com.example.monkey.product.infrastructure.ProductSpuRepository;
import com.example.monkey.risk.domain.RiskDecision;
import com.example.monkey.risk.domain.RiskDeviceFingerprint;
import com.example.monkey.risk.domain.RiskReviewCase;
import com.example.monkey.risk.domain.RiskReviewStatus;
import com.example.monkey.risk.domain.RiskScore;
import com.example.monkey.risk.domain.RiskSignal;
import com.example.monkey.risk.domain.RiskSignalType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class JpaRiskStoreTest {

    @Mock
    private RiskDeviceFingerprintRepository fingerprintRepository;

    @Mock
    private RiskScoreRepository scoreRepository;

    @Mock
    private RiskReviewCaseRepository reviewCaseRepository;

    @Mock
    private ProductSpuRepository productSpuRepository;

    private JpaRiskStore store;

    @BeforeEach
    void setUp() {
        store = new JpaRiskStore(
                fingerprintRepository,
                scoreRepository,
                reviewCaseRepository,
                productSpuRepository,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void saveDeviceFingerprintMapsDomainAndDelegatesDistinctCounters() {
        when(fingerprintRepository.save(any(RiskDeviceFingerprintEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fingerprintRepository.countDistinctUsersByDevice(
                        "device-hmac", LocalDateTime.parse("2026-07-01T00:00:00")))
                .thenReturn(3L);
        when(fingerprintRepository.countDistinctPhonesByDevice(
                        "device-hmac", LocalDateTime.parse("2026-07-01T00:00:00")))
                .thenReturn(2L);

        RiskDeviceFingerprint saved = store.saveDeviceFingerprint(new RiskDeviceFingerprint(
                101L,
                42L,
                "device-hmac",
                "198.51.100.10",
                "phone-hmac",
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T08:01:00"),
                LocalDateTime.parse("2026-08-03T08:00:00")));

        RiskDeviceFingerprintEntity entity = captureFingerprint();
        assertThat(entity.getUserId()).isEqualTo(42L);
        assertThat(entity.getPhoneHmac()).isEqualTo("phone-hmac");
        assertThat(saved.deviceFingerprintHash()).isEqualTo("device-hmac");
        assertThat(store.countDistinctUsersByDevice("device-hmac", LocalDateTime.parse("2026-07-01T00:00:00")))
                .isEqualTo(3L);
        assertThat(store.countDistinctPhonesByDevice("device-hmac", LocalDateTime.parse("2026-07-01T00:00:00")))
                .isEqualTo(2L);
    }

    @Test
    void saveRiskScoreSerializesSignalsAndFindsLatestScore() {
        when(scoreRepository.save(any(RiskScoreEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RiskScore saved = store.saveRiskScore(new RiskScore(
                201L,
                42L,
                "device-hmac",
                "phone-hmac",
                88,
                RiskDecision.TOTP_REQUIRED,
                List.of(new RiskSignal(RiskSignalType.PRICE_ANOMALY, 65, "price change=0.75")),
                LocalDateTime.parse("2026-07-04T09:00:00"),
                LocalDateTime.parse("2026-07-04T09:30:00"),
                7L));

        RiskScoreEntity entity = captureScore();
        assertThat(entity.getSignalsJson()).contains("PRICE_ANOMALY");
        assertThat(saved.signals()).extracting(RiskSignal::type).containsExactly(RiskSignalType.PRICE_ANOMALY);

        RiskScoreEntity latest = new RiskScoreEntity();
        latest.setId(202L);
        latest.setUserId(42L);
        latest.setDeviceFingerprintHash("device-hmac");
        latest.setPhoneHmac("phone-hmac");
        latest.setScore(12);
        latest.setDecision(RiskDecision.ALLOW);
        latest.setSignalsJson(" ");
        latest.setAssessedAt(LocalDateTime.parse("2026-07-04T10:00:00"));
        latest.setExpiresAt(LocalDateTime.parse("2026-07-04T10:30:00"));
        latest.setVersion(null);
        when(scoreRepository.findFirstByUserIdOrderByAssessedAtDesc(42L)).thenReturn(Optional.of(latest));

        RiskScore found = store.findLatestScore(42L).orElseThrow();
        assertThat(found.signals()).isEmpty();
        assertThat(found.version()).isZero();
    }

    @Test
    void reviewCasesRoundTripThroughQueueAndLookup() {
        when(reviewCaseRepository.save(any(RiskReviewCaseEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        RiskReviewCase reviewCase = new RiskReviewCase(
                301L,
                42L,
                501L,
                701L,
                RiskSignalType.SELF_BUY,
                85,
                RiskReviewStatus.PENDING,
                "buyer matches seller",
                LocalDateTime.parse("2026-07-04T10:00:00"),
                null,
                null,
                null);

        RiskReviewCase saved = store.enqueueReview(reviewCase);
        RiskReviewCaseEntity entity = captureReviewCase();
        assertThat(entity.getType()).isEqualTo(RiskSignalType.SELF_BUY);
        assertThat(saved.status()).isEqualTo(RiskReviewStatus.PENDING);

        when(reviewCaseRepository.findByStatusOrderByCreatedAtAsc(any(RiskReviewStatus.class), any(Pageable.class)))
                .thenReturn(List.of(entity));
        when(reviewCaseRepository.findById(301L)).thenReturn(Optional.of(entity));

        assertThat(store.findOpenReviewCases(0)).extracting(RiskReviewCase::id).containsExactly(301L);
        assertThat(store.findReviewCase(301L)).contains(saved);
    }

    @Test
    void unlistProductForPriceAnomalyOnlyPersistsActiveProducts() {
        when(productSpuRepository.findById(404L)).thenReturn(Optional.empty());
        assertThat(store.unlistProductForPriceAnomaly(404L)).isFalse();

        ProductSpu recycled = new ProductSpu(11L);
        recycled.setStatus(ProductStatus.RECYCLED);
        when(productSpuRepository.findById(11L)).thenReturn(Optional.of(recycled));
        assertThat(store.unlistProductForPriceAnomaly(11L)).isFalse();

        ProductSpu listed = new ProductSpu(12L);
        listed.setStatus(ProductStatus.LISTED);
        when(productSpuRepository.findById(12L)).thenReturn(Optional.of(listed));
        assertThat(store.unlistProductForPriceAnomaly(12L)).isTrue();
        assertThat(listed.getStatus()).isEqualTo(ProductStatus.UNLISTED);
        verify(productSpuRepository).save(listed);
        verify(productSpuRepository, never()).save(recycled);
    }

    private RiskDeviceFingerprintEntity captureFingerprint() {
        ArgumentCaptor<RiskDeviceFingerprintEntity> captor = ArgumentCaptor.forClass(RiskDeviceFingerprintEntity.class);
        verify(fingerprintRepository).save(captor.capture());
        return captor.getValue();
    }

    private RiskScoreEntity captureScore() {
        ArgumentCaptor<RiskScoreEntity> captor = ArgumentCaptor.forClass(RiskScoreEntity.class);
        verify(scoreRepository).save(captor.capture());
        return captor.getValue();
    }

    private RiskReviewCaseEntity captureReviewCase() {
        ArgumentCaptor<RiskReviewCaseEntity> captor = ArgumentCaptor.forClass(RiskReviewCaseEntity.class);
        verify(reviewCaseRepository).save(captor.capture());
        return captor.getValue();
    }
}
