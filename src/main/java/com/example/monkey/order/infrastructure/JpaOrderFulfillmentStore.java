package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderFulfillmentItem;
import com.example.monkey.order.domain.OrderFulfillmentStore;
import com.example.monkey.order.domain.OrderReview;
import com.example.monkey.order.domain.OrderShipmentBatch;
import com.example.monkey.order.domain.OrderShipmentLine;
import com.example.monkey.order.domain.OrderShipmentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.order.fulfillment-store", havingValue = "jpa", matchIfMissing = true)
public class JpaOrderFulfillmentStore implements OrderFulfillmentStore {

    private static final String IMAGE_SEPARATOR = "\n";

    private final OrderFulfillmentItemRepository itemRepository;
    private final OrderShipmentBatchRepository shipmentRepository;
    private final OrderShipmentLineRepository shipmentLineRepository;
    private final OrderReviewRepository reviewRepository;

    public JpaOrderFulfillmentStore(
            OrderFulfillmentItemRepository itemRepository,
            OrderShipmentBatchRepository shipmentRepository,
            OrderShipmentLineRepository shipmentLineRepository,
            OrderReviewRepository reviewRepository) {
        this.itemRepository = itemRepository;
        this.shipmentRepository = shipmentRepository;
        this.shipmentLineRepository = shipmentLineRepository;
        this.reviewRepository = reviewRepository;
    }

    @Override
    public List<OrderFulfillmentItem> findItems(Long orderId) {
        return itemRepository.findByOrderIdOrderBySkuIdAsc(orderId).stream()
                .map(JpaOrderFulfillmentStore::toDomain)
                .toList();
    }

    @Override
    public Optional<OrderFulfillmentItem> findItem(Long orderId, Long skuId) {
        return itemRepository.findByOrderIdAndSkuId(orderId, skuId).map(JpaOrderFulfillmentStore::toDomain);
    }

