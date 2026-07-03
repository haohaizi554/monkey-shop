package com.example.monkey.product.infrastructure;

import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.shared.infrastructure.privacy.EncryptedStringAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "product_spu")
@SQLDelete(sql = "UPDATE product_spu SET deleted = true, version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class ProductSpu {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal memberPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal strikePrice;

    @Column(columnDefinition = "json")
    private String regionPricesJson;

    @Column(columnDefinition = "json")
    private String attributesJson;

    @Column(columnDefinition = "json")
    private String detailJsonLd;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 2048)
    private String supplierPrivateRemark;

    @Column(length = 512)
    private String imageUrl;

    @Column(nullable = false)
    private boolean deleted;

    @Version
    @Column(nullable = false)
    private Long version;

    public ProductSpu() {}

    public ProductSpu(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public void setStatus(ProductStatus status) {
        this.status = status;
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

    public String getAttributesJson() {
        return attributesJson;
    }

    public void setAttributesJson(String attributesJson) {
        this.attributesJson = attributesJson;
    }

    public String getDetailJsonLd() {
        return detailJsonLd;
    }

    public void setDetailJsonLd(String detailJsonLd) {
        this.detailJsonLd = detailJsonLd;
    }

    public String getSupplierPrivateRemark() {
        return supplierPrivateRemark;
    }

    public void setSupplierPrivateRemark(String supplierPrivateRemark) {
        this.supplierPrivateRemark = supplierPrivateRemark;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
