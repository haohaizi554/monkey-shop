package com.example.monkey.search.infrastructure;

import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Immutable
@Table(name = "product_spu")
@SQLRestriction("deleted = false")
class SearchProductSpuEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    private Long categoryId;
    private String name;
    private String title;

    @Enumerated(EnumType.STRING)
    private ProductStatus status;

    @Column(precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal memberPrice;

    @Column(columnDefinition = "json")
    private String attributesJson;

    private String imageUrl;

    protected SearchProductSpuEntity() {}

    SearchProductSpuEntity(
            Long id,
            Long categoryId,
            String name,
            String title,
            ProductStatus status,
            BigDecimal originalPrice,
            BigDecimal memberPrice,
            String attributesJson,
            String imageUrl) {
        this.id = id;
        this.categoryId = categoryId;
        this.name = name;
        this.title = title;
        this.status = status;
        this.originalPrice = originalPrice;
        this.memberPrice = memberPrice;
        this.attributesJson = attributesJson;
        this.imageUrl = imageUrl;
    }

    Long getId() {
        return id;
    }

    Long getCategoryId() {
        return categoryId;
    }

    String getName() {
        return name;
    }

    String getTitle() {
        return title;
    }

    ProductStatus getStatus() {
        return status;
    }

    BigDecimal getOriginalPrice() {
        return originalPrice;
    }

    BigDecimal getMemberPrice() {
        return memberPrice;
    }

    String getAttributesJson() {
        return attributesJson;
    }

    String getImageUrl() {
        return imageUrl;
    }
}
