package com.example.monkey.cart.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartCheckoutRepository extends JpaRepository<CartCheckoutEntity, Long> {

    Optional<CartCheckoutEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
