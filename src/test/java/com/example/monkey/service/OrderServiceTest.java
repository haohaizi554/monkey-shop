package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.order.OrderIdempotencyStore.IdempotencyReservationRecord;
import com.example.monkey.domain.order.OrderNumberGenerator;
import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.domain.order.OrderStore;
import com.example.monkey.domain.order.OrderStore.AddressRecord;
import com.example.monkey.domain.order.OrderStore.BuyerRecord;
import com.example.monkey.domain.order.OrderStore.OrderPage;
import com.example.monkey.domain.order.OrderStore.OrderPageRequest;
import com.example.monkey.domain.order.OrderStore.OrderRecord;
import com.example.monkey.domain.order.OrderStore.ProductRecord;
import com.example.monkey.domain.order.OrderStore.SortOrder;
import com.example.monkey.domain.order.OrderStore.SortOrder.Direction;
import com.example.monkey.domain.order.OrderTransitionPolicy;
import com.example.monkey.domain.storage.ImageReferenceService;
import com.example.monkey.dto.OrderResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.infrastructure.order.SpringStateMachineOrderTransitionResolver;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderStore orderStore;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    @Mock
    private OrderIdempotencyService orderIdempotencyService;

    @Mock
    private OrderDistributedLockService orderDistributedLockService;

    @Mock
    private ImageReferenceService imageReferenceService;

    @Mock
    private BusinessMetricsService businessMetricsService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderStore,
                orderNumberGenerator,
                orderIdempotencyService,
                orderDistributedLockService,
                new SpringStateMachineOrderTransitionResolver(),
                immediateTransactions(),
                imageReferenceService,
                businessMetricsService);
        lenient().when(businessMetricsService.recordOrderCreate(any())).thenAnswer(invocation -> {
            Supplier<OrderResponseDto> supplier = invocation.getArgument(0);
            return supplier.get();
        });
        lenient()
                .when(orderDistributedLockService.withCreateOrderLock(any(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<OrderResponseDto> supplier = invocation.getArgument(2);
                    return supplier.get();
                });
        lenient().when(orderStore.transitionStatus(any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void findOrdersForUserDelegatesToScopedStoreQuery() {
        OrderRecord order = order();
        when(orderStore.findVisibleByUserOrderByCreateTimeDesc(42L)).thenReturn(List.of(order));

        List<OrderResponseDto> result = orderService.findOrdersForUser(42L);

        assertThat(result).containsExactly(response());
        verify(orderStore).findVisibleByUserOrderByCreateTimeDesc(42L);
    }

    @Test
    void findOrdersForUserSupportsDomainPageRequestContract() {
        OrderPageRequest pageRequest = new OrderPageRequest(0, 5, List.of(new SortOrder("createTime", Direction.DESC)));
        when(orderStore.findVisibleByUser(42L, pageRequest))
                .thenReturn(new OrderPage(List.of(order()), 0, 5, 12, 3, true, false));

        PageResponseDto<OrderResponseDto> result = orderService.findOrdersForUser(42L, pageRequest);

        assertThat(result.content()).containsExactly(response());
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.totalElements()).isEqualTo(12);
        assertThat(result.totalPages()).isEqualTo(3);
        verify(orderStore).findVisibleByUser(42L, pageRequest);
    }

    @Test
    void findAllOrdersUsesNewestFirstStoreQuery() {
        when(orderStore.findAllOrderByCreateTimeDesc()).thenReturn(List.of(order()));

        List<OrderResponseDto> result = orderService.findAllOrders();

        assertThat(result).containsExactly(response());
        verify(orderStore).findAllOrderByCreateTimeDesc();
    }

    @Test
    void findAllOrdersSupportsDomainPageRequestContract() {
        OrderPageRequest pageRequest = new OrderPageRequest(2, 10, List.of());
        when(orderStore.findAll(pageRequest)).thenReturn(new OrderPage(List.of(order()), 2, 10, 21, 3, false, true));

        PageResponseDto<OrderResponseDto> result = orderService.findAllOrders(pageRequest);

        assertThat(result.content()).containsExactly(response());
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.last()).isTrue();
        verify(orderStore).findAll(pageRequest);
    }

    @Test
    void createOrderSnapshotsProductIdAndUsesSnowflakeOrderNumber() {
        reserveOrderKey("order-key-1");
        when(orderStore.findProductById(7L)).thenReturn(Optional.of(product()));
        when(orderStore.findAddressById(3L)).thenReturn(Optional.of(address(42L)));
        when(orderStore.findBuyerById(42L)).thenReturn(Optional.of(buyer()));
        when(orderStore.deductProductStock(7L)).thenReturn(true);
        when(orderNumberGenerator.nextOrderNo()).thenReturn("ORD329861640192000000");
        when(orderStore.savePlacedOrder(any(OrderRecord.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 11L));

        OrderResponseDto result = orderService.createOrder(42L, 7L, 3L, "order-key-1");

        assertThat(result.status()).isEqualTo(OrderStatus.PAID.label());
        OrderRecord saved = capturePlacedOrder();
        assertThat(saved.productId()).isEqualTo(7L);
        assertThat(saved.price()).isEqualByComparingTo("199.99");
        assertThat(saved.orderNo()).isEqualTo("ORD329861640192000000");
        verify(orderDistributedLockService).withCreateOrderLock(eq(42L), eq(7L), any());
        verify(imageReferenceService).retain("/images/product/momo.png");
        verify(imageReferenceService).retain("/images/avatar/buyer.png");
        verify(orderIdempotencyService).complete(42L, "order-key-1", 11L);
        verify(businessMetricsService).recordOrderCreated();
    }

    @Test
    void createOrderRequiresIdempotencyKeyBeforeReservation() {
        assertThatThrownBy(() -> orderService.createOrder(42L, 7L, 3L, " "))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(orderDistributedLockService, orderIdempotencyService, orderStore);
    }

    @Test
    void createOrderReplaysCompletedIdempotentRequestWithoutDeductingStock() {
        when(orderIdempotencyService.reserve(eq(42L), eq("order-key-1"), any(String.class)))
                .thenAnswer(invocation -> OrderIdempotencyService.Reservation.duplicate(idempotencyRecord(
                        invocation.getArgument(2), IdempotencyReservationRecord.STATUS_COMPLETED, 11L)));
        when(orderStore.findById(11L)).thenReturn(Optional.of(order()));

        OrderResponseDto result = orderService.createOrder(42L, 7L, 3L, "order-key-1");

        assertThat(result).isEqualTo(response());
        verify(orderStore, never()).deductProductStock(any());
        verify(orderStore, never()).savePlacedOrder(any(OrderRecord.class));
    }

    @Test
    void createOrderRejectsReusedIdempotencyKeyWithDifferentPayload() {
        when(orderIdempotencyService.reserve(eq(42L), eq("order-key-1"), any(String.class)))
                .thenReturn(OrderIdempotencyService.Reservation.duplicate(
                        idempotencyRecord("different-request", IdempotencyReservationRecord.STATUS_COMPLETED, 11L)));

        assertThatThrownBy(() -> orderService.createOrder(42L, 7L, 3L, "order-key-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(orderStore, never()).findProductById(any());
        verify(orderStore, never()).savePlacedOrder(any(OrderRecord.class));
    }

    @Test
    void createOrderRejectsDuplicateRequestStillInProgress() {
        when(orderIdempotencyService.reserve(eq(42L), eq("order-key-1"), any(String.class)))
                .thenAnswer(invocation -> OrderIdempotencyService.Reservation.duplicate(idempotencyRecord(
                        invocation.getArgument(2), IdempotencyReservationRecord.STATUS_PROCESSING, null)));

        assertThatThrownBy(() -> orderService.createOrder(42L, 7L, 3L, "order-key-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(orderStore, never()).findProductById(any());
        verify(orderStore, never()).savePlacedOrder(any(OrderRecord.class));
    }

    @Test
    void createOrderFailsWhenStockCannotBeDeducted() {
        reserveOrderKey("order-key-1");
        when(orderStore.findProductById(7L)).thenReturn(Optional.of(product()));
        when(orderStore.findAddressById(3L)).thenReturn(Optional.of(address(42L)));
        when(orderStore.findBuyerById(42L)).thenReturn(Optional.of(buyer()));
        when(orderStore.deductProductStock(7L)).thenReturn(false);

        assertThatThrownBy(() -> orderService.createOrder(42L, 7L, 3L, "order-key-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK));

        verify(orderStore, never()).savePlacedOrder(any(OrderRecord.class));
        verify(businessMetricsService).recordStockDeductFailure();
    }

    @Test
    void createOrderRejectsProductWithoutStockBeforeAtomicDeduction() {
        reserveOrderKey("order-key-1");
        when(orderStore.findProductById(7L)).thenReturn(Optional.of(outOfStockProduct()));
        when(orderStore.findAddressById(3L)).thenReturn(Optional.of(address(42L)));
        when(orderStore.findBuyerById(42L)).thenReturn(Optional.of(buyer()));

        assertThatThrownBy(() -> orderService.createOrder(42L, 7L, 3L, "order-key-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK));

        verify(orderStore, never()).deductProductStock(7L);
        verify(orderStore, never()).savePlacedOrder(any(OrderRecord.class));
        verify(businessMetricsService).recordStockDeductFailure();
    }

    @Test
    void applyReturnRejectsAnotherUsersOrder() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.applyReturn(10L, 42L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void applyReturnChangesCompletedOwnedOrder() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderWithStatus(OrderStatus.COMPLETED)));

        OrderResponseDto result = orderService.applyReturn(10L, 42L);

        assertThat(result.status()).isEqualTo(OrderStatus.RETURN_REQUESTED.label());
        verify(orderStore)
                .transitionStatus(10L, OrderStatus.COMPLETED.label(), OrderStatus.RETURN_REQUESTED.label(), null);
    }

    @Test
    void hideOrderMarksOwnedOrderHiddenWithoutPhysicalDeleteOrStockRollback() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderWithStatus(OrderStatus.COMPLETED)));

        orderService.hideOrderForUser(10L, 42L);

        verify(orderStore).hideFromUser(10L);
        verify(orderStore, never()).recordStockRestore(any(), any());
        verify(orderStore, never()).restoreProductStock(any());
    }

    @Test
    void approveReturnMovesOrderToWaitingForUserShipment() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_REQUESTED)));

        OrderResponseDto result = orderService.approveReturn(10L);

        assertThat(result.status()).isEqualTo(OrderStatus.WAITING_RETURN_SHIPMENT.label());
        verify(orderStore)
                .transitionStatus(
                        10L, OrderStatus.RETURN_REQUESTED.label(), OrderStatus.WAITING_RETURN_SHIPMENT.label(), null);
    }

    @Test
    void shipOrderMapsIllegalAggregateTransitionToConflictWithoutSaving() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.COMPLETED)));

        assertThatThrownBy(() -> orderService.shipOrder(10L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(exception).hasMessage(OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
                });

        verify(orderStore, never()).transitionStatus(any(), any(), any(), any());
    }

    @Test
    void shipReturnMovesApprovedReturnToInTransit() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderWithStatus(OrderStatus.WAITING_RETURN_SHIPMENT)));

        OrderResponseDto result = orderService.shipReturn(10L, 42L);

        assertThat(result.status()).isEqualTo(OrderStatus.RETURN_SHIPPING.label());
        verify(orderStore)
                .transitionStatus(
                        10L, OrderStatus.WAITING_RETURN_SHIPMENT.label(), OrderStatus.RETURN_SHIPPING.label(), null);
    }

    @Test
    void confirmReturnRestoresStockBySnapshotProductId() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_SHIPPING)));
        when(orderStore.recordStockRestore(10L, 7L)).thenReturn(true);
        when(orderStore.restoreProductStock(7L)).thenReturn(true);

        OrderResponseDto result = orderService.confirmReturn(10L);

        assertThat(result.status()).isEqualTo(OrderStatus.REFUNDED.label());
        verify(orderStore).recordStockRestore(10L, 7L);
        verify(orderStore).restoreProductStock(7L);
        verify(orderStore)
                .transitionStatus(10L, OrderStatus.RETURN_SHIPPING.label(), OrderStatus.REFUNDED.label(), null);
    }

    @Test
    void confirmReturnSkipsDuplicateStockRestoreWhenLedgerAlreadyExists() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_SHIPPING)));
        when(orderStore.recordStockRestore(10L, 7L)).thenReturn(false);

        OrderResponseDto result = orderService.confirmReturn(10L);

        assertThat(result.status()).isEqualTo(OrderStatus.REFUNDED.label());
        verify(orderStore, never()).restoreProductStock(any());
        verify(orderStore)
                .transitionStatus(10L, OrderStatus.RETURN_SHIPPING.label(), OrderStatus.REFUNDED.label(), null);
    }

    @Test
    void confirmReturnFailsClosedWhenProductIdSnapshotIsMissing() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithoutProductSnapshot()));

        assertThatThrownBy(() -> orderService.confirmReturn(10L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(orderStore, never()).recordStockRestore(any(), any());
        verify(orderStore, never()).restoreProductStock(any());
        verify(orderStore, never()).transitionStatus(any(), any(), any(), any());
    }

    @Test
    void confirmReturnRejectsWrongStateBeforeRestoringStock() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.WAITING_RETURN_SHIPMENT)));

        assertThatThrownBy(() -> orderService.confirmReturn(10L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(exception).hasMessage(OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
                });

        verify(orderStore, never()).restoreProductStock(any());
        verify(orderStore, never()).transitionStatus(any(), any(), any(), any());
    }

    @Test
    void receiveOrderFailsWhenAtomicStatusTransitionLosesRace() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderWithStatus(OrderStatus.SHIPPED)));
        when(orderStore.transitionStatus(10L, OrderStatus.SHIPPED.label(), OrderStatus.COMPLETED.label(), null))
                .thenReturn(0);

        assertThatThrownBy(() -> orderService.receiveOrder(10L, 42L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    private OrderRecord capturePlacedOrder() {
        ArgumentCaptor<OrderRecord> captor = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderStore).savePlacedOrder(captor.capture());
        return captor.getValue();
    }

    private static ProductRecord product() {
        return new ProductRecord(7L, "Momo", "/images/product/momo.png", new BigDecimal("199.99"), "calm", 5);
    }

    private static ProductRecord outOfStockProduct() {
        return new ProductRecord(7L, "Momo", "/images/product/momo.png", new BigDecimal("199.99"), "calm", 0);
    }

    private static AddressRecord address(Long userId) {
        return new AddressRecord(3L, userId, "Ada", "13800000000", "Hangzhou");
    }

    private static BuyerRecord buyer() {
        return new BuyerRecord(42L, "buyer", "/images/avatar/buyer.png");
    }

    private static OrderRecord orderWithStatus(OrderStatus status) {
        return new OrderRecord(
                10L,
                "ORD202606280010",
                42L,
                "buyer",
                "/images/avatar/buyer.png",
                7L,
                "Momo",
                "/images/product/momo.png",
                BigDecimal.valueOf(199.99),
                "calm",
                "Ada",
                "13800138000",
                "Hangzhou",
                null,
                status.label(),
                LocalDateTime.of(2026, 6, 28, 14, 0),
                false);
    }

    private static OrderRecord orderWithoutProductSnapshot() {
        OrderRecord order = orderWithStatus(OrderStatus.RETURN_SHIPPING);
        return new OrderRecord(
                order.id(),
                order.orderNo(),
                order.userId(),
                order.buyerName(),
                order.buyerAvatar(),
                null,
                order.productName(),
                order.productImage(),
                order.price(),
                order.description(),
                order.receiverName(),
                order.receiverPhone(),
                order.addressSnapshot(),
                order.shippingTime(),
                order.status(),
                order.createTime(),
                order.userHidden());
    }

    private static OrderRecord order() {
        return new OrderRecord(
                11L,
                "ORD202606280001",
                42L,
                "buyer",
                "/images/avatar/buyer.png",
                7L,
                "Momo",
                "/images/product/momo.png",
                BigDecimal.valueOf(199.99),
                "calm",
                "Ada",
                "13800138000",
                "Hangzhou",
                LocalDateTime.of(2026, 6, 28, 15, 0),
                OrderStatus.PAID.label(),
                LocalDateTime.of(2026, 6, 28, 14, 0),
                false);
    }

    private static OrderRecord withId(OrderRecord order, Long id) {
        return new OrderRecord(
                id,
                order.orderNo(),
                order.userId(),
                order.buyerName(),
                order.buyerAvatar(),
                order.productId(),
                order.productName(),
                order.productImage(),
                order.price(),
                order.description(),
                order.receiverName(),
                order.receiverPhone(),
                order.addressSnapshot(),
                order.shippingTime(),
                order.status(),
                order.createTime(),
                order.userHidden());
    }

    private void reserveOrderKey(String idempotencyKey) {
        when(orderIdempotencyService.reserve(eq(42L), eq(idempotencyKey), any(String.class)))
                .thenReturn(OrderIdempotencyService.Reservation.newReservation());
    }

    private static TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private static IdempotencyReservationRecord idempotencyRecord(String requestHash, String status, Long orderId) {
        return new IdempotencyReservationRecord(
                1L,
                42L,
                "order-key-1",
                requestHash,
                orderId,
                status,
                LocalDateTime.parse("2026-06-28T00:00:00"),
                LocalDateTime.parse("2026-06-29T00:00:00"));
    }

    private static OrderResponseDto response() {
        return new OrderResponseDto(
                11L,
                "ORD202606280001",
                42L,
                "buyer",
                "/images/avatar/buyer.png",
                7L,
                "Momo",
                "/images/product/momo.png",
                BigDecimal.valueOf(199.99),
                "calm",
                "Ada",
                "13800138000",
                "Hangzhou",
                LocalDateTime.of(2026, 6, 28, 15, 0),
                OrderStatus.PAID.label(),
                LocalDateTime.of(2026, 6, 28, 14, 0));
    }
}
