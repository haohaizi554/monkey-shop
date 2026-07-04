package com.example.monkey.logistics.domain;

import java.time.LocalDateTime;

public record TrackingEventRecord(
        Long id,
        Long trackingId,
        String trackingNo,
        LogisticsCarrier carrier,
        TrackingEvent eventType,
        TrackingStatus fromStatus,
        TrackingStatus toStatus,
        String eventId,
        LocalDateTime eventTime,
        String location,
        String remark,
        LocalDateTime createTime) {}
