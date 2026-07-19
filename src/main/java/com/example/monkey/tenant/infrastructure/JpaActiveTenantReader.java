package com.example.monkey.tenant.infrastructure;

import com.example.monkey.tenant.domain.ActiveTenantReader;
import com.example.monkey.tenant.domain.TenantStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaActiveTenantReader implements ActiveTenantReader {

    private static final List<TenantStatus> SERVICEABLE_STATUSES =
            List.of(TenantStatus.TRIAL, TenantStatus.ACTIVE, TenantStatus.DOWNGRADED);

    private final TenantRepository tenantRepository;
    private final Clock clock;

    @Autowired
    public JpaActiveTenantReader(TenantRepository tenantRepository) {
        this(tenantRepository, Clock.systemDefaultZone());
    }

    JpaActiveTenantReader(TenantRepository tenantRepository, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findActiveTenantIds() {
        return tenantRepository.findServiceableTenantIds(SERVICEABLE_STATUSES, LocalDateTime.now(clock));
    }
}
