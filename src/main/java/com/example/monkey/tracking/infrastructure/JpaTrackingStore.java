package com.example.monkey.tracking.infrastructure;

import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.tracking.domain.ProductProfile;
import com.example.monkey.tracking.domain.TrackingEvent;
import com.example.monkey.tracking.domain.TrackingEventType;
import com.example.monkey.tracking.domain.TrackingStore;
import com.example.monkey.tracking.domain.UserProfileTag;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.tracking.store", havingValue = "jpa", matchIfMissing = true)
public class JpaTrackingStore implements TrackingStore {

    private static final TypeReference<Map<String, String>> ATTRIBUTES_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {};

    private final TrackingEventRepository eventRepository;
    private final UserProfileTagRepository userProfileTagRepository;
    private final ProductProfileRepository productProfileRepository;
    private final PiiCryptoService piiCryptoService;
    private final ObjectMapper objectMapper;

    public JpaTrackingStore(
            TrackingEventRepository eventRepository,
            UserProfileTagRepository userProfileTagRepository,
            ProductProfileRepository productProfileRepository,
            PiiCryptoService piiCryptoService,
            ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.userProfileTagRepository = userProfileTagRepository;
        this.productProfileRepository = productProfileRepository;
        this.piiCryptoService = piiCryptoService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TrackingEvent saveEvent(TrackingEvent event) {
        return toDomain(eventRepository.save(toEntity(event)));
    }

    @Override
    public List<TrackingEvent> findRecentEvents(LocalDateTime since, int limit) {
        return eventRepository
                .findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(since, PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countEvents(TrackingEventType eventType, LocalDateTime since) {
        return eventRepository.countByEventTypeAndOccurredAtGreaterThanEqual(eventType, since);
    }

    @Override
    public long countDistinctVisitors(LocalDateTime since) {
        return eventRepository.countDistinctVisitors(since);
    }

    @Override
    public BigDecimal sumPaymentAmount(LocalDateTime since) {
        return eventRepository.sumAmountByEventTypeSince(TrackingEventType.PAYMENT_SUCCESS, since);
    }

    @Override
    public Optional<UserProfileTag> findUserProfile(Long userId) {
        return userProfileTagRepository.findById(userId).map(this::toDomain);
    }

    @Override
    public UserProfileTag saveUserProfile(UserProfileTag profile) {
        UserProfileTagEntity entity =
                userProfileTagRepository.findById(profile.userId()).orElseGet(UserProfileTagEntity::new);
        entity.setUserId(profile.userId());
        entity.setEncryptedProfileSummary(piiCryptoService.encrypt(profile.profileSummary()));
        entity.setProfileSummaryHmac(piiCryptoService.blindIndex(profile.profileSummary()));
        entity.setBehaviorTagsJson(write(profile.behaviorTags()));
        entity.setInterestTagsJson(write(profile.interestTags()));
        entity.setLastEventAt(profile.lastEventAt());
        return toDomain(userProfileTagRepository.save(entity));
    }

    @Override
    public Optional<ProductProfile> findProductProfile(Long productId) {
        return productProfileRepository.findById(productId).map(this::toDomain);
    }

    @Override
    public ProductProfile saveProductProfile(ProductProfile profile) {
        ProductProfileEntity entity =
                productProfileRepository.findById(profile.productId()).orElseGet(ProductProfileEntity::new);
        entity.setProductId(profile.productId());
        entity.setCategoryId(profile.categoryId());
        entity.setTagVectorJson(write(profile.tagVector()));
        entity.setSalesCount(profile.salesCount());
        entity.setReviewScore(profile.reviewScore());
        entity.setLastEventAt(profile.lastEventAt());
        return toDomain(productProfileRepository.save(entity));
    }

    private TrackingEventEntity toEntity(TrackingEvent event) {
        TrackingEventEntity entity = new TrackingEventEntity();
        entity.setId(event.id());
        entity.setUserId(event.userId());
        entity.setSessionId(event.sessionId());
        entity.setTraceId(event.traceId());
        entity.setEventType(event.eventType());
        entity.setPage(event.page());
        entity.setSource(event.source());
        entity.setProductId(event.productId());
        entity.setCategoryId(event.categoryId());
        entity.setOrderId(event.orderId());
        entity.setAmount(event.amount());
        entity.setAttributesJson(write(event.attributes()));
        entity.setOccurredAt(event.occurredAt());
        entity.setCreateTime(LocalDateTime.now());
        return entity;
    }

    private TrackingEvent toDomain(TrackingEventEntity entity) {
        return new TrackingEvent(
                entity.getId(),
                entity.getUserId(),
                entity.getSessionId(),
                entity.getTraceId(),
                entity.getEventType(),
                entity.getPage(),
                entity.getSource(),
                entity.getProductId(),
                entity.getCategoryId(),
                entity.getOrderId(),
                entity.getAmount(),
                read(entity.getAttributesJson(), ATTRIBUTES_TYPE, Map.of()),
                entity.getOccurredAt());
    }

    private UserProfileTag toDomain(UserProfileTagEntity entity) {
        return new UserProfileTag(
                entity.getUserId(),
                decrypt(entity.getEncryptedProfileSummary()),
                read(entity.getBehaviorTagsJson(), TAGS_TYPE, List.of()),
                read(entity.getInterestTagsJson(), TAGS_TYPE, List.of()),
                entity.getLastEventAt(),
                entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private ProductProfile toDomain(ProductProfileEntity entity) {
        return new ProductProfile(
                entity.getProductId(),
                entity.getCategoryId(),
                read(entity.getTagVectorJson(), TAGS_TYPE, List.of()),
                entity.getSalesCount(),
                entity.getReviewScore(),
                entity.getLastEventAt(),
                entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private String decrypt(String value) {
        return StringUtils.hasText(value) ? piiCryptoService.decrypt(value) : "";
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tracking JSON cannot be serialized", exception);
        }
    }

    private <T> T read(String json, TypeReference<T> type, T fallback) {
        if (!StringUtils.hasText(json)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tracking JSON cannot be deserialized", exception);
        }
    }
}
