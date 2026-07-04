package com.example.monkey.tenant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.tenant.domain.Tenant;
import com.example.monkey.tenant.domain.TenantConfig;
import com.example.monkey.tenant.domain.TenantConfigType;
import com.example.monkey.tenant.domain.TenantPlan;
import com.example.monkey.tenant.domain.TenantStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
