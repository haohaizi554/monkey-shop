package com.example.monkey.cart.domain;

import java.time.LocalDateTime;
import java.util.List;

public interface CartCleanupTenantSource {

    List<Long> findTenantIdsWithReadyIntents(LocalDateTime cutoff, long afterTenantId, int limit);
}
