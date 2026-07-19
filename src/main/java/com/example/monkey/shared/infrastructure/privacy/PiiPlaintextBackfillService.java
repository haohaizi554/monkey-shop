package com.example.monkey.shared.infrastructure.privacy;

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
import org.springframework.util.StringUtils;

@Service
public class PiiPlaintextBackfillService {

    private static final int DEFAULT_BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final PiiCryptoService piiCryptoService;
    private final int batchSize;
    private final TransactionTemplate batchTransaction;

    @Autowired
    public PiiPlaintextBackfillService(
            JdbcTemplate jdbcTemplate,
            PiiCryptoService piiCryptoService,
            PlatformTransactionManager transactionManager,
            @Value("${app.pii.backfill.batch-size:500}") int batchSize) {
        this(jdbcTemplate, piiCryptoService, transactionManager, batchSize, true);
    }

    public PiiPlaintextBackfillService(JdbcTemplate jdbcTemplate, PiiCryptoService piiCryptoService) {
        this(jdbcTemplate, piiCryptoService, null, DEFAULT_BATCH_SIZE, false);
    }

    private PiiPlaintextBackfillService(
            JdbcTemplate jdbcTemplate,
            PiiCryptoService piiCryptoService,
            PlatformTransactionManager transactionManager,
            int batchSize,
            boolean requireTransactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.piiCryptoService = piiCryptoService;
        this.batchSize = Math.max(1, batchSize);
        if (requireTransactionManager) {
            Objects.requireNonNull(transactionManager, "transactionManager");
        }
        this.batchTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (this.batchTransaction != null) {
            this.batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    public BackfillReport backfillLegacyPlaintext() {
        if (!piiCryptoService.encryptionEnabled()) {
            throw new IllegalStateException("PII plaintext backfill requires APP_PII_ENCRYPTION_ENABLED=true");
        }
        int users = backfillUsers();
        int addresses = backfillAddresses();
        int orders = backfillOrders();
        int reviews = backfillOrderReviews();
        return new BackfillReport(users, addresses, orders, reviews);
    }

    private int backfillUsers() {
        return processBatches(this::backfillUserBatch);
    }

    private BatchResult backfillUserBatch(long afterId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT `id`, `phone`, `email`, `phone_hmac`, `totp_secret`
                FROM `user`
                WHERE `id` > ?
                  AND (`phone` IS NOT NULL
                    OR `email` IS NOT NULL
                    OR `totp_secret` IS NOT NULL)
                ORDER BY `id`
                LIMIT ?
                """, afterId, batchSize);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String phone = asString(row.get("phone"));
            String email = asString(row.get("email"));
            String phoneHmac = asString(row.get("phone_hmac"));
            String totpSecret = asString(row.get("totp_secret"));
            String encryptedPhone = encryptIfPlaintext(phone);
            String encryptedEmail = encryptIfPlaintext(email);
            String encryptedTotpSecret = encryptIfPlaintext(totpSecret);
            String nextPhoneHmac = blindIndexIfPlaintextPhone(phone, phoneHmac);
            if (changed(phone, encryptedPhone)
                    || changed(email, encryptedEmail)
                    || changed(totpSecret, encryptedTotpSecret)
                    || changed(phoneHmac, nextPhoneHmac)) {
                updated += jdbcTemplate.update(
                        """
                        UPDATE `user`
                        SET `phone` = ?, `email` = ?, `totp_secret` = ?, `phone_hmac` = ?
                        WHERE `id` = ?
                        """, encryptedPhone, encryptedEmail, encryptedTotpSecret, nextPhoneHmac, row.get("id"));
            }
        }
        return batchResult(afterId, rows, updated);
    }

    private int backfillAddresses() {
        return processBatches(this::backfillAddressBatch);
    }

    private BatchResult backfillAddressBatch(long afterId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT `id`, `receiver_name`, `phone`, `phone_hmac`, `detail_address`
                FROM `address`
                WHERE `id` > ?
                  AND (`receiver_name` IS NOT NULL
                    OR `phone` IS NOT NULL
                    OR `detail_address` IS NOT NULL)
                ORDER BY `id`
                LIMIT ?
                """, afterId, batchSize);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String receiverName = asString(row.get("receiver_name"));
            String phone = asString(row.get("phone"));
            String phoneHmac = asString(row.get("phone_hmac"));
            String detailAddress = asString(row.get("detail_address"));
            String encryptedReceiverName = encryptIfPlaintext(receiverName);
            String encryptedPhone = encryptIfPlaintext(phone);
            String nextPhoneHmac = blindIndexIfPlaintextPhone(phone, phoneHmac);
            String encryptedDetailAddress = encryptIfPlaintext(detailAddress);
            if (changed(receiverName, encryptedReceiverName)
                    || changed(phone, encryptedPhone)
                    || changed(phoneHmac, nextPhoneHmac)
                    || changed(detailAddress, encryptedDetailAddress)) {
                updated += jdbcTemplate.update(
                        """
                        UPDATE `address`
                        SET `receiver_name` = ?, `phone` = ?, `phone_hmac` = ?, `detail_address` = ?
                        WHERE `id` = ?
                        """,
                        encryptedReceiverName,
                        encryptedPhone,
                        nextPhoneHmac,
                        encryptedDetailAddress,
                        row.get("id"));
            }
        }
        return batchResult(afterId, rows, updated);
    }

