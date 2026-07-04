package com.example.monkey.logistics.domain;

import java.time.Duration;

public interface LogisticsWebhookReplayGuard {

    boolean reserve(LogisticsCarrier carrier, String trackingNo, String eventId, Duration ttl, String sourceIp);
}
