package com.example.monkey.tracking.infrastructure;

import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_profile")
public class ProductProfileEntity extends TenantScopedJpaEntity {

    @Id
    private Long productId;

    private Long categoryId;

    @Column(columnDefinition = "json")
    private String tagVectorJson;

    @Column(nullable = false)
    private long salesCount;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal reviewScore;

    @Column(nullable = false)
    private LocalDateTime lastEventAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getTagVectorJson() {
        return tagVectorJson;
    }

    public void setTagVectorJson(String tagVectorJson) {
        this.tagVectorJson = tagVectorJson;
    }

    public long getSalesCount() {
        return salesCount;
    }

    public void setSalesCount(long salesCount) {
        this.salesCount = salesCount;
    }

    public BigDecimal getReviewScore() {
        return reviewScore;
    }

    public void setReviewScore(BigDecimal reviewScore) {
        this.reviewScore = reviewScore;
    }

    public LocalDateTime getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(LocalDateTime lastEventAt) {
        this.lastEventAt = lastEventAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
