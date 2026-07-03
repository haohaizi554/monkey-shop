package com.example.monkey.cart.domain;

import java.util.Optional;

public interface CartCheckoutStore {

    Optional<CheckoutOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    CheckoutOrder save(CheckoutOrder checkout);
}