    @Override
    public OrderFulfillmentItem saveItem(OrderFulfillmentItem item) {
        OrderFulfillmentItemEntity entity = itemRepository
                .findByOrderIdAndSkuId(item.orderId(), item.skuId())
                .orElseGet(OrderFulfillmentItemEntity::new);
        entity.setId(item.id());
        entity.setOrderId(item.orderId());
        entity.setSkuId(item.skuId());
        entity.setProductName(item.productName());
        entity.setOrderedQuantity(item.orderedQuantity());
        entity.setShippedQuantity(item.shippedQuantity());
        entity.setReceivedQuantity(item.receivedQuantity());
        entity.setStatus(item.status());
        LocalDateTime now = LocalDateTime.now();
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(now);
        }
        entity.setUpdateTime(now);
        return toDomain(itemRepository.save(entity));
    }

    @Override
    public OrderShipmentBatch saveShipment(OrderShipmentBatch shipment) {
        OrderShipmentBatchEntity batch = toEntity(shipment);
        shipmentRepository.save(batch);
        for (OrderShipmentLine line : shipment.lines()) {
            shipmentLineRepository.save(toEntity(line));
        }
        return shipment;
    }

    @Override
    public List<OrderShipmentBatch> findShipments(Long orderId) {
        List<OrderShipmentBatchEntity> batches = shipmentRepository.findByOrderIdOrderByShippedAtAsc(orderId);
        return withLines(batches);
    }

    @Override
    public Optional<OrderShipmentBatch> findShipment(Long shipmentId) {
        return shipmentRepository.findById(shipmentId).map(batch -> toDomain(batch, linesFor(batch.getId())));
    }

    @Override
    public List<OrderShipmentBatch> findReceivableShipments(LocalDateTime shippedBefore, int limit) {
        return withLines(
                shipmentRepository
                        .findTop100ByStatusAndShippedAtBeforeOrderByShippedAtAsc(
                                OrderShipmentStatus.SHIPPED, shippedBefore)
                        .stream()
                        .limit(limit)
                        .toList());
    }

    @Override
    public OrderShipmentBatch markShipmentReceived(OrderShipmentBatch shipment) {
        OrderShipmentBatchEntity entity =
                shipmentRepository.findById(shipment.id()).orElseGet(() -> toEntity(shipment));
        entity.setStatus(OrderShipmentStatus.RECEIVED);
        entity.setReceivedAt(shipment.receivedAt());
        shipmentRepository.save(entity);
        return shipment;
    }

    @Override
    public boolean hasReview(Long orderId, Long userId, Long skuId) {
        return reviewRepository.existsByOrderIdAndUserIdAndSkuId(orderId, userId, skuId);
    }

    @Override
    public OrderReview saveReview(OrderReview review) {
        return toDomain(reviewRepository.save(toEntity(review)));
    }

    @Override
    public List<OrderReview> findReviews(Long orderId) {
        return reviewRepository.findByOrderIdOrderByCreateTimeDesc(orderId).stream()
                .map(JpaOrderFulfillmentStore::toDomain)
                .toList();
    }

    private List<OrderShipmentBatch> withLines(List<OrderShipmentBatchEntity> batches) {
        Map<Long, List<OrderShipmentLine>> linesByShipment =
                shipmentLineRepository
                        .findByShipmentIdIn(batches.stream()
                                .map(OrderShipmentBatchEntity::getId)
                                .toList())
                        .stream()
                        .map(JpaOrderFulfillmentStore::toDomain)
                        .collect(Collectors.groupingBy(OrderShipmentLine::shipmentId));
        return batches.stream()
                .map(batch -> toDomain(batch, linesByShipment.getOrDefault(batch.getId(), List.of())))
                .toList();
    }

    private List<OrderShipmentLine> linesFor(Long shipmentId) {
        return shipmentLineRepository.findByShipmentId(shipmentId).stream()
                .map(JpaOrderFulfillmentStore::toDomain)
                .toList();
    }

    private static OrderFulfillmentItem toDomain(OrderFulfillmentItemEntity entity) {
        return new OrderFulfillmentItem(
                entity.getId(),
                entity.getOrderId(),
                entity.getSkuId(),
                entity.getProductName(),
                entity.getOrderedQuantity(),
                entity.getShippedQuantity(),
                entity.getReceivedQuantity(),
                entity.getStatus());
    }

    private static OrderShipmentBatch toDomain(OrderShipmentBatchEntity entity, List<OrderShipmentLine> lines) {
        return new OrderShipmentBatch(
                entity.getId(),
                entity.getOrderId(),
                entity.getShipmentNo(),
                entity.getCarrier(),
                entity.getTrackingNo(),
                entity.getStatus(),
                entity.getShippedAt(),
                entity.getReceivedAt(),
                lines);
    }

    private static OrderShipmentLine toDomain(OrderShipmentLineEntity entity) {
        return new OrderShipmentLine(
                entity.getId(),
                entity.getShipmentId(),
                entity.getOrderId(),
                entity.getSkuId(),
                entity.getProductName(),
                entity.getQuantity());
    }

    private static OrderReview toDomain(OrderReviewEntity entity) {
        return new OrderReview(
                entity.getId(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getSkuId(),
                entity.getRating(),
                entity.getContent(),
                splitImages(entity.getImageUrls()),
                entity.isAnonymous(),
                entity.getCreateTime());
    }

    private static OrderShipmentBatchEntity toEntity(OrderShipmentBatch shipment) {
        OrderShipmentBatchEntity entity = new OrderShipmentBatchEntity();
        entity.setId(shipment.id());
        entity.setOrderId(shipment.orderId());
        entity.setShipmentNo(shipment.shipmentNo());
        entity.setCarrier(shipment.carrier());
        entity.setTrackingNo(shipment.trackingNo());
        entity.setStatus(shipment.status());
        entity.setShippedAt(shipment.shippedAt());
        entity.setReceivedAt(shipment.receivedAt());
        entity.setCreateTime(shipment.shippedAt());
        return entity;
    }

    private static OrderShipmentLineEntity toEntity(OrderShipmentLine line) {
        OrderShipmentLineEntity entity = new OrderShipmentLineEntity();
        entity.setId(line.id());
        entity.setShipmentId(line.shipmentId());
        entity.setOrderId(line.orderId());
        entity.setSkuId(line.skuId());
        entity.setProductName(line.productName());
        entity.setQuantity(line.quantity());
        return entity;
    }

    private static OrderReviewEntity toEntity(OrderReview review) {
        OrderReviewEntity entity = new OrderReviewEntity();
        entity.setId(review.id());
        entity.setOrderId(review.orderId());
        entity.setUserId(review.userId());
        entity.setSkuId(review.skuId());
        entity.setRating(review.rating());
        entity.setContent(review.content());
        entity.setImageUrls(String.join(IMAGE_SEPARATOR, review.imageUrls()));
        entity.setAnonymous(review.anonymous());
        entity.setCreateTime(review.createTime());
        return entity;
    }

    private static List<String> splitImages(String imageUrls) {
        if (imageUrls == null || imageUrls.isBlank()) {
            return List.of();
        }
        return List.of(imageUrls.split(IMAGE_SEPARATOR)).stream()
                .filter(value -> !value.isBlank())
                .toList();
    }
}
