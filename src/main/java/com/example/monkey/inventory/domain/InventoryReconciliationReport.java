package com.example.monkey.inventory.domain;

import java.util.List;

public record InventoryReconciliationReport(List<InventoryReconciliationReport.Discrepancy> discrepancies) {
    public InventoryReconciliationReport {
        discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
    }

    public boolean balanced() {
        return discrepancies.isEmpty();
    }

    public record Discrepancy(
            Long skuId,
            Long warehouseId,
            int actualLocked,
            int expectedLocked,
            int actualDeducted,
            int expectedDeducted) {}
}
