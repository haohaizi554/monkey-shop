package com.example.monkey.tracking.domain;

import java.math.BigDecimal;

public record FunnelStep(TrackingEventType eventType, long count, BigDecimal conversionRate) {}
