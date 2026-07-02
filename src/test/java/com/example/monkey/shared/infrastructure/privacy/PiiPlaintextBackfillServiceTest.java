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
                        "Hangzhou Road"));
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
        assertThat(firstUser.args()[2]).isEqualTo(cryptoService.blindIndexPhone("13800000000"));

        FakeJdbcTemplate.Update secondUser = jdbcTemplate.updates.get(1);
        assertThat(secondUser.args()[0]).isEqualTo(encryptedExistingPhone);
        assertThat(cryptoService.decrypt((String) secondUser.args()[1])).isEqualTo("bob@example.com");
        assertThat(secondUser.args()[2]).isEqualTo("existing-hmac");

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
        private final List<Update> updates = new ArrayList<>();

        @SafeVarargs
        final FakeJdbcTemplate thenRows(Map<String, Object>... rows) {
            queryResults.add(List.of(rows));
            return this;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            queries.add(sql);
            return queryResults.removeFirst();
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(new Update(sql, Arrays.copyOf(args, args.length)));
            return 1;
        }

        private record Update(String sql, Object[] args) {}
    }
}
