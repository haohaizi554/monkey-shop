package com.example.monkey.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WarehouseStockTest {

    @Test
    void reserveReleaseDeductAndCompensateKeepInventoryInvariant() {
        WarehouseStock stock = new WarehouseStock(1L, 2L, "BJ-01", "CN-BJ", 10, 0, 0, 0, 2, 0);

        WarehouseStock reserved = stock.reserve(3);
        WarehouseStock deducted = reserved.deduct(2);
        WarehouseStock released = deducted.release(1);
        WarehouseStock compensated = released.compensate(1);

        assertThat(stock.totalQuantity()).isEqualTo(10);
        assertThat(reserved.availableQuantity()).isEqualTo(7);
        assertThat(reserved.lockedQuantity()).isEqualTo(3);
        assertThat(deducted.lockedQuantity()).isEqualTo(1);
        assertThat(deducted.deductedQuantity()).isEqualTo(2);
        assertThat(released.availableQuantity()).isEqualTo(8);
        assertThat(compensated.availableQuantity()).isEqualTo(9);
        assertThat(compensated.deductedQuantity()).isEqualTo(1);
        assertThat(compensated.totalQuantity()).isEqualTo(10);
    }

    @Test
    void stockRejectsNegativeAndOversellMovements() {
        WarehouseStock stock = new WarehouseStock(1L, 2L, "BJ-01", "CN-BJ", 1, 0, 0, 0, 0, 0);

        assertThatThrownBy(() -> new WarehouseStock(1L, 2L, "BJ-01", "CN-BJ", -1, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available quantity cannot be negative");
        assertThatThrownBy(() -> stock.reserve(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient available inventory");
        assertThatThrownBy(() -> stock.deduct(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient locked inventory");
    }
}
