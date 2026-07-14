package com.example.monkey.payment.domain;

import java.util.Objects;

public record RefundAuditIntent(
        RefundAuditState state,
        String eventType,
        Long actorUserId,
        String actorRole,
        String sourceIp,
        boolean includeOwner,
        String detail) {

    public RefundAuditIntent {
        Objects.requireNonNull(state, "state");
        if (!RefundAuditState.NONE.equals(state)) {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(actorRole, "actorRole");
        }
        if ((RefundAuditState.PENDING.equals(state) || RefundAuditState.DELIVERED.equals(state)) && detail == null) {
            throw new IllegalArgumentException("pending refund audit requires immutable detail");
        }
    }

    public static RefundAuditIntent waiting(
            String eventType, Long actorUserId, String actorRole, String sourceIp, boolean includeOwner) {
        return new RefundAuditIntent(
                RefundAuditState.WAITING, eventType, actorUserId, actorRole, sourceIp, includeOwner, null);
    }

    public static RefundAuditIntent legacy() {
        return new RefundAuditIntent(RefundAuditState.NONE, null, null, null, null, false, null);
    }

    public RefundAuditIntent pending(String immutableDetail) {
        return new RefundAuditIntent(
                RefundAuditState.PENDING,
                eventType,
                actorUserId,
                actorRole,
                sourceIp,
                includeOwner,
                Objects.requireNonNull(immutableDetail, "immutableDetail"));
    }

    public RefundAuditIntent delivered() {
        if (!RefundAuditState.PENDING.equals(state)) {
            throw new IllegalStateException("only a pending refund audit can be delivered");
        }
        return new RefundAuditIntent(
                RefundAuditState.DELIVERED, eventType, actorUserId, actorRole, sourceIp, includeOwner, detail);
    }
}
