package com.example.monkey.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.application.CartApplicationService;
import com.example.monkey.cart.application.dto.CartCheckoutRequestDto;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartLockManager;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CartStore;
import com.example.monkey.cart.infrastructure.CartCheckoutLineRepository;
import com.example.monkey.cart.infrastructure.CartCheckoutRepository;
import com.example.monkey.cart.infrastructure.CartSubOrderRepository;
import com.example.monkey.cart.infrastructure.JpaCartCheckoutStore;
import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReserveRequestDto;
import com.example.monkey.inventory.application.dto.WarehouseStockResponseDto;
import com.example.monkey.inventory.domain.InventoryReservationStatus;
import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.marketing.application.dto.MarketingPriceQuoteDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.order.infrastructure.OrderRepository;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@MockitoBean(types = PiiCryptoService.class)
class CheckoutTransactionIntegrationTest {

    private static final SessionUser USER = new SessionUser(7L, "USER");

    private final TestEntityManager entityManager;
    private final CartCheckoutRepository checkoutRepository;
    private final CartSubOrderRepository subOrderRepository;
    private final CartCheckoutLineRepository lineRepository;
    private final OrderRepository orderRepository;

    @Autowired
    CheckoutTransactionIntegrationTest(
            TestEntityManager entityManager,
            CartCheckoutRepository checkoutRepository,
            CartSubOrderRepository subOrderRepository,
            CartCheckoutLineRepository lineRepository,
            OrderRepository orderRepository) {
        this.entityManager = entityManager;
        this.checkoutRepository = checkoutRepository;
        this.subOrderRepository = subOrderRepository;
        this.lineRepository = lineRepository;
        this.orderRepository = orderRepository;
    }

    @Test
    void checkoutPersistsPendingOrdersBeforeClearingSelectedCartLines() {
        Fixture fixture = new Fixture(new JpaCartCheckoutStore(checkoutRepository, subOrderRepository, lineRepository));
        fixture.seedSelectedCart();

        var checkout =
                fixture.service.checkout(USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "checkout-key-1");
        entityManager.flush();
        entityManager.clear();

        assertThat(checkoutRepository.findById(checkout.id())).isPresent();
        assertThat(subOrderRepository.findByCheckoutIdOrderByIdAsc(checkout.id()))
                .hasSize(2);
        assertThat(lineRepository.findByCheckoutIdOrderBySubOrderIdAscIdAsc(checkout.id()))
                .hasSize(2);
        assertThat(fixture.cartStore.findCart(USER.id()).items()).isEmpty();
        verify(fixture.inventoryApplicationService, times(2)).reserve(any(InventoryReserveRequestDto.class));

        assertThat(orderRepository.findAll())
                .hasSize(2)
                .allSatisfy(order -> assertThat(order.getStatus()).isIn("PENDING_PAYMENT", "\u5f85\u652f\u4ed8"));
    }

    private static final class Fixture {
        private final InMemoryCartStore cartStore = new InMemoryCartStore();
        private final Map<Long, CartSkuSnapshot> catalog = new ConcurrentHashMap<>();
        private final InventoryApplicationService inventoryApplicationService = mock(InventoryApplicationService.class);
        private final MarketingApplicationService marketingApplicationService = mock(MarketingApplicationService.class);
        private final CartApplicationService service;

        private Fixture(JpaCartCheckoutStore checkoutStore) {
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
                    .thenAnswer(invocation -> noDiscount(invocation.getArgument(0)));
            when(marketingApplicationService.quoteStorePrice(any()))
                    .thenAnswer(invocation -> noDiscount(invocation.getArgument(0)));
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
                    Duration.ofDays(7));
        }

        private void seedSelectedCart() {
            LocalDateTime now = LocalDateTime.now();
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
                    LocalDateTime.now().plusMinutes(15),
                    new WarehouseStockResponseDto(
                            request.skuId(), warehouseId, "WH", request.province(), 10, 1, 0, 0, 2, 11, false));
        }

        private static MarketingPriceQuoteDto noDiscount(MarketingPriceRequestDto request) {
            return new MarketingPriceQuoteDto(request.orderAmount(), BigDecimal.ZERO, request.orderAmount(), List.of());
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
