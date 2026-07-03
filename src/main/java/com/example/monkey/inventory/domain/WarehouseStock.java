package com.example.monkey.inventory.domain;

public record WarehouseStock(
        Long skuId,
        Long warehouseId,
        String warehouseCode,
        String province,
        int availableQuantity,
        int lockedQuantity,
        int deductedQuantity,
        int inTransitQuantity,
        int safetyStock,
        long version) {
    public WarehouseStock {
        if (skuId == null) {
            throw new IllegalArgumentException("SKU id is required");
        }
        if (warehouseId == null) {
            throw new IllegalArgumentException("warehouse id is required");
        }
        requireNonNegative("available quantity", availableQuantity);
        requireNonNegative("locked quantity", lockedQuantity);
        requireNonNegative("deducted quantity", deductedQuantity);
        requireNonNegative("in-transit quantity", inTransitQuantity);
        requireNonNegative("safety stock", safetyStock);
    }

    public int totalQuantity() {
        return availableQuantity + lockedQuantity + deductedQuantity + inTransitQuantity;
    }

    public boolean canReserve(int quantity) {
        return quantity > 0 && availableQuantity >= quantity;
    }

    public WarehouseStock reserve(int quantity) {
        requirePositive(quantity);
        if (availableQuantity < quantity) {
            throw new IllegalStateException("Insufficient available inventory");
        }
        return new WarehouseStock(
                skuId,
                warehouseId,
                warehouseCode,
                province,
                availableQuantity - quantity,
                lockedQuantity + quantity,
                deductedQuantity,
                inTransitQuantity,
                safetyStock,
                version + 1);
    }

    public WarehouseStock release(int quantity) {
        requirePositive(quantity);
        if (lockedQuantity < quantity) {
            throw new IllegalStateException("Insufficient locked inventory");
        }
        return new WarehouseStock(
                skuId,
                warehouseId,
                warehouseCode,
                province,
                availableQuantity + quantity,
                lockedQuantity - quantity,
                deductedQuantity,
                inTransitQuantity,
                safetyStock,
                version + 1);
    }

    public WarehouseStock deduct(int quantity) {
        requirePositive(quantity);
        if (lockedQuantity < quantity) {
            throw new IllegalStateException("Insufficient locked inventory");
        }
        return new WarehouseStock(
                skuId,
                warehouseId,
                warehouseCode,
                province,
                availableQuantity,
                lockedQuantity - quantity,
                deductedQuantity + quantity,
                inTransitQuantity,
                safetyStock,
                version + 1);
    }

    public WarehouseStock compensate(int quantity) {
        requirePositive(quantity);
        if (deductedQuantity < quantity) {
            throw new IllegalStateException("Insufficient deducted inventory");
        }
        return new WarehouseStock(
                skuId,
                warehouseId,
                warehouseCode,
                province,
                availableQuantity + quantity,
                lockedQuantity,
                deductedQuantity - quantity,
                inTransitQuantity,
                safetyStock,
                version + 1);
    }

    public boolean belowSafetyStock() {
        return availableQuantity <= safetyStock;
    }

    private static void requireNonNegative(String field, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
