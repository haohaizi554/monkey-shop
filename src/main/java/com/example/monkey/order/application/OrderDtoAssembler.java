package com.example.monkey.order.application;

import com.example.monkey.order.application.dto.OrderResponseDto;
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
}
