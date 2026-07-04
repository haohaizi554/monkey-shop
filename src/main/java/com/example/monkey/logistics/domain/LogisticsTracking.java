package com.example.monkey.logistics.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record LogisticsTracking(
        Long id,
        String trackingNo,
        Long orderId,
        Long userId,
        LogisticsCarrier carrier,
        TrackingStatus status,
        String recipientPhone,
        String recipientPhoneBlindIndex,
        String addressSnapshot,
        String addressBlindIndex,
        String province,
        String city,
        String district,
        String detailSummary,
        BigDecimal freightAmount,
        int etaHours,
        String idempotencyKey,
        LocalDateTime pickedUpAt,
        LocalDateTime inTransitAt,
        LocalDateTime outForDeliveryAt,
        LocalDateTime signedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public LogisticsTracking advance(TrackingStatus nextStatus, LocalDateTime eventTime) {
        LocalDateTime effectiveTime = eventTime == null ? updateTime : eventTime;
        return new LogisticsTracking(
                id,
                trackingNo,
                orderId,
                userId,
                carrier,
                nextStatus,
                recipientPhone,
                recipientPhoneBlindIndex,
                addressSnapshot,
                addressBlindIndex,
                province,
                city,
                district,
                detailSummary,
                freightAmount,
                etaHours,
                idempotencyKey,
                nextStatus == TrackingStatus.PICKED_UP && pickedUpAt == null ? effectiveTime : pickedUpAt,
                nextStatus == TrackingStatus.IN_TRANSIT && inTransitAt == null ? effectiveTime : inTransitAt,
                nextStatus == TrackingStatus.OUT_FOR_DELIVERY && outForDeliveryAt == null
                        ? effectiveTime
                        : outForDeliveryAt,
                nextStatus == TrackingStatus.SIGNED && signedAt == null ? effectiveTime : signedAt,
                createTime,
                effectiveTime);
    }

    public LogisticsTracking withGatewayResult(LogisticsGatewayResult result, LocalDateTime now) {
        return new LogisticsTracking(
                id,
                trackingNo,
                orderId,
                userId,
                carrier,
                result == null || result.status() == null ? status : result.status(),
                recipientPhone,
                recipientPhoneBlindIndex,
                addressSnapshot,
                addressBlindIndex,
                province,
                city,
                district,
                detailSummary,
                freightAmount,
                result == null ? etaHours : result.etaHours(),
                idempotencyKey,
                pickedUpAt,
                inTransitAt,
                outForDeliveryAt,
                signedAt,
                createTime,
                now);
    }
}
