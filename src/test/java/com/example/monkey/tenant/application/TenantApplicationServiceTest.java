package com.example.monkey.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.tenant.application.dto.TenantBillGenerateRequestDto;
import com.example.monkey.tenant.application.dto.TenantConfigRequestDto;
import com.example.monkey.tenant.application.dto.TenantCreateRequestDto;
import com.example.monkey.tenant.application.dto.TenantExportRequestDto;
import com.example.monkey.tenant.domain.Tenant;
import com.example.monkey.tenant.domain.TenantConfig;
import com.example.monkey.tenant.domain.TenantConfigType;
import com.example.monkey.tenant.domain.TenantDataExportJob;
import com.example.monkey.tenant.domain.TenantExportProvider;
import com.example.monkey.tenant.domain.TenantExportStatus;
import com.example.monkey.tenant.domain.TenantPlan;
import com.example.monkey.tenant.domain.TenantStatus;
import com.example.monkey.tenant.domain.TenantStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.OptimisticLockingFailureException;

class TenantApplicationServiceTest {

    private static final SessionUser ADMIN = new SessionUser(1L, "ADMIN", false, 1L);
    private final TenantStore tenantStore = mock(TenantStore.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final AuditService auditService = mock(AuditService.class);
    private final TenantExportProvider tenantExportProvider = mock(TenantExportProvider.class);
    private final TenantApplicationService service =
            new TenantApplicationService(tenantStore, idGenerator, auditService, tenantExportProvider);

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
    void configAndUnavailableExportWorkflowsPersistAuditableTenantState() {
        when(idGenerator.nextId()).thenReturn(1400L, 1500L);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantStore.saveConfig(any(TenantConfig.class), eq(1L)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tenantStore.saveExportJob(any(TenantDataExportJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tenantExportProvider.submit(any(TenantExportProvider.ExportRequest.class)))
                .thenReturn(new TenantExportProvider.ExportResult(
                        TenantExportStatus.UNAVAILABLE, null, null, "provider not configured"));

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
        assertThat(export.status()).isEqualTo(TenantExportStatus.UNAVAILABLE);
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
    void scheduledExportCompletionUsesOnlyTheProviderArtifactAndAudits() {
        TenantDataExportJob pending = new TenantDataExportJob(
                1600L,
                200L,
                "FULL",
                TenantExportStatus.QUEUED,
                "provider-job-1600",
                null,
                1L,
                LocalDateTime.now(),
                null,
                "trace-a",
                null,
                0L);
        when(tenantStore.findPendingExportJobs(20)).thenReturn(List.of(pending));
        when(tenantExportProvider.refresh(pending))
                .thenReturn(new TenantExportProvider.ExportResult(
                        TenantExportStatus.SUCCEEDED, "provider-job-1600", "s3://tenant-exports/200/1600.tink", null));
        when(tenantStore.saveExportJob(any(TenantDataExportJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int completed = service.completePendingExports();

        assertThat(completed).isEqualTo(1);
        verify(tenantStore)
                .saveExportJob(argThat(job -> job.status() == TenantExportStatus.SUCCEEDED
                        && "provider-job-1600".equals(job.providerJobId())
                        && "s3://tenant-exports/200/1600.tink".equals(job.artifactUri())));
        verify(auditService)
                .record(
                        eq(AuditService.TENANT_EXPORT_COMPLETED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(1L),
                        isNull(),
                        eq("tenant-export:1600"),
                        isNull(),
                        contains("artifactAvailable=true"));
    }

    @Test
    void providerCannotClaimSuccessWithoutAnArtifact() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TenantExportProvider.ExportResult(
                        TenantExportStatus.SUCCEEDED, "provider-job-1", null, null));
    }

    @Test
    void exportSubmissionPersistsARecoverableIntentBeforeCallingTheProvider() {
        when(idGenerator.nextId()).thenReturn(1700L);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantExportProvider.submit(any(TenantExportProvider.ExportRequest.class)))
                .thenReturn(new TenantExportProvider.ExportResult(
                        TenantExportStatus.QUEUED, "provider-job-1700", null, null));
        when(tenantStore.saveExportJob(any(TenantDataExportJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var export = service.requestExport(ADMIN, 200L, new TenantExportRequestDto("FULL"));

        InOrder submissionOrder = inOrder(tenantStore, tenantExportProvider);
        submissionOrder
                .verify(tenantStore)
                .saveExportJob(
                        argThat(job -> job.status() == TenantExportStatus.QUEUED && job.providerJobId() == null));
        submissionOrder.verify(tenantExportProvider).submit(any(TenantExportProvider.ExportRequest.class));
        submissionOrder
                .verify(tenantStore)
                .saveExportJob(argThat(job -> "provider-job-1700".equals(job.providerJobId())));
        assertThat(export.status()).isEqualTo(TenantExportStatus.QUEUED);
    }

    @Test
    void concurrentSchedulerProgressReturnsThePersistedProviderState() {
        when(idGenerator.nextId()).thenReturn(1750L);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantExportProvider.submit(any(TenantExportProvider.ExportRequest.class)))
                .thenReturn(new TenantExportProvider.ExportResult(
                        TenantExportStatus.RUNNING, "provider-job-1750", null, null));
        when(tenantStore.saveExportJob(any(TenantDataExportJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new OptimisticLockingFailureException("scheduler won"));
        TenantDataExportJob concurrent = new TenantDataExportJob(
                1750L,
                200L,
                "FULL",
                TenantExportStatus.RUNNING,
                "provider-job-1750",
                null,
                1L,
                LocalDateTime.now(),
                null,
                "trace-concurrent",
                null,
                1L);
        when(tenantStore.findExportJob(200L, 1750L)).thenReturn(Optional.of(concurrent));

        var export = service.requestExport(ADMIN, 200L, new TenantExportRequestDto("FULL"));

        assertThat(export.status()).isEqualTo(TenantExportStatus.RUNNING);
        assertThat(export.version()).isEqualTo(1L);
        verify(tenantStore).findExportJob(200L, 1750L);
    }

    @Test
    void providerExceptionsLeaveTheQueuedIntentRecoverableWithoutLeakingTheCause() {
        when(idGenerator.nextId()).thenReturn(1800L);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantExportProvider.submit(any(TenantExportProvider.ExportRequest.class)))
                .thenThrow(new IllegalStateException("secret provider credential was rejected"));
        when(tenantStore.saveExportJob(any(TenantDataExportJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var export = service.requestExport(ADMIN, 200L, new TenantExportRequestDto("FULL"));

        assertThat(export.status()).isEqualTo(TenantExportStatus.QUEUED);
        assertThat(export.artifactAvailable()).isFalse();
        verify(tenantStore)
                .saveExportJob(argThat(job -> job.status() == TenantExportStatus.QUEUED
                        && job.providerJobId() == null
                        && job.errorMessage() == null));
    }

    @Test
    void queuedSubmissionRecoveryUsesSubmitAndKeepsTheLocalJobIdentity() {
        TenantDataExportJob queued = new TenantDataExportJob(
                1900L,
                200L,
                "FULL",
                TenantExportStatus.QUEUED,
                null,
                null,
                1L,
                LocalDateTime.now(),
                null,
                "trace-retry",
                null,
                0L);
        when(tenantStore.findPendingExportJobs(20)).thenReturn(List.of(queued));
        when(tenantExportProvider.submit(any(TenantExportProvider.ExportRequest.class)))
                .thenReturn(new TenantExportProvider.ExportResult(
                        TenantExportStatus.RUNNING, "provider-job-1900", null, null));
        when(tenantStore.saveExportJob(any(TenantDataExportJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.completePendingExports();

        verify(tenantExportProvider)
                .submit(argThat(request -> request.jobId().equals(1900L)
                        && request.tenantId().equals(200L)
                        && request.auditTraceId().equals("trace-retry")));
        verify(tenantExportProvider, never()).refresh(any());
        verify(tenantStore)
                .saveExportJob(argThat(job ->
                        job.status() == TenantExportStatus.RUNNING && "provider-job-1900".equals(job.providerJobId())));
    }

    @Test
    void pollingCannotReplaceTheProviderJobIdentity() {
        TenantDataExportJob running = new TenantDataExportJob(
                2000L,
                200L,
                "FULL",
                TenantExportStatus.RUNNING,
                "provider-job-a",
                null,
                1L,
                LocalDateTime.now(),
                null,
                "trace-identity",
                null,
                0L);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> running.apply(
                        new TenantExportProvider.ExportResult(
                                TenantExportStatus.SUCCEEDED,
                                "provider-job-b",
                                "s3://tenant-exports/200/wrong.tink",
                                null),
                        LocalDateTime.now()))
                .withMessageContaining("provider job");
    }

    @Test
    void terminalProviderResultMayOmitAnAlreadyPersistedProviderIdentity() {
        TenantDataExportJob running = new TenantDataExportJob(
                2050L,
                200L,
                "FULL",
                TenantExportStatus.RUNNING,
                "provider-job-a",
                null,
                1L,
                LocalDateTime.now(),
                null,
                "trace-terminal",
                null,
                0L);

        TenantDataExportJob failed = running.apply(
                new TenantExportProvider.ExportResult(TenantExportStatus.FAILED, null, null, "provider failed"),
                LocalDateTime.now());

        assertThat(failed.status()).isEqualTo(TenantExportStatus.FAILED);
        assertThat(failed.providerJobId()).isEqualTo("provider-job-a");
        assertThat(failed.completedAt()).isNotNull();
    }

    @Test
    void publicExportDtoExcludesProviderInternals() throws Exception {
        TenantDataExportJob failed = new TenantDataExportJob(
                2100L,
                200L,
                "FULL",
                TenantExportStatus.FAILED,
                "provider-job-secret",
                null,
                1L,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "trace-public-contract",
                "credential=secret, endpoint=http://internal-provider",
                0L);

        String json =
                new ObjectMapper().findAndRegisterModules().writeValueAsString(TenantDtoAssembler.toExport(failed));

        assertThat(TenantDtoAssembler.toExport(failed).artifactDownloadUri()).isNull();
        assertThat(json)
                .contains("\"status\":\"FAILED\"", "\"artifactAvailable\":false")
                .doesNotContain(
                        "providerJobId",
                        "artifactUri",
                        "encryptedArchivePath",
                        "errorMessage",
                        "credential",
                        "internal-provider");
    }

    @Test
    void successfulPublicExportDtoExposesOnlyTheSameOriginArtifactDownloadUri() throws Exception {
        TenantDataExportJob succeeded = new TenantDataExportJob(
                2200L,
                200L,
                "FULL",
                TenantExportStatus.SUCCEEDED,
                "provider-job-secret",
                "file:///C:/tenant-exports/private/2200.tink?accessKey=storage-secret",
                1L,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "trace-download-contract",
                null,
                0L);

        String json =
                new ObjectMapper().findAndRegisterModules().writeValueAsString(TenantDtoAssembler.toExport(succeeded));

        assertThat(json)
                .contains("\"status\":\"SUCCEEDED\"")
                .contains("\"artifactAvailable\":true")
                .contains("\"artifactDownloadUri\":\"/api/v1/tenants/200/exports/2200/artifact\"")
                .doesNotContain(
                        "artifactUri", "providerJobId", "file:///", "C:/tenant-exports", "accessKey", "storage-secret");
    }

    @Test
    void completedArtifactDownloadIsTenantScopedAndStreamsProviderBytes() throws Exception {
        TenantDataExportJob succeeded = succeededExport(2300L, 200L);
        byte[] encryptedArchive = "encrypted-tenant-export".getBytes(StandardCharsets.UTF_8);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantStore.findExportJob(200L, 2300L)).thenReturn(Optional.of(succeeded));
        when(tenantExportProvider.downloadArtifact(succeeded))
                .thenReturn(new TenantExportProvider.ExportArtifact(
                        new ByteArrayInputStream(encryptedArchive), encryptedArchive.length));

        try (TenantExportProvider.ExportArtifact artifact = service.downloadExportArtifact(200L, 2300L)) {
            assertThat(artifact.content().readAllBytes()).isEqualTo(encryptedArchive);
            assertThat(artifact.contentLength()).isEqualTo(encryptedArchive.length);
        }

        verify(tenantStore).findExportJob(200L, 2300L);
        verify(tenantExportProvider).downloadArtifact(succeeded);
    }

    @Test
    void unfinishedExportCannotBeDownloaded() {
        TenantDataExportJob running = new TenantDataExportJob(
                2400L,
                200L,
                "FULL",
                TenantExportStatus.RUNNING,
                "provider-job-2400",
                null,
                1L,
                LocalDateTime.now(),
                null,
                "trace-running",
                null,
                0L);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantStore.findExportJob(200L, 2400L)).thenReturn(Optional.of(running));

        assertThatThrownBy(() -> service.downloadExportArtifact(200L, 2400L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(tenantExportProvider, never()).downloadArtifact(any());
    }

    @Test
    void providerDownloadFailureReturnsAGenericErrorWithoutLeakingCredentials() {
        TenantDataExportJob succeeded = succeededExport(2500L, 200L);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantStore.findExportJob(200L, 2500L)).thenReturn(Optional.of(succeeded));
        when(tenantExportProvider.downloadArtifact(succeeded))
                .thenThrow(new IllegalStateException("s3://access-key:storage-secret@internal/export.tink"));

        assertThatThrownBy(() -> service.downloadExportArtifact(200L, 2500L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(error.getMessage())
                            .isEqualTo("Tenant export artifact is temporarily unavailable")
                            .doesNotContain("access-key", "storage-secret", "internal");
                });
    }

    @Test
    void artifactLookupCannotCrossTheRequestedTenantBoundary() {
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantStore.findExportJob(200L, 2600L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadExportArtifact(200L, 2600L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(tenantStore).findExportJob(200L, 2600L);
        verify(tenantExportProvider, never()).downloadArtifact(any());
    }

    private static TenantDataExportJob succeededExport(Long jobId, Long tenantId) {
        return new TenantDataExportJob(
                jobId,
                tenantId,
                "FULL",
                TenantExportStatus.SUCCEEDED,
                "provider-job-" + jobId,
                "s3://private-tenant-exports/" + tenantId + "/" + jobId + ".tink?credential=secret",
                1L,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "trace-" + jobId,
                null,
                0L);
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
