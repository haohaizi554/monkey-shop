package com.example.monkey.membership.infrastructure;

import com.example.monkey.shared.application.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointsWalletRepository extends JpaRepository<PointsWalletEntity, Long> {

    Optional<PointsWalletEntity> findByUserId(Long userId);

    default int updateWallet(
            Long userId,
            long version,
            long balance,
            long totalEarned,
            long totalSpent,
            long pointsDebt,
            LocalDateTime now) {
        return updateWallet(
                userId,
                version,
                balance,
                totalEarned,
                totalSpent,
                pointsDebt,
                now,
                TenantContext.currentTenantIdOrDefault());
    }

    /** Compatibility overload for direct callers that predate persisted refund debt. */
    default int updateWallet(
            Long userId, long version, long balance, long totalEarned, long totalSpent, LocalDateTime now) {
        return updateWallet(userId, version, balance, totalEarned, totalSpent, 0, now);
    }

    @Modifying
    @Query("""
            update PointsWalletEntity w
               set w.balance = :balance,
                   w.totalEarned = :totalEarned,
                   w.totalSpent = :totalSpent,
                   w.pointsDebt = :pointsDebt,
                   w.updateTime = :now,
                   w.version = w.version + 1
             where w.userId = :userId
               and w.tenantId = :tenantId
               and w.version = :version
            """)
    int updateWallet(
            @Param("userId") Long userId,
            @Param("version") long version,
            @Param("balance") long balance,
            @Param("totalEarned") long totalEarned,
            @Param("totalSpent") long totalSpent,
            @Param("pointsDebt") long pointsDebt,
            @Param("now") LocalDateTime now,
            @Param("tenantId") Long tenantId);
}
