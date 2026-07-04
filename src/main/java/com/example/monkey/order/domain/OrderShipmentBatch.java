package com.example.monkey.order.domain;

import java.time.LocalDateTime;
import java.util.List;

public record OrderShipmentBatch(
        Long id,
        Long orderId,
        String shipmentNo,
        String carrier,
        String trackingNo,
        OrderShipmentStatus status,
        LocalDateTime shippedAt,
        LocalDateTime receivedAt,
        List<OrderShipmentLine> lines) {

    public OrderShipmentBatch {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public OrderShipmentBatch receive(LocalDateTime receivedAt) {
        if (OrderShipmentStatus.RECEIVED.equals(status)) {
            return this;
        }
        return new OrderShipmentBatch(
                id,
                orderId,
                shipmentNo,
                carrier,
                trackingNo,
                OrderShipmentStatus.RECEIVED,
                shippedAt,
                receivedAt,
                lines);
    }
}
