package com.example.monkey.membership.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipPurchaseRewardEventRepository
        extends JpaRepository<MembershipPurchaseRewardEventEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e
              from MembershipPurchaseRewardEventEntity e
             where e.tenantId = :tenantId
               and e.paymentId = :paymentId
               and e.eventKey = :eventKey
            """)
    Optional<MembershipPurchaseRewardEventEntity> findLockedByTenantIdAndPaymentIdAndEventKey(
            @Param("tenantId") Long tenantId, @Param("paymentId") Long paymentId, @Param("eventKey") String eventKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e
              from MembershipPurchaseRewardEventEntity e
             where e.tenantId = :tenantId
               and e.eventKey = :eventKey
            """)
    Optional<MembershipPurchaseRewardEventEntity> findLockedByTenantIdAndEventKey(
            @Param("tenantId") Long tenantId, @Param("eventKey") String eventKey);
}
