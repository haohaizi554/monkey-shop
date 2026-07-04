package com.example.monkey.membership.infrastructure;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointsWalletRepository extends JpaRepository<PointsWalletEntity, Long> {

    Optional<PointsWalletEntity> findByUserId(Long userId);

    @Modifying
    @Query("""
            update PointsWalletEntity w
               set w.balance = :balance,
                   w.totalEarned = :totalEarned,
                   w.totalSpent = :totalSpent,
                   w.updateTime = :now,
                   w.version = w.version + 1
             where w.userId = :userId
               and w.version = :version
            """)
    int updateWallet(
            @Param("userId") Long userId,
            @Param("version") long version,
            @Param("balance") long balance,
            @Param("totalEarned") long totalEarned,
            @Param("totalSpent") long totalSpent,
            @Param("now") LocalDateTime now);
}
