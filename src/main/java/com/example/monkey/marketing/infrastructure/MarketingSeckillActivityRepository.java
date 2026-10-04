package com.example.monkey.marketing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketingSeckillActivityRepository extends JpaRepository<MarketingSeckillActivityEntity, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    UPDATE marketing_seckill_activity
                    SET sold_quantity = sold_quantity + :quantity,
                        version = version + 1
                    WHERE id = :id
                      AND tenant_id = :tenantId
                      AND sold_quantity + :quantity <= stock_quantity
                    """, nativeQuery = true)
    int incrementSold(@Param("id") Long id, @Param("tenantId") long tenantId, @Param("quantity") int quantity);
}
