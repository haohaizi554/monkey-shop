package com.example.monkey.tenant.application;

import com.example.monkey.tenant.application.dto.TenantBillDto;
import com.example.monkey.tenant.application.dto.TenantConfigDto;
import com.example.monkey.tenant.application.dto.TenantDashboardDto;
import com.example.monkey.tenant.application.dto.TenantExportJobDto;
import com.example.monkey.tenant.application.dto.TenantResponseDto;
import com.example.monkey.tenant.domain.Tenant;
import com.example.monkey.tenant.domain.TenantBill;
import com.example.monkey.tenant.domain.TenantConfig;
import com.example.monkey.tenant.domain.TenantDashboard;
import com.example.monkey.tenant.domain.TenantDataExportJob;

public final class TenantDtoAssembler {

    private TenantDtoAssembler() {}

    public static TenantResponseDto toTenant(Tenant tenant) {
        return new TenantResponseDto(
                tenant.id(),
                tenant.code(),
                tenant.name(),
                tenant.status(),
                tenant.plan(),
                tenant.contactName(),
                maskPhone(tenant.contactPhone()),
                tenant.createdAt(),
                tenant.expiresAt(),
                tenant.version());
    }

    public static TenantConfigDto toConfig(TenantConfig config) {
        return new TenantConfigDto(
                config.id(),
                config.tenantId(),
                config.configType(),
                config.provider(),
                config.settings(),
                config.enabled(),
                config.updatedAt(),
                config.version());
    }

    public static TenantBillDto toBill(TenantBill bill) {
        return new TenantBillDto(
                bill.id(),
                bill.tenantId(),
                bill.billingMonth().toString(),
                bill.plan(),
                bill.orderCount(),
                bill.monthlyFee(),
                bill.usageFee(),
                bill.totalAmount(),
                bill.paymentAmount(),
                bill.status(),
                bill.generatedAt(),
                bill.reconciledAt(),
                bill.version());
    }

    public static TenantExportJobDto toExport(TenantDataExportJob job) {
        return new TenantExportJobDto(
                job.id(),
                job.tenantId(),
                job.exportType(),
                job.status(),
                job.artifactUri() != null,
                artifactDownloadUri(job),
                job.requestedBy(),
                job.requestedAt(),
                job.completedAt(),
                job.auditTraceId(),
                job.version());
    }

    private static String artifactDownloadUri(TenantDataExportJob job) {
        if (job.artifactUri() == null) {
            return null;
        }
        return "/api/v1/tenants/" + job.tenantId() + "/exports/" + job.id() + "/artifact";
    }

    public static TenantDashboardDto toDashboard(TenantDashboard dashboard) {
        return new TenantDashboardDto(
                dashboard.activeTenants(),
                dashboard.expiredTenants(),
                dashboard.currentMonthOrders(),
                dashboard.currentMonthRevenue(),
                dashboard.tenants().stream().map(TenantDtoAssembler::toTenant).toList());
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 7) {
            return "***";
        }
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }
}
