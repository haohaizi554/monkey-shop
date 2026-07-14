package com.example.monkey.order.application;

import com.example.monkey.order.domain.CheckoutOrderCommand;
import com.example.monkey.order.domain.CheckoutOrderCommand.Line;
import com.example.monkey.order.domain.CheckoutOrderCommand.SubOrder;
import com.example.monkey.order.domain.OrderCustomerPort;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import com.example.monkey.order.domain.OrderStore.CheckoutOrderLineRecord;
import com.example.monkey.order.domain.OrderStore.CheckoutOrderRecord;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutOrderApplicationService {

    private final OrderStore orderStore;
    private final OrderCustomerPort customerPort;

    public CheckoutOrderApplicationService(OrderStore orderStore, OrderCustomerPort customerPort) {
        this.orderStore = orderStore;
        this.customerPort = customerPort;
    }

    @Transactional
    public List<Long> create(CheckoutOrderCommand command) {
        List<OrderRecord> existing = orderStore.findByCheckoutId(command.checkoutId());
        if (!existing.isEmpty()) {
            return existingOrderIds(command, existing);
        }

        BuyerRecord buyer = customerPort
                .findBuyerById(command.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User does not exist"));
        AddressRecord address = customerPort
                .findAddressById(command.addressId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Address does not exist"));
        requireCustomerOwnership(command.userId(), buyer, address);

        List<CheckoutOrderRecord> orders = command.subOrders().stream()
                .map(subOrder -> toOrder(command, subOrder, buyer, address))
                .toList();
        List<OrderRecord> saved = orderStore.saveCheckoutOrders(orders);
        if (saved == null || saved.size() != orders.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Checkout orders were not fully persisted");
        }
        return existingOrderIds(command, saved);
    }

    private static CheckoutOrderRecord toOrder(
            CheckoutOrderCommand command, SubOrder subOrder, BuyerRecord buyer, AddressRecord address) {
        Line primaryLine = subOrder.lines().get(0);
        BigDecimal discountAmount = subOrder.storeDiscountAmount().add(subOrder.platformDiscountAmount());
        OrderRecord order = new OrderRecord(
                null,
                subOrder.orderNo(),
                command.userId(),
                buyer.username(),
                buyer.avatar(),
                primaryLine.skuId(),
                primaryLine.productName(),
                primaryLine.productImage(),
                subOrder.payableAmount(),
                null,
                address.receiverName(),
                address.phone(),
                address.detailAddress(),
                null,
                OrderStatus.PENDING_PAYMENT.label(),
                null,
                false,
                command.checkoutId(),
                subOrder.checkoutSubOrderId(),
                subOrder.shopId(),
                subOrder.originalAmount(),
                discountAmount,
                command.idempotencyKey());
        List<CheckoutOrderLineRecord> lines = subOrder.lines().stream()
                .map(CheckoutOrderApplicationService::toLine)
                .toList();
        return new CheckoutOrderRecord(order, lines);
    }

    private static CheckoutOrderLineRecord toLine(Line line) {
        return new CheckoutOrderLineRecord(
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
                String.join(",", line.couponCodes()),
                line.reservationKey(),
                line.warehouseId());
    }

    private static List<Long> existingOrderIds(CheckoutOrderCommand command, List<OrderRecord> existing) {
        Map<Long, OrderRecord> bySubOrder = new HashMap<>();
        for (OrderRecord order : existing) {
            if (!Objects.equals(order.checkoutId(), command.checkoutId())
                    || !Objects.equals(order.userId(), command.userId())
                    || order.checkoutSubOrderId() == null
                    || bySubOrder.put(order.checkoutSubOrderId(), order) != null) {
                throw idempotencyConflict();
            }
        }
        if (bySubOrder.size() != command.subOrders().size()) {
            throw idempotencyConflict();
        }
        List<OrderRecord> ordered = new java.util.ArrayList<>();
        for (SubOrder subOrder : command.subOrders()) {
            OrderRecord order = bySubOrder.get(subOrder.checkoutSubOrderId());
            BigDecimal expectedDiscount = subOrder.storeDiscountAmount().add(subOrder.platformDiscountAmount());
            if (order == null
                    || order.id() == null
                    || !Objects.equals(order.orderNo(), subOrder.orderNo())
                    || !Objects.equals(order.shopId(), subOrder.shopId())
                    || !Objects.equals(order.checkoutIdempotencyKey(), command.idempotencyKey())
                    || compare(order.originalAmount(), subOrder.originalAmount()) != 0
                    || compare(order.discountAmount(), expectedDiscount) != 0
                    || compare(order.price(), subOrder.payableAmount()) != 0) {
                throw idempotencyConflict();
            }
            ordered.add(order);
        }
        return ordered.stream().map(OrderRecord::id).toList();
    }

    private static int compare(BigDecimal actual, BigDecimal expected) {
        return actual == null ? -1 : actual.compareTo(expected);
    }

    private static void requireCustomerOwnership(Long userId, BuyerRecord buyer, AddressRecord address) {
        if (!Objects.equals(buyer.id(), userId) || !Objects.equals(address.userId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Address does not belong to current user");
        }
    }

    private static BusinessException idempotencyConflict() {
        return new BusinessException(ErrorCode.CONFLICT, "Checkout references inconsistent formal orders");
    }
}
