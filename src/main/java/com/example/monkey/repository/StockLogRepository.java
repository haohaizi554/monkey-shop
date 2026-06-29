package com.example.monkey.repository;

import com.example.monkey.entity.StockLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockLogRepository extends JpaRepository<StockLog, Long> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO stock_log (order_id, product_id, direction, created_at)
            VALUES (:orderId, :productId, 'RESTORE', CURRENT_TIMESTAMP(6))
            """, nativeQuery = true)
    int recordRestore(@Param("orderId") Long orderId, @Param("productId") Long productId);
}
