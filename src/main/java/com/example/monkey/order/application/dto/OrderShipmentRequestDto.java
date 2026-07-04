package com.example.monkey.order.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OrderShipmentRequestDto(
        @Size(max = 64) String carrier,
        @Size(max = 96) String trackingNo,
        @Valid List<OrderShipmentLineRequestDto> lines) {

    public OrderShipmentRequestDto {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
