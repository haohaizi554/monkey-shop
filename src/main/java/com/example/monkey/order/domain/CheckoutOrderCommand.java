package com.example.monkey.order.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record CheckoutOrderCommand(
        Long checkoutId, Long userId, Long addressId, String idempotencyKey, List<SubOrder> subOrders) {

    public CheckoutOrderCommand {
        subOrders = subOrders == null ? List.of() : List.copyOf(subOrders);
        requirePositive(checkoutId, "Checkout ID is required");
        requirePositive(userId, "User ID is required");
        requirePositive(addressId, "Address ID is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw validation("Idempotency key is required");
        }
        if (subOrders.isEmpty()) {
            throw validation("Checkout must contain at least one suborder");
        }
        Set<Long> subOrderIds = new HashSet<>();
        Set<Long> shopIds = new HashSet<>();
        for (SubOrder subOrder : subOrders) {
            if (!subOrderIds.add(subOrder.checkoutSubOrderId())) {
                throw validation("Checkout contains a duplicate suborder");
            }
            if (!shopIds.add(subOrder.shopId())) {
                throw validation("Checkout contains a duplicate shop");
            }
        }
    }

    public BigDecimal totalPayable() {
        return subOrders.stream().map(SubOrder::payableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public record SubOrder(
            Long checkoutSubOrderId,
            Long shopId,
            String orderNo,
            BigDecimal originalAmount,
            BigDecimal storeDiscountAmount,
            BigDecimal platformDiscountAmount,
            BigDecimal payableAmount,
            List<Line> lines) {

        public SubOrder {
            lines = lines == null ? List.of() : List.copyOf(lines);
            requirePositive(checkoutSubOrderId, "Checkout suborder ID is required");
            requirePositive(shopId, "Shop ID is required");
            if (orderNo == null || orderNo.isBlank()) {
                throw validation("Order number is required");
            }
            requireMoney(originalAmount, "Original amount");
            requireMoney(storeDiscountAmount, "Store discount amount");
            requireMoney(platformDiscountAmount, "Platform discount amount");
            requireMoney(payableAmount, "Payable amount");
            if (lines.isEmpty()) {
                throw validation("Checkout suborder must contain at least one line");
            }
            BigDecimal lineOriginal = sum(lines, Line::originalAmount);
            BigDecimal lineDiscount = sum(lines, Line::discountAmount);
            BigDecimal linePayable = sum(lines, Line::payableAmount);
            BigDecimal allocatedDiscount = storeDiscountAmount.add(platformDiscountAmount);
            if (lineOriginal.compareTo(originalAmount) != 0
                    || lineDiscount.compareTo(allocatedDiscount) != 0
                    || linePayable.compareTo(payableAmount) != 0
                    || originalAmount.subtract(allocatedDiscount).compareTo(payableAmount) != 0) {
                throw validation("Checkout suborder amounts are inconsistent");
            }
        }
    }

    public record Line(
            Long checkoutLineId,
            Long skuId,
            Long shopId,
            Long categoryId,
            String productName,
            String productImage,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            BigDecimal payableAmount,
            List<String> couponCodes,
            String reservationKey,
            Long warehouseId) {

        public Line {
            couponCodes = couponCodes == null ? List.of() : List.copyOf(couponCodes);
            requirePositive(checkoutLineId, "Checkout line ID is required");
            requirePositive(skuId, "SKU ID is required");
            requirePositive(shopId, "Shop ID is required");
            if (productName == null || productName.isBlank()) {
                throw validation("Product name is required");
            }
            if (quantity <= 0) {
                throw validation("Quantity must be positive");
            }
            requireMoney(unitPrice, "Unit price");
            requireMoney(originalAmount, "Original amount");
            requireMoney(discountAmount, "Discount amount");
            requireMoney(payableAmount, "Payable amount");
            if (originalAmount.subtract(discountAmount).compareTo(payableAmount) != 0) {
                throw validation("Checkout line amounts are inconsistent");
            }
            if (reservationKey == null || reservationKey.isBlank()) {
                throw validation("Inventory reservation key is required");
            }
        }
    }

    private static <T> BigDecimal sum(List<T> values, java.util.function.Function<T, BigDecimal> amount) {
        return values.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void requirePositive(Long value, String message) {
        if (value == null || value <= 0) {
            throw validation(message);
        }
    }

    private static void requireMoney(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw validation(field + " must not be negative");
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
