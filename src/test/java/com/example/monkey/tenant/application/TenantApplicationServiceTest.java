package com.example.monkey.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.tenant.application.dto.TenantBillGenerateRequestDto;
import com.example.monkey.tenant.application.dto.TenantConfigRequestDto;
import com.example.monkey.tenant.application.dto.TenantCreateRequestDto;
import com.example.monkey.tenant.application.dto.TenantExportRequestDto;
import com.example.monkey.tenant.domain.Tenant;
import com.example.monkey.tenant.domain.TenantConfig;
import com.example.monkey.tenant.domain.TenantConfigType;
import com.example.monkey.tenant.domain.TenantDataExportJob;
import com.example.monkey.tenant.domain.TenantExportStatus;
import com.example.monkey.tenant.domain.TenantPlan;
import com.example.monkey.tenant.domain.TenantStatus;
import com.example.monkey.tenant.domain.TenantStore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenantApplicationServiceTest {

    private static final SessionUser ADMIN = new SessionUser(1L, "ADMIN", false, 1L);
    private final TenantStore tenantStore = mock(TenantStore.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final AuditService auditService = mock(AuditService.class);
    private final TenantApplicationService service =
            new TenantApplicationService(tenantStore, idGenerator, auditService);

    @Test
    void createTenantUsesSnowflakeIdAndAuditsLifecycle() {
        when(idGenerator.nextId()).thenReturn(1200L);
        when(tenantStore.findTenantByCode("merchant-a")).thenReturn(Optional.empty());
        when(tenantStore.saveTenant(any(Tenant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createTenant(
                ADMIN,
                new TenantCreateRequestDto("merchant-a", "Merchant A", TenantPlan.GROWTH, "Ops", "13800000000", 6));

        assertThat(response.id()).isEqualTo(1200L);
        assertThat(response.code()).isEqualTo("merchant-a");
        assertThat(response.status()).isEqualTo(TenantStatus.TRIAL);
        assertThat(response.plan()).isEqualTo(TenantPlan.GROWTH);
        assertThat(response.maskedContactPhone()).isEqualTo("138****0000");
        verify(auditService)
                .record(
                        eq(AuditService.TENANT_CREATED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(1L),
                        eq("ADMIN"),
                        eq("tenant:1200"),
                        isNull(),
                        contains("plan=GROWTH"));
    }

    @Test
    void generateBillAppliesPlanTierAndPaymentReconciliationInputs() {
        Tenant tenant = tenant();
        when(idGenerator.nextId()).thenReturn(1300L);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant));
        when(tenantStore.countOrdersForTenant(200L, YearMonth.of(2026, 7))).thenReturn(10_020L);
        when(tenantStore.sumPaidAmountForTenant(200L, YearMonth.of(2026, 7))).thenReturn(new BigDecimal("8888.88"));
        when(tenantStore.saveBill(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var bill = service.generateBill(ADMIN, 200L, new TenantBillGenerateRequestDto("2026-07"));

        assertThat(bill.orderCount()).isEqualTo(10_020L);
        assertThat(bill.monthlyFee()).isEqualByComparingTo("399.00");
        assertThat(bill.usageFee()).isEqualByComparingTo("0.6000");
        assertThat(bill.totalAmount()).isEqualByComparingTo("399.6000");
        assertThat(bill.paymentAmount()).isEqualByComparingTo("8888.88");
        verify(auditService)
                .record(
                        eq(AuditService.TENANT_BILL_GENERATED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(1L),
                        eq("ADMIN"),
                        eq("tenant-bill:1300"),
                        isNull(),
                        contains("orders=10020"));
    }

    @Test
    void configAndExportWorkflowsPersistAuditableTenantState() {
        when(idGenerator.nextId()).thenReturn(1400L, 1500L);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantStore.saveConfig(any(TenantConfig.class), eq(1L)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tenantStore.saveExportJob(any(TenantDataExportJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var config = service.upsertConfig(
                ADMIN,
                200L,
                new TenantConfigRequestDto(
                        TenantConfigType.ROLLOUT,
                        "argo-rollouts",
                        Map.of("revision", "v2", "canaryWeight", "10"),
                        true));
        var export = service.requestExport(ADMIN, 200L, new TenantExportRequestDto("FULL"));

        assertThat(config.configType()).isEqualTo(TenantConfigType.ROLLOUT);
        assertThat(config.settings()).containsEntry("canaryWeight", "10");
        assertThat(export.status()).isEqualTo(TenantExportStatus.REQUESTED);
        assertThat(export.auditTraceId()).isNotBlank();
        verify(auditService)
                .record(
                        eq(AuditService.TENANT_CONFIG_UPDATED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(1L),
                        eq("ADMIN"),
                        eq("tenant-config:1400"),
                        isNull(),
                        contains("type=ROLLOUT"));
        verify(auditService)
                .record(
                        eq(AuditService.TENANT_EXPORT_REQUESTED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(1L),
                        eq("ADMIN"),
                        eq("tenant-export:1500"),
                        isNull(),
                        contains("type=FULL"));
    }

    @Test
    void scheduledExportCompletionWritesEncryptedArchiveAndAudit() {
        TenantDataExportJob pending = new TenantDataExportJob(
                1600L,
                200L,
                "FULL",
                TenantExportStatus.REQUESTED,
                null,
                1L,
                LocalDateTime.now(),
                null,
                "trace-a",
                null,
                0L);
        when(tenantStore.findPendingExportJobs(20)).thenReturn(List.of(pending));
        when(tenantStore.saveExportJob(any(TenantDataExportJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int completed = service.completePendingExports();

        assertThat(completed).isEqualTo(1);
        verify(auditService)
                .record(
                        eq(AuditService.TENANT_EXPORT_COMPLETED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(1L),
                        isNull(),
                        eq("tenant-export:1600"),
                        isNull(),
                        contains(".zip.tink"));
    }

    private static Tenant tenant() {
        return new Tenant(
                200L,
                "merchant-a",
                "Merchant A",
                TenantStatus.ACTIVE,
                TenantPlan.GROWTH,
                "Ops",
                "13800000000",
                LocalDateTime.now().minusMonths(1),
                LocalDateTime.now().plusMonths(11),
                0L);
    }
}
