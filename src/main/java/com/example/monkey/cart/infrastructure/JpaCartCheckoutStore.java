package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCheckoutStore;
import com.example.monkey.cart.domain.CheckoutLine;
import com.example.monkey.cart.domain.CheckoutOrder;
import com.example.monkey.cart.domain.CheckoutSubOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.cart.checkout-store.provider", havingValue = "jpa", matchIfMissing = true)
public class JpaCartCheckoutStore implements CartCheckoutStore {

    private final CartCheckoutRepository checkoutRepository;
    private final CartSubOrderRepository subOrderRepository;
    private final CartCheckoutLineRepository lineRepository;

    public JpaCartCheckoutStore(
            CartCheckoutRepository checkoutRepository,
            CartSubOrderRepository subOrderRepository,
            CartCheckoutLineRepository lineRepository) {
        this.checkoutRepository = checkoutRepository;
        this.subOrderRepository = subOrderRepository;
        this.lineRepository = lineRepository;
    }

    @Override
    public Optional<CheckoutOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
        return checkoutRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(this::toDomain);
    }

    @Override
    public CheckoutOrder save(CheckoutOrder checkout) {
        CartCheckoutEntity savedCheckout = checkoutRepository.save(toEntity(checkout));
        for (CheckoutSubOrder subOrder : checkout.subOrders()) {
            subOrderRepository.save(toEntity(savedCheckout.getId(), checkout.createdAt(), subOrder));
            for (CheckoutLine line : subOrder.lines()) {
                lineRepository.save(toEntity(savedCheckout.getId(), subOrder.id(), checkout.createdAt(), line));
            }
        }
        return toDomain(savedCheckout);
    }

    private CheckoutOrder toDomain(CartCheckoutEntity checkout) {
        List<CartSubOrderEntity> subOrders = subOrderRepository.findByCheckoutIdOrderByIdAsc(checkout.getId());
        Map<Long, List<CartCheckoutLineEntity>> linesBySubOrder =
                lineRepository.findByCheckoutIdOrderBySubOrderIdAscIdAsc(checkout.getId()).stream()
                        .collect(Collectors.groupingBy(CartCheckoutLineEntity::getSubOrderId, Collectors.toList()));
        List<CheckoutSubOrder> domainSubOrders = subOrders.stream()
                .map(subOrder -> toSubOrder(subOrder, linesForSubOrder(subOrder.getId(), linesBySubOrder)))
                .toList();
        return new CheckoutOrder(
                checkout.getId(),
                checkout.getCheckoutNo(),
                checkout.getUserId(),
                checkout.getAddressId(),
                checkout.getIdempotencyKey(),
                checkout.getOriginalAmount(),
                checkout.getDiscountAmount(),
                checkout.getPayableAmount(),
                checkout.getStatus(),
                checkout.getProvince(),
                checkout.getCreateTime(),
                domainSubOrders);
    }

    private static List<CheckoutLine> linesForSubOrder(
            Long subOrderId, Map<Long, List<CartCheckoutLineEntity>> linesBySubOrder) {
        return linesBySubOrder.getOrDefault(subOrderId, List.of()).stream()
                .map(JpaCartCheckoutStore::toLine)
                .toList();
    }

    private static CheckoutSubOrder toSubOrder(CartSubOrderEntity subOrder, List<CheckoutLine> lines) {
        return new CheckoutSubOrder(
                subOrder.getId(),
                subOrder.getShopId(),
                subOrder.getOrderNo(),
                subOrder.getOriginalAmount(),
                subOrder.getDiscountAmount(),
                subOrder.getPayableAmount(),
                subOrder.getStatus(),
                lines);
    }

    private static CartCheckoutEntity toEntity(CheckoutOrder checkout) {
        CartCheckoutEntity entity = new CartCheckoutEntity();
        entity.setId(checkout.id());
        entity.setCheckoutNo(checkout.checkoutNo());
        entity.setUserId(checkout.userId());
        entity.setAddressId(checkout.addressId());
        entity.setIdempotencyKey(checkout.idempotencyKey());
        entity.setOriginalAmount(checkout.originalAmount());
        entity.setDiscountAmount(checkout.discountAmount());
        entity.setPayableAmount(checkout.payableAmount());
        entity.setStatus(checkout.status());
        entity.setProvince(checkout.province());
        entity.setCreateTime(checkout.createdAt());
        return entity;
    }

    private static CartSubOrderEntity toEntity(
            Long checkoutId, java.time.LocalDateTime createdAt, CheckoutSubOrder subOrder) {
        CartSubOrderEntity entity = new CartSubOrderEntity();
        entity.setId(subOrder.id());
        entity.setCheckoutId(checkoutId);
        entity.setOrderNo(subOrder.orderNo());
        entity.setShopId(subOrder.shopId());
        entity.setOriginalAmount(subOrder.originalAmount());
        entity.setDiscountAmount(subOrder.discountAmount());
        entity.setPayableAmount(subOrder.payableAmount());
        entity.setStatus(subOrder.status());
        entity.setCreateTime(createdAt);
        return entity;
    }

    private static CartCheckoutLineEntity toEntity(
            Long checkoutId, Long subOrderId, java.time.LocalDateTime createdAt, CheckoutLine line) {
        CartCheckoutLineEntity entity = new CartCheckoutLineEntity();
        entity.setId(line.id());
        entity.setCheckoutId(checkoutId);
        entity.setSubOrderId(subOrderId);
        entity.setSkuId(line.skuId());
        entity.setShopId(line.shopId());
        entity.setCategoryId(line.categoryId());
        entity.setProductName(line.productName());
        entity.setProductImage(line.productImage());
        entity.setQuantity(line.quantity());
        entity.setUnitPrice(line.unitPrice());
        entity.setOriginalAmount(line.originalAmount());
        entity.setDiscountAmount(line.discountAmount());
        entity.setPayableAmount(line.payableAmount());
        entity.setCouponCodes(String.join(",", line.couponCodes()));
        entity.setReservationKey(line.reservationKey());
        entity.setWarehouseId(line.warehouseId());
        entity.setCreateTime(createdAt);
        return entity;
    }

    private static CheckoutLine toLine(CartCheckoutLineEntity entity) {
        return new CheckoutLine(
                entity.getId(),
                entity.getSkuId(),
                entity.getShopId(),
                entity.getCategoryId(),
                entity.getProductName(),
                entity.getProductImage(),
                entity.getQuantity(),
                entity.getUnitPrice(),
                entity.getOriginalAmount(),
                entity.getDiscountAmount(),
                entity.getPayableAmount(),
                splitCoupons(entity.getCouponCodes()),
                entity.getReservationKey(),
                entity.getWarehouseId());
    }

    private static List<String> splitCoupons(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).filter(StringUtils::hasText).toList();
    }
}
