package com.example.monkey.logistics.domain;

import java.util.List;
import java.util.Optional;

public interface LogisticsStore {

    Optional<LogisticsTracking> findByTrackingNo(String trackingNo);

    Optional<LogisticsTracking> findByOrderIdAndUserId(Long orderId, Long userId);

    Optional<LogisticsTracking> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    LogisticsTracking saveTracking(LogisticsTracking tracking);

    TrackingEventRecord saveEvent(TrackingEventRecord event);

    List<TrackingEventRecord> findEvents(String trackingNo);

    List<FreightTemplate> findFreightTemplates(LogisticsCarrier carrier, String province);
}
