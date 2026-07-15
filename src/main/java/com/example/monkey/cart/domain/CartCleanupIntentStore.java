package com.example.monkey.cart.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CartCleanupIntentStore {

    CartCleanupIntent save(CartCleanupIntent intent);

    Optional<CartCleanupIntent> findByCheckoutId(Long checkoutId);

    List<Long> findReadyCheckoutIds(LocalDateTime now);
}
