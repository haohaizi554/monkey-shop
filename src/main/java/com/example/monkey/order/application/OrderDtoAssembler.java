package com.example.monkey.order.application;

import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.dto.OrderReviewResponseDto;
import com.example.monkey.order.application.dto.OrderShipmentLineResponseDto;
import com.example.monkey.order.application.dto.OrderShipmentResponseDto;
import com.example.monkey.order.domain.OrderReview;
import com.example.monkey.order.domain.OrderShipmentBatch;
import com.example.monkey.order.domain.OrderStore.OrderRecord;

public final class OrderDtoAssembler {

    private OrderDtoAssembler() {}

    public static OrderResponseDto toResponse(OrderRecord order) {
        return new OrderResponseDto(
                order.id(),
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
                order.createTime());
    }

    public static OrderShipmentResponseDto toResponse(OrderShipmentBatch shipment) {
        return new OrderShipmentResponseDto(
                shipment.id(),
                shipment.orderId(),
                shipment.shipmentNo(),
                shipment.carrier(),
                shipment.trackingNo(),
                shipment.status().name(),
                shipment.shippedAt(),
                shipment.receivedAt(),
                shipment.lines().stream()
                        .map(line ->
                                new OrderShipmentLineResponseDto(line.skuId(), line.productName(), line.quantity()))
                        .toList());
    }

    public static OrderReviewResponseDto toResponse(OrderReview review) {
        return new OrderReviewResponseDto(
                review.id(),
                review.orderId(),
                review.userId(),
                review.skuId(),
                review.rating(),
                review.content(),
                review.imageUrls(),
                review.anonymous(),
                review.createTime());
    }
}
