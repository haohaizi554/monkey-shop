package com.example.monkey.cart.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CartCleanupIntentStore {

    CartCleanupIntent save(CartCleanupIntent intent);

    Optional<CartCleanupIntent> findByCheckoutId(Long checkoutId);

    Optional<CartCleanupIntent> claim(
            Long checkoutId, String claimToken, LocalDateTime now, LocalDateTime leaseExpiresAt);

    boolean completeClaim(Long checkoutId, String claimToken, LocalDateTime now);

    boolean failClaim(Long checkoutId, String claimToken, LocalDateTime now, LocalDateTime nextAttemptAt, String error);

    List<Long> findReadyCheckoutIds(LocalDateTime now);
}
