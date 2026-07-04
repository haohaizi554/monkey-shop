package com.example.monkey.order.application.dto;

import java.time.LocalDateTime;
import java.util.List;

public record OrderShipmentResponseDto(
        Long id,
        Long orderId,
        String shipmentNo,
        String carrier,
        String trackingNo,
        String status,
        LocalDateTime shippedAt,
        LocalDateTime receivedAt,
        List<OrderShipmentLineResponseDto> lines) {

    public OrderShipmentResponseDto {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
