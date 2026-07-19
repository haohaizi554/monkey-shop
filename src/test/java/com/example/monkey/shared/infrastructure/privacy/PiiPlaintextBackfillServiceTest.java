package com.example.monkey.shared.infrastructure.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class PiiPlaintextBackfillServiceTest {

    @Test
    void rewritesLegacyPlaintextPiiAndBlindIndexes() {
        PiiCryptoService cryptoService = enabledService();
        String encryptedExistingPhone = cryptoService.encrypt("13900000000");
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate()
                .thenRows(
                        row("id", 1L, "phone", "13800000000", "email", "alice@example.com", "phone_hmac", "old"),
                        row(
                                "id",
                                2L,
                                "phone",
                                encryptedExistingPhone,
                                "email",
                                "bob@example.com",
                                "phone_hmac",
                                "existing-hmac"))
                .thenRows(row(
                        "id",
                        3L,
                        "receiver_name",
                        "Alice",
                        "phone",
                        "13700000000",
                        "phone_hmac",
                        "old-address",
                        "detail_address",
                        "Shanghai Road"))
                .thenRows(row(
                        "id",
                        4L,
                        "buyer_name",
                        "Buyer",
                        "receiver_name",
                        "Receiver",
                        "receiver_phone",
                        "13600000000",
                        "receiver_phone_hmac",
                        "old-order",
                        "address_snapshot",
                        "Hangzhou Road"))
                .thenRows();
        PiiPlaintextBackfillService service = new PiiPlaintextBackfillService(jdbcTemplate, cryptoService);

        PiiPlaintextBackfillService.BackfillReport report = service.backfillLegacyPlaintext();

        assertThat(report.users()).isEqualTo(2);
        assertThat(report.addresses()).isEqualTo(1);
        assertThat(report.orders()).isEqualTo(1);
        assertThat(report.total()).isEqualTo(4);
        assertThat(jdbcTemplate.updates).hasSize(4);

        FakeJdbcTemplate.Update firstUser = jdbcTemplate.updates.get(0);
        assertThat(firstUser.sql()).contains("UPDATE `user`");
        assertThat(cryptoService.decrypt((String) firstUser.args()[0])).isEqualTo("13800000000");
        assertThat(cryptoService.decrypt((String) firstUser.args()[1])).isEqualTo("alice@example.com");
        assertThat(firstUser.args()[3]).isEqualTo(cryptoService.blindIndexPhone("13800000000"));

        FakeJdbcTemplate.Update secondUser = jdbcTemplate.updates.get(1);
        assertThat(secondUser.args()[0]).isEqualTo(encryptedExistingPhone);
        assertThat(cryptoService.decrypt((String) secondUser.args()[1])).isEqualTo("bob@example.com");
        assertThat(secondUser.args()[3]).isEqualTo("existing-hmac");

        FakeJdbcTemplate.Update address = jdbcTemplate.updates.get(2);
        assertThat(address.sql()).contains("UPDATE `address`");
        assertThat(cryptoService.decrypt((String) address.args()[0])).isEqualTo("Alice");
        assertThat(cryptoService.decrypt((String) address.args()[1])).isEqualTo("13700000000");
        assertThat(address.args()[2]).isEqualTo(cryptoService.blindIndexPhone("13700000000"));
        assertThat(cryptoService.decrypt((String) address.args()[3])).isEqualTo("Shanghai Road");

        FakeJdbcTemplate.Update order = jdbcTemplate.updates.get(3);
        assertThat(order.sql()).contains("UPDATE `orders`");
        assertThat(cryptoService.decrypt((String) order.args()[0])).isEqualTo("Buyer");
        assertThat(cryptoService.decrypt((String) order.args()[1])).isEqualTo("Receiver");
        assertThat(cryptoService.decrypt((String) order.args()[2])).isEqualTo("13600000000");
        assertThat(order.args()[3]).isEqualTo(cryptoService.blindIndexPhone("13600000000"));
        assertThat(cryptoService.decrypt((String) order.args()[4])).isEqualTo("Hangzhou Road");
    }

    @Test
    void rewritesLegacyTotpSecretsAndReviewContent() {
        PiiCryptoService cryptoService = enabledService();
        String prefixedPlaintextReview = PiiCryptoService.ENCRYPTION_PREFIX + "private review";
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate()
                .thenRows(row(
                        "id", 10L, "phone", null, "email", null, "phone_hmac", null, "totp_secret", "JBSWY3DPEHPK3PXP"))
                .thenRows()
                .thenRows()
                .thenRows(row("id", 11L, "content", prefixedPlaintextReview));
        PiiPlaintextBackfillService service = new PiiPlaintextBackfillService(jdbcTemplate, cryptoService);

        PiiPlaintextBackfillService.BackfillReport report = service.backfillLegacyPlaintext();

        assertThat(report.users()).isEqualTo(1);
        assertThat(report.reviews()).isEqualTo(1);
        assertThat(report.total()).isEqualTo(2);
        assertThat(jdbcTemplate.updates).hasSize(2);
        assertThat(cryptoService.decrypt((String) jdbcTemplate.updates.get(0).args()[2]))
                .isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(cryptoService.decrypt((String) jdbcTemplate.updates.get(1).args()[0]))
                .isEqualTo(prefixedPlaintextReview);
    }

    @Test
    void leavesAlreadyEncryptedRowsUntouched() {
        PiiCryptoService cryptoService = enabledService();
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate()
                .thenRows(row(
                        "id",
                        1L,
                        "phone",
                        cryptoService.encrypt("13800000000"),
                        "email",
                        cryptoService.encrypt("alice@example.com"),
                        "phone_hmac",
                        "existing-hmac"))
                .thenRows()
                .thenRows()
                .thenRows();
        PiiPlaintextBackfillService service = new PiiPlaintextBackfillService(jdbcTemplate, cryptoService);

        PiiPlaintextBackfillService.BackfillReport report = service.backfillLegacyPlaintext();

        assertThat(report.total()).isZero();
        assertThat(jdbcTemplate.updates).isEmpty();
    }

    @Test
    void refusesToRunWhenEncryptionIsDisabled() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        PiiPlaintextBackfillService service =
                new PiiPlaintextBackfillService(jdbcTemplate, new PiiCryptoService(false, null, null, "v1", true));

        assertThatThrownBy(service::backfillLegacyPlaintext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PII plaintext backfill requires APP_PII_ENCRYPTION_ENABLED=true");
        assertThat(jdbcTemplate.queries).isEmpty();
        assertThat(jdbcTemplate.updates).isEmpty();
    }

    @Test
    void processesStableIdPagesInIndependentTransactions() {
        PiiCryptoService cryptoService = enabledService();
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate()
                .thenRows(row("id", 1L, "phone", "13800000001"))
                .thenRows(row("id", 2L, "phone", "13800000002"))
                .thenRows()
                .thenRows()
                .thenRows()
                .thenRows();
        CountingTransactionManager transactionManager = new CountingTransactionManager();
        PiiPlaintextBackfillService service =
                new PiiPlaintextBackfillService(jdbcTemplate, cryptoService, transactionManager, 1);

        PiiPlaintextBackfillService.BackfillReport report = service.backfillLegacyPlaintext();

        assertThat(report.users()).isEqualTo(2);
        assertThat(jdbcTemplate.queryCalls.subList(0, 3))
                .extracting(call -> Arrays.asList(call.args()))
                .containsExactly(List.of(0L, 1), List.of(1L, 1), List.of(2L, 1));
        assertThat(jdbcTemplate.queryCalls.subList(0, 3))
                .allSatisfy(call -> assertThat(call.sql())
                        .contains("`id` > ?")
                        .contains("ORDER BY `id`")
                        .contains("LIMIT ?"));
        assertThat(transactionManager.begins).isEqualTo(6);
        assertThat(transactionManager.commits).isEqualTo(6);
        assertThat(transactionManager.rollbacks).isZero();
    }

    private static PiiCryptoService enabledService() {
        return new PiiCryptoService(
                true,
                new SecretKeySpec(new byte[32], "AES"),
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                "v1",
                true);
    }

    private static Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            row.put((String) entries[i], entries[i + 1]);
        }
        return row;
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {

        private final ArrayDeque<List<Map<String, Object>>> queryResults = new ArrayDeque<>();
        private final List<String> queries = new ArrayList<>();
        private final List<QueryCall> queryCalls = new ArrayList<>();
        private final List<Update> updates = new ArrayList<>();

        @SafeVarargs
        final FakeJdbcTemplate thenRows(Map<String, Object>... rows) {
            queryResults.add(List.of(rows));
            return this;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            queries.add(sql);
            queryCalls.add(new QueryCall(sql, Arrays.copyOf(args, args.length)));
            return queryResults.removeFirst();
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(new Update(sql, Arrays.copyOf(args, args.length)));
            return 1;
        }

        private record QueryCall(String sql, Object[] args) {}

        private record Update(String sql, Object[] args) {}
    }

    private static final class CountingTransactionManager extends AbstractPlatformTransactionManager {

        private int begins;
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
