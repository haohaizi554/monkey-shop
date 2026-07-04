package com.example.monkey.order.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;

public record OrderFulfillmentItem(
        Long id,
        Long orderId,
        Long skuId,
        String productName,
        int orderedQuantity,
        int shippedQuantity,
        int receivedQuantity,
        String status) {

    public OrderFulfillmentItem {
        if (orderedQuantity <= 0 || shippedQuantity < 0 || receivedQuantity < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Fulfillment quantities must be positive");
        }
        if (shippedQuantity > orderedQuantity || receivedQuantity > shippedQuantity) {
            throw new BusinessException(ErrorCode.CONFLICT, "Fulfillment quantity invariant was violated");
        }
    }

    public int unshippedQuantity() {
        return orderedQuantity - shippedQuantity;
    }

    public int unreceivedQuantity() {
        return shippedQuantity - receivedQuantity;
    }

    public OrderFulfillmentItem ship(int quantity) {
        if (quantity <= 0 || quantity > unshippedQuantity()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Shipment quantity exceeds the unshipped quantity");
        }
        int nextShipped = shippedQuantity + quantity;
        return new OrderFulfillmentItem(
                id,
                orderId,
                skuId,
                productName,
                orderedQuantity,
                nextShipped,
                receivedQuantity,
                nextShipped == orderedQuantity ? "SHIPPED" : "PARTIALLY_SHIPPED");
    }

    public OrderFulfillmentItem receive(int quantity) {
        if (quantity <= 0 || quantity > unreceivedQuantity()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Receipt quantity exceeds the shipped quantity");
        }
        int nextReceived = receivedQuantity + quantity;
        return new OrderFulfillmentItem(
                id,
                orderId,
                skuId,
                productName,
                orderedQuantity,
                shippedQuantity,
                nextReceived,
                nextReceived == orderedQuantity ? "RECEIVED" : "PARTIALLY_RECEIVED");
    }
}
