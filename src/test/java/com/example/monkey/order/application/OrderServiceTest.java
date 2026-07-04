package com.example.monkey.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.order.domain.OrderCustomerPort;
import com.example.monkey.order.domain.OrderFulfillmentItem;
import com.example.monkey.order.domain.OrderFulfillmentStore;
import com.example.monkey.order.domain.OrderIdempotencyStore.IdempotencyReservationRecord;
import com.example.monkey.order.domain.OrderLockManager;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.order.domain.OrderProductPort;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import com.example.monkey.order.domain.OrderStore.OrderPage;
import com.example.monkey.order.domain.OrderStore.OrderPageRequest;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.order.domain.OrderStore.ProductRecord;
import com.example.monkey.order.domain.OrderStore.SortOrder;
import com.example.monkey.order.domain.OrderStore.SortOrder.Direction;
import com.example.monkey.order.domain.OrderTransitionPolicy;
import com.example.monkey.order.infrastructure.SpringStateMachineOrderTransitionResolver;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
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
    private OrderProductPort orderProductPort;

    @Mock
    private OrderCustomerPort orderCustomerPort;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    @Mock
    private OrderIdempotencyService orderIdempotencyService;

    @Mock
    private OrderLockManager orderLockManager;

    @Mock
    private OrderFulfillmentStore fulfillmentStore;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private ImageReferenceService imageReferenceService;

    @Mock
    private BusinessMetricsService businessMetricsService;

    @Mock
    private AuditService auditService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderStore,
                orderProductPort,
                orderCustomerPort,
                orderNumberGenerator,
                orderIdempotencyService,
                orderLockManager,
                new SpringStateMachineOrderTransitionResolver(),
                immediateTransactions(),
                imageReferenceService,
                businessMetricsService,
                auditService,
                fulfillmentStore,
                idGenerator,
                java.time.Duration.ofDays(7));
        lenient().when(businessMetricsService.recordOrderCreate(any())).thenAnswer(invocation -> {
            Supplier<OrderResponseDto> supplier = invocation.getArgument(0);
            return supplier.get();
        });
        lenient()
                .when(orderLockManager.withCreateOrderLock(any(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<OrderResponseDto> supplier = invocation.getArgument(2);
                    return supplier.get();
                });
        lenient().when(orderStore.transitionStatus(any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void findOrdersForUserUsesBoundedLegacyPageRequest() {
        OrderRecord order = order();
        when(orderStore.findVisibleByUser(eq(42L), any(OrderPageRequest.class)))
                .thenReturn(new OrderPage(List.of(order), 0, 100, 1, 1, true, true));

        List<OrderResponseDto> result = orderService.findOrdersForUser(42L);

        assertThat(result).containsExactly(response());
        OrderPageRequest pageRequest = captureVisibleOrderPageRequest();
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(100);
        assertThat(pageRequest.sortOrders()).containsExactly(new SortOrder("createTime", Direction.DESC));
    }

    @Test
    void findOrdersForUserSupportsApplicationPageQueryContract() {
        OrderPageQuery pageQuery = new OrderPageQuery(
                0, 5, List.of(new OrderPageQuery.SortOrder("createTime", OrderPageQuery.SortOrder.Direction.DESC)));
        when(orderStore.findVisibleByUser(eq(42L), any(OrderPageRequest.class)))
                .thenReturn(new OrderPage(List.of(order()), 0, 5, 12, 3, true, false));

        PageResponseDto<OrderResponseDto> result = orderService.findOrdersForUser(42L, pageQuery);

        assertThat(result.content()).containsExactly(response());
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.totalElements()).isEqualTo(12);
        assertThat(result.totalPages()).isEqualTo(3);
        OrderPageRequest pageRequest = captureVisibleOrderPageRequest();
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(5);
        assertThat(pageRequest.sortOrders()).containsExactly(new SortOrder("createTime", Direction.DESC));
    }

    @Test
    void findAllOrdersUsesBoundedLegacyPageRequest() {
        when(orderStore.findAll(any(OrderPageRequest.class)))
                .thenReturn(new OrderPage(List.of(order()), 0, 100, 1, 1, true, true));

        List<OrderResponseDto> result = orderService.findAllOrders();

        assertThat(result).containsExactly(response());
        OrderPageRequest pageRequest = captureAllOrderPageRequest();
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(100);
        assertThat(pageRequest.sortOrders()).containsExactly(new SortOrder("createTime", Direction.DESC));
    }

    @Test
    void findAllOrdersSupportsApplicationPageQueryContract() {
        OrderPageQuery pageQuery = new OrderPageQuery(2, 10, List.of());
        when(orderStore.findAll(any(OrderPageRequest.class)))
                .thenReturn(new OrderPage(List.of(order()), 2, 10, 21, 3, false, true));

        PageResponseDto<OrderResponseDto> result = orderService.findAllOrders(pageQuery);

        assertThat(result.content()).containsExactly(response());
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.last()).isTrue();
        OrderPageRequest pageRequest = captureAllOrderPageRequest();
        assertThat(pageRequest.page()).isEqualTo(2);
        assertThat(pageRequest.size()).isEqualTo(10);
        assertThat(pageRequest.sortOrders()).isEmpty();
    }

    @Test
    void createOrderSnapshotsProductIdAndUsesSnowflakeOrderNumber() {
        reserveOrderKey("order-key-1");
        when(orderProductPort.findProductById(7L)).thenReturn(Optional.of(product()));
        when(orderCustomerPort.findAddressById(3L)).thenReturn(Optional.of(address(42L)));
        when(orderCustomerPort.findBuyerById(42L)).thenReturn(Optional.of(buyer()));
        when(orderProductPort.deductProductStock(7L)).thenReturn(true);
        when(orderNumberGenerator.nextOrderNo()).thenReturn("ORD329861640192000000");
        when(orderStore.savePlacedOrder(any(OrderRecord.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 11L));

        OrderResponseDto result = orderService.createOrder(42L, 7L, 3L, "order-key-1");

        assertThat(result.status()).isEqualTo(OrderStatus.PAID.label());
        OrderRecord saved = capturePlacedOrder();
        assertThat(saved.productId()).isEqualTo(7L);
        assertThat(saved.price()).isEqualByComparingTo("199.99");
        assertThat(saved.orderNo()).isEqualTo("ORD329861640192000000");
        verify(orderLockManager).withCreateOrderLock(eq(42L), eq(7L), any());
        verify(imageReferenceService).retain("/images/product/momo.png");
        verify(imageReferenceService).retain("/images/avatar/buyer.png");
        verify(orderIdempotencyService).complete(42L, "order-key-1", 11L);
        verify(businessMetricsService).recordOrderCreated();
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_CREATED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(42L),
                        eq("USER"),
                        eq("ORD329861640192000000"),
                        isNull(),
                        eq("orderId=11 status=PAID"));
    }

    @Test
    void createOrderRequiresIdempotencyKeyBeforeReservation() {
        assertThatThrownBy(() -> orderService.createOrder(42L, 7L, 3L, " "))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(auditService)
                .record(
                        eq(AuditService.ORDER_CREATE_FAILURE),
                        eq(AuditService.OUTCOME_FAILURE),
                        eq(42L),
                        eq("USER"),
                        eq("order-create:42:7"),
                        isNull(),
                        eq("monkeyId=7 reason=VALIDATION_ERROR"));
        verifyNoInteractions(orderLockManager, orderIdempotencyService, orderStore);
    }

    @Test
    void createOrderReplaysCompletedIdempotentRequestWithoutDeductingStock() {
        when(orderIdempotencyService.reserve(eq(42L), eq("order-key-1"), any(String.class)))
                .thenAnswer(invocation -> OrderIdempotencyService.Reservation.duplicate(idempotencyRecord(
                        invocation.getArgument(2), IdempotencyReservationRecord.STATUS_COMPLETED, 11L)));
        when(orderStore.findById(11L)).thenReturn(Optional.of(order()));

        OrderResponseDto result = orderService.createOrder(42L, 7L, 3L, "order-key-1");

        assertThat(result).isEqualTo(response());
        verify(orderProductPort, never()).deductProductStock(any());
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

        verify(orderProductPort, never()).findProductById(any());
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

        verify(orderProductPort, never()).findProductById(any());
        verify(orderStore, never()).savePlacedOrder(any(OrderRecord.class));
    }

    @Test
    void createOrderFailsWhenStockCannotBeDeducted() {
        reserveOrderKey("order-key-1");
        when(orderProductPort.findProductById(7L)).thenReturn(Optional.of(product()));
        when(orderCustomerPort.findAddressById(3L)).thenReturn(Optional.of(address(42L)));
        when(orderCustomerPort.findBuyerById(42L)).thenReturn(Optional.of(buyer()));
        when(orderProductPort.deductProductStock(7L)).thenReturn(false);

        assertThatThrownBy(() -> orderService.createOrder(42L, 7L, 3L, "order-key-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK));

        verify(orderStore, never()).savePlacedOrder(any(OrderRecord.class));
        verify(businessMetricsService).recordStockDeductFailure();
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_CREATE_FAILURE),
                        eq(AuditService.OUTCOME_FAILURE),
                        eq(42L),
                        eq("USER"),
                        eq("order-create:42:7"),
                        isNull(),
                        eq("monkeyId=7 reason=OUT_OF_STOCK"));
    }

    @Test
    void createOrderRejectsProductWithoutStockBeforeAtomicDeduction() {
        reserveOrderKey("order-key-1");
        when(orderProductPort.findProductById(7L)).thenReturn(Optional.of(outOfStockProduct()));
        when(orderCustomerPort.findAddressById(3L)).thenReturn(Optional.of(address(42L)));
        when(orderCustomerPort.findBuyerById(42L)).thenReturn(Optional.of(buyer()));

        assertThatThrownBy(() -> orderService.createOrder(42L, 7L, 3L, "order-key-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK));

        verify(orderProductPort, never()).deductProductStock(7L);
        verify(orderStore, never()).savePlacedOrder(any(OrderRecord.class));
        verify(businessMetricsService).recordStockDeductFailure();
    }

    @Test
    void createOrderRejectsAddressOwnedByAnotherUserBeforeAtomicDeduction() {
        reserveOrderKey("order-key-1");
        when(orderProductPort.findProductById(7L)).thenReturn(Optional.of(product()));
        when(orderCustomerPort.findAddressById(3L)).thenReturn(Optional.of(address(99L)));
        when(orderCustomerPort.findBuyerById(42L)).thenReturn(Optional.of(buyer()));

        assertThatThrownBy(() -> orderService.createOrder(42L, 7L, 3L, "order-key-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(orderProductPort, never()).deductProductStock(7L);
        verify(orderStore, never()).savePlacedOrder(any(OrderRecord.class));
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
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_RETURN_REQUESTED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(42L),
                        eq("USER"),
                        eq("ORD202606280010"),
                        isNull(),
                        eq("orderId=10 from=COMPLETED to=RETURN_REQUESTED"));
    }

    @Test
    void hideOrderMarksOwnedOrderHiddenWithoutPhysicalDeleteOrStockRollback() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderWithStatus(OrderStatus.COMPLETED)));

        orderService.hideOrderForUser(10L, 42L);

        verify(orderStore).hideFromUser(10L);
        verify(orderStore, never()).recordStockRestore(any(), any());
        verify(orderProductPort, never()).restoreProductStock(any());
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_HIDDEN),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(42L),
                        eq("USER"),
                        eq("ORD202606280010"),
                        isNull(),
                        eq("orderId=10 status=COMPLETED"));
    }

    @Test
    void approveReturnMovesOrderToWaitingForUserShipment() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_REQUESTED)));

        OrderResponseDto result = orderService.approveReturn(10L);

        assertThat(result.status()).isEqualTo(OrderStatus.WAITING_RETURN_SHIPMENT.label());
        verify(orderStore)
                .transitionStatus(
                        10L, OrderStatus.RETURN_REQUESTED.label(), OrderStatus.WAITING_RETURN_SHIPMENT.label(), null);
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_RETURN_APPROVED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        isNull(),
                        eq("ADMIN"),
                        eq("ORD202606280010"),
                        isNull(),
                        eq("orderId=10 from=RETURN_REQUESTED to=WAITING_RETURN_SHIPMENT"));
    }

    @Test
    void shipOrderAuditsAdminStatusTransition() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.PAID)));
        when(idGenerator.nextId()).thenReturn(100L, 200L, 201L);
        when(fulfillmentStore.findItem(10L, 7L)).thenReturn(Optional.empty());
        when(fulfillmentStore.saveItem(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fulfillmentStore.saveShipment(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fulfillmentStore.findItems(10L))
                .thenReturn(List.of(new OrderFulfillmentItem(100L, 10L, 7L, "Momo", 1, 1, 0, "SHIPPED")));

        OrderResponseDto result = orderService.shipOrder(10L);

        assertThat(result.status()).isEqualTo(OrderStatus.SHIPPED.label());
        verify(orderStore)
                .transitionStatus(eq(10L), eq(OrderStatus.PAID.label()), eq(OrderStatus.SHIPPED.label()), any());
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_SHIPPED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        isNull(),
                        eq("ADMIN"),
                        eq("ORD202606280010"),
                        isNull(),
                        eq("orderId=10 from=PAID to=SHIPPED"));
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
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_RETURN_SHIPPED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(42L),
                        eq("USER"),
                        eq("ORD202606280010"),
                        isNull(),
                        eq("orderId=10 from=WAITING_RETURN_SHIPMENT to=RETURN_SHIPPING"));
    }

    @Test
    void confirmReturnRestoresStockBySnapshotProductId() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_SHIPPING)));
        when(orderStore.recordStockRestore(10L, 7L)).thenReturn(true);
        when(orderProductPort.restoreProductStock(7L)).thenReturn(true);

        OrderResponseDto result = orderService.confirmReturn(10L);

        assertThat(result.status()).isEqualTo(OrderStatus.REFUNDED.label());
        verify(orderStore).recordStockRestore(10L, 7L);
        verify(orderProductPort).restoreProductStock(7L);
        verify(orderStore)
                .transitionStatus(10L, OrderStatus.RETURN_SHIPPING.label(), OrderStatus.REFUNDED.label(), null);
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_REFUNDED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        isNull(),
                        eq("ADMIN"),
                        eq("ORD202606280010"),
                        isNull(),
                        eq("orderId=10 from=RETURN_SHIPPING to=REFUNDED"));
    }

    @Test
    void confirmReturnSkipsDuplicateStockRestoreWhenLedgerAlreadyExists() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_SHIPPING)));
        when(orderStore.recordStockRestore(10L, 7L)).thenReturn(false);

        OrderResponseDto result = orderService.confirmReturn(10L);

        assertThat(result.status()).isEqualTo(OrderStatus.REFUNDED.label());
        verify(orderProductPort, never()).restoreProductStock(any());
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
        verify(orderProductPort, never()).restoreProductStock(any());
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

        verify(orderProductPort, never()).restoreProductStock(any());
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

    @Test
    void receiveOrderAuditsUserStatusTransition() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderWithStatus(OrderStatus.SHIPPED)));

        OrderResponseDto result = orderService.receiveOrder(10L, 42L);

        assertThat(result.status()).isEqualTo(OrderStatus.COMPLETED.label());
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_RECEIVED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(42L),
                        eq("USER"),
                        eq("ORD202606280010"),
                        isNull(),
                        eq("orderId=10 from=SHIPPED to=COMPLETED"));
    }

    private OrderRecord capturePlacedOrder() {
        ArgumentCaptor<OrderRecord> captor = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderStore).savePlacedOrder(captor.capture());
        return captor.getValue();
    }

    private OrderPageRequest captureVisibleOrderPageRequest() {
        ArgumentCaptor<OrderPageRequest> captor = ArgumentCaptor.forClass(OrderPageRequest.class);
        verify(orderStore).findVisibleByUser(eq(42L), captor.capture());
        return captor.getValue();
    }

    private OrderPageRequest captureAllOrderPageRequest() {
        ArgumentCaptor<OrderPageRequest> captor = ArgumentCaptor.forClass(OrderPageRequest.class);
        verify(orderStore).findAll(captor.capture());
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
