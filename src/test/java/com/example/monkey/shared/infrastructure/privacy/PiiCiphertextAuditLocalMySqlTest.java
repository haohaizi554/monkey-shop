package com.example.monkey.shared.infrastructure.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "task9b.local-mysql", matches = "true")
class PiiCiphertextAuditLocalMySqlTest {

    private static final String SCHEMA_PREFIX = "monkeyshop_task9b_";

    @Test
    void authenticatesRealMysqlRowsAndRejectsForgedEnvelope() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway.configure().dataSource(schema.dataSource()).load().migrate();
            PiiCryptoService cryptoService = enabledCryptoService();
            String phone = "13800000000";
            schema.jdbcTemplate()
                    .update(
                            """
                    INSERT INTO `user` (
                        id, username, password, role, tenant_id,
                        phone, phone_hmac, email, totp_secret
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                            990001L,
                            "task9b-audit-user",
                            "not-used",
                            "USER",
                            1L,
                            cryptoService.encrypt(phone),
                            cryptoService.blindIndexPhone(phone),
                            cryptoService.encrypt("audit@example.com"),
                            cryptoService.encrypt("JBSWY3DPEHPK3PXP"));
            PiiCiphertextAuditService auditService =
                    new PiiCiphertextAuditService(schema.jdbcTemplate(), cryptoService, 100);

            PiiCiphertextAuditService.AuditReport valid = auditService.auditStoredCiphertext();

            assertThat(valid.populatedEncryptedValues()).isEqualTo(3);
            assertThat(valid.authenticatedCiphertexts()).isEqualTo(3);
            assertThat(valid.unprotectedValues()).isZero();
            assertThat(valid.blindIndexMismatches()).isZero();
            assertThat(valid.protectedAtRest()).isTrue();

            schema.jdbcTemplate()
                    .update("UPDATE `user` SET `email` = ? WHERE `id` = ?", "enc:v1:v1:tink:AAAA", 990001L);

            PiiCiphertextAuditService.AuditReport forged = auditService.auditStoredCiphertext();
            assertThat(forged.authenticatedCiphertexts()).isEqualTo(2);
            assertThat(forged.unprotectedValues()).isEqualTo(1);
            assertThat(forged.protectedAtRest()).isFalse();
        });
    }

    private static PiiCryptoService enabledCryptoService() {
        return new PiiCryptoService(
                true,
                new SecretKeySpec(new byte[32], "AES"),
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                "v1",
                false);
    }

    private static void withIsolatedSchema(Consumer<Schema> test) throws Exception {
        String schemaName = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String username = System.getProperty("task9b.mysql.username", "root");
        String password = requiredProperty("task9b.mysql.password");
        String adminUrl = mysqlUrl("");
        createSchema(adminUrl, username, password, schemaName);
        try {
            DataSource dataSource = new DriverManagerDataSource(mysqlUrl(schemaName), username, password);
            test.accept(new Schema(dataSource));
        } finally {
            dropSchema(adminUrl, username, password, schemaName);
        }
    }

    private static void createSchema(String url, String username, String password, String schemaName)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schemaName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static void dropSchema(String url, String username, String password, String schemaName)
            throws SQLException {
        if (!schemaName.startsWith(SCHEMA_PREFIX)
                || !schemaName.substring(SCHEMA_PREFIX.length()).matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Refusing to drop an unexpected schema");
        }
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + schemaName + "`");
        }
    }

    private static String mysqlUrl(String schemaName) {
        String serverUrl = System.getProperty("task9b.mysql.server-url", "jdbc:mysql://127.0.0.1:3306");
        if (!serverUrl.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):\\d+")) {
            throw new IllegalArgumentException("Task 9B audit verification requires a local MySQL server URL");
        }
        return serverUrl + "/" + schemaName
                + "?sslMode=REQUIRED&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai"
                + "&characterEncoding=utf8";
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property " + name);
        }
        return value;
    }

    private record Schema(DataSource dataSource) {

        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(dataSource);
        }
    }
}
