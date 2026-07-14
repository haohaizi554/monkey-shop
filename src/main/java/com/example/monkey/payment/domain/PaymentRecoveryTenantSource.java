package com.example.monkey.payment.domain;

import java.time.LocalDateTime;
import java.util.List;

@FunctionalInterface
public interface PaymentRecoveryTenantSource {

    List<Long> findTenantIdsReadyForRecovery(LocalDateTime cutoff, int limit);

    static PaymentRecoveryTenantSource none() {
        return (cutoff, limit) -> List.of();
    }
}
