package com.example.monkey.logistics.application;

import com.example.monkey.logistics.application.dto.FreightQuoteResponseDto;
import com.example.monkey.logistics.application.dto.LogisticsTrackingResponseDto;
import com.example.monkey.logistics.application.dto.ParsedAddressDto;
import com.example.monkey.logistics.application.dto.TrackingEventResponseDto;
import com.example.monkey.logistics.domain.FreightQuote;
import com.example.monkey.logistics.domain.LogisticsTracking;
import com.example.monkey.logistics.domain.ParsedAddress;
import com.example.monkey.logistics.domain.TrackingEventRecord;
import java.util.List;

final class LogisticsDtoAssembler {

    private LogisticsDtoAssembler() {}

    static FreightQuoteResponseDto toResponse(FreightQuote quote) {
        return new FreightQuoteResponseDto(
                quote.carrier(),
                quote.province(),
                quote.weightKg(),
                quote.itemCount(),
                quote.amount(),
                quote.etaHours(),
                quote.appliedModes());
    }

    static ParsedAddressDto toResponse(ParsedAddress address) {
        return new ParsedAddressDto(address.province(), address.city(), address.district(), address.detail());
    }

    static LogisticsTrackingResponseDto toResponse(LogisticsTracking tracking, List<TrackingEventRecord> eventRecords) {
        return new LogisticsTrackingResponseDto(
                tracking.id(),
                tracking.trackingNo(),
                tracking.orderId(),
                tracking.userId(),
                tracking.carrier(),
                tracking.status(),
                tracking.province(),
                tracking.city(),
                tracking.district(),
                tracking.detailSummary(),
                tracking.freightAmount(),
                tracking.etaHours(),
                tracking.pickedUpAt(),
                tracking.inTransitAt(),
                tracking.outForDeliveryAt(),
                tracking.signedAt(),
                tracking.createTime(),
                tracking.updateTime(),
                eventRecords == null
                        ? List.of()
                        : eventRecords.stream()
                                .map(LogisticsDtoAssembler::toResponse)
                                .toList());
    }

    private static TrackingEventResponseDto toResponse(TrackingEventRecord event) {
        return new TrackingEventResponseDto(
                event.id(),
                event.eventType(),
                event.fromStatus(),
                event.toStatus(),
                event.eventId(),
                event.eventTime(),
                event.location(),
                event.remark());
    }
}
