package com.example.monkey.inventory.application.dto;

public record WarehouseStockResponseDto(
        Long skuId,
        Long warehouseId,
        String warehouseCode,
        String province,
        int availableQuantity,
        int lockedQuantity,
        int deductedQuantity,
        int inTransitQuantity,
        int safetyStock,
        int totalQuantity,
        boolean belowSafetyStock) {}
