package com.example.monkey.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class TenantPermissionMigrationTest {

    @Test
    void v48RemovesTenantReadFromUserAndKeepsAdminAccess() throws SQLException {
        try (Connection connection =
                DriverManager.getConnection("jdbc:h2:mem:tenant_permission_matrix;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            seedV45PermissionMatrix(connection);

            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/V48__revoke_tenant_read_from_user.sql"));

            assertThat(permissionCount(connection, "USER", "TENANT_READ")).isZero();
            assertThat(permissionCount(connection, "ADMIN", "TENANT_READ")).isEqualTo(1);
            assertThat(permissionCount(connection, "ADMIN", "TENANT_WRITE")).isEqualTo(1);
            assertThat(permissionCount(connection, "ADMIN", "TENANT_ADMIN")).isEqualTo(1);
        }
    }

    private static void seedV45PermissionMatrix(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE roles (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL UNIQUE)");
            statement.execute("CREATE TABLE permissions (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL UNIQUE)");
            statement.execute("CREATE TABLE role_permissions (role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL,"
                    + " PRIMARY KEY (role_id, permission_id))");
            statement.execute("INSERT INTO roles (id, name) VALUES (1, 'USER'), (2, 'ADMIN')");
            statement.execute("INSERT INTO permissions (id, name) VALUES"
                    + " (10, 'TENANT_READ'), (11, 'TENANT_WRITE'), (12, 'TENANT_ADMIN')");
            statement.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES"
                    + " (1, 10), (2, 10), (2, 11), (2, 12)");
        }
    }

    private static int permissionCount(Connection connection, String role, String permission) throws SQLException {
        String query = "SELECT COUNT(*) FROM role_permissions rp"
                + " JOIN roles r ON r.id = rp.role_id"
                + " JOIN permissions p ON p.id = rp.permission_id"
                + " WHERE r.name = ? AND p.name = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, role);
            statement.setString(2, permission);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
