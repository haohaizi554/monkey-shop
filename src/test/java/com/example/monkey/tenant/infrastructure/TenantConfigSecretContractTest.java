package com.example.monkey.tenant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.tenant.application.TenantApplicationService;
import com.example.monkey.tenant.application.TenantDtoAssembler;
import com.example.monkey.tenant.application.dto.TenantConfigRequestDto;
import com.example.monkey.tenant.domain.Tenant;
import com.example.monkey.tenant.domain.TenantConfig;
import com.example.monkey.tenant.domain.TenantConfigType;
import com.example.monkey.tenant.domain.TenantExportProvider;
import com.example.monkey.tenant.domain.TenantPlan;
import com.example.monkey.tenant.domain.TenantStatus;
import com.example.monkey.tenant.domain.TenantStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;

class TenantConfigSecretContractTest {

    private static final String MASKED_VALUE = "****";
    private static final TypeReference<Map<String, String>> SETTINGS = new TypeReference<>() {};

    private final TenantConfigRepository configRepository = mock(TenantConfigRepository.class);
    private final TenantConfigHistoryRepository historyRepository = mock(TenantConfigHistoryRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final TenantBillRepository billRepository = mock(TenantBillRepository.class);
    private final TenantDataExportJobRepository exportJobRepository = mock(TenantDataExportJobRepository.class);
    private final PiiCryptoService cryptoService = mock(PiiCryptoService.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private JpaTenantStore store;

    @BeforeEach
    void setUp() {
        when(cryptoService.encryptionEnabled()).thenReturn(true);
        when(cryptoService.encrypt(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value.startsWith("enc:")
                    ? value
                    : "enc:"
                            + Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        });
        when(cryptoService.decrypt(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value.startsWith("enc:")
                    ? new String(
                            Base64.getUrlDecoder().decode(value.substring("enc:".length())), StandardCharsets.UTF_8)
                    : value;
        });
        store = new JpaTenantStore(
                tenantRepository,
                configRepository,
                historyRepository,
                billRepository,
                exportJobRepository,
                cryptoService,
                objectMapper,
                idGenerator);
    }

    @Test
    void newConfigAndHistorySnapshotsNeverContainRawSettingValues() throws Exception {
        TenantConfigEntity existing = configEntity(
                300L,
                objectMapper.writeValueAsString(Map.of("apiKey", "legacy-secret", "merchantId", "legacy-merchant")));
        when(configRepository.findByTenantIdAndConfigTypeAndProvider(200L, TenantConfigType.PAYMENT, "wechat"))
                .thenReturn(Optional.of(existing));
        when(configRepository.save(any(TenantConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idGenerator.nextId()).thenReturn(9000L);

        store.saveConfig(
                new TenantConfig(
                        300L,
                        200L,
                        TenantConfigType.PAYMENT,
                        "wechat",
                        Map.of("apiKey", "sample-secret", "merchantId", "merchant-123", "unknown", "private"),
                        true,
                        LocalDateTime.parse("2026-08-28T10:00:00"),
                        0L),
                1L);

        TenantConfigEntity saved = captureConfig();
        TenantConfigHistoryEntity history = captureHistory();
        assertThat(saved.getSettingsJson()).doesNotContain("sample-secret", "merchant-123");
        assertThat(history.getOldSettingsJson()).doesNotContain("legacy-secret", "legacy-merchant");
        assertThat(history.getNewSettingsJson()).doesNotContain("sample-secret", "merchant-123");
        assertThat(objectMapper.readValue(saved.getSettingsJson(), SETTINGS))
                .allSatisfy((key, value) -> assertThat(value).startsWith("enc:"));
        assertThat(objectMapper.readValue(history.getOldSettingsJson(), SETTINGS))
                .allSatisfy((key, value) -> assertThat(value).startsWith("enc:"));
    }

    @Test
    void configDtoLeavesOnlyExplicitSafeMetadataReadable() {
        TenantConfigDtoAssert.assertMasked(
                TenantDtoAssembler.toConfig(new TenantConfig(
                        300L,
                        200L,
                        TenantConfigType.PAYMENT,
                        "wechat",
                        Map.of("merchantId", "merchant-123", "apiKey", "sample-secret", "unknown", "private"),
                        true,
                        LocalDateTime.parse("2026-08-28T10:00:00"),
                        0L)),
                MASKED_VALUE);
    }

    @Test
    void configPutResponseMasksSecretsWhileKeepingSafeMetadataReadable() {
        TenantStore tenantStore = mock(TenantStore.class);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        when(tenantStore.saveConfig(any(TenantConfig.class), eq(1L)))
                .thenReturn(new TenantConfig(
                        300L,
                        200L,
                        TenantConfigType.PAYMENT,
                        "wechat",
                        Map.of("apiKey", "sample-secret", "merchantId", "merchant-123", "unknown", "private"),
                        true,
                        LocalDateTime.parse("2026-08-28T10:00:00"),
                        0L));
        TenantApplicationService service = new TenantApplicationService(
                tenantStore, idGenerator, mock(AuditService.class), mock(TenantExportProvider.class));

        var response = service.upsertConfig(
                new SessionUser(1L, "ADMIN", false, 200L),
                200L,
                new TenantConfigRequestDto(
                        TenantConfigType.PAYMENT,
                        "wechat",
                        Map.of("apiKey", "sample-secret", "merchantId", "merchant-123"),
                        true));

        TenantConfigDtoAssert.assertMasked(response, MASKED_VALUE);
    }

    @Test
    void maskedReplayPreservesExistingSecretButChangedInputReplacesIt() throws Exception {
        TenantConfigEntity existing = configEntity(
                300L,
                objectMapper.writeValueAsString(
                        Map.of("apiKey", ciphertext("old-secret"), "merchantId", ciphertext("old-merchant"))));
        when(configRepository.findByTenantIdAndConfigTypeAndProvider(200L, TenantConfigType.PAYMENT, "wechat"))
                .thenReturn(Optional.of(existing));
        when(configRepository.save(any(TenantConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idGenerator.nextId()).thenReturn(9001L, 9002L);

        store.saveConfig(config(300L, Map.of("apiKey", MASKED_VALUE, "merchantId", "merchant-123")), 1L);
        Map<String, String> preserved = objectMapper.readValue(captureConfig().getSettingsJson(), SETTINGS);
        assertThat(preserved.get("apiKey")).isEqualTo(ciphertext("old-secret"));

        store.saveConfig(config(300L, Map.of("apiKey", "new-secret", "merchantId", "merchant-123")), 1L);
        Map<String, String> replaced = objectMapper.readValue(captureConfig().getSettingsJson(), SETTINGS);
        assertThat(replaced.get("apiKey")).isEqualTo(ciphertext("new-secret"));
    }

    @Test
    void maskedReplayForANonexistentSettingIsRejected() throws Exception {
        when(configRepository.findByTenantIdAndConfigTypeAndProvider(200L, TenantConfigType.PAYMENT, "wechat"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> store.saveConfig(config(300L, Map.of("apiKey", MASKED_VALUE)), 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void omittedSettingIsRemovedInsteadOfBeingPreservedImplicitly() throws Exception {
        TenantConfigEntity existing = configEntity(
                300L,
                objectMapper.writeValueAsString(
                        Map.of("apiKey", ciphertext("old-secret"), "merchantId", ciphertext("old-merchant"))));
        when(configRepository.findByTenantIdAndConfigTypeAndProvider(200L, TenantConfigType.PAYMENT, "wechat"))
                .thenReturn(Optional.of(existing));
        when(configRepository.save(any(TenantConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idGenerator.nextId()).thenReturn(9003L);

        store.saveConfig(config(300L, Map.of("merchantId", "merchant-123")), 1L);

        Map<String, String> saved = objectMapper.readValue(captureConfig().getSettingsJson(), SETTINGS);
        assertThat(saved).containsOnlyKeys("merchantId");
    }

    @Test
    void legacyRowsAreReconciledWithCasAndSecondRunIsIdempotent() throws Exception {
        String legacyConfig = objectMapper.writeValueAsString(Map.of("apiKey", "legacy-secret"));
        String legacyOld = objectMapper.writeValueAsString(Map.of("apiKey", "legacy-old"));
        String legacyNew = objectMapper.writeValueAsString(Map.of("apiKey", "legacy-new"));
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate()
                .thenRows(row("id", 1L, "settings_json", legacyConfig))
                .thenRows()
                .thenRows(row("id", 10L, "old_settings_json", legacyOld, "new_settings_json", legacyNew))
                .thenRows();
        TenantConfigPlaintextReconciliationService reconciler =
                new TenantConfigPlaintextReconciliationService(jdbcTemplate, cryptoService, null, 1);

        TenantConfigPlaintextReconciliationService.ReconciliationReport report = reconciler.reconcileLegacyPlaintext();

        assertThat(report.configs()).isEqualTo(1);
        assertThat(report.history()).isEqualTo(1);
        assertThat(report.total()).isEqualTo(2);
        assertThat(jdbcTemplate.updates).hasSize(2);
        assertThat(jdbcTemplate.updates.get(0).sql())
                .contains("UPDATE `tenant_config`")
                .contains("AND `settings_json` = ?");
        assertThat(jdbcTemplate.updates.get(1).sql())
                .contains("UPDATE `tenant_config_history`")
                .contains("AND `old_settings_json` <=> ?")
                .contains("AND `new_settings_json` <=> ?");
        assertThat((String) jdbcTemplate.updates.get(0).args()[0]).doesNotContain("legacy-secret");
        assertThat((String) jdbcTemplate.updates.get(1).args()[0]).doesNotContain("legacy-old");
        assertThat((String) jdbcTemplate.updates.get(1).args()[1]).doesNotContain("legacy-new");

        jdbcTemplate
                .thenRows(row("id", 1L, "settings_json", "{\"apiKey\":\"enc:bGVnYWN5LXNlY3JldA\"}"))
                .thenRows()
                .thenRows(row(
                        "id",
                        10L,
                        "old_settings_json",
                        "{\"apiKey\":\"enc:bGVnYWN5LW9sZA\"}",
                        "new_settings_json",
                        "{\"apiKey\":\"enc:bGVnYWN5LW5ldw\"}"))
                .thenRows();
        TenantConfigPlaintextReconciliationService.ReconciliationReport secondRun =
                reconciler.reconcileLegacyPlaintext();
        assertThat(secondRun.total()).isZero();
        assertThat(jdbcTemplate.updates).hasSize(2);
    }

    @Test
    void malformedSettingMapsAreRejectedAsBusinessValidationErrors() {
        TenantStore tenantStore = mock(TenantStore.class);
        when(tenantStore.findTenant(200L)).thenReturn(Optional.of(tenant()));
        TenantApplicationService service = new TenantApplicationService(
                tenantStore, mock(IdGenerator.class), mock(AuditService.class), mock(TenantExportProvider.class));
        Map<String, String> blankKey = new HashMap<>();
        blankKey.put(" ", "value");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("key", null);
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "value");
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 65; index++) {
            tooMany.put("key-" + index, "value");
        }

        for (Map<String, String> invalid : Arrays.asList(
                null,
                blankKey,
                nullValue,
                nullKey,
                Map.of("key", " "),
                Map.of("k".repeat(129), "value"),
                Map.of("key", "v".repeat(4097)),
                tooMany)) {
            assertThatThrownBy(() -> service.upsertConfig(
                            new SessionUser(1L, "ADMIN", false, 200L),
                            200L,
                            new TenantConfigRequestDto(TenantConfigType.PAYMENT, "wechat", invalid, true)))
                    .isInstanceOf(BusinessException.class);
        }
    }

    private TenantConfigEntity configEntity(Long id, String settingsJson) {
        TenantConfigEntity entity = new TenantConfigEntity();
        entity.setId(id);
        entity.setTenantId(200L);
        entity.setConfigType(TenantConfigType.PAYMENT);
        entity.setProvider("wechat");
        entity.setSettingsJson(settingsJson);
        entity.setEnabled(true);
        entity.setUpdatedAt(LocalDateTime.parse("2026-08-27T10:00:00"));
        entity.setVersion(0L);
        return entity;
    }

    private static String ciphertext(String value) {
        return "enc:" + Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private TenantConfig config(Long id, Map<String, String> settings) {
        return new TenantConfig(
                id,
                200L,
                TenantConfigType.PAYMENT,
                "wechat",
                settings,
                true,
                LocalDateTime.parse("2026-08-28T10:00:00"),
                0L);
    }

    private TenantConfigEntity captureConfig() {
        ArgumentCaptor<TenantConfigEntity> captor = ArgumentCaptor.forClass(TenantConfigEntity.class);
        org.mockito.Mockito.verify(configRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private TenantConfigHistoryEntity captureHistory() {
        ArgumentCaptor<TenantConfigHistoryEntity> captor = ArgumentCaptor.forClass(TenantConfigHistoryEntity.class);
        org.mockito.Mockito.verify(historyRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private static Tenant tenant() {
        LocalDateTime now = LocalDateTime.parse("2026-08-28T10:00:00");
        return new Tenant(
                200L,
                "merchant-a",
                "Merchant A",
                TenantStatus.ACTIVE,
                TenantPlan.STARTER,
                "Ops",
                "13800000000",
                now,
                now.plusDays(1),
                0L);
    }

    private static final class TenantConfigDtoAssert {

        private TenantConfigDtoAssert() {}

        static void assertMasked(com.example.monkey.tenant.application.dto.TenantConfigDto dto, String mask) {
            assertThat(dto.settings()).containsEntry("merchantId", "merchant-123");
            assertThat(dto.settings()).containsEntry("apiKey", mask);
            assertThat(dto.settings()).containsEntry("unknown", mask);
        }
    }

    private static Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            row.put((String) entries[index], entries[index + 1]);
        }
        return row;
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {

        private final ArrayDeque<List<Map<String, Object>>> queryResults = new ArrayDeque<>();
        private final List<QueryCall> queryCalls = new ArrayList<>();
        private final List<Update> updates = new ArrayList<>();

        @SafeVarargs
        final FakeJdbcTemplate thenRows(Map<String, Object>... rows) {
            queryResults.add(List.of(rows));
            return this;
        }

        @Override
        public List<Map<String, Object>> queryForList(@NonNull String sql, @NonNull Object... args) {
            queryCalls.add(new QueryCall(sql, Arrays.copyOf(args, args.length)));
            return queryResults.removeFirst();
        }

        @Override
        public int update(@NonNull String sql, @NonNull Object... args) {
            updates.add(new Update(sql, Arrays.copyOf(args, args.length)));
            return 1;
        }

        private record QueryCall(String sql, Object[] args) {}

        private record Update(String sql, Object[] args) {}
    }
}
