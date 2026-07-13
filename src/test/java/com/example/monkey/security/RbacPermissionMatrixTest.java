package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RbacPermissionMatrixTest {

    private static final List<String> USER_PERMISSIONS = List.of(
            "USER_PROFILE_READ",
            "USER_PROFILE_WRITE",
            "ADDRESS_MANAGE",
            "ORDER_CREATE",
            "ORDER_READ_OWN",
            "ORDER_RETURN_REQUEST",
            "UPLOAD_AVATAR");

    private static final List<String> ADMIN_ONLY_PERMISSIONS =
            List.of("ADMIN_DASHBOARD_READ", "PRODUCT_MANAGE", "ORDER_MANAGE", "UPLOAD_PRODUCT_IMAGE");

    private static final List<String> ALL_PERMISSIONS = List.of(
            "USER_PROFILE_READ",
            "USER_PROFILE_WRITE",
            "ADDRESS_MANAGE",
            "ORDER_CREATE",
            "ORDER_READ_OWN",
            "ORDER_RETURN_REQUEST",
            "UPLOAD_AVATAR",
            "ADMIN_DASHBOARD_READ",
            "PRODUCT_MANAGE",
            "ORDER_MANAGE",
            "UPLOAD_PRODUCT_IMAGE");

    @Test
    void flywaySeedDefinesExpectedPermissionCatalogAndRoleGrants() throws IOException {
        String migration = read("src/main/resources/db/migration/V6__rbac_roles_permissions.sql");
        String permissionSeed = between(
                migration,
                "INSERT INTO `permissions` (`name`, `description`)",
                "WHERE NOT EXISTS (SELECT 1 FROM `permissions` existing");
        String userGrant = between(migration, "JOIN `permissions` p ON p.`name` IN (", "WHERE r.`name` = 'USER'");
        String adminGrant = between(migration, "JOIN `permissions` p\nWHERE r.`name` = 'ADMIN'", "AND NOT EXISTS");

        for (String permission : ALL_PERMISSIONS) {
            assertThat(permissionSeed).contains("'" + permission + "'");
        }
        for (String permission : USER_PERMISSIONS) {
            assertThat(userGrant).contains("'" + permission + "'");
        }
        for (String permission : ADMIN_ONLY_PERMISSIONS) {
            assertThat(userGrant).doesNotContain("'" + permission + "'");
        }
        assertThat(adminGrant).contains("JOIN `permissions` p").doesNotContain("p.`name` IN");
    }

    @Test
    void documentationListsTheSameRbacMatrixAndInvariants() throws IOException {
        String docs = read("docs/security/ws2-rbac-matrix.md");
        String readme = read("README.md");

        assertThat(docs)
                .contains("# WS2 RBAC Permission Matrix")
                .contains("hasAuthority(...)")
                .contains("`hasRole(...)`, `hasAnyRole(...)`, or bare")
                .contains("`isAuthenticated()`")
                .contains("@orderOwnership.isOwner(#id, authentication)")
                .contains("V6__rbac_roles_permissions.sql");
        for (String permission : ALL_PERMISSIONS) {
            assertThat(docs).contains("`" + permission + "`");
        }
        assertThat(readme).contains("docs/security/ws2-rbac-matrix.md");
    }

    @Test
    void securityRequestMatrixUsesDocumentedPermissionNames() throws IOException {
        String securityConfig =
                read("src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java");

        assertThat(securityConfig).doesNotContain("hasRole(").doesNotContain("hasAnyRole(");
        assertThat(securityConfig)
                .contains("\"/api/upload/presigned-get\", \"/api/v1/uploads/presigned-get\")")
                .contains(".hasAnyAuthority(\"UPLOAD_AVATAR\", \"UPLOAD_PRODUCT_IMAGE\")");
        for (String permission : ALL_PERMISSIONS) {
            assertThat(securityConfig).contains(permission);
        }
    }

    @Test
    void taskSixControllerBoundariesUseTheExistingPermissionCatalog() throws IOException {
        String catalog = read("src/main/java/com/example/monkey/product/interfaces/CatalogController.java");
        String inventory = read("src/main/java/com/example/monkey/inventory/interfaces/InventoryController.java");
        String marketing = read("src/main/java/com/example/monkey/marketing/interfaces/MarketingController.java");

        assertThat(between(catalog, "@PostMapping(\"/spus\")", "@PostMapping(\"/spus/{spuId}/status\")"))
                .contains("hasAuthority('PRODUCT_MANAGE')");
        assertThat(between(
                        inventory,
                        "@PostMapping(\"/reservations/{reservationKey}/release\")",
                        "@PostMapping(\"/reservations/{reservationKey}/deduct\")"))
                .contains("hasAuthority('ORDER_MANAGE')")
                .doesNotContain("ORDER_CREATE");
        assertThat(between(inventory, "@PostMapping(\"/compensations\")", "@GetMapping(\"/reconciliation\")"))
                .contains("hasAuthority('ORDER_MANAGE')")
                .doesNotContain("ORDER_CREATE");
        assertThat(between(marketing, "@PostMapping(\"/coupons/return\")", "@PostMapping(\"/price/quote\")"))
                .contains("hasAnyAuthority('ORDER_CREATE', 'ORDER_MANAGE')");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start marker").isGreaterThanOrEqualTo(0);
        assertThat(endIndex).as("end marker").isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
