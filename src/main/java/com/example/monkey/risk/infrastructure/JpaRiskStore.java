package com.example.monkey.risk.infrastructure;

import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.product.infrastructure.ProductSpu;
import com.example.monkey.product.infrastructure.ProductSpuRepository;
import com.example.monkey.risk.domain.RiskDeviceFingerprint;
import com.example.monkey.risk.domain.RiskReviewCase;
import com.example.monkey.risk.domain.RiskReviewStatus;
import com.example.monkey.risk.domain.RiskScore;
import com.example.monkey.risk.domain.RiskSignal;
import com.example.monkey.risk.domain.RiskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.risk.store", havingValue = "jpa", matchIfMissing = true)
public class JpaRiskStore implements RiskStore {

    private static final TypeReference<List<RiskSignal>> SIGNALS_TYPE = new TypeReference<>() {};

    private final RiskDeviceFingerprintRepository fingerprintRepository;
    private final RiskScoreRepository scoreRepository;
    private final RiskReviewCaseRepository reviewCaseRepository;
    private final ProductSpuRepository productSpuRepository;
    private final ObjectMapper objectMapper;

    public JpaRiskStore(
            RiskDeviceFingerprintRepository fingerprintRepository,
            RiskScoreRepository scoreRepository,
            RiskReviewCaseRepository reviewCaseRepository,
            ProductSpuRepository productSpuRepository,
            ObjectMapper objectMapper) {
        this.fingerprintRepository = fingerprintRepository;
        this.scoreRepository = scoreRepository;
        this.reviewCaseRepository = reviewCaseRepository;
        this.productSpuRepository = productSpuRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public RiskDeviceFingerprint saveDeviceFingerprint(RiskDeviceFingerprint fingerprint) {
        return toDomain(fingerprintRepository.save(toEntity(fingerprint)));
    }

    @Override
    public long countDistinctUsersByDevice(String deviceFingerprintHash, LocalDateTime since) {
        return fingerprintRepository.countDistinctUsersByDevice(deviceFingerprintHash, since);
    }

    @Override
    public long countDistinctPhonesByDevice(String deviceFingerprintHash, LocalDateTime since) {
        return fingerprintRepository.countDistinctPhonesByDevice(deviceFingerprintHash, since);
    }

    @Override
    public RiskScore saveRiskScore(RiskScore score) {
        return toDomain(scoreRepository.save(toEntity(score)));
    }

    @Override
    public Optional<RiskScore> findLatestScore(Long userId) {
        return scoreRepository.findFirstByUserIdOrderByAssessedAtDesc(userId).map(this::toDomain);
    }

    @Override
    public RiskReviewCase enqueueReview(RiskReviewCase reviewCase) {
        return saveReviewCase(reviewCase);
    }

    @Override
    public List<RiskReviewCase> findOpenReviewCases(int limit) {
        return reviewCaseRepository
                .findByStatusOrderByCreatedAtAsc(RiskReviewStatus.PENDING, PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(JpaRiskStore::toDomain)
                .toList();
    }

    @Override
    public Optional<RiskReviewCase> findReviewCase(Long caseId) {
        return reviewCaseRepository.findById(caseId).map(JpaRiskStore::toDomain);
    }

    @Override
    public RiskReviewCase saveReviewCase(RiskReviewCase reviewCase) {
        return toDomain(reviewCaseRepository.save(toEntity(reviewCase)));
    }

    @Override
    public boolean unlistProductForPriceAnomaly(Long productId) {
        Optional<ProductSpu> product = productSpuRepository.findById(productId);
        if (product.isEmpty()) {
            return false;
        }
        ProductSpu spu = product.get();
        if (spu.getStatus() == ProductStatus.UNLISTED || spu.getStatus() == ProductStatus.RECYCLED) {
            return false;
        }
        spu.setStatus(ProductStatus.UNLISTED);
        productSpuRepository.save(spu);
        return true;
    }

    private static RiskDeviceFingerprintEntity toEntity(RiskDeviceFingerprint fingerprint) {
        RiskDeviceFingerprintEntity entity = new RiskDeviceFingerprintEntity();
        entity.setId(fingerprint.id());
        entity.setUserId(fingerprint.userId());
        entity.setDeviceFingerprintHash(fingerprint.deviceFingerprintHash());
        entity.setClientIp(fingerprint.clientIp());
        entity.setPhoneHmac(fingerprint.phoneHmac());
        entity.setFirstSeenAt(fingerprint.firstSeenAt());
        entity.setLastSeenAt(fingerprint.lastSeenAt());
        entity.setExpiresAt(fingerprint.expiresAt());
        return entity;
    }

    private static RiskDeviceFingerprint toDomain(RiskDeviceFingerprintEntity entity) {
        return new RiskDeviceFingerprint(
                entity.getId(),
                entity.getUserId(),
                entity.getDeviceFingerprintHash(),
                entity.getClientIp(),
                entity.getPhoneHmac(),
                entity.getFirstSeenAt(),
                entity.getLastSeenAt(),
                entity.getExpiresAt());
    }

    private RiskScoreEntity toEntity(RiskScore score) {
        RiskScoreEntity entity = new RiskScoreEntity();
        entity.setId(score.id());
        entity.setUserId(score.userId());
        entity.setDeviceFingerprintHash(score.deviceFingerprintHash());
        entity.setPhoneHmac(score.phoneHmac());
        entity.setScore(score.score());
        entity.setDecision(score.decision());
        entity.setSignalsJson(writeSignals(score.signals()));
        entity.setAssessedAt(score.assessedAt());
        entity.setExpiresAt(score.expiresAt());
        entity.setVersion(score.version());
        return entity;
    }

    private RiskScore toDomain(RiskScoreEntity entity) {
        return new RiskScore(
                entity.getId(),
                entity.getUserId(),
                entity.getDeviceFingerprintHash(),
                entity.getPhoneHmac(),
                entity.getScore(),
                entity.getDecision(),
                readSignals(entity.getSignalsJson()),
                entity.getAssessedAt(),
                entity.getExpiresAt(),
                entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private static RiskReviewCaseEntity toEntity(RiskReviewCase reviewCase) {
        RiskReviewCaseEntity entity = new RiskReviewCaseEntity();
        entity.setId(reviewCase.id());
        entity.setUserId(reviewCase.userId());
        entity.setOrderId(reviewCase.orderId());
        entity.setProductId(reviewCase.productId());
        entity.setType(reviewCase.type());
        entity.setScore(reviewCase.score());
        entity.setStatus(reviewCase.status());
        entity.setDetail(reviewCase.detail());
        entity.setCreatedAt(reviewCase.createdAt());
        entity.setHandledAt(reviewCase.handledAt());
        entity.setHandlerUserId(reviewCase.handlerUserId());
        entity.setResolution(reviewCase.resolution());
        return entity;
    }

    private static RiskReviewCase toDomain(RiskReviewCaseEntity entity) {
        return new RiskReviewCase(
                entity.getId(),
                entity.getUserId(),
                entity.getOrderId(),
                entity.getProductId(),
                entity.getType(),
                entity.getScore(),
                entity.getStatus(),
                entity.getDetail(),
                entity.getCreatedAt(),
                entity.getHandledAt(),
                entity.getHandlerUserId(),
                entity.getResolution());
    }

    private String writeSignals(List<RiskSignal> signals) {
        try {
            return objectMapper.writeValueAsString(signals == null ? List.of() : signals);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Risk signals cannot be serialized", exception);
        }
    }

    private List<RiskSignal> readSignals(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, SIGNALS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Risk signals cannot be deserialized", exception);
        }
    }
}