    private int backfillOrders() {
        return processBatches(this::backfillOrderBatch);
    }

    private BatchResult backfillOrderBatch(long afterId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT `id`, `buyer_name`, `receiver_name`, `receiver_phone`, `receiver_phone_hmac`, `address_snapshot`
                FROM `orders`
                WHERE `id` > ?
                  AND (`buyer_name` IS NOT NULL
                    OR `receiver_name` IS NOT NULL
                    OR `receiver_phone` IS NOT NULL
                    OR `address_snapshot` IS NOT NULL)
                ORDER BY `id`
                LIMIT ?
                """, afterId, batchSize);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String buyerName = asString(row.get("buyer_name"));
            String receiverName = asString(row.get("receiver_name"));
            String receiverPhone = asString(row.get("receiver_phone"));
            String receiverPhoneHmac = asString(row.get("receiver_phone_hmac"));
            String addressSnapshot = asString(row.get("address_snapshot"));
            String encryptedBuyerName = encryptIfPlaintext(buyerName);
            String encryptedReceiverName = encryptIfPlaintext(receiverName);
            String encryptedReceiverPhone = encryptIfPlaintext(receiverPhone);
            String nextReceiverPhoneHmac = blindIndexIfPlaintextPhone(receiverPhone, receiverPhoneHmac);
            String encryptedAddressSnapshot = encryptIfPlaintext(addressSnapshot);
            if (changed(buyerName, encryptedBuyerName)
                    || changed(receiverName, encryptedReceiverName)
                    || changed(receiverPhone, encryptedReceiverPhone)
                    || changed(receiverPhoneHmac, nextReceiverPhoneHmac)
                    || changed(addressSnapshot, encryptedAddressSnapshot)) {
                updated += jdbcTemplate.update(
                        """
                        UPDATE `orders`
                        SET `buyer_name` = ?,
                            `receiver_name` = ?,
                            `receiver_phone` = ?,
                            `receiver_phone_hmac` = ?,
                            `address_snapshot` = ?
                        WHERE `id` = ?
                        """,
                        encryptedBuyerName,
                        encryptedReceiverName,
                        encryptedReceiverPhone,
                        nextReceiverPhoneHmac,
                        encryptedAddressSnapshot,
                        row.get("id"));
            }
        }
        return batchResult(afterId, rows, updated);
    }

    private int backfillOrderReviews() {
        return processBatches(this::backfillOrderReviewBatch);
    }

    private BatchResult backfillOrderReviewBatch(long afterId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT `id`, `content`
                FROM `order_review`
                WHERE `id` > ?
                  AND `content` IS NOT NULL
                ORDER BY `id`
                LIMIT ?
                """, afterId, batchSize);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String content = asString(row.get("content"));
            String encryptedContent = encryptIfPlaintext(content);
            if (changed(content, encryptedContent)) {
                updated += jdbcTemplate.update(
                        "UPDATE `order_review` SET `content` = ? WHERE `id` = ?", encryptedContent, row.get("id"));
            }
        }
        return batchResult(afterId, rows, updated);
    }

    private int processBatches(BatchProcessor processor) {
        long afterId = 0L;
        int updated = 0;
        while (true) {
            long cursor = afterId;
            BatchResult batch = executeBatch(() -> processor.process(cursor));
            updated = Math.addExact(updated, batch.updatedRows());
            if (batch.rowCount() < batchSize) {
                return updated;
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

    private static BatchResult batchResult(long afterId, List<Map<String, Object>> rows, int updatedRows) {
        if (rows.isEmpty()) {
            return new BatchResult(0, afterId, updatedRows);
        }
        Object id = rows.getLast().get("id");
        if (!(id instanceof Number number)) {
            throw new IllegalStateException("PII backfill row id must be numeric");
        }
        return new BatchResult(rows.size(), number.longValue(), updatedRows);
    }

    private String encryptIfPlaintext(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return piiCryptoService.encrypt(value);
    }

    private String blindIndexIfPlaintextPhone(String phone, String currentBlindIndex) {
        if (!StringUtils.hasText(phone)) {
            return null;
        }
        if (piiCryptoService.isAuthenticatedCiphertext(phone)) {
            return currentBlindIndex;
        }
        return piiCryptoService.blindIndexPhone(phone);
    }

    private static boolean changed(String before, String after) {
        return !Objects.equals(before, after);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    @FunctionalInterface
    private interface BatchProcessor {
        BatchResult process(long afterId);
    }

    private record BatchResult(int rowCount, long lastSeenId, int updatedRows) {}

    public record BackfillReport(int users, int addresses, int orders, int reviews) {

        public int total() {
            return users + addresses + orders + reviews;
        }
    }
}
