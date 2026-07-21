package com.example.monkey.order.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface OrderStore {

    OrderPage findVisibleByUser(Long userId, OrderPageRequest request);

    OrderPage findAll(OrderPageRequest request);

    Optional<OrderRecord> findById(Long id);

    Optional<OrderRecord> findVisibleByIdAndUserId(Long id, Long userId);

    OrderRecord savePlacedOrder(OrderRecord order);

    default List<OrderRecord> findByCheckoutId(Long checkoutId) {
        return List.of();
    }

    default List<CheckoutOrderLineRecord> findLines(Long orderId) {
        return List.of();
    }

    default Map<Long, List<CheckoutOrderLineRecord>> findLinesByOrderIds(List<Long> orderIds) {
        return Map.of();
    }

    default List<OrderRecord> saveCheckoutOrders(List<CheckoutOrderRecord> orders) {
        throw new UnsupportedOperationException("Checkout order persistence is not configured");
    }

    void hideFromUser(Long orderId);

    boolean recordStockRestore(Long orderId, Long productId);

    int transitionStatus(Long orderId, String expectedStatus, String nextStatus, LocalDateTime shippingTime);

    record OrderRecord(
            Long id,
            String orderNo,
            Long userId,
            String buyerName,
            String buyerAvatar,
            Long productId,
            String productName,
            String productImage,
            BigDecimal price,
            String description,
            String receiverName,
            String receiverPhone,
            String addressSnapshot,
            LocalDateTime shippingTime,
            String status,
            LocalDateTime createTime,
            boolean userHidden,
            Long checkoutId,
            Long checkoutSubOrderId,
            Long shopId,
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            String checkoutIdempotencyKey) {

        public OrderRecord(
                Long id,
                String orderNo,
                Long userId,
                String buyerName,
                String buyerAvatar,
                Long productId,
                String productName,
                String productImage,
                BigDecimal price,
                String description,
                String receiverName,
                String receiverPhone,
                String addressSnapshot,
                LocalDateTime shippingTime,
                String status,
                LocalDateTime createTime,
                boolean userHidden) {
            this(
                    id,
                    orderNo,
                    userId,
                    buyerName,
                    buyerAvatar,
                    productId,
                    productName,
                    productImage,
                    price,
                    description,
                    receiverName,
                    receiverPhone,
                    addressSnapshot,
                    shippingTime,
                    status,
                    createTime,
                    userHidden,
                    null,
                    null,
                    null,
                    price,
                    BigDecimal.ZERO,
                    null);
        }

        public static OrderRecord place(
                String orderNo, Long currentUserId, BuyerRecord buyer, ProductRecord product, AddressRecord address) {
            ensurePlaceable(currentUserId, buyer, product, address);
            return new OrderRecord(
                    null,
                    orderNo,
                    buyer.id(),
                    buyer.username(),
                    buyer.avatar(),
                    product.id(),
                    product.name(),
                    product.imageUrl(),
                    product.price(),
                    product.description(),
                    address.receiverName(),
                    address.phone(),
                    address.detailAddress(),
                    null,
                    OrderStatus.PENDING_PAYMENT.label(),
                    null,
                    false);
        }

        public static void ensurePlaceable(
                Long currentUserId, BuyerRecord buyer, ProductRecord product, AddressRecord address) {
            if (!Objects.equals(buyer.id(), currentUserId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "Buyer does not match current user");
            }
            if (!Objects.equals(address.userId(), currentUserId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "Address does not belong to current user");
            }
            if (!product.hasStock()) {
                throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock");
            }
        }

        public OrderRecord withStatus(OrderStatus nextStatus, LocalDateTime nextShippingTime) {
            return new OrderRecord(
                    id,
                    orderNo,
                    userId,
                    buyerName,
                    buyerAvatar,
                    productId,
                    productName,
                    productImage,
                    price,
                    description,
                    receiverName,
                    receiverPhone,
                    addressSnapshot,
                    nextShippingTime == null ? shippingTime : nextShippingTime,
                    nextStatus.label(),
                    createTime,
                    userHidden,
                    checkoutId,
                    checkoutSubOrderId,
                    shopId,
                    originalAmount,
                    discountAmount,
                    checkoutIdempotencyKey);
        }

        public OrderRecord withId(Long assignedId) {
            return new OrderRecord(
                    assignedId,
                    orderNo,
                    userId,
                    buyerName,
                    buyerAvatar,
                    productId,
                    productName,
                    productImage,
                    price,
                    description,
                    receiverName,
                    receiverPhone,
                    addressSnapshot,
                    shippingTime,
                    status,
                    createTime,
                    userHidden,
                    checkoutId,
                    checkoutSubOrderId,
                    shopId,
                    originalAmount,
                    discountAmount,
                    checkoutIdempotencyKey);
        }
    }

    record CheckoutOrderRecord(OrderRecord order, List<CheckoutOrderLineRecord> lines) {
        public CheckoutOrderRecord {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    record CheckoutOrderLineRecord(
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
            String couponCodes,
            String reservationKey,
            Long warehouseId) {}

    record ProductRecord(Long id, String name, String imageUrl, BigDecimal price, String description, Integer stock) {
        public boolean hasStock() {
            return stock != null && stock > 0;
        }
    }

    record AddressRecord(Long id, Long userId, String receiverName, String phone, String detailAddress) {}

    record BuyerRecord(Long id, String username, String avatar) {}

    record OrderPageRequest(int page, int size, List<SortOrder> sortOrders, List<String> statuses, String keyword) {
        public OrderPageRequest {
            sortOrders = sortOrders == null ? List.of() : List.copyOf(sortOrders);
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        }

        public OrderPageRequest(int page, int size, List<SortOrder> sortOrders) {
            this(page, size, sortOrders, List.of(), null);
        }

        public boolean hasFilters() {
            return !statuses.isEmpty() || keyword != null;
        }
    }

    record OrderPage(
            List<OrderRecord> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {
        public OrderPage {
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    record SortOrder(String property, Direction direction) {
        public enum Direction {
            ASC,
            DESC
        }
    }
}
