package com.example.monkey.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.application.dto.CartAddItemRequestDto;
import com.example.monkey.cart.application.dto.CartCheckoutRequestDto;
import com.example.monkey.cart.application.dto.CartSelectItemRequestDto;
import com.example.monkey.cart.application.dto.CartUpdateItemRequestDto;
import com.example.monkey.cart.domain.CartCheckoutStore;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartLockManager;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CartStore;
import com.example.monkey.cart.domain.CheckoutOrder;
import com.example.monkey.cart.domain.FormalOrderCreator;
import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReserveRequestDto;
import com.example.monkey.inventory.application.dto.WarehouseStockResponseDto;
import com.example.monkey.inventory.domain.InventoryReservationStatus;
import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.marketing.application.dto.MarketingPriceAllocationDto;
import com.example.monkey.marketing.application.dto.MarketingPriceQuoteDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.order.domain.CheckoutOrderCommand;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

class CartApplicationServiceTest {

    private static final SessionUser USER = new SessionUser(7L, "USER");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String LEGACY_V51_FINGERPRINT =
            "LEGACY_V51_CHECKOUT_REPLAY_SENTINEL_____________________________";

    @Test
    void addItemRecalculatesCartFromCatalogSnapshot() {
        Fixture fixture = new Fixture();

        var response = fixture.service.addItem(USER, new CartAddItemRequestDto(1001L, 1L, 2, true));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).lineAmount()).isEqualByComparingTo("200.00");
        assertThat(response.selectedQuantity()).isEqualTo(2);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentAddsDoNotLoseQuantity() throws Exception {
        ConcurrentMutationCartStore cartStore = new ConcurrentMutationCartStore();
        Fixture fixture = new Fixture(cartStore);
        fixture.seedSelectedCart();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                await(start);
                fixture.service.addItem(USER, new CartAddItemRequestDto(1001L, 1L, 1, true));
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                fixture.service.addItem(USER, new CartAddItemRequestDto(1001L, 1L, 1, true));
            });

            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertThat(cartStore.findCart(USER.id()).items())
                    .filteredOn(item -> item.skuId().equals(1001L))
                    .singleElement()
                    .extracting(CartItem::quantity)
                    .isEqualTo(4);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentQuantityAndSelectionChangesPreserveBothFields() throws Exception {
        ConcurrentMutationCartStore cartStore = new ConcurrentMutationCartStore();
        Fixture fixture = new Fixture(cartStore);
        fixture.seedSelectedCart();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> quantity = executor.submit(() -> {
                await(start);
                fixture.service.updateItem(USER, 1001L, new CartUpdateItemRequestDto(3));
            });
            Future<?> selection = executor.submit(() -> {
                await(start);
                fixture.service.selectItem(USER, 1001L, new CartSelectItemRequestDto(false));
            });

            start.countDown();
            quantity.get(5, TimeUnit.SECONDS);
            selection.get(5, TimeUnit.SECONDS);

            assertThat(cartStore.findCart(USER.id()).items())
                    .filteredOn(item -> item.skuId().equals(1001L))
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.quantity()).isEqualTo(3);
                        assertThat(item.selected()).isFalse();
                    });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void delayedMutationAfterCheckoutCleanupDoesNotResurrectRemovedItem() throws Exception {
        BlockingMutationCartStore cartStore = new BlockingMutationCartStore();
        Fixture fixture = new Fixture(cartStore);
        fixture.seedSelectedCart();
        List<CartItem> checkoutSnapshots = cartStore.findCart(USER.id()).items();
        cartStore.blockNextRead();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> mutation =
                    executor.submit(() -> fixture.service.updateItem(USER, 1001L, new CartUpdateItemRequestDto(3)));
            assertThat(cartStore.awaitBlockedRead(5, TimeUnit.SECONDS)).isTrue();

            cartStore.removeMatchingItems(USER.id(), checkoutSnapshots, Duration.ofDays(7));
            assertThat(cartStore.findCart(USER.id()).items()).isEmpty();
            cartStore.releaseBlockedRead();
            assertThatThrownBy(() -> mutation.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class)
                    .satisfies(failure -> assertThat(((BusinessException) failure.getCause()).errorCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));
            assertThat(cartStore.findCart(USER.id()).items()).isEmpty();
        } finally {
            cartStore.releaseBlockedRead();
            executor.shutdownNow();
        }
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
        InOrder transactionOrder = inOrder(fixture.formalOrderCreator, fixture.marketingApplicationService);
        transactionOrder.verify(fixture.formalOrderCreator).create(any());
        transactionOrder
                .verify(fixture.marketingApplicationService)
                .redeemForCheckout(eq(USER.id()), eq(checkout.id()), eq(List.of("SHOP-10", "PLATFORM-20")));
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
        assertThat(checkout.subOrders().stream()
                        .map(subOrder -> subOrder.platformDiscountAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("20.00");
    }

    @Test
    void categoryCouponOnlyDiscountsEligibleCartLine() {
        Fixture fixture = new Fixture();
        fixture.seedSameShopMixedCategories();

        var checkout = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("CATEGORY-20")), "cart-key-category");

        assertThat(checkout.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(checkout.subOrders()).hasSize(1);
        assertThat(checkout.subOrders().get(0).lines())
                .anySatisfy(line -> {
                    assertThat(line.skuId()).isEqualTo(1001L);
                    assertThat(line.discountAmount()).isEqualByComparingTo("20.00");
                })
                .anySatisfy(line -> {
                    assertThat(line.skuId()).isEqualTo(1002L);
                    assertThat(line.discountAmount()).isEqualByComparingTo("0.00");
                });
        verify(fixture.marketingApplicationService).redeemForCheckout(USER.id(), checkout.id(), List.of("CATEGORY-20"));
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
    void v51LegacyCheckoutReplayReturnsOriginalResultWithoutSideEffects() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();
        var first = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-v51-replay");
        fixture.checkoutStore.replaceFingerprint(USER.id(), "cart-key-v51-replay", LEGACY_V51_FINGERPRINT);
        fixture.seedSelectedCart();

        var replay = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-SH", List.of("DIFFERENT")), "cart-key-v51-replay");

        assertThat(replay).isEqualTo(first);
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
        verify(fixture.marketingApplicationService, times(1)).redeemForCheckout(any(), any(), any());
    }

    @Test
    void v51LegacyCheckoutReplayIgnoresCurrentUnavailableSku() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();
        var first = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-v51-unavailable");
        fixture.checkoutStore.replaceFingerprint(USER.id(), "cart-key-v51-unavailable", LEGACY_V51_FINGERPRINT);
        fixture.seedSelectedCart();
        fixture.catalog.clear();

        var replay = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-SH", List.of("DIFFERENT")), "cart-key-v51-unavailable");

        assertThat(replay).isEqualTo(first);
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
    }

    @Test
    void v2ReplayWithCurrentUnavailableSkuFailsAsIdempotencyConflict() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();
        fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-v2-unavailable");
        fixture.seedSelectedCart();
        fixture.catalog.clear();

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-v2-unavailable"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
    }

    @Test
    void repeatedCheckoutWithDifferentAddressConflictsBeforeSideEffects() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();
        fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-conflict");

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER, new CartCheckoutRequestDto(10L, "CN-BJ", List.of("SHOP-10")), "cart-key-conflict"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
        verify(fixture.marketingApplicationService, times(1)).redeemForCheckout(any(), any(), any());
    }

    @Test
    void repeatedCheckoutWithDifferentProvinceConflictsBeforeSideEffects() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();
        fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-province");

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER, new CartCheckoutRequestDto(9L, "CN-SH", List.of("SHOP-10")), "cart-key-province"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
    }

    @Test
    void repeatedCheckoutWithDifferentCouponSetConflictsBeforeSideEffects() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();
        fixture.service.checkout(USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-coupons");

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("PLATFORM-20")), "cart-key-coupons"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
    }

    @Test
    void repeatedCheckoutWithDifferentSelectedCartConflictsBeforeSideEffects() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();
        fixture.service.checkout(USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-lines");
        fixture.seedSelectedCart(3);

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-lines"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
    }

    @Test
    void repeatedCheckoutWithNormalizedEquivalentInputReplaysOriginal() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();

        var first = fixture.service.checkout(
                USER,
                new CartCheckoutRequestDto(9L, " cn-bj ", List.of(" shop-10 ", "SHOP-10")),
                "cart-key-normalized");
        var replay = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of("SHOP-10")), "cart-key-normalized");

        assertThat(replay.id()).isEqualTo(first.id());
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
    }

    @Test
    void repeatedCheckoutWithDifferentCatalogSnapshotConflictsBeforeSideEffects() {
        Fixture fixture = new Fixture();
        fixture.seedSelectedCart();
        fixture.service.checkout(USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "cart-key-catalog");
        fixture.seedSelectedCart();
        fixture.catalog.put(
                1001L,
                new CartSkuSnapshot(
                        1001L, 501L, 11L, "SKU-1001", "Phone Pro", "/phone-pro.png", new BigDecimal("120.00")));

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "cart-key-catalog"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
    }

    @Test
    void repeatedCheckoutDistinguishesNullAndEmptyCatalogImageBeforeSideEffects() {
        Fixture fixture = new Fixture();
        fixture.catalog.put(
                1001L, new CartSkuSnapshot(1001L, 501L, 11L, "SKU-1001", "Phone", null, new BigDecimal("100.00")));
        fixture.seedSelectedCart();
        fixture.service.checkout(USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "cart-key-null-image");

        fixture.catalog.put(
                1001L, new CartSkuSnapshot(1001L, 501L, 11L, "SKU-1001", "Phone", "", new BigDecimal("100.00")));
        fixture.seedSelectedCart();

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "cart-key-null-image"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
        verify(fixture.marketingApplicationService, times(1)).redeemForCheckout(any(), any(), any());
    }

    @Test
    void fingerprintSeparatesCouponAndCartLineSections() {
        Fixture fixture = new Fixture();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        fixture.cartStore.seed(new CartSnapshot(USER.id(), List.of(new CartItem(1001L, 1L, 2, true, now, now))));
        fixture.service.checkout(
                USER,
                new CartCheckoutRequestDto(9L, "CN-BJ", List.of("1", "2", "3", "4", "5", "6", "7.00")),
                "cart-key-section-boundary");

        fixture.catalog.put(1L, new CartSkuSnapshot(1L, 99L, 4L, "IGNORED", "5", "6", new BigDecimal("7.00")));
        fixture.cartStore.seed(new CartSnapshot(
                USER.id(),
                List.of(new CartItem(1L, 2L, 3, true, now, now), new CartItem(1001L, 1L, 2, true, now, now))));

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "cart-key-section-boundary"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(fixture.inventoryApplicationService, times(1)).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.formalOrderCreator, times(1)).create(any());
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent cart test interrupted", exception);
        }
    }

    private static final class Fixture {
        private final InMemoryCartStore cartStore;
        private final InMemoryCheckoutStore checkoutStore = new InMemoryCheckoutStore();
        private final Map<Long, CartSkuSnapshot> catalog = new ConcurrentHashMap<>();
        private final InventoryApplicationService inventoryApplicationService = mock(InventoryApplicationService.class);
        private final MarketingApplicationService marketingApplicationService = mock(MarketingApplicationService.class);
        private final FormalOrderCreator formalOrderCreator = mock(FormalOrderCreator.class);
        private final CartApplicationService service;

        private Fixture() {
            this(new InMemoryCartStore());
        }

        private Fixture(InMemoryCartStore cartStore) {
            this.cartStore = cartStore;
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
            when(formalOrderCreator.create(any())).thenAnswer(invocation -> {
                CheckoutOrderCommand command = invocation.getArgument(0);
                return command.subOrders().stream()
                        .map(CheckoutOrderCommand.SubOrder::checkoutSubOrderId)
                        .toList();
            });
            service = new CartApplicationService(
                    cartStore,
                    skuId -> Optional.ofNullable(catalog.get(skuId)),
                    checkoutStore,
                    (checkoutId, userId, itemSnapshots, ttl) ->
                            cartStore.removeMatchingItems(userId, itemSnapshots, ttl),
                    new DirectCartLockManager(),
                    Supplier::get,
                    inventoryApplicationService,
                    marketingApplicationService,
                    formalOrderCreator,
                    new AtomicOrderNumberGenerator(),
                    new AtomicIdGenerator(),
                    mock(AuditService.class),
                    CLOCK,
                    Duration.ofDays(7));
        }

        private void seedSelectedCart() {
            seedSelectedCart(2);
        }

        private void seedSelectedCart(int firstQuantity) {
            LocalDateTime now = LocalDateTime.now(CLOCK);
            cartStore.seed(new CartSnapshot(
                    USER.id(),
                    List.of(
                            new CartItem(1001L, 1L, firstQuantity, true, now, now),
                            new CartItem(1002L, 2L, 1, true, now, now))));
        }

        private void seedSameShopMixedCategories() {
            LocalDateTime now = LocalDateTime.now(CLOCK);
            cartStore.seed(new CartSnapshot(
                    USER.id(),
                    List.of(new CartItem(1001L, 1L, 1, true, now, now), new CartItem(1002L, 1L, 1, true, now, now))));
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
            if (request.couponCodes().contains("CATEGORY-20")) {
                List<MarketingPriceAllocationDto> allocations = request.lines().stream()
                        .map(line -> new MarketingPriceAllocationDto(
                                line.lineId(),
                                Long.valueOf(11L).equals(line.categoryId()) ? new BigDecimal("20.00") : BigDecimal.ZERO,
                                Long.valueOf(11L).equals(line.categoryId()) ? List.of("CATEGORY-20") : List.of()))
                        .toList();
                return new MarketingPriceQuoteDto(
                        request.orderAmount(),
                        new BigDecimal("20.00"),
                        request.orderAmount().subtract(new BigDecimal("20.00")),
                        List.of("CATEGORY-20"),
                        allocations);
            }
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

    private static class InMemoryCartStore implements CartStore {
        private final Map<Long, CartSnapshot> carts = new ConcurrentHashMap<>();

        @Override
        public CartSnapshot findCart(Long userId) {
            return carts.getOrDefault(userId, new CartSnapshot(userId, List.of()));
        }

        @Override
        public boolean putItemIfUnchanged(Long userId, CartItem expectedItem, CartItem item, Duration ttl) {
            AtomicBoolean updated = new AtomicBoolean();
            carts.compute(userId, (ignored, current) -> {
                CartSnapshot cart = current == null ? new CartSnapshot(userId, List.of()) : current;
                CartItem actual = cart.items().stream()
                        .filter(existing -> existing.skuId().equals(item.skuId()))
                        .findFirst()
                        .orElse(null);
                if (!java.util.Objects.equals(actual, expectedItem)) {
                    return current;
                }
                List<CartItem> items = new java.util.ArrayList<>(cart.items());
                items.removeIf(existing -> existing.skuId().equals(item.skuId()));
                items.add(item);
                updated.set(true);
                return new CartSnapshot(userId, items);
            });
            return updated.get();
        }

        @Override
        public void putItem(Long userId, CartItem item, Duration ttl) {
            carts.compute(userId, (ignored, current) -> {
                CartSnapshot cart = current == null ? new CartSnapshot(userId, List.of()) : current;
                List<CartItem> items = new java.util.ArrayList<>(cart.items());
                items.removeIf(existing -> existing.skuId().equals(item.skuId()));
                items.add(item);
                return new CartSnapshot(userId, items);
            });
        }

        @Override
        public void removeItem(Long userId, Long skuId, Duration ttl) {
            carts.computeIfPresent(
                    userId,
                    (ignored, cart) -> new CartSnapshot(
                            userId,
                            cart.items().stream()
                                    .filter(item -> !item.skuId().equals(skuId))
                                    .toList()));
        }

        @Override
        public void removeMatchingItems(Long userId, List<CartItem> expectedItems, Duration ttl) {
            carts.computeIfPresent(
                    userId,
                    (ignored, cart) -> new CartSnapshot(
                            userId,
                            cart.items().stream()
                                    .filter(item -> !expectedItems.contains(item))
                                    .toList()));
        }

        private void seed(CartSnapshot cart) {
            carts.put(cart.userId(), cart);
        }
    }

    private static final class BlockingMutationCartStore extends InMemoryCartStore {
        private final AtomicBoolean blockNextRead = new AtomicBoolean();
        private final CountDownLatch blockedRead = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);

        @Override
        public CartSnapshot findCart(Long userId) {
            CartSnapshot snapshot = super.findCart(userId);
            if (blockNextRead.compareAndSet(true, false)) {
                blockedRead.countDown();
                try {
                    if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("cart mutation read release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cart mutation read interrupted", exception);
                }
            }
            return snapshot;
        }

        private void blockNextRead() {
            blockNextRead.set(true);
        }

        private boolean awaitBlockedRead(long timeout, TimeUnit unit) throws InterruptedException {
            return blockedRead.await(timeout, unit);
        }

        private void releaseBlockedRead() {
            releaseRead.countDown();
        }
    }

    private static final class ConcurrentMutationCartStore extends InMemoryCartStore {
        private final AtomicLong readsToSynchronize = new AtomicLong(2);
        private final CountDownLatch bothReadsCaptured = new CountDownLatch(2);

        @Override
        public CartSnapshot findCart(Long userId) {
            CartSnapshot snapshot = super.findCart(userId);
            if (readsToSynchronize.getAndDecrement() > 0) {
                bothReadsCaptured.countDown();
                try {
                    if (!bothReadsCaptured.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent cart reads did not rendezvous");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("concurrent cart read interrupted", exception);
                }
            }
            return snapshot;
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

        private void replaceFingerprint(Long userId, String idempotencyKey, String fingerprint) {
            checkouts.compute(
                    userId + ":" + idempotencyKey,
                    (ignored, checkout) -> new CheckoutOrder(
                            checkout.id(),
                            checkout.checkoutNo(),
                            checkout.userId(),
                            checkout.addressId(),
                            checkout.idempotencyKey(),
                            fingerprint,
                            checkout.originalAmount(),
                            checkout.discountAmount(),
                            checkout.payableAmount(),
                            checkout.status(),
                            checkout.province(),
                            checkout.createdAt(),
                            checkout.subOrders()));
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
