package com.example.monkey.logistics.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/** Persistence boundary for the durable, one-provider-create shipment claim. */
public interface ShipmentCreationClaimStore {

    ShipmentClaimReservation reserve(
            ShipmentCreationClaim candidate, LocalDateTime now, Duration leaseDuration);

    /** Release an in-flight claim after a provider/local failure so a retry can reuse its stable token. */
    void releaseForRetry(ShipmentCreationClaim claim, LocalDateTime now);

    /** Mark the claim accepted in the same transaction as the local tracking projection. */
    void markAccepted(ShipmentCreationClaim claim, LocalDateTime now);
}
