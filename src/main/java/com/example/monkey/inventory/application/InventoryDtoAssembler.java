package com.example.monkey.inventory.application;

import com.example.monkey.inventory.application.dto.InventoryReconciliationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.WarehouseStockResponseDto;
import com.example.monkey.inventory.domain.InventoryReconciliationReport;
import com.example.monkey.inventory.domain.InventoryReservation;
import com.example.monkey.inventory.domain.WarehouseStock;

final class InventoryDtoAssembler {

    private InventoryDtoAssembler() {}

    static WarehouseStockResponseDto toResponse(WarehouseStock stock) {
        return new WarehouseStockResponseDto(
                stock.skuId(),
                stock.warehouseId(),
                stock.warehouseCode(),
                stock.province(),
                stock.availableQuantity(),
                stock.lockedQuantity(),
                stock.deductedQuantity(),
                stock.inTransitQuantity(),
                stock.safetyStock(),
                stock.totalQuantity(),
                stock.belowSafetyStock());
    }

    static InventoryReservationResponseDto toResponse(InventoryReservation reservation, WarehouseStock stock) {
        return new InventoryReservationResponseDto(
                reservation.reservationKey(),
                reservation.skuId(),
                reservation.warehouseId(),
                reservation.orderId(),
                reservation.quantity(),
                reservation.status(),
                reservation.expiresAt(),
                toResponse(stock));
    }

    static InventoryReconciliationResponseDto toResponse(InventoryReconciliationReport report) {
        return new InventoryReconciliationResponseDto(
                report.balanced(),
                report.discrepancies().stream()
                        .map(discrepancy -> new InventoryReconciliationResponseDto.DiscrepancyDto(
                                discrepancy.skuId(),
                                discrepancy.warehouseId(),
                                discrepancy.actualLocked(),
                                discrepancy.expectedLocked(),
                                discrepancy.actualDeducted(),
                                discrepancy.expectedDeducted()))
                        .toList());
    }
}
