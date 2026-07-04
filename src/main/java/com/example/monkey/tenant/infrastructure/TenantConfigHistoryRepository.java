package com.example.monkey.tenant.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantConfigHistoryRepository extends JpaRepository<TenantConfigHistoryEntity, Long> {}
