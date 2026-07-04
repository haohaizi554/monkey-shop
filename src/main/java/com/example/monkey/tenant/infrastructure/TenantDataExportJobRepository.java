package com.example.monkey.tenant.infrastructure;

import com.example.monkey.tenant.domain.TenantExportStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantDataExportJobRepository extends JpaRepository<TenantDataExportJobEntity, Long> {

    List<TenantDataExportJobEntity> findByTenantIdOrderByRequestedAtDesc(Long tenantId);

    List<TenantDataExportJobEntity> findByStatusOrderByRequestedAtAsc(TenantExportStatus status, Pageable pageable);
}
