package com.example.monkey.inventory.domain;

import java.util.function.Supplier;

public interface InventoryLockManager {

    <T> T withStockLock(Long skuId, Long warehouseId, Supplier<T> action);
}
