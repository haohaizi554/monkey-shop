package com.example.monkey.marketing.domain;

import java.util.Optional;

/** Durable tenant-scoped storage for group-buy idempotency decisions. */
public interface GroupBuyIdempotencyBindingStore {

    Optional<GroupBuyIdempotencyBinding> find(Long tenantId, Long userId, String idempotencyKey);

    /**
     * Attempts to create a binding without changing an existing decision.
     *
     * @return {@code true} only when this call inserted the binding; {@code false} when the unique
     *     key was already claimed
     */
    boolean reserve(GroupBuyIdempotencyBinding binding);
}
