package com.example.monkey.membership.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipPurchaseRewardRepository extends JpaRepository<MembershipPurchaseRewardEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
              from MembershipPurchaseRewardEntity r
             where r.tenantId = :tenantId
               and r.paymentId = :paymentId
            """)
    Optional<MembershipPurchaseRewardEntity> findLockedByTenantIdAndPaymentId(
            @Param("tenantId") Long tenantId, @Param("paymentId") Long paymentId);
}
