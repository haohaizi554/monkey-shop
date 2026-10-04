package com.example.monkey.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.product.application.dto.CatalogManagementPageQueryDto;
import com.example.monkey.product.application.dto.CatalogSpecificationDimensionDto;
import com.example.monkey.product.application.dto.CatalogUpdateSpuRequestDto;
import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.CatalogStore;
import com.example.monkey.product.domain.CategoryTreeCache;
import com.example.monkey.product.domain.ProductPriceBook;
import com.example.monkey.product.domain.ProductPriceContextResolver;
import com.example.monkey.product.domain.ProductPriceStrategy;
import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductCatalogManagementTest {

    @Test
    void managementPageReturnsNonListedProductsFromTheCurrentTenantCatalog() {
        CatalogStore store = mock();
        CatalogSpu draft = spu(ProductStatus.DRAFT);
        CatalogStore.CatalogPage page = new CatalogStore.CatalogPage(List.of(draft), 0, 20, 1);
        when(store.findManagementPage(any(CatalogStore.CatalogPageRequest.class)))
                .thenReturn(page);
        ProductCatalogApplicationService service = service(store, mock(IdGenerator.class));

        PageResponseDto<?> result = service.findManagementPage(new CatalogManagementPageQueryDto(0, 20, null, null));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().getClass().getSimpleName()).isEqualTo("CatalogSpuResponseDto");
        verify(store).findManagementPage(new CatalogStore.CatalogPageRequest(0, 20, null, null));
    }

    @Test
    void updatePreservesTheCurrentStatusAndUsesServerGeneratedSkuIdsForNewSpecifications() {
        CatalogStore store = mock();
        IdGenerator ids = mock();
        CatalogSpu existing = spu(ProductStatus.DRAFT);
        when(store.findSpuById(11L)).thenReturn(Optional.of(existing));
        when(store.isLeafCategory(12L)).thenReturn(true);
        when(ids.nextId()).thenReturn(200L);
        when(store.save(any(CatalogSpu.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ProductCatalogApplicationService service = service(store, ids);

        CatalogUpdateSpuRequestDto request = new CatalogUpdateSpuRequestDto(
                12L,
                13L,
                "Updated phone",
                "Updated title",
                new BigDecimal("120.00"),
                null,
                null,
                Map.of(),
                Map.of("color", "blue"),
                null,
                null,
                "/images/phone.webp",
                List.of(new CatalogSpecificationDimensionDto("color", List.of("blue"))));

        var response = service.updateSpu(11L, request);

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(response.skus())
                .singleElement()
                .satisfies(sku -> assertThat(sku.id()).isEqualTo(200L));
    }

    @Test
    void retiringAListedProductFollowsTheExplicitUnlistedThenRecycledTransition() {
        CatalogStore store = mock();
        CatalogSpu listed = spu(ProductStatus.LISTED);
        when(store.findSpuById(11L)).thenReturn(Optional.of(listed));
        when(store.save(any(CatalogSpu.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ProductCatalogApplicationService service = service(store, mock(IdGenerator.class));

        var response = service.retireSpu(11L);

        assertThat(response.status()).isEqualTo(ProductStatus.RECYCLED);
        verify(store).save(any(CatalogSpu.class));
    }

    private static ProductCatalogApplicationService service(CatalogStore store, IdGenerator ids) {
        return new ProductCatalogApplicationService(
                store,
                mock(CategoryTreeCache.class),
                ids,
                mock(ProductPriceStrategy.class),
                mock(ProductPriceContextResolver.class),
                mock(AuditService.class),
                mock(ImageReferenceService.class));
    }

    private static CatalogSpu spu(ProductStatus status) {
        ProductPriceBook priceBook = new ProductPriceBook(new BigDecimal("100.00"), null, null, Map.of());
        return new CatalogSpu(
                11L,
                12L,
                13L,
                "Phone",
                "Phone",
                status,
                priceBook,
                Map.of(),
                null,
                null,
                "/images/phone.webp",
                List.of());
    }
}
