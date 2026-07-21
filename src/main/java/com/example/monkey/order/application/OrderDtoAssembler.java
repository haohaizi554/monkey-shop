package com.example.monkey.order.application;

import com.example.monkey.order.application.dto.OrderLineResponseDto;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.dto.OrderReviewResponseDto;
import com.example.monkey.order.application.dto.OrderShipmentLineResponseDto;
import com.example.monkey.order.application.dto.OrderShipmentResponseDto;
import com.example.monkey.order.domain.OrderReview;
import com.example.monkey.order.domain.OrderShipmentBatch;
import com.example.monkey.order.domain.OrderStore.CheckoutOrderLineRecord;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public final class OrderDtoAssembler {

    private OrderDtoAssembler() {}

    public static OrderResponseDto toResponse(OrderRecord order) {
        return toResponse(order, List.of());
    }

    public static OrderResponseDto toResponse(OrderRecord order, List<CheckoutOrderLineRecord> checkoutLines) {
        List<OrderLineResponseDto> lines = toLineResponses(order, checkoutLines);
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
                order.createTime(),
                order.checkoutId(),
                order.checkoutSubOrderId(),
                order.shopId(),
                order.originalAmount(),
                order.discountAmount(),
                order.checkoutIdempotencyKey(),
                lines);
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

    private static List<OrderLineResponseDto> toLineResponses(
            OrderRecord order, List<CheckoutOrderLineRecord> checkoutLines) {
        if (checkoutLines != null && !checkoutLines.isEmpty()) {
            return checkoutLines.stream().map(OrderDtoAssembler::toLineResponse).toList();
        }
        if (order.checkoutId() != null || order.productId() == null) {
            return List.of();
        }
        BigDecimal payable = defaultAmount(order.price());
        return List.of(new OrderLineResponseDto(
                null,
                order.productId(),
                order.shopId(),
                null,
                order.productName(),
                order.productImage(),
                1,
                payable,
                order.originalAmount() == null ? payable : order.originalAmount(),
                defaultAmount(order.discountAmount()),
                payable,
                List.of()));
    }

    private static OrderLineResponseDto toLineResponse(CheckoutOrderLineRecord line) {
        return new OrderLineResponseDto(
                line.checkoutLineId(),
                line.skuId(),
                line.shopId(),
                line.categoryId(),
                line.productName(),
                line.productImage(),
                line.quantity(),
                line.unitPrice(),
                line.originalAmount(),
                line.discountAmount(),
                line.payableAmount(),
                couponCodes(line.couponCodes()));
    }

    private static List<String> couponCodes(String couponCodes) {
        if (couponCodes == null || couponCodes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(couponCodes.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
    }

    private static BigDecimal defaultAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
