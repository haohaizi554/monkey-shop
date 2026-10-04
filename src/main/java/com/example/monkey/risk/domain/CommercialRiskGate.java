package com.example.monkey.risk.domain;

/**
 * Commercial workflows use this port so risk enforcement remains inside the
 * application boundary instead of being an HTTP-only concern.
 */
public interface CommercialRiskGate {

    void requireAllowed(
            Long userId,
            Long activityId,
            Long productId,
            Long orderId,
            String deviceFingerprint,
            String clientIp,
            String operation);

    /**
     * Enforces the same decision while allowing a caller to provide an already-bound TOTP code. The default keeps
     * existing functional implementations source-compatible; adapters that understand TOTP should override it.
     */
    default void requireAllowed(
            Long userId,
            Long activityId,
            Long productId,
            Long orderId,
            String deviceFingerprint,
            String clientIp,
            String operation,
            String totpCode) {
        requireAllowed(userId, activityId, productId, orderId, deviceFingerprint, clientIp, operation);
    }
}
