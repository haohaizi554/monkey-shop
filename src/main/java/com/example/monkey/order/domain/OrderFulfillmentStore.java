package com.example.monkey.order.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderFulfillmentStore {

    List<OrderFulfillmentItem> findItems(Long orderId);

    Optional<OrderFulfillmentItem> findItem(Long orderId, Long skuId);

    OrderFulfillmentItem saveItem(OrderFulfillmentItem item);

    OrderShipmentBatch saveShipment(OrderShipmentBatch shipment);

    List<OrderShipmentBatch> findShipments(Long orderId);

    Optional<OrderShipmentBatch> findShipment(Long shipmentId);

    List<OrderShipmentBatch> findReceivableShipments(LocalDateTime shippedBefore, int limit);

    OrderShipmentBatch markShipmentReceived(OrderShipmentBatch shipment);

    boolean hasReview(Long orderId, Long userId, Long skuId);

    OrderReview saveReview(OrderReview review);

    List<OrderReview> findReviews(Long orderId);
}
