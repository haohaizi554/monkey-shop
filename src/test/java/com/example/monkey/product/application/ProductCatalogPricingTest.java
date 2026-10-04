package com.example.monkey.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.CatalogStore;
import com.example.monkey.product.domain.CategoryTreeCache;
import com.example.monkey.product.domain.IdentityRegionPriceStrategy;
import com.example.monkey.product.domain.PriceContext;
import com.example.monkey.product.domain.ProductPriceBook;
import com.example.monkey.product.domain.ProductPriceContextResolver;
import com.example.monkey.product.domain.ProductPriceStrategy;
import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProductCatalogPricingTest {

    @Test
    void quoteUsesServerResolvedMembershipInsteadOfCallerClaim() {
        CatalogStore catalogStore = mock();
        ProductPriceContextResolver priceContextResolver = mock();
        when(catalogStore.findSpuById(11L)).thenReturn(Optional.of(spu()));
        when(priceContextResolver.resolve(7L, "cn-bj"))
                .thenReturn(new PriceContext("MEMBER", "CN-BJ"));
        ProductCatalogApplicationService service = new ProductCatalogApplicationService(
                catalogStore,
                mock(CategoryTreeCache.class),
                mock(IdGenerator.class),
                new IdentityRegionPriceStrategy(),
                priceContextResolver,
                mock(AuditService.class));

        var quote = service.quotePrice(11L, 7L, "cn-bj");

        assertThat(quote.salePrice()).isEqualByComparingTo("80.00");
        assertThat(quote.strategy()).isEqualTo("MEMBER");
        verify(priceContextResolver).resolve(7L, "cn-bj");
    }

    @ParameterizedTest(name = "public detail hides {0} SPU as not found")
    @EnumSource(value = ProductStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "LISTED")
    void publicDetailHidesEveryNonListedSpuAsNotFound(ProductStatus status) {
        CatalogStore catalogStore = mock();
        when(catalogStore.findSpuById(11L)).thenReturn(Optional.of(spu(status)));
        ProductCatalogApplicationService service = service(
                catalogStore, mock(ProductPriceStrategy.class), mock(ProductPriceContextResolver.class));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.getSpu(11L))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void publicDetailAllowsListedSpu() {
        CatalogStore catalogStore = mock();
        when(catalogStore.findSpuById(11L)).thenReturn(Optional.of(spu(ProductStatus.LISTED)));
        ProductCatalogApplicationService service = service(
                catalogStore, mock(ProductPriceStrategy.class), mock(ProductPriceContextResolver.class));

        var response = service.getSpu(11L);

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.status()).isEqualTo(ProductStatus.LISTED);
    }

    @ParameterizedTest(name = "public quote hides {0} SPU before pricing")
    @EnumSource(value = ProductStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "LISTED")
    void publicQuoteHidesEveryNonListedSpuBeforeResolvingOrQuotingPrice(ProductStatus status) {
        CatalogStore catalogStore = mock();
        ProductPriceStrategy priceStrategy = mock();
        ProductPriceContextResolver priceContextResolver = mock();
        when(catalogStore.findSpuById(11L)).thenReturn(Optional.of(spu(status)));
        ProductCatalogApplicationService service = service(catalogStore, priceStrategy, priceContextResolver);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.quotePrice(11L, 7L, "cn-bj"))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(priceContextResolver, priceStrategy);
    }

    private static ProductCatalogApplicationService service(
            CatalogStore catalogStore,
            ProductPriceStrategy priceStrategy,
            ProductPriceContextResolver priceContextResolver) {
        return new ProductCatalogApplicationService(
                catalogStore,
                mock(CategoryTreeCache.class),
                mock(IdGenerator.class),
                priceStrategy,
                priceContextResolver,
                mock(AuditService.class));
    }

    private static CatalogSpu spu() {
        return spu(ProductStatus.LISTED);
    }

    private static CatalogSpu spu(ProductStatus status) {
        return new CatalogSpu(
                11L,
                12L,
                13L,
                "Phone",
                "Phone",
                status,
                new ProductPriceBook(
                        new BigDecimal("100.00"),
                        new BigDecimal("80.00"),
                        new BigDecimal("120.00"),
                        Map.of("CN-BJ", new BigDecimal("90.00"))),
                Map.of(),
                null,
                null,
                "/phone.png",
                List.of());
    }
}
