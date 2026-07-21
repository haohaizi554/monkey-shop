package com.example.monkey.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.dto.OrderReviewRequestDto;
import com.example.monkey.order.application.dto.OrderShipmentLineRequestDto;
import com.example.monkey.order.application.dto.OrderShipmentRequestDto;
import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.order.domain.OrderCustomerPort;
import com.example.monkey.order.domain.OrderFulfillmentItem;
import com.example.monkey.order.domain.OrderFulfillmentStore;
import com.example.monkey.order.domain.OrderLockManager;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.order.domain.OrderProductPort;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.CheckoutOrderLineRecord;
import com.example.monkey.order.domain.OrderStore.OrderPage;
import com.example.monkey.order.domain.OrderStore.OrderPageRequest;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.order.infrastructure.SpringStateMachineOrderTransitionResolver;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class OrderResponseLinesTest {

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
    private TransactionOperations transactionOperations;

    @Mock
    private ImageReferenceService imageReferenceService;

    @Mock
    private BusinessMetricsService businessMetricsService;

    @Mock
    private AuditService auditService;

    @Mock
    private OrderFulfillmentStore fulfillmentStore;

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
                transactionOperations,
                imageReferenceService,
                businessMetricsService,
                auditService,
                fulfillmentStore,
                idGenerator,
                Duration.ofDays(7));
    }

    @Test
    void orderListsBatchLoadAndExposeEveryPersistedLine() {
        OrderRecord firstOrder = checkoutOrder(11L, "ORD-11", OrderStatus.PAID);
        OrderRecord secondOrder = checkoutOrder(12L, "ORD-12", OrderStatus.PAID);
        CheckoutOrderLineRecord firstLine = checkoutLine(101L, 501L, "Momo", 2);
        CheckoutOrderLineRecord secondLine = checkoutLine(102L, 502L, "Kiki", 3);
        CheckoutOrderLineRecord thirdLine = checkoutLine(103L, 503L, "Lulu", 1);
        when(orderStore.findAll(any(OrderPageRequest.class)))
                .thenReturn(new OrderPage(List.of(firstOrder, secondOrder), 0, 100, 2, 1, true, true));
        when(orderStore.findLinesByOrderIds(List.of(11L, 12L)))
                .thenReturn(Map.of(11L, List.of(firstLine, secondLine), 12L, List.of(thirdLine)));

        List<OrderResponseDto> responses = orderService.findAllOrders();

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).lines()).extracting(line -> line.skuId()).containsExactly(501L, 502L);
        assertThat(responses.get(0).lines()).extracting(line -> line.quantity()).containsExactly(2, 3);
        assertThat(responses.get(1).lines())
                .singleElement()
                .satisfies(line -> assertThat(line.skuId()).isEqualTo(503L));
        verify(orderStore).findLinesByOrderIds(List.of(11L, 12L));
        verify(orderStore, never()).findLines(any());
    }

    @Test
    void ownedOrderDetailLoadsItsPersistedLines() {
        OrderRecord order = checkoutOrder(11L, "ORD-11", OrderStatus.PAID);
        CheckoutOrderLineRecord firstLine = checkoutLine(101L, 501L, "Momo", 2);
        CheckoutOrderLineRecord secondLine = checkoutLine(102L, 502L, "Kiki", 3);
        when(orderStore.findVisibleByIdAndUserId(11L, 42L)).thenReturn(java.util.Optional.of(order));
        when(orderStore.findLines(11L)).thenReturn(List.of(firstLine, secondLine));

        OrderResponseDto response = orderService.findOrderForUser(11L, 42L);

        assertThat(response.lines()).extracting(line -> line.skuId()).containsExactly(501L, 502L);
    }

    @Test
    void checkoutShipmentUsesPersistedQuantityAndProductSnapshot() {
        OrderRecord order = checkoutOrder(11L, "ORD-11", OrderStatus.PAID);
        CheckoutOrderLineRecord persistedLine = checkoutLine(101L, 501L, "Momo", 2);
        when(orderStore.findById(11L)).thenReturn(java.util.Optional.of(order));
        when(orderStore.findLines(11L)).thenReturn(List.of(persistedLine));
        when(fulfillmentStore.findItem(11L, 501L)).thenReturn(java.util.Optional.empty());
        when(fulfillmentStore.saveItem(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fulfillmentStore.saveShipment(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fulfillmentStore.findItems(11L))
                .thenReturn(List.of(new OrderFulfillmentItem(900L, 11L, 501L, "Momo", 2, 1, 0, "PARTIALLY_SHIPPED")));
        when(orderStore.transitionStatus(any(), any(), any(), any())).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(900L, 901L, 902L);

        orderService.shipOrder(
                11L,
                new OrderShipmentRequestDto(
                        "SF", "SF100", List.of(new OrderShipmentLineRequestDto(501L, "forged-name", 1, 999))));

        ArgumentCaptor<OrderFulfillmentItem> itemCaptor = ArgumentCaptor.forClass(OrderFulfillmentItem.class);
        verify(fulfillmentStore, org.mockito.Mockito.atLeastOnce()).saveItem(itemCaptor.capture());
        OrderFulfillmentItem initialized = itemCaptor.getAllValues().get(0);
        assertThat(initialized.productName()).isEqualTo("Momo");
        assertThat(initialized.orderedQuantity()).isEqualTo(2);
    }

    @Test
    void reviewRejectsSkuThatDoesNotBelongToCheckoutOrder() {
        OrderRecord order = checkoutOrder(11L, "ORD-11", OrderStatus.COMPLETED);
        when(orderStore.findVisibleByIdAndUserId(11L, 42L)).thenReturn(java.util.Optional.of(order));
        when(orderStore.findLines(11L)).thenReturn(List.of(checkoutLine(101L, 501L, "Momo", 2)));

        assertThatThrownBy(() -> orderService.reviewOrder(
                        11L, 42L, new OrderReviewRequestDto(999L, 5, "great", List.of(), false)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static OrderRecord checkoutOrder(Long id, String orderNo, OrderStatus status) {
        return new OrderRecord(
                id,
                orderNo,
                42L,
                "buyer",
                "/images/avatar/buyer.png",
                501L,
                "Momo",
                "/images/product/momo.png",
                BigDecimal.valueOf(120),
                "calm",
                "Ada",
                "13800138000",
                "Hangzhou",
                null,
                status.label(),
                LocalDateTime.parse("2026-07-21T09:00:00"),
                false,
                700L,
                701L,
                88L,
                BigDecimal.valueOf(140),
                BigDecimal.valueOf(20),
                "checkout-key");
    }

    private static CheckoutOrderLineRecord checkoutLine(
            Long checkoutLineId, Long skuId, String productName, int quantity) {
        BigDecimal unitPrice = BigDecimal.valueOf(50);
        BigDecimal originalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return new CheckoutOrderLineRecord(
                checkoutLineId,
                skuId,
                88L,
                9L,
                productName,
                "/images/product/" + skuId + ".png",
                quantity,
                unitPrice,
                originalAmount,
                BigDecimal.ZERO,
                originalAmount,
                "PLATFORM-20",
                "cart:42:pay:" + checkoutLineId,
                3L);
    }
}
