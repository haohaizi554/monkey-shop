package com.example.monkey.tenant.infrastructure;

import com.example.monkey.tenant.domain.TenantExportStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantDataExportJobRepository extends JpaRepository<TenantDataExportJobEntity, Long> {

    Optional<TenantDataExportJobEntity> findByIdAndTenantId(Long id, Long tenantId);

    List<TenantDataExportJobEntity> findByTenantIdOrderByRequestedAtDesc(Long tenantId);

    List<TenantDataExportJobEntity> findByStatusInOrderByRequestedAtAsc(
            Collection<TenantExportStatus> statuses, Pageable pageable);
}
