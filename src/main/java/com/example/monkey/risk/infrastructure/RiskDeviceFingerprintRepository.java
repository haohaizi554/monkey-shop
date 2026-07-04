package com.example.monkey.risk.infrastructure;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskDeviceFingerprintRepository extends JpaRepository<RiskDeviceFingerprintEntity, Long> {

    @Query("""
            select count(distinct f.userId)
            from RiskDeviceFingerprintEntity f
            where f.deviceFingerprintHash = :deviceFingerprintHash
              and f.lastSeenAt >= :since
            """)
    long countDistinctUsersByDevice(
            @Param("deviceFingerprintHash") String deviceFingerprintHash, @Param("since") LocalDateTime since);

    @Query("""
            select count(distinct f.phoneHmac)
            from RiskDeviceFingerprintEntity f
            where f.deviceFingerprintHash = :deviceFingerprintHash
              and f.phoneHmac is not null
              and f.lastSeenAt >= :since
            """)
    long countDistinctPhonesByDevice(
            @Param("deviceFingerprintHash") String deviceFingerprintHash, @Param("since") LocalDateTime since);
}
