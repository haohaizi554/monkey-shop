package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderEvent;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.order.domain.OrderTransitionResolver;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LogisticsOrderFulfillmentAdapterTest {

    @Mock
    private OrderStore orderStore;

    @Mock
    private OrderTransitionResolver transitionResolver;

    private LogisticsOrderFulfillmentAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LogisticsOrderFulfillmentAdapter(orderStore, transitionResolver);
    }

    @Test
    void requireShippableRejectsOrdersThatAreNotPaidOrPartiallyShipped() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(OrderStatus.PENDING_PAYMENT)));

        assertThatThrownBy(() -> adapter.requireShippable(1L, 10L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(orderStore, never()).transitionStatus(anyLong(), any(), any(), any());
    }

    @Test
    void shipmentCreationUsesCanonicalResolverAndCompareAndSetTransition() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(OrderStatus.PAID)));
        when(transitionResolver.nextStatus(OrderStatus.PAID, OrderEvent.SHIP)).thenReturn(OrderStatus.SHIPPED);
        when(orderStore.transitionStatus(10L, OrderStatus.PAID.label(), OrderStatus.SHIPPED.label(), null))
                .thenReturn(1);

        adapter.requireShippable(1L, 10L);
        adapter.markShipmentCreated(1L, 10L, 7000L);

        verify(transitionResolver).nextStatus(OrderStatus.PAID, OrderEvent.SHIP);
        verify(orderStore).transitionStatus(10L, OrderStatus.PAID.label(), OrderStatus.SHIPPED.label(), null);
    }

    @Test
    void shipmentCreationRejectsAlreadyShippedOrderInsteadOfTreatingItAsReplaySuccess() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));

        assertThatThrownBy(() -> adapter.markShipmentCreated(1L, 10L, 7001L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(transitionResolver, never()).nextStatus(any(), any());
        verify(orderStore, never()).transitionStatus(anyLong(), any(), any(), any());
    }

    @Test
    void signedShipmentCompletesShippedOrderThroughCanonicalResolver() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(OrderStatus.SHIPPED)));
        when(transitionResolver.nextStatus(OrderStatus.SHIPPED, OrderEvent.RECEIVE)).thenReturn(OrderStatus.COMPLETED);
        when(orderStore.transitionStatus(10L, OrderStatus.SHIPPED.label(), OrderStatus.COMPLETED.label(), null))
                .thenReturn(1);

        adapter.markDelivered(1L, 10L, 7000L, Instant.parse("2026-07-04T12:00:00Z"));

        verify(transitionResolver).nextStatus(OrderStatus.SHIPPED, OrderEvent.RECEIVE);
        verify(orderStore).transitionStatus(10L, OrderStatus.SHIPPED.label(), OrderStatus.COMPLETED.label(), null);
    }

    @Test
    void signedPartialShipmentUsesCanonicalPartialReceiptTransition() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(OrderStatus.PARTIALLY_SHIPPED)));
        when(transitionResolver.nextStatus(OrderStatus.PARTIALLY_SHIPPED, OrderEvent.RECEIVE_PARTIAL))
                .thenReturn(OrderStatus.PARTIALLY_RECEIVED);
        when(orderStore.transitionStatus(
                        10L,
                        OrderStatus.PARTIALLY_SHIPPED.label(),
                        OrderStatus.PARTIALLY_RECEIVED.label(),
                        null))
                .thenReturn(1);

        adapter.markDelivered(1L, 10L, 7000L, Instant.parse("2026-07-04T12:00:00Z"));

        verify(transitionResolver).nextStatus(OrderStatus.PARTIALLY_SHIPPED, OrderEvent.RECEIVE_PARTIAL);
        verify(orderStore).transitionStatus(
                10L,
                OrderStatus.PARTIALLY_SHIPPED.label(),
                OrderStatus.PARTIALLY_RECEIVED.label(),
                null);
    }

    @Test
    void signedWebhookCannotReceiveAnOrderThatHasNotBeenMarkedShipped() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(OrderStatus.PAID)));

        assertThatThrownBy(() -> adapter.markDelivered(1L, 10L, 7000L, Instant.parse("2026-07-04T12:00:00Z")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(orderStore, never()).transitionStatus(anyLong(), any(), any(), any());
    }

    @Test
    void completedOrderDeliveryIsIdempotentWithoutASecondTransition() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(OrderStatus.COMPLETED)));

        adapter.markDelivered(1L, 10L, 7000L, Instant.parse("2026-07-04T12:00:00Z"));

        verify(transitionResolver, never()).nextStatus(any(), any());
        verify(orderStore, never()).transitionStatus(anyLong(), any(), any(), any());
    }

    private static OrderRecord order(OrderStatus status) {
        return new OrderRecord(
                10L,
                "ORD202607040001",
                42L,
                "buyer",
                null,
                7L,
                "Momo",
                null,
                new BigDecimal("100.00"),
                "calm",
                "Ada",
                "13800138000",
                "Zhejiang Hangzhou Xihu Wenyi Road 100",
                null,
                status.label(),
                LocalDateTime.parse("2026-07-04T08:00:00"),
                false);
    }
}
