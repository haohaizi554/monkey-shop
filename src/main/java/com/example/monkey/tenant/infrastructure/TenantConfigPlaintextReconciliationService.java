package com.example.monkey.tenant.infrastructure;

import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciles legacy tenant setting JSON in bounded, compare-and-set pages.
 *
 * <p>This is intentionally separate from normal reads and writes: a rollout can first deploy readers that understand
 * both legacy plaintext and per-value ciphertext, run this bounded rewrite, and only then disable legacy plaintext
 * reads. A concurrent update wins its compare-and-set and is retried by the next reconciliation run.
 */
@Service
public class TenantConfigPlaintextReconciliationService {

    private static final int DEFAULT_BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final TenantConfigSettingsCodec settingsCodec;
    private final int batchSize;
    private final TransactionTemplate batchTransaction;

    @Autowired
    public TenantConfigPlaintextReconciliationService(
            JdbcTemplate jdbcTemplate,
            PiiCryptoService piiCryptoService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${app.tenant.config-backfill.batch-size:500}") int batchSize) {
        this(jdbcTemplate, piiCryptoService, objectMapper, transactionManager, batchSize, true);
    }

    public TenantConfigPlaintextReconciliationService(JdbcTemplate jdbcTemplate, PiiCryptoService piiCryptoService) {
        this(
                jdbcTemplate,
                piiCryptoService,
                new ObjectMapper().findAndRegisterModules(),
                null,
                DEFAULT_BATCH_SIZE,
                false);
    }

    public TenantConfigPlaintextReconciliationService(
            JdbcTemplate jdbcTemplate,
            PiiCryptoService piiCryptoService,
            PlatformTransactionManager transactionManager,
            int batchSize) {
        this(
                jdbcTemplate,
                piiCryptoService,
                new ObjectMapper().findAndRegisterModules(),
                transactionManager,
                batchSize,
                false);
    }

    private TenantConfigPlaintextReconciliationService(
            JdbcTemplate jdbcTemplate,
            PiiCryptoService piiCryptoService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            int batchSize,
            boolean requireTransactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.settingsCodec = new TenantConfigSettingsCodec(
                Objects.requireNonNull(piiCryptoService, "piiCryptoService"),
                Objects.requireNonNull(objectMapper, "objectMapper"));
        this.batchSize = Math.max(1, batchSize);
        if (requireTransactionManager) {
            Objects.requireNonNull(transactionManager, "transactionManager");
        }
        this.batchTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (this.batchTransaction != null) {
            this.batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    public ReconciliationReport reconcileLegacyPlaintext() {
        settingsCodec.requireEncryption();
        int configs = reconcileConfigs();
        int history = reconcileHistory();
        return new ReconciliationReport(configs, history);
    }

    public ReconciliationReport backfillLegacyPlaintext() {
        return reconcileLegacyPlaintext();
    }

    private int reconcileConfigs() {
        return processBatches(this::configBatch, "tenant config");
    }

    private int reconcileHistory() {
        return processBatches(this::historyBatch, "tenant config history");
    }

    private BatchResult configBatch(long afterId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT `id`, `settings_json`
                FROM `tenant_config`
                WHERE `id` > ?
                ORDER BY `id`
                LIMIT ?
                """, afterId, batchSize);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            long id = numericId(row.get("id"));
            String original = asString(row.get("settings_json"));
            TenantConfigSettingsCodec.ReconciledJson reconciled = settingsCodec.reconcile(original);
            if (reconciled.changed()) {
                updated += jdbcTemplate.update(
                        "UPDATE `tenant_config` SET `settings_json` = ? WHERE `id` = ? AND `settings_json` = ?",
                        reconciled.json(),
                        id,
                        original);
            }
        }
        return result(afterId, rows, updated);
    }

    private BatchResult historyBatch(long afterId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT `id`, `old_settings_json`, `new_settings_json`
                FROM `tenant_config_history`
                WHERE `id` > ?
                ORDER BY `id`
                LIMIT ?
                """, afterId, batchSize);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            long id = numericId(row.get("id"));
            String originalOld = asString(row.get("old_settings_json"));
            String originalNew = asString(row.get("new_settings_json"));
            TenantConfigSettingsCodec.ReconciledJson oldSettings = settingsCodec.reconcile(originalOld);
            TenantConfigSettingsCodec.ReconciledJson newSettings = settingsCodec.reconcile(originalNew);
            if (oldSettings.changed() || newSettings.changed()) {
                updated += jdbcTemplate.update(
                        "UPDATE `tenant_config_history` "
                                + "SET `old_settings_json` = ?, `new_settings_json` = ? "
                                + "WHERE `id` = ? "
                                + "AND `old_settings_json` <=> ? "
                                + "AND `new_settings_json` <=> ?",
                        oldSettings.json(),
                        newSettings.json(),
                        id,
                        originalOld,
                        originalNew);
            }
        }
        return result(afterId, rows, updated);
    }

    private int processBatches(BatchProcessor processor, String tableName) {
        long afterId = 0L;
        int updated = 0;
        while (true) {
            long cursor = afterId;
            BatchResult batch = executeBatch(() -> processor.process(cursor));
            updated = Math.addExact(updated, batch.updatedRows());
            if (batch.rowCount() < batchSize) {
                return updated;
            }
            if (batch.lastSeenId() <= afterId) {
                throw new IllegalStateException(tableName + " reconciliation cursor did not advance");
            }
            afterId = batch.lastSeenId();
        }
    }

    private BatchResult executeBatch(Supplier<BatchResult> work) {
        if (batchTransaction == null) {
            return work.get();
        }
        BatchResult result = batchTransaction.execute(status -> work.get());
        return Objects.requireNonNull(result, "batch transaction result");
    }

    private BatchResult result(long afterId, List<Map<String, Object>> rows, int updatedRows) {
        if (rows.isEmpty()) {
            return new BatchResult(0, afterId, updatedRows);
        }
        return new BatchResult(rows.size(), numericId(rows.getLast().get("id")), updatedRows);
    }

    private static long numericId(Object id) {
        if (!(id instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalStateException("Tenant config reconciliation row id must be positive");
        }
        return number.longValue();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    @FunctionalInterface
    private interface BatchProcessor {
        BatchResult process(long afterId);
    }

    private record BatchResult(int rowCount, long lastSeenId, int updatedRows) {}

    public record ReconciliationReport(int configs, int history) {

        public int total() {
            return configs + history;
        }
    }
}
