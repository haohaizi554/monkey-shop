package com.example.monkey.cart.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.application.dto.CartDirectCheckoutRequestDto;
import com.example.monkey.cart.domain.CartCatalogReader;
import com.example.monkey.cart.domain.CartCheckoutStore;
import com.example.monkey.cart.domain.CartCleanupScheduler;
import com.example.monkey.cart.domain.CartLockManager;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.cart.domain.CartStore;
import com.example.monkey.cart.domain.FormalOrderCreator;
import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.product.domain.PriceContext;
import com.example.monkey.product.domain.ProductPriceContextResolver;
import com.example.monkey.risk.domain.CommercialRiskGate;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CartCommercialRiskBoundaryTest {

    @Test
    void directApplicationCallerMustPassCanonicalProductToRiskBeforeAnyCommercialMutation() {
        CartStore cartStore = mock(CartStore.class);
        CartCatalogReader catalogReader = mock(CartCatalogReader.class);
        CartCheckoutStore checkoutStore = mock(CartCheckoutStore.class);
        CartCleanupScheduler cleanupScheduler = mock(CartCleanupScheduler.class);
        InventoryApplicationService inventory = mock(InventoryApplicationService.class);
        MarketingApplicationService marketing = mock(MarketingApplicationService.class);
        FormalOrderCreator formalOrderCreator = mock(FormalOrderCreator.class);
        CommercialRiskGate riskGate = mock(CommercialRiskGate.class);
        ProductPriceContextResolver priceContextResolver = (userId, region) -> new PriceContext("MEMBER", region);
        CartSkuSnapshot canonicalSku = new CartSkuSnapshot(
                1001L,
                501L,
                9L,
                11L,
                "SKU-1001",
                "Phone",
                "/images/product/phone.png",
                new BigDecimal("100.00"));
        when(catalogReader.findActiveSku(eq(1001L), any(PriceContext.class)))
                .thenReturn(Optional.of(canonicalSku));
        when(checkoutStore.findByUserIdAndIdempotencyKey(7L, "direct-risk-key"))
                .thenReturn(Optional.empty());
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "blocked"))
                .when(riskGate)
                .requireAllowed(
                        7L,
                        null,
                        canonicalSku.spuId(),
                        null,
                        "device-1",
                        "203.0.113.7",
                        "cart.checkout.direct");

        CartApplicationService service = new CartApplicationService(
                cartStore,
                catalogReader,
                priceContextResolver,
                checkoutStore,
                cleanupScheduler,
                new CartLockManager() {
                    @Override
                    public <T> T withCheckoutLock(Long userId, String idempotencyKey, Supplier<T> action) {
                        return action.get();
                    }
                },
                new CartTransactions() {
                    @Override
                    public <T> T execute(Supplier<T> action) {
                        return action.get();
                    }
                },
                inventory,
                marketing,
                formalOrderCreator,
                (OrderNumberGenerator) () -> "ORD-1",
                (IdGenerator) () -> 100L,
                mock(AuditService.class),
                riskGate,
                Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofDays(7));
        CartDirectCheckoutRequestDto request =
                new CartDirectCheckoutRequestDto(1001L, 9L, 1, 77L, "CN-BJ", List.of());

        assertThatThrownBy(() -> service.directCheckout(
                        new SessionUser(7L, "USER"),
                        request,
                        "direct-risk-key",
                        "device-1",
                        "203.0.113.7"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));

        verify(riskGate)
                .requireAllowed(
                        7L,
                        null,
                        canonicalSku.spuId(),
                        null,
                        "device-1",
                        "203.0.113.7",
                        "cart.checkout.direct");
        verify(checkoutStore, never()).save(any());
        verifyNoInteractions(inventory, marketing, formalOrderCreator);
    }
}
