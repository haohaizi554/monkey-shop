package com.example.monkey.tenant.infrastructure;

import com.example.monkey.tenant.domain.TenantConfigType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantConfigRepository extends JpaRepository<TenantConfigEntity, Long> {

    Optional<TenantConfigEntity> findByTenantIdAndConfigTypeAndProvider(
            Long tenantId, TenantConfigType configType, String provider);

    List<TenantConfigEntity> findByTenantIdOrderByUpdatedAtDesc(Long tenantId);
}
