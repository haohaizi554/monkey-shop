package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderPage;
import com.example.monkey.order.domain.OrderStore.OrderPageRequest;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.order.domain.OrderStore.SortOrder.Direction;
import com.example.monkey.shared.infrastructure.persistence.JpaPageRequests;
import com.example.monkey.shared.infrastructure.persistence.JpaSorts;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class JpaOrderStore implements OrderStore {

    private static final Set<String> ALLOWED_SORT_PROPERTIES =
            Set.of("id", "orderNo", "createTime", "status", "price", "productName", "userId");

    private final OrderRepository orderRepository;
    private final StockLogRepository stockLogRepository;

    public JpaOrderStore(OrderRepository orderRepository, StockLogRepository stockLogRepository) {
        this.orderRepository = orderRepository;
        this.stockLogRepository = stockLogRepository;
    }

    @Override
    public OrderPage findVisibleByUser(Long userId, OrderPageRequest request) {
        Page<OrderRecord> page = orderRepository
                .findByUserIdAndUserHiddenFalse(userId, toPageable(request))
                .map(JpaOrderStore::toRecord);
        return toOrderPage(page);
    }

    @Override
    public OrderPage findAll(OrderPageRequest request) {
        return toOrderPage(orderRepository.findAll(toPageable(request)).map(JpaOrderStore::toRecord));
    }

    @Override
    public Optional<OrderRecord> findById(Long id) {
        return orderRepository.findById(id).map(JpaOrderStore::toRecord);
    }

    @Override
    public Optional<OrderRecord> findVisibleByIdAndUserId(Long id, Long userId) {
        return orderRepository.findByIdAndUserIdAndUserHiddenFalse(id, userId).map(JpaOrderStore::toRecord);
    }

    @Override
    public OrderRecord savePlacedOrder(OrderRecord order) {
        Order entity = toEntity(order);
        Order saved = orderRepository.save(entity);
        return toRecord(saved == null ? entity : saved);
    }

    @Override
    public void hideFromUser(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.hideFromUser();
            orderRepository.save(order);
        });
    }

    @Override
    public boolean recordStockRestore(Long orderId, Long productId) {
        return stockLogRepository.recordRestore(orderId, productId) > 0;
    }

    @Override
    public int transitionStatus(Long orderId, String expectedStatus, String nextStatus, LocalDateTime shippingTime) {
        return shippingTime == null
                ? orderRepository.transitionStatus(orderId, expectedStatus, nextStatus)
                : orderRepository.transitionStatusWithShippingTime(orderId, expectedStatus, nextStatus, shippingTime);
    }

    private static Pageable toPageable(OrderPageRequest request) {
        List<Sort.Order> orders = request.sortOrders().stream()
                .flatMap(
                        order -> JpaSorts.allowedOrder(
                                order.property(), toSpringDirection(order.direction()), ALLOWED_SORT_PROPERTIES)
                                .stream())
                .toList();
        Sort sort = orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
        return JpaPageRequests.bounded(request.page(), request.size(), sort);
    }

    private static Sort.Direction toSpringDirection(Direction direction) {
        return direction == Direction.DESC ? Sort.Direction.DESC : Sort.Direction.ASC;
    }

    private static OrderPage toOrderPage(Page<OrderRecord> page) {
        return new OrderPage(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    private static OrderRecord toRecord(Order order) {
        return new OrderRecord(
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
                order.getCreateTime(),
                order.isUserHidden());
    }

    private static Order toEntity(OrderRecord record) {
        Order order = new Order();
        order.setId(record.id());
        order.setOrderNo(record.orderNo());
        order.setUserId(record.userId());
        order.setBuyerName(record.buyerName());
        order.setBuyerAvatar(record.buyerAvatar());
        order.setProductId(record.productId());
        order.setProductName(record.productName());
        order.setProductImage(record.productImage());
        order.setPrice(record.price());
        order.setDescription(record.description());
        order.setReceiverName(record.receiverName());
        order.setReceiverPhone(record.receiverPhone());
        order.setAddressSnapshot(record.addressSnapshot());
        order.setShippingTime(record.shippingTime());
        order.setStatus(record.status());
        order.setCreateTime(record.createTime());
        order.setUserHidden(record.userHidden());
        return order;
    }
}
