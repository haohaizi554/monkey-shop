package com.example.monkey.inventory.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory_warehouse")
public class InventoryWarehouse {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64)
    private String province;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean active;

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getProvince() {
        return province;
    }

    public boolean isActive() {
        return active;
    }
}
