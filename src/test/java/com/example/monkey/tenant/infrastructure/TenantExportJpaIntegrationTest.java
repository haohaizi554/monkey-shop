package com.example.monkey.tenant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.tenant.domain.TenantDataExportJob;
import com.example.monkey.tenant.domain.TenantExportProvider;
import com.example.monkey.tenant.domain.TenantExportStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import(JpaTenantStore.class)
@MockitoBean(types = {PiiCryptoService.class, ObjectMapper.class, IdGenerator.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantExportJpaIntegrationTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.parse("2026-07-18T08:00:00");

    private final JpaTenantStore store;
    private final TenantDataExportJobRepository repository;

    @Autowired
    TenantExportJpaIntegrationTest(JpaTenantStore store, TenantDataExportJobRepository repository) {
        this.store = store;
        this.repository = repository;
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAllInBatch();
    }

    @Test
    void assignedIdPersistsAsNewAndStaleProviderSnapshotsCannotOverwriteIt() {
        TenantDataExportJob queued = store.saveExportJob(new TenantDataExportJob(
                500L,
                200L,
                "FULL",
                TenantExportStatus.QUEUED,
                null,
                null,
                1L,
                REQUESTED_AT,
                null,
                "trace-500",
                null,
                0L));

        TenantDataExportJob running = store.saveExportJob(queued.apply(
                new TenantExportProvider.ExportResult(TenantExportStatus.RUNNING, "provider-job-current", null, null),
                REQUESTED_AT.plusSeconds(1)));

        TenantDataExportJob stale = queued.apply(
                new TenantExportProvider.ExportResult(TenantExportStatus.RUNNING, "provider-job-stale", null, null),
                REQUESTED_AT.plusSeconds(2));

        assertThatThrownBy(() -> store.saveExportJob(stale)).isInstanceOf(OptimisticLockingFailureException.class);

        TenantDataExportJob persisted =
                repository.findById(500L).map(this::toDomain).orElseThrow();
        assertThat(queued.version()).isZero();
        assertThat(running.version()).isEqualTo(1L);
        assertThat(persisted.status()).isEqualTo(TenantExportStatus.RUNNING);
        assertThat(persisted.providerJobId()).isEqualTo("provider-job-current");
        assertThat(persisted.version()).isEqualTo(1L);
    }

    private TenantDataExportJob toDomain(TenantDataExportJobEntity entity) {
        return new TenantDataExportJob(
                entity.getId(),
                entity.getTenantId(),
                entity.getExportType(),
                entity.getStatus(),
                entity.getProviderJobId(),
                entity.getArtifactUri(),
                entity.getRequestedBy(),
                entity.getRequestedAt(),
                entity.getCompletedAt(),
                entity.getAuditTraceId(),
                entity.getErrorMessage(),
                entity.getVersion());
    }
}
