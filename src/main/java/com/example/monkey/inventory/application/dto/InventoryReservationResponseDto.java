package com.example.monkey.inventory.application.dto;

import com.example.monkey.inventory.domain.InventoryReservationStatus;
import java.time.LocalDateTime;

public record InventoryReservationResponseDto(
        String reservationKey,
        Long skuId,
        Long warehouseId,
        Long orderId,
        int quantity,
        InventoryReservationStatus status,
        LocalDateTime expiresAt,
        WarehouseStockResponseDto stock) {}
