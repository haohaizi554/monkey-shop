package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipProfileRepository extends JpaRepository<MembershipProfileEntity, Long> {

    Optional<MembershipProfileEntity> findByUserId(Long userId);

    default int updateLevel(Long userId, long version, MembershipLevel level, LocalDateTime now) {
        return updateLevel(userId, version, level, now, TenantContext.currentTenantIdOrDefault());
    }

    @Modifying
    @Query("""
            update MembershipProfileEntity p
               set p.level = :level,
                   p.updateTime = :now,
                   p.version = p.version + 1
             where p.userId = :userId
               and p.tenantId = :tenantId
               and p.version = :version
            """)
    int updateLevel(
            @Param("userId") Long userId,
            @Param("version") long version,
            @Param("level") MembershipLevel level,
            @Param("now") LocalDateTime now,
            @Param("tenantId") Long tenantId);
}
