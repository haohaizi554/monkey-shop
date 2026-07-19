package com.example.monkey.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderFulfillmentStore;
import com.example.monkey.order.domain.OrderShipmentBatch;
import com.example.monkey.order.domain.OrderShipmentLine;
import com.example.monkey.order.domain.OrderShipmentStatus;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
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

@ExtendWith(MockitoExtension.class)
class OrderAdminShipmentReadTest {

    @Mock
    private OrderStore orderStore;

    @Mock
    private OrderFulfillmentStore fulfillmentStore;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderStore,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                fulfillmentStore,
                null,
                Duration.ofDays(7));
    }

    @Test
    void readsShipmentBatchesWithoutApplyingCustomerOwnership() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(fulfillmentStore.findShipments(10L)).thenReturn(List.of(shipment()));

        var result = orderService.findShipmentsAsAdmin(10L);

        assertThat(result).singleElement().satisfies(response -> {
            assertThat(response.orderId()).isEqualTo(10L);
            assertThat(response.trackingNo()).isEqualTo("SF100");
            assertThat(response.lines()).singleElement().satisfies(line -> {
                assertThat(line.skuId()).isEqualTo(7L);
                assertThat(line.quantity()).isEqualTo(1);
            });
        });
    }

    @Test
    void rejectsMissingOrderBeforeReadingShipmentBatches() {
        when(orderStore.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findShipmentsAsAdmin(404L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(fulfillmentStore, never()).findShipments(404L);
    }

    private static OrderRecord order() {
        return new OrderRecord(
                10L,
                "ORD202607040001",
                42L,
                "buyer",
                null,
                7L,
                "Momo",
                null,
                new BigDecimal("199.99"),
                null,
                "Ada",
                "13800138000",
                "Hangzhou",
                null,
                OrderStatus.PAID.label(),
                LocalDateTime.parse("2026-07-04T07:00:00"),
                false);
    }

    private static OrderShipmentBatch shipment() {
        return new OrderShipmentBatch(
                200L,
                10L,
                "SHP200",
                "SF",
                "SF100",
                OrderShipmentStatus.SHIPPED,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                null,
                List.of(new OrderShipmentLine(201L, 200L, 10L, 7L, "Momo", 1)));
    }
}
