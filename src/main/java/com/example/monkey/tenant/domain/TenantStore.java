package com.example.monkey.tenant.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public interface TenantStore {

    Tenant saveTenant(Tenant tenant);

    Optional<Tenant> findTenant(Long tenantId);

    Optional<Tenant> findTenantByCode(String code);

    List<Tenant> findTenants();

    long countTenantsByStatus(TenantStatus status);

    TenantConfig saveConfig(TenantConfig config, Long operatorUserId);

    List<TenantConfig> findConfigs(Long tenantId);

    TenantBill saveBill(TenantBill bill);

    List<TenantBill> findBills(Long tenantId);

    long countOrdersForTenant(Long tenantId, YearMonth month);

    BigDecimal sumPaidAmountForTenant(Long tenantId, YearMonth month);

    TenantDataExportJob saveExportJob(TenantDataExportJob exportJob);

    List<TenantDataExportJob> findExportJobs(Long tenantId);

    List<TenantDataExportJob> findPendingExportJobs(int limit);
}
