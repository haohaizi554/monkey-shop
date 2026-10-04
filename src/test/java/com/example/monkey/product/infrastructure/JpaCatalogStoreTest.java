package com.example.monkey.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.product.domain.CatalogSku;
import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.ProductPriceBook;
import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.product.domain.SkuSpecification;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JpaCatalogStoreTest {

    private final ProductSpuRepository spuRepository = mock(ProductSpuRepository.class);
    private final ProductSkuRepository skuRepository = mock(ProductSkuRepository.class);
    private final ProductCategoryRepository categoryRepository = mock(ProductCategoryRepository.class);
    private final ImageReferenceService imageReferenceService = mock(ImageReferenceService.class);
    private final JpaCatalogStore store =
            new JpaCatalogStore(spuRepository, skuRepository, categoryRepository, new ObjectMapper(), imageReferenceService);

    @Test
    void savingAnExistingSpuStatusNeverDeletesOrRecreatesReferencedSkus() {
        CatalogSpu transitioned = spu(ProductStatus.PENDING_REVIEW);
        ProductSpu existing = new ProductSpu(10L);
        ProductSku persistedSku = persistedSku();
        when(spuRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(spuRepository.save(any(ProductSpu.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(skuRepository.findBySpuIdOrderByIdAsc(10L)).thenReturn(List.of(persistedSku));

        CatalogSpu saved = store.save(transitioned);

        assertThat(saved.status()).isEqualTo(ProductStatus.PENDING_REVIEW);
        assertThat(saved.skus()).extracting(CatalogSku::id).containsExactly(100L);
        verify(skuRepository, never()).saveAll(any());
        verify(skuRepository).findBySpuIdOrderByIdAsc(10L);
        verifyNoMoreInteractions(skuRepository);
        verifyNoInteractions(imageReferenceService);
    }

    @Test
    void savingAnExistingSpuUpdatesItsSkuSpecificationAndPriceWithoutDuplicatingTheRow() {
        CatalogSpu updated = new CatalogSpu(
                10L,
                3L,
                1L,
                "Golden monkey",
                "Golden monkey",
                ProductStatus.DRAFT,
                new ProductPriceBook(new BigDecimal("109.00"), null, null, Map.of()),
                Map.of(),
                null,
                null,
                "/images/golden-monkey.webp",
                List.of(new CatalogSku(
                        100L,
                        10L,
                        "SKU-100",
                        new SkuSpecification(Map.of("color", "silver")),
                        new ProductPriceBook(new BigDecimal("109.00"), null, null, Map.of()),
                        true)));
        ProductSpu existing = new ProductSpu(10L);
        ProductSku persistedSku = persistedSku();
        when(spuRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(spuRepository.save(any(ProductSpu.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(skuRepository.findBySpuIdOrderByIdAsc(10L)).thenReturn(List.of(persistedSku));
        when(skuRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        store.save(updated);

        verify(skuRepository).saveAll(any());
        assertThat(persistedSku.getSpecJson()).contains("silver");
        assertThat(persistedSku.getOriginalPrice()).isEqualByComparingTo("109.00");
    }

    @Test
    void compatibilityConstructorRejectsTrackableSpuBeforeRepositorySave() {
        JpaCatalogStore compatibilityStore =
                new JpaCatalogStore(spuRepository, skuRepository, categoryRepository, new ObjectMapper());

        assertThatThrownBy(() -> compatibilityStore.save(spu(ProductStatus.DRAFT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Image reference services are required for trackable image writes");

        verify(spuRepository, never()).save(any(ProductSpu.class));
    }

    @Test
    void compatibilityConstructorRejectsReplacingPersistedTrackableSpuImage() {
        ProductSpu existing = new ProductSpu(10L);
        existing.setImageUrl("/images/existing-spu.webp");
        when(spuRepository.findById(10L)).thenReturn(Optional.of(existing));
        JpaCatalogStore compatibilityStore =
                new JpaCatalogStore(spuRepository, skuRepository, categoryRepository, new ObjectMapper());

        assertThatThrownBy(() -> compatibilityStore.save(spuWithoutImage(ProductStatus.DRAFT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Image reference services are required for trackable image writes");

        verify(spuRepository, never()).save(any(ProductSpu.class));
    }

    private static CatalogSpu spu(ProductStatus status) {
        ProductPriceBook priceBook = new ProductPriceBook(new BigDecimal("99.00"), null, null, Map.of());
        CatalogSku sku =
                new CatalogSku(100L, 10L, "SKU-100", new SkuSpecification(Map.of("color", "gold")), priceBook, true);
        return new CatalogSpu(
                10L,
                3L,
                1L,
                "Golden monkey",
                "Golden monkey",
                status,
                priceBook,
                Map.of(),
                null,
                null,
                "/images/golden-monkey.webp",
                List.of(sku));
    }

    private static CatalogSpu spuWithoutImage(ProductStatus status) {
        CatalogSpu source = spu(status);
        return new CatalogSpu(
                source.id(),
                source.categoryId(),
                source.shopId(),
                source.name(),
                source.title(),
                source.status(),
                source.priceBook(),
                source.attributes(),
                source.detailJsonLd(),
                source.supplierPrivateRemark(),
                null,
                source.skus());
    }

    private static ProductSku persistedSku() {
        ProductSku sku = new ProductSku(100L);
        sku.setSpuId(10L);
        sku.setSkuCode("SKU-100");
        sku.setSpecJson("{\"color\":\"gold\"}");
        sku.setOriginalPrice(new BigDecimal("99.00"));
        sku.setRegionPricesJson("{}");
        sku.setActive(true);
        return sku;
    }
}
