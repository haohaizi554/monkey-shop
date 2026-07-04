package com.example.monkey.logistics.domain;

import java.time.LocalDateTime;

public record LogisticsGatewayResult(
        LogisticsCarrier carrier, String trackingNo, TrackingStatus status, int etaHours, LocalDateTime acceptedAt) {}
