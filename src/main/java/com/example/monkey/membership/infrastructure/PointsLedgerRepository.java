package com.example.monkey.membership.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointsLedgerRepository extends JpaRepository<PointsLedgerEntity, Long> {

    Optional<PointsLedgerEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
