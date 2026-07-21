package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore.CheckoutOrderLineRecord;
import com.example.monkey.order.domain.OrderStore.OrderPage;
import com.example.monkey.order.domain.OrderStore.OrderPageRequest;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.order.domain.OrderStore.SortOrder;
import com.example.monkey.order.domain.OrderStore.SortOrder.Direction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class JpaOrderStoreTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private StockLogRepository stockLogRepository;

    @Mock
    private OrderLineRepository orderLineRepository;

    private JpaOrderStore store;

    @BeforeEach
    void setUp() {
        store = new JpaOrderStore(orderRepository, stockLogRepository, orderLineRepository);
    }

    @Test
    void findVisibleByUserMapsPageAndPreservesSortOrders() {
        PageRequest repositoryPageable =
                PageRequest.of(1, 5, Sort.by(Sort.Order.desc("createTime"), Sort.Order.asc("id")));
        when(orderRepository.findByUserIdAndUserHiddenFalse(eq(42L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order()), repositoryPageable, 12));

        OrderPage result = store.findVisibleByUser(
                42L,
                new OrderPageRequest(
                        1,
                        5,
                        List.of(new SortOrder("createTime", Direction.DESC), new SortOrder("id", Direction.ASC))));

        assertThat(result.content()).containsExactly(record());
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.totalElements()).isEqualTo(12);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isFalse();

        Pageable pageable = captureVisiblePageable();
        assertThat(pageable.getSort().getOrderFor("createTime").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void findAllMapsPageAndUsesUnsortedPageableWhenNoSortOrdersAreProvided() {
        when(orderRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order()), PageRequest.of(0, 10), 1));

        OrderPage result = store.findAll(new OrderPageRequest(0, 10, null));

        assertThat(result.content()).containsExactly(record());
        Pageable pageable = captureAllPageable();
        assertThat(pageable.getSort().isUnsorted()).isTrue();
    }

    @Test
    void findAllFiltersUnsupportedSortPropertiesBeforeQueryingJpa() {
        when(orderRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order()), PageRequest.of(0, 10), 1));

        store.findAll(new OrderPageRequest(
                0,
                10,
                List.of(new SortOrder("receiverPhone", Direction.ASC), new SortOrder("createTime", Direction.DESC))));

        Pageable pageable = captureAllPageable();
        assertThat(pageable.getSort().getOrderFor("receiverPhone")).isNull();
        assertThat(pageable.getSort().getOrderFor("createTime").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void findAllBoundsPageAndSizeBeforeQueryingJpa() {
        when(orderRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order()), PageRequest.of(0, 100), 1));

        store.findAll(new OrderPageRequest(-3, 5000, List.of()));

        Pageable pageable = captureAllPageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(100);
    }

    @Test
    void findByIdAndVisibleOwnedOrderMapRepositoryOptionals() {
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order()));
        when(orderRepository.findByIdAndUserIdAndUserHiddenFalse(10L, 42L)).thenReturn(Optional.of(order()));

        assertThat(store.findById(10L)).contains(record());
        assertThat(store.findVisibleByIdAndUserId(10L, 42L)).contains(record());
    }

    @Test
    void findLinesReturnsEveryPersistedCheckoutLineWithInventoryCoordinates() {
        CheckoutOrderLineRecord first = checkoutLine(101L, 1L, 2, "cart:42:pay:101");
        CheckoutOrderLineRecord second = checkoutLine(202L, 2L, 3, "cart:42:pay:202");
        when(orderLineRepository.findByOrderIdOrderByIdAsc(10L))
                .thenReturn(List.of(OrderLineEntity.from(10L, first), OrderLineEntity.from(10L, second)));

        assertThat(store.findLines(10L)).containsExactly(first, second);
    }

    @Test
    void findLinesByOrderIdsUsesOneRepositoryQueryAndGroupsLinesByOrder() {
        CheckoutOrderLineRecord first = checkoutLine(101L, 1L, 2, "cart:42:pay:101");
        CheckoutOrderLineRecord second = checkoutLine(202L, 2L, 3, "cart:42:pay:202");
        CheckoutOrderLineRecord third = checkoutLine(303L, 3L, 1, "cart:42:pay:303");
        when(orderLineRepository.findByOrderIdInOrderByOrderIdAscIdAsc(List.of(10L, 20L)))
                .thenReturn(List.of(
                        OrderLineEntity.from(10L, first),
                        OrderLineEntity.from(10L, second),
                        OrderLineEntity.from(20L, third)));

        Map<Long, List<CheckoutOrderLineRecord>> result = store.findLinesByOrderIds(List.of(10L, 20L));

        assertThat(result).containsEntry(10L, List.of(first, second)).containsEntry(20L, List.of(third));
        verify(orderLineRepository).findByOrderIdInOrderByOrderIdAscIdAsc(List.of(10L, 20L));
    }

    @Test
    void recordStockRestoreDelegatesToAtomicRepository() {
        when(stockLogRepository.recordRestore(10L, 7L)).thenReturn(1);

        assertThat(store.recordStockRestore(10L, 7L)).isTrue();
    }

    @Test
    void savePlacedOrderMapsDomainRecordThroughRepositoryEntity() {
        when(orderRepository.save(any(Order.class))).thenReturn(order());

        OrderRecord result = store.savePlacedOrder(unsavedRecord());

        assertThat(result).isEqualTo(record());
        Order savedOrder = captureSavedOrder();
        assertThat(savedOrder.getId()).isNull();
        assertThat(savedOrder.getOrderNo()).isEqualTo("ORD202606280001");
        assertThat(savedOrder.getUserId()).isEqualTo(42L);
        assertThat(savedOrder.getProductId()).isEqualTo(7L);
        assertThat(savedOrder.getPrice()).isEqualByComparingTo("199.99");
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAID.label());
    }

    @Test
    void hideFromUserMarksLoadedOrderAndSavesIt() {
        Order order = order();
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        store.hideFromUser(10L);

        assertThat(order.isUserHidden()).isTrue();
        verify(orderRepository).save(order);
    }

    @Test
    void transitionStatusDelegatesToRepositoryVariantForShippingTimePresence() {
        LocalDateTime shippedAt = LocalDateTime.parse("2026-06-28T15:00:00");
        when(orderRepository.transitionStatus(10L, OrderStatus.PAID.label(), OrderStatus.COMPLETED.label()))
                .thenReturn(1);
        when(orderRepository.transitionStatusWithShippingTime(
                        10L, OrderStatus.PAID.label(), OrderStatus.SHIPPED.label(), shippedAt))
                .thenReturn(1);

        assertThat(store.transitionStatus(10L, OrderStatus.PAID.label(), OrderStatus.COMPLETED.label(), null))
                .isEqualTo(1);
        assertThat(store.transitionStatus(10L, OrderStatus.PAID.label(), OrderStatus.SHIPPED.label(), shippedAt))
                .isEqualTo(1);
    }

    @Test
    void orderPageNormalizesNullContentToEmptyList() {
        OrderPage page = new OrderPage(null, 0, 5, 0, 0, true, true);

        assertThat(page.content()).isEmpty();
    }

    private static CheckoutOrderLineRecord checkoutLine(
            Long skuId, Long warehouseId, int quantity, String reservationKey) {
        BigDecimal amount = BigDecimal.TEN.multiply(BigDecimal.valueOf(quantity));
        return new CheckoutOrderLineRecord(
                skuId,
                skuId,
                9L,
                3L,
                "SKU-" + skuId,
                "/images/product/" + skuId + ".png",
                quantity,
                BigDecimal.TEN,
                amount,
                BigDecimal.ZERO,
                amount,
                "",
                reservationKey,
                warehouseId);
    }

    private Pageable captureVisiblePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByUserIdAndUserHiddenFalse(eq(42L), captor.capture());
        return captor.getValue();
    }

    private Pageable captureAllPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findAll(captor.capture());
        return captor.getValue();
    }

    private Order captureSavedOrder() {
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Order order() {
        Order order = new Order();
        order.setId(10L);
        order.setOrderNo("ORD202606280001");
        order.setUserId(42L);
        order.setBuyerName("buyer");
        order.setBuyerAvatar("/images/avatar/buyer.png");
        order.setProductId(7L);
        order.setProductName("Momo");
        order.setProductImage("/images/product/momo.png");
        order.setPrice(new BigDecimal("199.99"));
        order.setDescription("calm");
        order.setReceiverName("Ada");
        order.setReceiverPhone("13800138000");
        order.setAddressSnapshot("Hangzhou");
        order.setShippingTime(LocalDateTime.parse("2026-06-28T15:00:00"));
        order.setStatus(OrderStatus.PAID.label());
        order.setCreateTime(LocalDateTime.parse("2026-06-28T14:00:00"));
        order.setUserHidden(false);
        return order;
    }

    private static OrderRecord record() {
        return new OrderRecord(
                10L,
                "ORD202606280001",
                42L,
                "buyer",
                "/images/avatar/buyer.png",
                7L,
                "Momo",
                "/images/product/momo.png",
                new BigDecimal("199.99"),
                "calm",
                "Ada",
                "13800138000",
                "Hangzhou",
                LocalDateTime.parse("2026-06-28T15:00:00"),
                OrderStatus.PAID.label(),
                LocalDateTime.parse("2026-06-28T14:00:00"),
                false);
    }

    private static OrderRecord unsavedRecord() {
        return new OrderRecord(
                null,
                "ORD202606280001",
                42L,
                "buyer",
                "/images/avatar/buyer.png",
                7L,
                "Momo",
                "/images/product/momo.png",
                new BigDecimal("199.99"),
                "calm",
                "Ada",
                "13800138000",
                "Hangzhou",
                null,
                OrderStatus.PAID.label(),
                null,
                false);
    }
}
