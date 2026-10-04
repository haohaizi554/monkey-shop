package com.example.monkey.logistics.domain;

import java.time.Instant;

/**
 * Canonical order boundary used by logistics.
 *
 * <p>Logistics owns carrier tracking, while the order module remains the only owner of order lifecycle
 * transitions. Implementations must perform their checks and transitions in the caller's transaction.
 */
public interface OrderFulfillmentPort {

    void requireShippable(long tenantId, long orderId);

    void markShipmentCreated(long tenantId, long orderId, long shipmentId);

    void markDelivered(long tenantId, long orderId, long shipmentId, Instant deliveredAt);
}
