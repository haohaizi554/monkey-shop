package com.example.monkey.product.infrastructure;

import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "monkey")
@SQLDelete(sql = "UPDATE monkey SET deleted = true, version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Monkey extends TenantScopedJpaEntity {
    public static final String STOCK_NOT_AVAILABLE = "Stock is not available";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String breed;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    private String description;
    private String imageUrl;
    private Integer stock; // 搴撳瓨瀛楁

    @Column(nullable = false)
    private boolean deleted;

    @Version
    @Column(nullable = false)
    private Long version;

    public Monkey() {}

    public Monkey(
            Long id, String name, String breed, BigDecimal price, String description, String imageUrl, Integer stock) {
        this.id = id;
        this.name = name;
        this.breed = breed;
        this.price = price;
        this.description = description;
        this.imageUrl = imageUrl;
        this.stock = stock;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBreed() {
        return breed;
    }

    public void setBreed(String breed) {
        this.breed = breed;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public boolean hasStock() {
        return stock != null && stock > 0;
    }

    public void deductStock() {
        if (!hasStock()) {
            throw new IllegalStateException(STOCK_NOT_AVAILABLE);
        }
        stock = stock - 1;
    }

    public void restoreStock() {
        stock = stock == null ? 1 : stock + 1;
    }
}
