package com.example.monkey.marketing.infrastructure;

import com.example.monkey.marketing.domain.GroupBuyStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketingGroupBuyTeamRepository extends JpaRepository<MarketingGroupBuyTeamEntity, Long> {

    List<MarketingGroupBuyTeamEntity> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            GroupBuyStatus status, LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    UPDATE marketing_group_buy_team
                    SET joined_count = joined_count + :delta,
                        status = CASE
                            WHEN joined_count + :delta >= target_size THEN 'SUCCEEDED'
                            ELSE status
                        END,
                        version = version + 1
                    WHERE id = :id
                      AND tenant_id = :tenantId
                      AND status = 'OPEN'
                      AND joined_count + :delta <= target_size
                    """, nativeQuery = true)
    int joinIfOpen(@Param("id") Long id, @Param("tenantId") long tenantId, @Param("delta") int delta);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    UPDATE marketing_group_buy_team
                    SET status = :nextStatus,
                        version = version + 1
                    WHERE id = :id
                      AND tenant_id = :tenantId
                      AND status = :currentStatus
                      AND joined_count = :joinedCount
                    """, nativeQuery = true)
    int transitionStatus(
            @Param("id") Long id,
            @Param("tenantId") long tenantId,
            @Param("currentStatus") String currentStatus,
            @Param("nextStatus") String nextStatus,
            @Param("joinedCount") int joinedCount);
}
