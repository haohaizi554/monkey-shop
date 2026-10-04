package com.example.monkey.logistics.domain;

public interface LogisticsGateway {

    /**
     * Create the provider shipment using {@link LogisticsTracking#trackingNo()} as the stable provider
     * idempotency token. Implementations must make retries with the same token return the original provider
     * shipment instead of creating another one.
     */
    LogisticsGatewayResult createShipment(LogisticsTracking tracking);
}
