package com.example.monkey.inventory.application.dto;

import java.util.List;

public record InventoryReconciliationResponseDto(
        boolean balanced, List<InventoryReconciliationResponseDto.DiscrepancyDto> discrepancies) {
    public record DiscrepancyDto(
            Long skuId,
            Long warehouseId,
            int actualLocked,
            int expectedLocked,
            int actualDeducted,
            int expectedDeducted) {}
}
