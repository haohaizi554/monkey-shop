package com.example.monkey.logistics.application.dto;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.TrackingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record LogisticsTrackingResponseDto(
        Long id,
        String trackingNo,
        Long orderId,
        Long userId,
        LogisticsCarrier carrier,
        TrackingStatus status,
        String province,
        String city,
        String district,
        String detailSummary,
        BigDecimal freightAmount,
        int etaHours,
        LocalDateTime pickedUpAt,
        LocalDateTime inTransitAt,
        LocalDateTime outForDeliveryAt,
        LocalDateTime signedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        List<TrackingEventResponseDto> events) {

    public LogisticsTrackingResponseDto {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
