package com.example.monkey.tenant.infrastructure;

import com.example.monkey.tenant.domain.TenantStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<TenantEntity, Long> {

    Optional<TenantEntity> findByCode(String code);

    List<TenantEntity> findAllByOrderByCreatedAtDesc();

    long countByStatus(TenantStatus status);
}
