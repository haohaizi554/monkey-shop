package com.example.monkey.product.infrastructure;

import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "product_sku")
public class ProductSku extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long spuId;

    @Column(nullable = false, length = 191)
    private String skuCode;

    @Column(nullable = false, columnDefinition = "json")
    private String specJson;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal memberPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal strikePrice;

    @Column(columnDefinition = "json")
    private String regionPricesJson;

    @Column(nullable = false)
    private boolean active = true;

    public ProductSku() {}

    public ProductSku(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public String getSpecJson() {
        return specJson;
    }

    public void setSpecJson(String specJson) {
        this.specJson = specJson;
    }

    public BigDecimal getOriginalPrice() {
        return originalPrice;
    }

    public void setOriginalPrice(BigDecimal originalPrice) {
        this.originalPrice = originalPrice;
    }

    public BigDecimal getMemberPrice() {
        return memberPrice;
    }

    public void setMemberPrice(BigDecimal memberPrice) {
        this.memberPrice = memberPrice;
    }

    public BigDecimal getStrikePrice() {
        return strikePrice;
    }

    public void setStrikePrice(BigDecimal strikePrice) {
        this.strikePrice = strikePrice;
    }

    public String getRegionPricesJson() {
        return regionPricesJson;
    }

    public void setRegionPricesJson(String regionPricesJson) {
        this.regionPricesJson = regionPricesJson;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
