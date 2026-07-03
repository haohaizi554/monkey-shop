package com.example.monkey.cart.application;

import com.example.monkey.cart.application.dto.CartCheckoutLineResponseDto;
import com.example.monkey.cart.application.dto.CartCheckoutResponseDto;
import com.example.monkey.cart.application.dto.CartItemResponseDto;
import com.example.monkey.cart.application.dto.CartResponseDto;
import com.example.monkey.cart.application.dto.CartSubOrderResponseDto;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CheckoutLine;
import com.example.monkey.cart.domain.CheckoutOrder;
import com.example.monkey.cart.domain.CheckoutSubOrder;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class CartDtoAssembler {

    private CartDtoAssembler() {}

    static CartResponseDto toResponse(CartSnapshot cart, Map<Long, CartSkuSnapshot> skuSnapshots) {
        List<CartItemResponseDto> items = cart.items().stream()
                .map(item -> toItemResponse(item, skuSnapshots.get(item.skuId())))
                .toList();
        int selectedQuantity =
                cart.selectedItems().stream().mapToInt(CartItem::quantity).sum();
        BigDecimal selectedAmount = items.stream()
                .filter(CartItemResponseDto::selected)
                .map(CartItemResponseDto::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponseDto(cart.userId(), items, selectedQuantity, selectedAmount);
    }

    static CartCheckoutResponseDto toResponse(CheckoutOrder checkout) {
        return new CartCheckoutResponseDto(
                checkout.id(),
                checkout.checkoutNo(),
                checkout.userId(),
                checkout.addressId(),
                checkout.originalAmount(),
                checkout.discountAmount(),
                checkout.payableAmount(),
                checkout.status(),
                checkout.province(),
                checkout.createdAt(),
                checkout.subOrders().stream()
                        .map(CartDtoAssembler::toSubOrderResponse)
                        .toList());
    }

    private static CartSubOrderResponseDto toSubOrderResponse(CheckoutSubOrder subOrder) {
        return new CartSubOrderResponseDto(
                subOrder.id(),
                subOrder.shopId(),
                subOrder.orderNo(),
                subOrder.originalAmount(),
                subOrder.discountAmount(),
                subOrder.payableAmount(),
                subOrder.status(),
                subOrder.lines().stream().map(CartDtoAssembler::toLineResponse).toList());
    }

    private static CartCheckoutLineResponseDto toLineResponse(CheckoutLine line) {
        return new CartCheckoutLineResponseDto(
                line.id(),
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
                line.couponCodes(),
                line.reservationKey(),
                line.warehouseId());
    }

    private static CartItemResponseDto toItemResponse(CartItem item, CartSkuSnapshot sku) {
        BigDecimal unitPrice = sku == null ? BigDecimal.ZERO : sku.salePrice();
        return new CartItemResponseDto(
                item.skuId(),
                item.shopId(),
                sku == null ? "SKU " + item.skuId() : sku.productName(),
                sku == null ? null : sku.productImage(),
                unitPrice,
                item.quantity(),
                item.selected(),
                unitPrice.multiply(BigDecimal.valueOf(item.quantity())),
                item.updatedAt());
    }
}
