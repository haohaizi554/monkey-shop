package com.example.monkey.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.application.dto.OrderReviewRequestDto;
import com.example.monkey.order.application.dto.OrderReviewResponseDto;
import com.example.monkey.order.application.dto.OrderShipmentLineRequestDto;
import com.example.monkey.order.application.dto.OrderShipmentRequestDto;
import com.example.monkey.order.application.dto.OrderShipmentResponseDto;
import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.order.domain.OrderCustomerPort;
import com.example.monkey.order.domain.OrderFulfillmentItem;
import com.example.monkey.order.domain.OrderFulfillmentStore;
import com.example.monkey.order.domain.OrderLockManager;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.order.domain.OrderProductPort;
import com.example.monkey.order.domain.OrderShipmentBatch;
import com.example.monkey.order.domain.OrderShipmentLine;
import com.example.monkey.order.domain.OrderShipmentStatus;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.order.infrastructure.SpringStateMachineOrderTransitionResolver;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class OrderFulfillmentApplicationTest {

    @Mock
    private OrderStore orderStore;

    @Mock
    private OrderProductPort orderProductPort;

    @Mock
    private OrderCustomerPort orderCustomerPort;

    @Mock
    private OrderFulfillmentStore fulfillmentStore;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    @Mock
    private OrderIdempotencyService orderIdempotencyService;

    @Mock
    private OrderLockManager orderLockManager;

    @Mock
    private ImageReferenceService imageReferenceService;

    @Mock
    private BusinessMetricsService businessMetricsService;

    @Mock
    private AuditService auditService;

    @Mock
    private IdGenerator idGenerator;

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
                Duration.ofDays(7));
        lenient().when(orderStore.transitionStatus(any(), any(), any(), any())).thenReturn(1);
        lenient().when(fulfillmentStore.saveItem(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(fulfillmentStore.saveShipment(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient()
                .when(fulfillmentStore.markShipmentReceived(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void partialShipmentCreatesBatchAndMovesOrderToPartiallyShipped() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(OrderStatus.PAID)));
        when(idGenerator.nextId()).thenReturn(100L, 200L, 201L);
        when(fulfillmentStore.findItem(10L, 7L)).thenReturn(Optional.empty());
        when(fulfillmentStore.findItems(10L))
                .thenReturn(List.of(new OrderFulfillmentItem(100L, 10L, 7L, "Momo", 2, 1, 0, "PARTIALLY_SHIPPED")));

        OrderShipmentResponseDto response = orderService.shipOrder(
                10L,
                new OrderShipmentRequestDto("SF", "SF100", List.of(new OrderShipmentLineRequestDto(7L, "Momo", 1, 2))));

        assertThat(response.id()).isEqualTo(200L);
        assertThat(response.lines()).singleElement().satisfies(line -> {
            assertThat(line.skuId()).isEqualTo(7L);
            assertThat(line.quantity()).isEqualTo(1);
        });
        verify(orderStore).transitionStatus(10L, OrderStatus.PAID.label(), OrderStatus.PARTIALLY_SHIPPED.label(), null);
    }

    @Test
    void receivingFinalShipmentCompletesOrder() {
        OrderShipmentBatch shipment = shipment(OrderShipmentStatus.SHIPPED, null);
        when(fulfillmentStore.findShipment(200L)).thenReturn(Optional.of(shipment));
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
        when(fulfillmentStore.findItem(10L, 7L))
                .thenReturn(Optional.of(new OrderFulfillmentItem(100L, 10L, 7L, "Momo", 1, 1, 0, "SHIPPED")));
        when(fulfillmentStore.findItems(10L))
                .thenReturn(List.of(new OrderFulfillmentItem(100L, 10L, 7L, "Momo", 1, 1, 1, "RECEIVED")));

        OrderShipmentResponseDto response = orderService.receiveShipment(200L, 42L);

        assertThat(response.status()).isEqualTo("RECEIVED");
        verify(orderStore).transitionStatus(10L, OrderStatus.SHIPPED.label(), OrderStatus.COMPLETED.label(), null);
    }

    @Test
    void completedOrderCanBeReviewedOnceAndRetainsImages() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.of(order(OrderStatus.COMPLETED)));
        when(fulfillmentStore.hasReview(10L, 42L, 7L)).thenReturn(false);
        when(idGenerator.nextId()).thenReturn(300L);
        when(fulfillmentStore.saveReview(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OrderReviewResponseDto response = orderService.reviewOrder(
                10L, 42L, new OrderReviewRequestDto(7L, 5, "fast delivery", List.of("/images/review/1.png"), true));

        assertThat(response.id()).isEqualTo(300L);
        assertThat(response.rating()).isEqualTo(5);
        verify(imageReferenceService).retain("/images/review/1.png");
        verify(auditService)
                .record(
                        eq(AuditService.ORDER_REVIEWED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(42L),
                        eq("USER"),
                        eq("ORD202607040001"),
                        isNull(),
                        eq("orderId=10,skuId=7,rating=5"));
    }

    @Test
    void autoReceiveOverdueShipmentsUsesSameReceiptTransition() {
        when(fulfillmentStore.findReceivableShipments(any(), eq(100)))
                .thenReturn(List.of(shipment(OrderShipmentStatus.SHIPPED, null)));
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
        when(fulfillmentStore.findItem(10L, 7L))
                .thenReturn(Optional.of(new OrderFulfillmentItem(100L, 10L, 7L, "Momo", 1, 1, 0, "SHIPPED")));
        when(fulfillmentStore.findItems(10L))
                .thenReturn(List.of(new OrderFulfillmentItem(100L, 10L, 7L, "Momo", 1, 1, 1, "RECEIVED")));

        assertThat(orderService.autoReceiveOverdueShipments()).isEqualTo(1);

        verify(orderStore).transitionStatus(10L, OrderStatus.SHIPPED.label(), OrderStatus.COMPLETED.label(), null);
    }

    private static TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private static OrderRecord order(OrderStatus status) {
        return new OrderRecord(
                10L,
                "ORD202607040001",
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
                status.label(),
                LocalDateTime.parse("2026-07-04T07:00:00"),
                false);
    }

    private static OrderShipmentBatch shipment(OrderShipmentStatus status, LocalDateTime receivedAt) {
        return new OrderShipmentBatch(
                200L,
                10L,
                "SHP200",
                "SF",
                "SF100",
                status,
                LocalDateTime.parse("2026-06-26T07:00:00"),
                receivedAt,
                List.of(new OrderShipmentLine(201L, 200L, 10L, 7L, "Momo", 1)));
    }
}
