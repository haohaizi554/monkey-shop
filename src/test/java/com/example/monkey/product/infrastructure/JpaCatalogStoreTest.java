package com.example.monkey.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.product.domain.CatalogSku;
import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.ProductPriceBook;
import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.product.domain.SkuSpecification;
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
    private final JpaCatalogStore store =
            new JpaCatalogStore(spuRepository, skuRepository, categoryRepository, new ObjectMapper());

    @Test
    void savingAnExistingSpuStatusNeverDeletesOrRecreatesReferencedSkus() {
        CatalogSpu transitioned = spu(ProductStatus.PENDING_REVIEW);
        ProductSpu existing = new ProductSpu(10L);
        ProductSku persistedSku = persistedSku();
        when(spuRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(spuRepository.save(any(ProductSpu.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(skuRepository.findBySpuIdAndActiveTrueOrderByIdAsc(10L)).thenReturn(List.of(persistedSku));

        CatalogSpu saved = store.save(transitioned);

        assertThat(saved.status()).isEqualTo(ProductStatus.PENDING_REVIEW);
        assertThat(saved.skus()).extracting(CatalogSku::id).containsExactly(100L);
        verify(skuRepository, never()).saveAll(any());
        verify(skuRepository).findBySpuIdAndActiveTrueOrderByIdAsc(10L);
        verifyNoMoreInteractions(skuRepository);
    }

    private static CatalogSpu spu(ProductStatus status) {
        ProductPriceBook priceBook = new ProductPriceBook(new BigDecimal("99.00"), null, null, Map.of());
        CatalogSku sku =
                new CatalogSku(100L, 10L, "SKU-100", new SkuSpecification(Map.of("color", "gold")), priceBook, true);
        return new CatalogSpu(
                10L,
                3L,
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
