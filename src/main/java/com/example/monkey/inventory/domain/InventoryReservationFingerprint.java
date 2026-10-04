package com.example.monkey.inventory.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable identity for an inventory reservation request.
 *
 * <p>The request key is the durable claim key. The digest binds that key to every request field that can
 * change the reservation outcome, including the warehouse selected by routing.
 */
public record InventoryReservationFingerprint(String value) {

    public static final String LEGACY_UNREPLAYABLE = "LEGACY_UNREPLAYABLE";

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    public InventoryReservationFingerprint {
        value = value == null ? LEGACY_UNREPLAYABLE : value.strip();
        if (!LEGACY_UNREPLAYABLE.equals(value) && !SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("inventory reservation fingerprint must be a SHA-256 hex digest");
        }
    }

    public static InventoryReservationFingerprint of(
            String normalizedReservationKey,
            Long skuId,
            Long requestedWarehouseId,
            String province,
            Long orderId,
            int quantity,
            Long selectedWarehouseId) {
        String reservationKey = Objects.requireNonNull(normalizedReservationKey, "reservation key must not be null")
                .strip();
        if (reservationKey.isBlank()) {
            throw new IllegalArgumentException("reservation key must not be blank");
        }
        Objects.requireNonNull(skuId, "sku id must not be null");
        Objects.requireNonNull(selectedWarehouseId, "selected warehouse id must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("reservation quantity must be positive");
        }
        String canonical = String.join(
                "|",
                "v1",
                field(reservationKey),
                field(skuId.toString()),
                field(requestedWarehouseId == null ? null : requestedWarehouseId.toString()),
                field(normalizeProvince(province)),
                field(orderId == null ? null : orderId.toString()),
                field(Integer.toString(quantity)),
                field(selectedWarehouseId.toString()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new InventoryReservationFingerprint(
                    HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    public boolean replayable() {
        return SHA_256_HEX.matcher(value).matches();
    }

    public static String normalizeProvince(String province) {
        return province == null || province.isBlank() ? null : province.strip();
    }

    private static String field(String value) {
        if (value == null) {
            return "-1:";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }
}
