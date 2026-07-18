package com.example.monkey.tenant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "task9a.local-mysql", matches = "true")
class TenantExportMigrationLocalMySqlTest {

    private static final String LATEST_SCHEMA_VERSION = "53";
    private static final String SCHEMA_PREFIX = "monkeyshop_task9a_";

    @Test
    void emptySchemaMigratesThroughLatestVersion() throws Exception {
        withIsolatedSchema(schema -> {
            schema.latestFlyway().migrate();

            assertThat(schema.jdbcTemplate().queryForObject("""
                                    SELECT version
                                    FROM flyway_schema_history
                                    WHERE success = 1
                                    ORDER BY installed_rank DESC
                                    LIMIT 1
                                    """, String.class)).isEqualTo(LATEST_SCHEMA_VERSION);
            assertThat(schema.jdbcTemplate().queryForObject("""
                                    SELECT COUNT(*)
                                    FROM information_schema.columns
                                    WHERE table_schema = DATABASE()
                                      AND table_name = 'tenant_data_export_job'
                                      AND column_name = 'provider_job_id'
                                    """, Long.class)).isEqualTo(1L);
        });
    }

    @Test
    void v52PlaceholderExportsNeverBecomeProviderSuccesses() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway.configure()
                    .dataSource(schema.dataSource())
                    .target(MigrationVersion.fromVersion("52"))
                    .load()
                    .migrate();
            JdbcTemplate jdbc = schema.jdbcTemplate();
            jdbc.update("""
                    INSERT INTO `user` (id, username, password, role, tenant_id)
                    VALUES (900001, 'task9a-migration-user', 'not-used', 'ADMIN', 1)
                    """);
            jdbc.update("""
                    INSERT INTO tenant_data_export_job (
                        id, tenant_id, export_type, status, encrypted_archive_path,
                        requested_by, requested_at, completed_at, audit_trace_id, version
                    ) VALUES
                        (910001, 1, 'FULL', 'REQUESTED', NULL, 900001, NOW(6), NULL, 'trace-requested', 0),
                        (910002, 1, 'FULL', 'COMPLETED',
                         'encrypted://tenant/1/exports/910002.zip.tink',
                         900001, NOW(6), NOW(6), 'trace-completed', 0),
                        (910003, 1, 'FULL', 'COMPLETED', '   ',
                         900001, NOW(6), NOW(6), 'trace-empty', 0),
                        (910004, 1, 'FULL', 'FAILED',
                         'encrypted://tenant/1/exports/910004.zip.tink',
                         900001, NOW(6), NOW(6), 'trace-failed', 0)
                    """);

            schema.latestFlyway().migrate();

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, status, provider_job_id, encrypted_archive_path, error_message
                    FROM tenant_data_export_job
                    ORDER BY id
                    """);
            assertThat(rows)
                    .extracting(row -> row.get("status"))
                    .containsExactly("UNAVAILABLE", "FAILED", "FAILED", "FAILED");
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.get("provider_job_id")).isNull();
                assertThat(row.get("encrypted_archive_path")).isNull();
                assertThat(row.get("error_message")).isNotNull();
            });
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM tenant_data_export_job WHERE status = 'SUCCEEDED'", Long.class))
                    .isZero();
        });
    }

    private static void withIsolatedSchema(Consumer<Schema> test) throws Exception {
        String schemaName = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String username = System.getProperty("task9a.mysql.username", "root");
        String password = requiredProperty("task9a.mysql.password");
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
        String serverUrl = System.getProperty("task9a.mysql.server-url", "jdbc:mysql://127.0.0.1:3306");
        if (!serverUrl.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):\\d+")) {
            throw new IllegalArgumentException("Task 9A migration verification requires a local MySQL server URL");
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

        Flyway latestFlyway() {
            return Flyway.configure().dataSource(dataSource).load();
        }
    }
}
