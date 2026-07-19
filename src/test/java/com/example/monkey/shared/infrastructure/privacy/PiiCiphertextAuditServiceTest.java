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
import org.springframework.lang.NonNull;

class PiiCiphertextAuditServiceTest {

    @Test
    void rejectsEnvelopeShapedPlaintextThatCannotBeAuthenticated() {
        PiiCryptoService cryptoService = enabledService();
        String encryptedPhone = cryptoService.encrypt("13800000000");
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate()
                .thenRows(row(
                        "id",
                        1L,
                        "phone",
                        encryptedPhone,
                        "phone_hmac",
                        cryptoService.blindIndexPhone("13800000000"),
                        "email",
                        "enc:v1:v1:tink:AAAA",
                        "totp_secret",
                        null))
                .thenRows()
                .thenRows()
                .thenRows();
        PiiCiphertextAuditService service = new PiiCiphertextAuditService(jdbcTemplate, cryptoService, 100);

        PiiCiphertextAuditService.AuditReport report = service.auditStoredCiphertext();

        assertThat(report.populatedEncryptedValues()).isEqualTo(2);
        assertThat(report.authenticatedCiphertexts()).isEqualTo(1);
        assertThat(report.unprotectedValues()).isEqualTo(1);
        assertThat(report.blindIndexMismatches()).isZero();
        assertThat(report.protectedAtRest()).isFalse();
    }

    @Test
    void authenticatesCiphertextAndRecomputesBlindIndexesInStableIdPages() {
        PiiCryptoService cryptoService = enabledService();
        String firstPhone = cryptoService.encrypt("13800000001");
        String secondPhone = cryptoService.encrypt("13800000002");
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate()
                .thenRows(
                        row("id", 1L, "phone", firstPhone, "phone_hmac", cryptoService.blindIndexPhone("13800000001")))
                .thenRows(row("id", 2L, "phone", secondPhone, "phone_hmac", "0".repeat(64)))
                .thenRows()
                .thenRows()
                .thenRows()
                .thenRows();
        PiiCiphertextAuditService service = new PiiCiphertextAuditService(jdbcTemplate, cryptoService, 1);

        PiiCiphertextAuditService.AuditReport report = service.auditStoredCiphertext();

        assertThat(report.populatedEncryptedValues()).isEqualTo(2);
        assertThat(report.authenticatedCiphertexts()).isEqualTo(2);
        assertThat(report.unprotectedValues()).isZero();
        assertThat(report.blindIndexMismatches()).isEqualTo(1);
        assertThat(report.protectedAtRest()).isFalse();
        assertThat(jdbcTemplate.queryCalls.subList(0, 3))
                .extracting(call -> Arrays.asList(call.args()))
                .containsExactly(List.of(0L, 1), List.of(1L, 1), List.of(2L, 1));
        assertThat(jdbcTemplate.queryCalls.subList(0, 3))
                .allSatisfy(call -> assertThat(call.sql())
                        .contains("`id` > ?")
                        .contains("ORDER BY `id`")
                        .contains("LIMIT ?"));
    }

    @Test
    void cliFailsClosedForUnauthenticatedOrMissingRuntimeEvidence() {
        PiiCiphertextAuditService.AuditReport unauthenticated = new PiiCiphertextAuditService.AuditReport(
                Map.of("user.email", new PiiCiphertextAuditService.FieldAudit(1, 0, 1)), 0);
        PiiCiphertextAuditService.AuditReport empty = new PiiCiphertextAuditService.AuditReport(Map.of(), 0);

        assertThatThrownBy(() -> PiiCiphertextAuditCli.assertProtected(unauthenticated, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unprotected=1");
        assertThatThrownBy(() -> PiiCiphertextAuditCli.assertProtected(empty, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no populated PII");
    }

    private static PiiCryptoService enabledService() {
        return new PiiCryptoService(
                true,
                new SecretKeySpec(new byte[32], "AES"),
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                "v1",
                false);
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

        private record QueryCall(String sql, Object[] args) {}
    }
}
