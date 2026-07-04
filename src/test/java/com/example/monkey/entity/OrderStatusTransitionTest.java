package com.example.monkey.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import com.example.monkey.order.domain.OrderStore.ProductRecord;
import com.example.monkey.order.infrastructure.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class OrderStatusTransitionTest {

    @Test
    void placeSnapshotsBuyerProductAddressAndMarksPaid() {
        BuyerRecord buyer = new BuyerRecord(42L, "buyer", "/images/avatar/buyer.png");
        ProductRecord product =
                new ProductRecord(7L, "Momo", "/images/product/momo.png", new BigDecimal("199.99"), "calm", 5);
        AddressRecord address = new AddressRecord(3L, 42L, "Ada", "13800000000", "Hangzhou");

        Order order = Order.place("ORD-1", buyer, product, address);

        assertThat(order.getOrderNo()).isEqualTo("ORD-1");
        assertThat(order.getUserId()).isEqualTo(42L);
        assertThat(order.getBuyerName()).isEqualTo("buyer");
        assertThat(order.getBuyerAvatar()).isEqualTo("/images/avatar/buyer.png");
        assertThat(order.getProductId()).isEqualTo(7L);
        assertThat(order.getProductName()).isEqualTo("Momo");
        assertThat(order.getProductImage()).isEqualTo("/images/product/momo.png");
        assertThat(order.getPrice()).isEqualByComparingTo("199.99");
        assertThat(order.getDescription()).isEqualTo("calm");
        assertThat(order.getReceiverName()).isEqualTo("Ada");
        assertThat(order.getReceiverPhone()).isEqualTo("13800000000");
        assertThat(order.getAddressSnapshot()).isEqualTo("Hangzhou");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID.label());
    }

    @Test
    void paidOrderCanBeShippedWithTimestamp() {
        Order order = orderWithStatus(OrderStatus.PAID);
        LocalDateTime shippedAt = LocalDateTime.of(2026, 6, 28, 15, 30);

        order.ship(shippedAt);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED.label());
        assertThat(order.getShippingTime()).isEqualTo(shippedAt);
    }

    @Test
    void paidOrderCannotBeReceivedBeforeShipment() {
        Order order = orderWithStatus(OrderStatus.PAID);

        assertThatIllegalStateException().isThrownBy(order::receive).withMessage(Order.STATUS_TRANSITION_NOT_ALLOWED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID.label());
    }

    @Test
    void completedOrderMovesThroughReturnFlowToRefunded() {
        Order order = orderWithStatus(OrderStatus.COMPLETED);

        order.requestReturn();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED.label());
        order.approveReturn();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.WAITING_RETURN_SHIPMENT.label());
        order.shipReturn();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_SHIPPING.label());
        order.refund();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED.label());
    }

    @Test
    void refundRequiresReturnShipmentState() {
        Order order = orderWithStatus(OrderStatus.WAITING_RETURN_SHIPMENT);

        assertThatIllegalStateException().isThrownBy(order::refund).withMessage(Order.STATUS_TRANSITION_NOT_ALLOWED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.WAITING_RETURN_SHIPMENT.label());
    }

    private static Order orderWithStatus(OrderStatus status) {
        Order order = new Order();
        order.markStatus(status);
        return order;
    }
}
