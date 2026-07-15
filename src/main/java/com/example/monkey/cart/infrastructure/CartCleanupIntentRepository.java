package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCleanupIntentStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartCleanupIntentRepository extends JpaRepository<CartCleanupIntentEntity, Long> {

    List<CartCleanupIntentEntity> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreateTimeAsc(
            CartCleanupIntentStatus status, LocalDateTime nextAttemptAt);
}
