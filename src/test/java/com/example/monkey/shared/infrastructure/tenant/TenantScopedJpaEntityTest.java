package com.example.monkey.shared.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.monkey.inventory.infrastructure.InventoryStock;
import com.example.monkey.logistics.infrastructure.LogisticsTrackingEntity;
import com.example.monkey.payment.infrastructure.PaymentOrderEntity;
import com.example.monkey.product.infrastructure.Monkey;
import com.example.monkey.product.infrastructure.ProductSku;
import com.example.monkey.shared.application.tenant.TenantContext;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantScopedJpaEntityTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tenantFilterIsAutoEnabledAndAppliesToLoadByKey() {
        FilterDef filterDef = TenantScopedJpaEntity.class.getAnnotation(FilterDef.class);
        Filter filter = TenantScopedJpaEntity.class.getAnnotation(Filter.class);

        assertThat(filterDef).isNotNull();
        assertThat(filterDef.name()).isEqualTo(TenantScopedJpaEntity.TENANT_FILTER);
        assertThat(filterDef.autoEnabled()).isTrue();
        assertThat(filterDef.applyToLoadByKey()).isTrue();
        assertThat(filterDef.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.name()).isEqualTo(TenantScopedJpaEntity.TENANT_ID_PARAMETER);
            assertThat(parameter.type()).isEqualTo(Long.class);
            assertThat(parameter.resolver()).isEqualTo(CurrentTenantIdSupplier.class);
        });
        assertThat(filter).isNotNull();
        assertThat(filter.condition()).isEqualTo("tenant_id = :tenantId");
    }

    @Test
    void currentTenantIdSupplierUsesTenantContextDefault() {
        CurrentTenantIdSupplier supplier = new CurrentTenantIdSupplier();

        assertThat(supplier.get()).isEqualTo(TenantContext.PLATFORM_TENANT_ID);

        TenantContext.setTenantId(700L);

        assertThat(supplier.get()).isEqualTo(700L);
    }

    @Test
    void inheritedTenantFilterBuildsHibernateMetadataForMultipleEntities() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.MySQLDialect")
                .applySetting(AvailableSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, "none")
                .applySetting(AvailableSettings.ALLOW_METADATA_ON_BOOT, false)
                .build();
        try {
            MetadataSources metadataSources = new MetadataSources(registry)
                    .addAnnotatedClass(Monkey.class)
                    .addAnnotatedClass(ProductSku.class)
                    .addAnnotatedClass(InventoryStock.class)
                    .addAnnotatedClass(PaymentOrderEntity.class)
                    .addAnnotatedClass(LogisticsTrackingEntity.class);

            assertThatCode(metadataSources::buildMetadata).doesNotThrowAnyException();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
