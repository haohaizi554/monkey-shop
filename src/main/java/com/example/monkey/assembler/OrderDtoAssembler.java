package com.example.monkey.assembler;

import com.example.monkey.domain.order.OrderStore.OrderRecord;
import com.example.monkey.dto.OrderResponseDto;
import com.example.monkey.entity.Order;

public final class OrderDtoAssembler {

    private OrderDtoAssembler() {}

    public static OrderResponseDto toResponse(Order order) {
        return new OrderResponseDto(
                order.getId(),
                order.getOrderNo(),
                order.getUserId(),
                order.getBuyerName(),
                order.getBuyerAvatar(),
                order.getProductId(),
                order.getProductName(),
                order.getProductImage(),
                order.getPrice(),
                order.getDescription(),
                order.getReceiverName(),
                order.getReceiverPhone(),
                order.getAddressSnapshot(),
                order.getShippingTime(),
                order.getStatus(),
                order.getCreateTime());
    }

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
