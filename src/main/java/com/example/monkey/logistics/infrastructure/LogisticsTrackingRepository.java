package com.example.monkey.logistics.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsTrackingRepository extends JpaRepository<LogisticsTrackingEntity, Long> {

    Optional<LogisticsTrackingEntity> findByTrackingNo(String trackingNo);

    Optional<LogisticsTrackingEntity> findFirstByOrderIdAndUserIdOrderByCreateTimeDesc(Long orderId, Long userId);

    Optional<LogisticsTrackingEntity> findByTenantIdAndUserIdAndIdempotencyKey(
            Long tenantId, Long userId, String idempotencyKey);
}
