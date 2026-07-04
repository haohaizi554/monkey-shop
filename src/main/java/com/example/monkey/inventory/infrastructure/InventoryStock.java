package com.example.monkey.inventory.infrastructure;

import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(
        name = "inventory_stock",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_inventory_stock_sku_warehouse",
                        columnNames = {"sku_id", "warehouse_id"}))
public class InventoryStock extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(nullable = false)
    private int availableQuantity;

    @Column(nullable = false)
    private int lockedQuantity;

    @Column(nullable = false)
    private int deductedQuantity;

    @Column(nullable = false)
    private int inTransitQuantity;

    @Column(nullable = false)
    private int safetyStock;

    @Version
    @Column(nullable = false)
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public void setAvailableQuantity(int availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public int getLockedQuantity() {
        return lockedQuantity;
    }

    public void setLockedQuantity(int lockedQuantity) {
        this.lockedQuantity = lockedQuantity;
    }

    public int getDeductedQuantity() {
        return deductedQuantity;
    }

    public void setDeductedQuantity(int deductedQuantity) {
        this.deductedQuantity = deductedQuantity;
    }

    public int getInTransitQuantity() {
        return inTransitQuantity;
    }

    public void setInTransitQuantity(int inTransitQuantity) {
        this.inTransitQuantity = inTransitQuantity;
    }

    public int getSafetyStock() {
        return safetyStock;
    }

    public void setSafetyStock(int safetyStock) {
        this.safetyStock = safetyStock;
    }

    public Long getVersion() {
        return version;
    }
}
