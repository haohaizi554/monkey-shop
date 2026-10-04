package com.example.monkey.order.infrastructure;

import com.example.monkey.logistics.domain.OrderFulfillmentPort;
import com.example.monkey.order.domain.OrderEvent;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.order.domain.OrderTransitionPolicy;
import com.example.monkey.order.domain.OrderTransitionResolver;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapts carrier lifecycle callbacks to the canonical order state machine.
 *
 * <p>The legacy logistics table remains the carrier projection. This adapter deliberately does not create a
 * second {@code order_shipment_batch}; it only applies the order transition through the existing resolver and
 * optimistic compare-and-set store operation.
 */
@Component
public class LogisticsOrderFulfillmentAdapter implements OrderFulfillmentPort {

    private static final String MISSING_ORDER = "Order does not exist";

    private final OrderStore orderStore;
    private final OrderTransitionResolver transitionResolver;

    public LogisticsOrderFulfillmentAdapter(OrderStore orderStore, OrderTransitionResolver transitionResolver) {
        this.orderStore = orderStore;
        this.transitionResolver = transitionResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireShippable(long tenantId, long orderId) {
        requireTenantAndId(tenantId, orderId, 0L);
        requireShippableStatus(statusOf(requireOrder(orderId)));
    }

    @Override
    @Transactional
    public void markShipmentCreated(long tenantId, long orderId, long shipmentId) {
        requireTenantAndId(tenantId, orderId, shipmentId);
        OrderRecord order = requireOrder(orderId);
        OrderStatus currentStatus = statusOf(order);
        // SHIPPED is not a successful create replay. The durable logistics claim handles retries; accepting
        // this status here would let a second idempotency key bypass the one-shipment invariant.
        requireShippableStatus(currentStatus);
        transition(order, currentStatus, OrderEvent.SHIP);
    }

    @Override
    @Transactional
    public void markDelivered(long tenantId, long orderId, long shipmentId, Instant deliveredAt) {
        requireTenantAndId(tenantId, orderId, shipmentId);
        if (deliveredAt == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Delivery time is required");
        }
        OrderRecord order = requireOrder(orderId);
        OrderStatus currentStatus = statusOf(order);
        if (OrderStatus.COMPLETED.equals(currentStatus)) {
            return;
        }
        if (!OrderStatus.SHIPPED.equals(currentStatus)
                && !OrderStatus.PARTIALLY_SHIPPED.equals(currentStatus)
                && !OrderStatus.PARTIALLY_RECEIVED.equals(currentStatus)) {
            throw transitionNotAllowed();
        }
        OrderEvent event =
                OrderStatus.PARTIALLY_SHIPPED.equals(currentStatus) ? OrderEvent.RECEIVE_PARTIAL : OrderEvent.RECEIVE;
        transition(order, currentStatus, event);
    }

    private void transition(OrderRecord order, OrderStatus currentStatus, OrderEvent event) {
        OrderStatus nextStatus = transitionResolver.nextStatus(currentStatus, event);
        if (nextStatus == null) {
            throw transitionNotAllowed();
        }
        int rows = orderStore.transitionStatus(order.id(), currentStatus.label(), nextStatus.label(), null);
        if (rows == 0) {
            throw transitionNotAllowed();
        }
    }

    private OrderRecord requireOrder(long orderId) {
        return orderStore
                .findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, MISSING_ORDER));
    }

    private static void requireShippableStatus(OrderStatus status) {
        if (!OrderStatus.PAID.equals(status) && !OrderStatus.PARTIALLY_SHIPPED.equals(status)) {
            throw transitionNotAllowed();
        }
    }

    private static OrderStatus statusOf(OrderRecord order) {
        try {
            return OrderStatus.fromStoredValue(order.status());
        } catch (IllegalArgumentException exception) {
            throw transitionNotAllowed();
        }
    }

    private static void requireTenantAndId(long tenantId, long orderId, long shipmentId) {
        if (tenantId <= 0 || orderId <= 0 || shipmentId < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Fulfillment identifiers are invalid");
        }
    }

    private static BusinessException transitionNotAllowed() {
        return new BusinessException(ErrorCode.CONFLICT, OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
    }
}
