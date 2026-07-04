package com.example.monkey.logistics.application.dto;

import com.example.monkey.logistics.domain.TrackingEvent;
import com.example.monkey.logistics.domain.TrackingStatus;
import java.time.LocalDateTime;

public record TrackingEventResponseDto(
        Long id,
        TrackingEvent eventType,
        TrackingStatus fromStatus,
        TrackingStatus toStatus,
        String eventId,
        LocalDateTime eventTime,
        String location,
        String remark) {}
