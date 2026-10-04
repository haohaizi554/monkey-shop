package com.example.monkey.product.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.product.application.dto.CatalogCreateSpuRequestDto;
import com.example.monkey.product.application.dto.CatalogSpecificationDimensionDto;
import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.CatalogStore;
import com.example.monkey.product.domain.CategoryTreeCache;
import com.example.monkey.product.domain.ProductPriceContextResolver;
import com.example.monkey.product.domain.ProductPriceStrategy;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ProductCatalogImageReferenceTest {

    @Test
    void reservesTheCatalogImageBeforePersistingTheSpu() {
        CatalogStore catalogStore = mock();
        CategoryTreeCache categoryTreeCache = mock();
        IdGenerator idGenerator = mock();
        ImageReferenceService imageReferences = mock();
        when(catalogStore.isLeafCategory(10L)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(100L, 101L);
        when(catalogStore.save(any(CatalogSpu.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ProductCatalogApplicationService service = new ProductCatalogApplicationService(
                catalogStore,
                categoryTreeCache,
                idGenerator,
                mock(ProductPriceStrategy.class),
                mock(ProductPriceContextResolver.class),
                mock(AuditService.class),
                imageReferences);
        CatalogCreateSpuRequestDto request = new CatalogCreateSpuRequestDto(
                10L,
                20L,
                "Phone",
                "Phone",
                new BigDecimal("100.00"),
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                "/images/product/immutable.png",
                List.of(new CatalogSpecificationDimensionDto("Color", List.of("Black"))));

        service.createSpu(request);

        InOrder mutationOrder = inOrder(imageReferences, catalogStore);
        mutationOrder.verify(imageReferences).retain("/images/product/immutable.png");
        mutationOrder.verify(catalogStore).save(any(CatalogSpu.class));
    }
}
