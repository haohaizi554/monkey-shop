package com.example.monkey.tracking.application.dto;

import com.example.monkey.tracking.domain.TrackingEventType;
import java.math.BigDecimal;

public record FunnelStepDto(TrackingEventType eventType, long count, BigDecimal conversionRate) {}
