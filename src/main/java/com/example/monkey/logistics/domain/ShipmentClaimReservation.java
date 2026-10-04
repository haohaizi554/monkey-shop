package com.example.monkey.logistics.domain;

/** Result of attempting to acquire a durable shipment creation claim. */
public record ShipmentClaimReservation(ShipmentCreationClaim claim, boolean acquired) {

    public ShipmentClaimReservation {
        if (claim == null) {
            throw new IllegalArgumentException("shipment claim is required");
        }
    }
}
