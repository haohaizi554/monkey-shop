package com.example.monkey.marketing.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketingCouponRepository extends JpaRepository<MarketingCouponEntity, Long> {

    Optional<MarketingCouponEntity> findByCode(String code);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    UPDATE marketing_coupon
                    SET claimed_count = claimed_count + :quantity,
                        version = version + 1
                    WHERE id = :id
                      AND tenant_id = :tenantId
                      AND claimed_count + :quantity <= total_quota
                    """, nativeQuery = true)
    int incrementClaimed(@Param("id") Long id, @Param("tenantId") long tenantId, @Param("quantity") int quantity);
}
