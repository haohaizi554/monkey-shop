package com.example.monkey.product.infrastructure;

import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_category")
public class ProductCategory extends TenantScopedJpaEntity {

    @Id
    private Long id;

    private Long parentId;

    @Column(nullable = false)
    private Integer level;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false)
    private Integer sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    public ProductCategory() {}

    public ProductCategory(Long id, Long parentId, Integer level, String code, String name, Integer sortOrder) {
        this.id = id;
        this.parentId = parentId;
        this.level = level;
        this.code = code;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getParentId() {
        return parentId;
    }

    public Integer getLevel() {
        return level;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }
}
