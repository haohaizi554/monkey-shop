package com.example.monkey.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.application.dto.CartAddItemRequestDto;
import com.example.monkey.cart.application.dto.CartCheckoutRequestDto;
import com.example.monkey.cart.domain.CartCheckoutStore;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartLockManager;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CartStore;
import com.example.monkey.cart.domain.CheckoutOrder;
import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReserveRequestDto;
import com.example.monkey.inventory.application.dto.WarehouseStockResponseDto;
import com.example.monkey.inventory.domain.InventoryReservationStatus;
import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.marketing.application.dto.MarketingPriceQuoteDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CartApplicationServiceTest {

    private static final SessionUser USER = new SessionUser(7L, "USER");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void addItemRecalculatesCartFromCatalogSnapshot() {
        Fixture fixture = new Fixture();

        var response = fixture.service.addItem(USER, new CartAddItemRequestDto(1001L, 1L, 2, true));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).lineAmount()).isEqualByComparingTo("200.00");
        assertThat(response.selectedQuantity()).isEqualTo(2);
    }

    @Test
    void checkoutSplitsByShopReservesInventoryAndClearsSelectedItems() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();

        var checkout = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("PLATFORM-20", "SHOP-10")), "cart-key-1");

        assertThat(checkout.subOrders()).hasSize(2);
        assertThat(checkout.originalAmount()).isEqualByComparingTo("230.00");
        assertThat(checkout.discountAmount()).isEqualByComparingTo("30.00");
        assertThat(checkout.payableAmount()).isEqualByComparingTo("200.00");
        assertThat(fixture.cartStore.findCart(USER.id()).items()).isEmpty();
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
    }

    @Test
    void platformCouponIsAppliedOnceAcrossMultipleShops() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();

        var checkout = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("PLATFORM-20")), "cart-key-platform");

        assertThat(checkout.subOrders()).hasSize(2);
        assertThat(checkout.originalAmount()).isEqualByComparingTo("230.00");
        assertThat(checkout.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(checkout.payableAmount()).isEqualByComparingTo("210.00");
    }

    @Test
    void repeatedCheckoutReturnsOriginalResultWithoutDuplicateReservations() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();

        var first = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-2");
        var duplicate = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-2");

        assertThat(duplicate.id()).isEqualTo(first.id());
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
    }

    @Test
    void previewRecalculatesPriceWithoutInventoryReservation() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();
        fixture.catalog.put(
                1001L,
                new CartSkuSnapshot(1001L, 501L, 11L, "SKU-1001", "Phone XL", "/phone.png", new BigDecimal("120.00")));

        var preview =
                fixture.service.previewCheckout(USER, new CartCheckoutRequestDto(9L, "CN-SH", List.of("SHOP-10")));

        assertThat(preview.status().name()).isEqualTo("RESERVED");
        assertThat(preview.originalAmount()).isEqualByComparingTo("270.00");
        verify(fixture.inventoryApplicationService, never()).reserve(any(InventoryReserveRequestDto.class));
    }

    private static final class Fixture {
        private final InMemoryCartStore cartStore = new InMemoryCartStore();
        private final InMemoryCheckoutStore checkoutStore = new InMemoryCheckoutStore();
        private final Map<Long, CartSkuSnapshot> catalog = new ConcurrentHashMap<>();
        private final InventoryApplicationService inventoryApplicationService = mock(InventoryApplicationService.class);
        private final MarketingApplicationService marketingApplicationService = mock(MarketingApplicationService.class);
        private final CartApplicationService service;

        private Fixture() {
            catalog.put(
                    1001L,
                    new CartSkuSnapshot(1001L, 501L, 11L, "SKU-1001", "Phone", "/phone.png", new BigDecimal("100.00")));
            catalog.put(
                    1002L,
                    new CartSkuSnapshot(
                            1002L, 502L, 12L, "SKU-1002", "Keyboard", "/keyboard.png", new BigDecimal("30.00")));
            when(inventoryApplicationService.reserve(any()))
                    .thenAnswer(invocation -> reservation(invocation.getArgument(0)));
            when(marketingApplicationService.quotePlatformPrice(any()))
                    .thenAnswer(invocation -> platformQuote(invocation.getArgument(0)));
            when(marketingApplicationService.quoteStorePrice(any()))
                    .thenAnswer(invocation -> storeQuote(invocation.getArgument(0)));
            service = new CartApplicationService(
                    cartStore,
                    skuId -> Optional.ofNullable(catalog.get(skuId)),
                    checkoutStore,
                    new DirectCartLockManager(),
                    inventoryApplicationService,
                    marketingApplicationService,
                    new AtomicOrderNumberGenerator(),
                    new AtomicIdGenerator(),
                    mock(AuditService.class),
                    CLOCK,
                    Duration.ofDays(7));
        }

        private void seedSelectedCart() {
            LocalDateTime now = LocalDateTime.now(CLOCK);
            cartStore.save(
                    new CartSnapshot(
                            USER.id(),
                            List.of(
                                    new CartItem(1001L, 1L, 2, true, now, now),
                                    new CartItem(1002L, 2L, 1, true, now, now))),
                    Duration.ofDays(7));
        }

        private static InventoryReservationResponseDto reservation(InventoryReserveRequestDto request) {
            Long warehouseId = 9000L + request.skuId();
            return new InventoryReservationResponseDto(
                    request.reservationKey(),
                    request.skuId(),
                    warehouseId,
                    request.orderId(),
                    request.quantity(),
                    InventoryReservationStatus.RESERVED,
                    LocalDateTime.now(CLOCK).plusMinutes(15),
                    new WarehouseStockResponseDto(
                            request.skuId(), warehouseId, "WH", request.province(), 10, 1, 0, 0, 2, 11, false));
        }

        private static MarketingPriceQuoteDto quote(MarketingPriceRequestDto request) {
            BigDecimal discount = request.couponCodes().contains("PLATFORM-20")
                            && request.orderAmount().compareTo(new BigDecimal("100.00")) >= 0
                    ? new BigDecimal("20.00")
                    : BigDecimal.ZERO;
            return new MarketingPriceQuoteDto(
                    request.orderAmount(),
                    discount,
                    request.orderAmount().subtract(discount),
                    discount.signum() > 0 ? List.of("PLATFORM-20") : List.of());
        }

        private static MarketingPriceQuoteDto platformQuote(MarketingPriceRequestDto request) {
            return quote(request);
        }

        private static MarketingPriceQuoteDto storeQuote(MarketingPriceRequestDto request) {
            BigDecimal discount = Long.valueOf(1L).equals(request.shopId())
                            && request.couponCodes().contains("SHOP-10")
                    ? new BigDecimal("10.00")
                    : BigDecimal.ZERO;
            return new MarketingPriceQuoteDto(
                    request.orderAmount(),
                    discount,
                    request.orderAmount().subtract(discount),
                    discount.signum() > 0 ? List.of("SHOP-10") : List.of());
        }
    }

    private static final class InMemoryCartStore implements CartStore {
        private final Map<Long, CartSnapshot> carts = new ConcurrentHashMap<>();

        @Override
        public CartSnapshot findCart(Long userId) {
            return carts.getOrDefault(userId, new CartSnapshot(userId, List.of()));
        }

        @Override
        public CartSnapshot save(CartSnapshot cart, Duration ttl) {
            carts.put(cart.userId(), cart);
            return cart;
        }

        @Override
        public void removeItems(Long userId, List<Long> skuIds, Duration ttl) {
            CartSnapshot cart = findCart(userId);
            for (Long skuId : skuIds) {
                cart = cart.remove(skuId);
            }
            save(cart, ttl);
        }
    }

    private static final class InMemoryCheckoutStore implements CartCheckoutStore {
        private final Map<String, CheckoutOrder> checkouts = new ConcurrentHashMap<>();

        @Override
        public Optional<CheckoutOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
            return Optional.ofNullable(checkouts.get(userId + ":" + idempotencyKey));
        }

        @Override
        public CheckoutOrder save(CheckoutOrder checkout) {
            checkouts.put(checkout.userId() + ":" + checkout.idempotencyKey(), checkout);
            return checkout;
        }
    }

    private static final class DirectCartLockManager implements CartLockManager {
        @Override
        public <T> T withCheckoutLock(Long userId, String idempotencyKey, Supplier<T> action) {
            return action.get();
        }
    }

    private static final class AtomicIdGenerator implements IdGenerator {
        private final AtomicLong next = new AtomicLong(1_000L);

        @Override
        public long nextId() {
            return next.incrementAndGet();
        }
    }

    private static final class AtomicOrderNumberGenerator implements OrderNumberGenerator {
        private final AtomicLong next = new AtomicLong(1_000L);

        @Override
        public String nextOrderNo() {
            return "ORD" + next.incrementAndGet();
        }
    }
}
