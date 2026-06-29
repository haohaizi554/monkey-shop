package com.example.monkey.domain.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderStore {

    List<OrderRecord> findVisibleByUserOrderByCreateTimeDesc(Long userId);

    OrderPage findVisibleByUser(Long userId, OrderPageRequest request);

    List<OrderRecord> findAllOrderByCreateTimeDesc();

    OrderPage findAll(OrderPageRequest request);

    Optional<OrderRecord> findById(Long id);

    Optional<OrderRecord> findVisibleByIdAndUserId(Long id, Long userId);

    Optional<ProductRecord> findProductById(Long productId);

    Optional<AddressRecord> findAddressById(Long addressId);

    Optional<BuyerRecord> findBuyerById(Long userId);

    boolean deductProductStock(Long productId);

    OrderRecord savePlacedOrder(OrderRecord order);

    void hideFromUser(Long orderId);

    boolean recordStockRestore(Long orderId, Long productId);

    boolean restoreProductStock(Long productId);

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
            boolean userHidden) {

        public static OrderRecord place(
                String orderNo, BuyerRecord buyer, ProductRecord product, AddressRecord address) {
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
                    OrderStatus.PAID.label(),
                    null,
                    false);
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
                    userHidden);
        }
    }

    record ProductRecord(Long id, String name, String imageUrl, BigDecimal price, String description, Integer stock) {
        public boolean hasStock() {
            return stock != null && stock > 0;
        }
    }

    record AddressRecord(Long id, Long userId, String receiverName, String phone, String detailAddress) {}

    record BuyerRecord(Long id, String username, String avatar) {}

    record OrderPageRequest(int page, int size, List<SortOrder> sortOrders) {
        public OrderPageRequest {
            sortOrders = sortOrders == null ? List.of() : List.copyOf(sortOrders);
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
