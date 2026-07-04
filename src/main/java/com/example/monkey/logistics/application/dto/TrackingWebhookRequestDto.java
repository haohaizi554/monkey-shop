package com.example.monkey.logistics.application.dto;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.TrackingEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record TrackingWebhookRequestDto(
        @NotNull LogisticsCarrier carrier,
        @NotBlank String trackingNo,
        @NotBlank String eventId,
        @NotNull TrackingEvent event,
        LocalDateTime eventTime,
        String location,
        String remark,
        @NotBlank @Size(max = 128) String signature) {}
