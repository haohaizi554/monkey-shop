package com.example.monkey.product.infrastructure;

import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_attribute_template")
public class ProductAttributeTemplate extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 64)
    private String templateCode;

    @Column(nullable = false, length = 128)
    private String templateName;

    @Column(columnDefinition = "json")
    private String requiredAttributesJson;

    @Column(columnDefinition = "json")
    private String optionalAttributesJson;

    @Column(nullable = false)
    private boolean active = true;

    public ProductAttributeTemplate() {}

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getRequiredAttributesJson() {
        return requiredAttributesJson;
    }

    public String getOptionalAttributesJson() {
        return optionalAttributesJson;
    }

    public boolean isActive() {
        return active;
    }
}
