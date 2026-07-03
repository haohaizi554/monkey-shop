package com.example.monkey.inventory.domain;

public record InventoryStockLedgerEntry(
        Long id,
        Long skuId,
        Long warehouseId,
        String reservationKey,
        Long orderId,
        InventoryOperation operation,
        int quantity,
        String idempotencyKey) {
    public InventoryStockLedgerEntry {
        if (id == null) {
            throw new IllegalArgumentException("ledger id is required");
        }
        if (skuId == null) {
            throw new IllegalArgumentException("SKU id is required");
        }
        if (warehouseId == null) {
            throw new IllegalArgumentException("warehouse id is required");
        }
        if (operation == null) {
            throw new IllegalArgumentException("inventory operation is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("ledger quantity must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("ledger idempotency key is required");
        }
    }
}
