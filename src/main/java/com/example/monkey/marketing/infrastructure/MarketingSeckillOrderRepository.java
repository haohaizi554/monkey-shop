package com.example.monkey.marketing.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketingSeckillOrderRepository extends JpaRepository<MarketingSeckillOrderEntity, Long> {

    Optional<MarketingSeckillOrderEntity> findByActivityIdAndUserIdAndIdempotencyKey(
            Long activityId, Long userId, String idempotencyKey);

    @Query("select coalesce(sum(o.quantity), 0) from MarketingSeckillOrderEntity o "
            + "where o.activityId = :activityId and o.userId = :userId")
    int purchasedQuantity(@Param("activityId") Long activityId, @Param("userId") Long userId);
}
