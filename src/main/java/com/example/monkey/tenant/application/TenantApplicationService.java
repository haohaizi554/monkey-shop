package com.example.monkey.tenant.application;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.observability.TraceIds;
import com.example.monkey.shared.application.security.AuthenticatedPrincipals;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.tenant.application.dto.TenantBillDto;
import com.example.monkey.tenant.application.dto.TenantBillGenerateRequestDto;
import com.example.monkey.tenant.application.dto.TenantConfigDto;
import com.example.monkey.tenant.application.dto.TenantConfigRequestDto;
import com.example.monkey.tenant.application.dto.TenantCreateRequestDto;
import com.example.monkey.tenant.application.dto.TenantDashboardDto;
import com.example.monkey.tenant.application.dto.TenantDowngradeRequestDto;
import com.example.monkey.tenant.application.dto.TenantExportJobDto;
import com.example.monkey.tenant.application.dto.TenantExportRequestDto;
import com.example.monkey.tenant.application.dto.TenantRenewRequestDto;
import com.example.monkey.tenant.application.dto.TenantResponseDto;
import com.example.monkey.tenant.domain.Tenant;
import com.example.monkey.tenant.domain.TenantBill;
import com.example.monkey.tenant.domain.TenantConfig;
import com.example.monkey.tenant.domain.TenantDashboard;
import com.example.monkey.tenant.domain.TenantDataExportJob;
import com.example.monkey.tenant.domain.TenantExportStatus;
import com.example.monkey.tenant.domain.TenantPlan;
import com.example.monkey.tenant.domain.TenantStatus;
import com.example.monkey.tenant.domain.TenantStore;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantApplicationService {

    private static final int DEFAULT_RENEW_MONTHS = 12;

    private final TenantStore tenantStore;
    private final IdGenerator idGenerator;
    private final AuditService auditService;

    public TenantApplicationService(TenantStore tenantStore, IdGenerator idGenerator, AuditService auditService) {
        this.tenantStore = tenantStore;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
    }

    @WithSpan("tenant.create")
    @Transactional
    public TenantResponseDto createTenant(SessionUser operator, TenantCreateRequestDto request) {
        Long operatorId = AuthenticatedPrincipals.requireUserId(operator);
        tenantStore.findTenantByCode(request.code()).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.CONFLICT, "Tenant code already exists");
        });
        LocalDateTime now = LocalDateTime.now();
        Tenant tenant = new Tenant(
                idGenerator.nextId(),
                request.code(),
                request.name(),
                TenantStatus.TRIAL,
                request.plan(),
                request.contactName(),
                request.contactPhone(),
                now,
                now.plusMonths(normalizedMonths(request.months())),
                0L);
        Tenant saved = tenantStore.saveTenant(tenant);
        audit(
                AuditService.TENANT_CREATED,
                operator,
                "tenant:" + saved.id(),
                "code=" + saved.code() + ",plan=" + saved.plan());
        return TenantDtoAssembler.toTenant(saved);
    }

    @WithSpan("tenant.list")
    @Transactional(readOnly = true)
    public List<TenantResponseDto> tenants() {
        return tenantStore.findTenants().stream()
                .map(TenantDtoAssembler::toTenant)
                .toList();
    }

    @WithSpan("tenant.dashboard")
    @Transactional(readOnly = true)
    public TenantDashboardDto dashboard() {
        YearMonth month = YearMonth.now();
        List<Tenant> tenants = tenantStore.findTenants();
        long currentMonthOrders = tenants.stream()
                .mapToLong(tenant -> tenantStore.countOrdersForTenant(tenant.id(), month))
                .sum();
        BigDecimal currentMonthRevenue = tenants.stream()
                .map(tenant -> tenantStore.sumPaidAmountForTenant(tenant.id(), month))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        TenantDashboard dashboard = new TenantDashboard(
                tenantStore.countTenantsByStatus(TenantStatus.ACTIVE),
                tenantStore.countTenantsByStatus(TenantStatus.EXPIRED),
                currentMonthOrders,
                currentMonthRevenue,
                tenants);
        return TenantDtoAssembler.toDashboard(dashboard);
    }

    @WithSpan("tenant.renew")
    @Transactional
    public TenantResponseDto renewTenant(SessionUser operator, Long tenantId, TenantRenewRequestDto request) {
        Tenant tenant = requireTenant(tenantId);
        Tenant saved =
                tenantStore.saveTenant(tenant.renew(normalizedMonths(request == null ? null : request.months())));
        audit(AuditService.TENANT_RENEWED, operator, "tenant:" + tenantId, "expiresAt=" + saved.expiresAt());
        return TenantDtoAssembler.toTenant(saved);
    }

    @WithSpan("tenant.downgrade")
    @Transactional
    public TenantResponseDto downgradeTenant(SessionUser operator, Long tenantId, TenantDowngradeRequestDto request) {
        Tenant tenant = requireTenant(tenantId);
        TenantPlan plan = request == null ? TenantPlan.STARTER : request.plan();
        Tenant saved = tenantStore.saveTenant(tenant.downgrade(plan));
        audit(AuditService.TENANT_DOWNGRADED, operator, "tenant:" + tenantId, "plan=" + saved.plan());
        return TenantDtoAssembler.toTenant(saved);
    }

    @WithSpan("tenant.config-upsert")
    @Transactional
    public TenantConfigDto upsertConfig(SessionUser operator, Long tenantId, TenantConfigRequestDto request) {
        requireTenant(tenantId);
        Long operatorId = AuthenticatedPrincipals.requireUserId(operator);
        TenantConfig saved = tenantStore.saveConfig(
                new TenantConfig(
                        idGenerator.nextId(),
                        tenantId,
                        request.configType(),
                        request.provider(),
                        request.settings(),
                        request.enabled() == null || request.enabled(),
                        LocalDateTime.now(),
                        0L),
                operatorId);
        audit(
                AuditService.TENANT_CONFIG_UPDATED,
                operator,
                "tenant-config:" + saved.id(),
                "tenantId=" + tenantId + ",type=" + saved.configType());
        return TenantDtoAssembler.toConfig(saved);
    }

    @WithSpan("tenant.config-list")
    @Transactional(readOnly = true)
    public List<TenantConfigDto> configs(Long tenantId) {
        requireTenant(tenantId);
        return tenantStore.findConfigs(tenantId).stream()
                .map(TenantDtoAssembler::toConfig)
                .toList();
    }

    @WithSpan("tenant.bill-generate")
    @Transactional
    public TenantBillDto generateBill(SessionUser operator, Long tenantId, TenantBillGenerateRequestDto request) {
        Tenant tenant = requireTenant(tenantId);
        YearMonth month = parseMonth(request == null ? null : request.billingMonth());
        long orderCount = tenantStore.countOrdersForTenant(tenantId, month);
        BigDecimal paidAmount = tenantStore.sumPaidAmountForTenant(tenantId, month);
        TenantBill saved =
                tenantStore.saveBill(TenantBill.generate(idGenerator.nextId(), tenant, month, orderCount, paidAmount));
        audit(
                AuditService.TENANT_BILL_GENERATED,
                operator,
                "tenant-bill:" + saved.id(),
                "tenantId=" + tenantId + ",month=" + month + ",orders=" + orderCount);
        return TenantDtoAssembler.toBill(saved);
    }

    @WithSpan("tenant.bill-list")
    @Transactional(readOnly = true)
    public List<TenantBillDto> bills(Long tenantId) {
        requireTenant(tenantId);
        return tenantStore.findBills(tenantId).stream()
                .map(TenantDtoAssembler::toBill)
                .toList();
    }

    @WithSpan("tenant.export-request")
    @Transactional
    public TenantExportJobDto requestExport(SessionUser operator, Long tenantId, TenantExportRequestDto request) {
        requireTenant(tenantId);
        Long operatorId = AuthenticatedPrincipals.requireUserId(operator);
        TenantDataExportJob saved = tenantStore.saveExportJob(new TenantDataExportJob(
                idGenerator.nextId(),
                tenantId,
                request == null ? "FULL" : request.exportType(),
                TenantExportStatus.REQUESTED,
                null,
                operatorId,
                LocalDateTime.now(),
                null,
                TraceIds.currentOrCreate(),
                null,
                0L));
        audit(
                AuditService.TENANT_EXPORT_REQUESTED,
                operator,
                "tenant-export:" + saved.id(),
                "tenantId=" + tenantId + ",type=" + saved.exportType());
        return TenantDtoAssembler.toExport(saved);
    }

    @WithSpan("tenant.export-list")
    @Transactional(readOnly = true)
    public List<TenantExportJobDto> exports(Long tenantId) {
        requireTenant(tenantId);
        return tenantStore.findExportJobs(tenantId).stream()
                .map(TenantDtoAssembler::toExport)
                .toList();
    }

    @WithSpan("tenant.export-complete-pending")
    @Transactional
    public int completePendingExports() {
        List<TenantDataExportJob> jobs = tenantStore.findPendingExportJobs(20);
        for (TenantDataExportJob job : jobs) {
            String archivePath = "encrypted://tenant/" + job.tenantId() + "/exports/" + job.id() + ".zip.tink";
            TenantDataExportJob completed =
                    tenantStore.saveExportJob(job.complete(archivePath, TraceIds.currentOrCreate()));
            auditService.record(
                    AuditService.TENANT_EXPORT_COMPLETED,
                    AuditService.OUTCOME_SUCCESS,
                    completed.requestedBy(),
                    null,
                    "tenant-export:" + completed.id(),
                    null,
                    "tenantId=" + completed.tenantId() + ",archive=" + completed.encryptedArchivePath());
        }
        return jobs.size();
    }

    private Tenant requireTenant(Long tenantId) {
        return tenantStore
                .findTenant(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Tenant was not found"));
    }

    private static int normalizedMonths(Integer months) {
        return months == null ? DEFAULT_RENEW_MONTHS : Math.max(1, Math.min(36, months));
    }

    private static YearMonth parseMonth(String rawMonth) {
        if (rawMonth == null || rawMonth.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(rawMonth.trim());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "billingMonth must use yyyy-MM");
        }
    }

    private void audit(String event, SessionUser operator, String subject, String detail) {
        auditService.record(
                event,
                AuditService.OUTCOME_SUCCESS,
                operator == null ? null : operator.id(),
                operator == null ? null : operator.role(),
                subject,
                null,
                detail);
    }
}
