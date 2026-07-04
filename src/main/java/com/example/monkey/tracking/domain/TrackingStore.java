package com.example.monkey.tracking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TrackingStore {

    TrackingEvent saveEvent(TrackingEvent event);

    List<TrackingEvent> findRecentEvents(LocalDateTime since, int limit);

    long countEvents(TrackingEventType eventType, LocalDateTime since);

    long countDistinctVisitors(LocalDateTime since);

    BigDecimal sumPaymentAmount(LocalDateTime since);

    Optional<UserProfileTag> findUserProfile(Long userId);

    UserProfileTag saveUserProfile(UserProfileTag profile);

    Optional<ProductProfile> findProductProfile(Long productId);

    ProductProfile saveProductProfile(ProductProfile profile);
}
