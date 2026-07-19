package com.example.monkey.shared.infrastructure.privacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PiiCiphertextAuditService {

    private final JdbcTemplate jdbcTemplate;
    private final PiiCryptoService piiCryptoService;
    private final int batchSize;

    @Autowired
    public PiiCiphertextAuditService(
            JdbcTemplate jdbcTemplate,
            PiiCryptoService piiCryptoService,
            @Value("${app.pii.ciphertext-audit.batch-size:500}") int batchSize) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.piiCryptoService = Objects.requireNonNull(piiCryptoService, "piiCryptoService");
        this.batchSize = Math.max(1, batchSize);
    }

    public AuditReport auditStoredCiphertext() {
        if (!piiCryptoService.encryptionEnabled()) {
            throw new IllegalStateException("PII ciphertext audit requires APP_PII_ENCRYPTION_ENABLED=true");
        }
        AuditAccumulator audit = new AuditAccumulator();
        auditUsers(audit);
        auditAddresses(audit);
        auditOrders(audit);
        auditOrderReviews(audit);
        return audit.toReport();
    }

    private void auditUsers(AuditAccumulator audit) {
        scan("""
                SELECT `id`, `phone`, `phone_hmac`, `email`, `totp_secret`
                FROM `user`
                WHERE `id` > ?
                  AND (`phone` IS NOT NULL
                    OR `phone_hmac` IS NOT NULL
                    OR `email` IS NOT NULL
                    OR `totp_secret` IS NOT NULL)
                ORDER BY `id`
                LIMIT ?
                """, row -> {
            String phone = asString(row.get("phone"));
            boolean authenticated = audit.auditCiphertext("user.phone", phone, piiCryptoService);
            auditBlindIndex(audit, phone, asString(row.get("phone_hmac")), authenticated);
            audit.auditCiphertext("user.email", asString(row.get("email")), piiCryptoService);
            audit.auditCiphertext("user.totp_secret", asString(row.get("totp_secret")), piiCryptoService);
        });
    }

    private void auditAddresses(AuditAccumulator audit) {
        scan("""
                SELECT `id`, `receiver_name`, `phone`, `phone_hmac`, `detail_address`
                FROM `address`
                WHERE `id` > ?
                  AND (`receiver_name` IS NOT NULL
                    OR `phone` IS NOT NULL
                    OR `phone_hmac` IS NOT NULL
                    OR `detail_address` IS NOT NULL)
                ORDER BY `id`
                LIMIT ?
                """, row -> {
            audit.auditCiphertext("address.receiver_name", asString(row.get("receiver_name")), piiCryptoService);
            String phone = asString(row.get("phone"));
            boolean authenticated = audit.auditCiphertext("address.phone", phone, piiCryptoService);
            auditBlindIndex(audit, phone, asString(row.get("phone_hmac")), authenticated);
            audit.auditCiphertext("address.detail_address", asString(row.get("detail_address")), piiCryptoService);
        });
    }

    private void auditOrders(AuditAccumulator audit) {
        scan("""
                SELECT `id`, `buyer_name`, `receiver_name`, `receiver_phone`, `receiver_phone_hmac`, `address_snapshot`
                FROM `orders`
                WHERE `id` > ?
                  AND (`buyer_name` IS NOT NULL
                    OR `receiver_name` IS NOT NULL
                    OR `receiver_phone` IS NOT NULL
                    OR `receiver_phone_hmac` IS NOT NULL
                    OR `address_snapshot` IS NOT NULL)
                ORDER BY `id`
                LIMIT ?
                """, row -> {
            audit.auditCiphertext("orders.buyer_name", asString(row.get("buyer_name")), piiCryptoService);
            audit.auditCiphertext("orders.receiver_name", asString(row.get("receiver_name")), piiCryptoService);
            String receiverPhone = asString(row.get("receiver_phone"));
            boolean authenticated = audit.auditCiphertext("orders.receiver_phone", receiverPhone, piiCryptoService);
            auditBlindIndex(audit, receiverPhone, asString(row.get("receiver_phone_hmac")), authenticated);
            audit.auditCiphertext("orders.address_snapshot", asString(row.get("address_snapshot")), piiCryptoService);
        });
    }

    private void auditOrderReviews(AuditAccumulator audit) {
        scan("""
                SELECT `id`, `content`
                FROM `order_review`
                WHERE `id` > ?
                  AND `content` IS NOT NULL
                ORDER BY `id`
                LIMIT ?
                """, row -> audit.auditCiphertext("order_review.content", asString(row.get("content")), piiCryptoService));
    }

    private void scan(String sql, Consumer<Map<String, Object>> rowAudit) {
        long afterId = 0L;
        while (true) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, afterId, batchSize);
            rows.forEach(rowAudit);
            if (rows.size() < batchSize) {
                return;
            }
            Object id = rows.getLast().get("id");
            if (!(id instanceof Number number)) {
                throw new IllegalStateException("PII ciphertext audit row id must be numeric");
            }
            afterId = number.longValue();
        }
    }

    private void auditBlindIndex(
            AuditAccumulator audit, String encryptedPhone, String storedBlindIndex, boolean authenticated) {
        if (!hasStoredValue(encryptedPhone)) {
            if (hasStoredValue(storedBlindIndex)) {
                audit.blindIndexMismatch();
            }
            return;
        }
        if (!authenticated) {
            return;
        }
        String plaintextPhone = piiCryptoService.decrypt(encryptedPhone);
        String expectedBlindIndex = piiCryptoService.blindIndexPhone(plaintextPhone);
        if (!constantTimeEquals(expectedBlindIndex, storedBlindIndex)) {
            audit.blindIndexMismatch();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean hasStoredValue(String value) {
        return value != null && !value.isEmpty();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static final class AuditAccumulator {

        private final Map<String, MutableFieldAudit> fields = new LinkedHashMap<>();
        private long blindIndexMismatches;

        boolean auditCiphertext(String field, String value, PiiCryptoService piiCryptoService) {
            if (!hasStoredValue(value)) {
                return false;
            }
            MutableFieldAudit fieldAudit = fields.computeIfAbsent(field, ignored -> new MutableFieldAudit());
            fieldAudit.populated++;
            if (piiCryptoService.isAuthenticatedCiphertext(value)) {
                fieldAudit.authenticated++;
                return true;
            }
            fieldAudit.unprotected++;
            return false;
        }

        void blindIndexMismatch() {
            blindIndexMismatches++;
        }

        AuditReport toReport() {
            Map<String, FieldAudit> snapshots = new LinkedHashMap<>();
            fields.forEach((field, audit) ->
                    snapshots.put(field, new FieldAudit(audit.populated, audit.authenticated, audit.unprotected)));
            return new AuditReport(snapshots, blindIndexMismatches);
        }
    }

    private static final class MutableFieldAudit {

        private long populated;
        private long authenticated;
        private long unprotected;
    }

    public record FieldAudit(long populated, long authenticated, long unprotected) {}

    public record AuditReport(Map<String, FieldAudit> fields, long blindIndexMismatches) {

        public AuditReport {
            fields = Map.copyOf(fields);
        }

        public long populatedEncryptedValues() {
            return fields.values().stream().mapToLong(FieldAudit::populated).sum();
        }

        public long authenticatedCiphertexts() {
            return fields.values().stream().mapToLong(FieldAudit::authenticated).sum();
        }

        public long unprotectedValues() {
            return fields.values().stream().mapToLong(FieldAudit::unprotected).sum();
        }

        public boolean protectedAtRest() {
            return unprotectedValues() == 0L && blindIndexMismatches == 0L;
        }
    }
}
