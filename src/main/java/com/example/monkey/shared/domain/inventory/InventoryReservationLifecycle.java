package com.example.monkey.shared.domain.inventory;

public interface InventoryReservationLifecycle {

    void deductReservation(String reservationKey);

    void compensateReturn(Long skuId, Long warehouseId, Long orderId, int quantity, String idempotencyKey);

    static InventoryReservationLifecycle noop() {
        return new InventoryReservationLifecycle() {
            @Override
            public void deductReservation(String reservationKey) {}

            @Override
            public void compensateReturn(
                    Long skuId, Long warehouseId, Long orderId, int quantity, String idempotencyKey) {}
        };
    }
}
