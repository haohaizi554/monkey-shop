package com.example.monkey.tenant.infrastructure;

import com.example.monkey.tenant.domain.TenantStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<TenantEntity, Long> {

    Optional<TenantEntity> findByCode(String code);

    List<TenantEntity> findAllByOrderByCreatedAtDesc();

    long countByStatus(TenantStatus status);

    @Query("""
            SELECT tenant.id
            FROM TenantEntity tenant
            WHERE tenant.status IN :statuses
              AND tenant.expiresAt > :now
            ORDER BY tenant.id
            """)
    List<Long> findServiceableTenantIds(
            @Param("statuses") List<TenantStatus> statuses, @Param("now") LocalDateTime now);
}
