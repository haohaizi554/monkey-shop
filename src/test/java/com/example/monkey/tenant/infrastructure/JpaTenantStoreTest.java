package com.example.monkey.tenant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.tenant.domain.Tenant;
import com.example.monkey.tenant.domain.TenantConfig;
import com.example.monkey.tenant.domain.TenantConfigType;
import com.example.monkey.tenant.domain.TenantDataExportJob;
import com.example.monkey.tenant.domain.TenantExportStatus;
import com.example.monkey.tenant.domain.TenantPlan;
import com.example.monkey.tenant.domain.TenantStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class JpaTenantStoreTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantConfigRepository configRepository;

    @Mock
    private TenantConfigHistoryRepository historyRepository;

    @Mock
    private TenantBillRepository billRepository;

    @Mock
    private TenantDataExportJobRepository exportJobRepository;

    @Mock
    private PiiCryptoService piiCryptoService;

    @Mock
    private IdGenerator idGenerator;

    private JpaTenantStore store;

    @BeforeEach
    void setUp() {
        store = new JpaTenantStore(
                tenantRepository,
                configRepository,
                historyRepository,
                billRepository,
                exportJobRepository,
                piiCryptoService,
                new ObjectMapper().findAndRegisterModules(),
                idGenerator);
    }

    @Test
    void saveTenantEncryptsContactPhoneAndBlindIndex() {
        when(tenantRepository.findById(200L)).thenReturn(Optional.empty());
        when(piiCryptoService.encrypt("13800000000")).thenReturn("enc-phone");
        when(piiCryptoService.blindIndex("13800000000")).thenReturn("phone-hmac");
        when(piiCryptoService.decrypt("enc-phone")).thenReturn("13800000000");
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Tenant saved = store.saveTenant(new Tenant(
                200L,
                "merchant-a",
                "Merchant A",
                TenantStatus.ACTIVE,
                TenantPlan.GROWTH,
                "Ops",
                "13800000000",
                LocalDateTime.parse("2026-07-01T10:00:00"),
                LocalDateTime.parse("2027-07-01T10:00:00"),
                0L));

        TenantEntity entity = captureTenant();
        assertThat(entity.getEncryptedContactPhone()).isEqualTo("enc-phone");
        assertThat(entity.getContactPhoneHmac()).isEqualTo("phone-hmac");
        assertThat(saved.contactPhone()).isEqualTo("13800000000");
    }

    @Test
    void saveConfigUpsertsAndRecordsConfigHistory() {
        when(configRepository.findByTenantIdAndConfigTypeAndProvider(200L, TenantConfigType.ROLLOUT, "argo-rollouts"))
                .thenReturn(Optional.empty());
        when(configRepository.save(any(TenantConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idGenerator.nextId()).thenReturn(9000L);

        TenantConfig saved = store.saveConfig(
                new TenantConfig(
                        300L,
                        200L,
                        TenantConfigType.ROLLOUT,
                        "argo-rollouts",
                        Map.of("revision", "v2", "canaryWeight", "10"),
                        true,
                        LocalDateTime.parse("2026-07-04T10:00:00"),
                        0L),
                1L);

        TenantConfigEntity entity = captureConfig();
        TenantConfigHistoryEntity history = captureHistory();
        assertThat(entity.getSettingsJson()).contains("canaryWeight", "10");
        assertThat(saved.settings()).containsEntry("revision", "v2");
        assertThat(history.getId()).isEqualTo(9000L);
        assertThat(history.getTenantId()).isEqualTo(200L);
        assertThat(history.getConfigId()).isEqualTo(300L);
        assertThat(history.getNewSettingsJson()).contains("canaryWeight", "revision");
    }

    @Test
    void serviceableTenantLookupAllowsOnlyOperationalStatuses() {
        List<TenantStatus> serviceableStatuses =
                List.of(TenantStatus.TRIAL, TenantStatus.ACTIVE, TenantStatus.DOWNGRADED);
        for (int index = 0; index < serviceableStatuses.size(); index++) {
            long tenantId = index + 1L;
            TenantEntity entity = new TenantEntity();
            entity.setId(tenantId);
            entity.setStatus(serviceableStatuses.get(index));
            entity.setExpiresAt(LocalDateTime.now().plusDays(1));
            when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(entity));

            assertThat(store.isServiceableTenant(tenantId)).isTrue();
        }

        for (TenantStatus status : List.of(TenantStatus.EXPIRED, TenantStatus.SUSPENDED)) {
            long tenantId = 100L + status.ordinal();
            TenantEntity entity = new TenantEntity();
            entity.setId(tenantId);
            entity.setStatus(status);
            entity.setExpiresAt(LocalDateTime.now().plusDays(1));
            when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(entity));

            assertThat(store.isServiceableTenant(tenantId)).isFalse();
        }
        TenantEntity staleActiveTenant = new TenantEntity();
        staleActiveTenant.setId(200L);
        staleActiveTenant.setStatus(TenantStatus.ACTIVE);
        staleActiveTenant.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(tenantRepository.findById(200L)).thenReturn(Optional.of(staleActiveTenant));
        assertThat(store.isServiceableTenant(200L)).isFalse();

        when(tenantRepository.findById(999L)).thenReturn(Optional.empty());
        assertThat(store.isServiceableTenant(999L)).isFalse();
        assertThat(store.isServiceableTenant(null)).isFalse();
    }

    @Test
    void exportJobsRoundTripOnlyProviderOwnedStateAndArtifacts() {
        when(exportJobRepository.findById(500L)).thenReturn(Optional.empty());
        when(exportJobRepository.saveAndFlush(any(TenantDataExportJobEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LocalDateTime requestedAt = LocalDateTime.parse("2026-07-18T08:00:00");
        LocalDateTime completedAt = LocalDateTime.parse("2026-07-18T08:01:00");

        TenantDataExportJob saved = store.saveExportJob(new TenantDataExportJob(
                500L,
                200L,
                "FULL",
                TenantExportStatus.SUCCEEDED,
                "provider-job-500",
                "s3://tenant-exports/200/500.tink",
                1L,
                requestedAt,
                completedAt,
                "trace-500",
                null,
                0L));

        ArgumentCaptor<TenantDataExportJobEntity> captor = ArgumentCaptor.forClass(TenantDataExportJobEntity.class);
        verify(exportJobRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getProviderJobId()).isEqualTo("provider-job-500");
        assertThat(captor.getValue().getArtifactUri()).isEqualTo("s3://tenant-exports/200/500.tink");
        assertThat(captor.getValue().getVersion()).isNull();
        assertThat(saved.providerJobId()).isEqualTo("provider-job-500");
        assertThat(saved.artifactUri()).isEqualTo("s3://tenant-exports/200/500.tink");
    }

    @Test
    void staleExportSnapshotCannotOverwriteTheCurrentProviderState() {
        TenantDataExportJobEntity current = new TenantDataExportJobEntity();
        current.setId(501L);
        current.setTenantId(200L);
        current.setStatus(TenantExportStatus.RUNNING);
        current.setProviderJobId("provider-job-current");
        current.setVersion(2L);
        when(exportJobRepository.findById(501L)).thenReturn(Optional.of(current));

        TenantDataExportJob staleUpdate = new TenantDataExportJob(
                501L,
                200L,
                "FULL",
                TenantExportStatus.RUNNING,
                "provider-job-stale",
                null,
                1L,
                LocalDateTime.parse("2026-07-18T08:00:00"),
                null,
                "trace-stale",
                null,
                2L);

        assertThatThrownBy(() -> store.saveExportJob(staleUpdate))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(exportJobRepository, never()).saveAndFlush(any(TenantDataExportJobEntity.class));
        assertThat(current.getProviderJobId()).isEqualTo("provider-job-current");
    }

    @Test
    void pendingExportLookupIncludesQueuedAndRunningButNoTerminalState() {
        when(exportJobRepository.findByStatusInOrderByRequestedAtAsc(
                        eq(List.of(TenantExportStatus.QUEUED, TenantExportStatus.RUNNING)), eq(PageRequest.of(0, 7))))
                .thenReturn(List.of());

        assertThat(store.findPendingExportJobs(7)).isEmpty();

        verify(exportJobRepository)
                .findByStatusInOrderByRequestedAtAsc(
                        List.of(TenantExportStatus.QUEUED, TenantExportStatus.RUNNING), PageRequest.of(0, 7));
    }

    private TenantEntity captureTenant() {
        ArgumentCaptor<TenantEntity> captor = ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).save(captor.capture());
        return captor.getValue();
    }

    private TenantConfigEntity captureConfig() {
        ArgumentCaptor<TenantConfigEntity> captor = ArgumentCaptor.forClass(TenantConfigEntity.class);
        verify(configRepository).save(captor.capture());
        return captor.getValue();
    }

    private TenantConfigHistoryEntity captureHistory() {
        ArgumentCaptor<TenantConfigHistoryEntity> captor = ArgumentCaptor.forClass(TenantConfigHistoryEntity.class);
        verify(historyRepository).save(captor.capture());
        return captor.getValue();
    }
}
