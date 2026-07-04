package com.example.monkey.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws12SaaSWorkflowTest {

    @Test
    void ws12SaaSArtifactsWireTenantIsolationBillingExportsAndFrontend() throws IOException {
        String docs = read("docs/saas/ws12.md");
        String isolationMigration = read("src/main/resources/db/migration/V44__tenant_isolation.sql");
        String managementMigration = read("src/main/resources/db/migration/V45__tenant_management.sql");
        String billingMigration = read("src/main/resources/db/migration/V46__tenant_billing.sql");
        String tenantContext = read("src/main/java/com/example/monkey/shared/application/tenant/TenantContext.java");
        String tenantFilter = read("src/main/java/com/example/monkey/shared/interfaces/web/TenantContextFilter.java");
        String tenantListener =
                read("src/main/java/com/example/monkey/shared/infrastructure/tenant/TenantScopedEntityListener.java");
        String jwt = read("src/main/java/com/example/monkey/user/infrastructure/JwtTokenService.java");
        String service = read("src/main/java/com/example/monkey/tenant/application/TenantApplicationService.java");
        String store = read("src/main/java/com/example/monkey/tenant/infrastructure/JpaTenantStore.java");
        String controller = read("src/main/java/com/example/monkey/tenant/interfaces/TenantAdminController.java");
        String task = read("src/main/java/com/example/monkey/tenant/infrastructure/TenantDataExportTask.java");
        String filter = read("src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java");
        String security = read("src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java");
        String audit = read("src/main/java/com/example/monkey/shared/application/observability/AuditService.java");
        String frontendApi = read("frontend/src/api/tenant.ts");
        String frontendView = read("frontend/src/views/TenantAdminView.vue");
        String router = read("frontend/src/router/index.ts");
        String verify = read("scripts/verify-ws12-saas.ps1");

        assertThat(docs).contains("tenant_id", "TenantContextFilter", "PiiCryptoService", "ShedLock");
        assertThat(isolationMigration)
                .contains("CREATE TABLE tenant", "ALTER TABLE `orders` ADD COLUMN tenant_id")
                .contains("ALTER TABLE tracking_event ADD COLUMN tenant_id")
                .contains("fk_orders_tenant", "idx_tracking_event_tenant_type_time");
        assertThat(managementMigration).contains("CREATE TABLE tenant_config", "tenant_rollout_policy", "TENANT_ADMIN");
        assertThat(billingMigration)
                .contains("CREATE TABLE tenant_bill", "CREATE TABLE tenant_data_export_job")
                .contains("tenant_billing_reconciliation");
        assertThat(tenantContext).contains("ThreadLocal<Long>", "currentTenantIdOrDefault");
        assertThat(tenantFilter).contains("X-Tenant-Id", "TenantContext.setTenantId");
        assertThat(tenantListener).contains("@PrePersist", "TenantContext.currentTenantIdOrDefault");
        assertThat(jwt).contains("tenant_id", "tenantId()");
        assertThat(service)
                .contains("@WithSpan(\"tenant.create\")", "TENANT_BILL_GENERATED", "TENANT_EXPORT_REQUESTED");
        assertThat(store).contains("PiiCryptoService", "countOrdersForTenant", "app.tenant.store");
        assertThat(controller).contains("@RequestMapping({\"/api/tenants\"", "TENANT_ADMIN");
        assertThat(task).contains("@SchedulerLock(name = \"tenant-data-export\"");
        assertThat(filter).contains("ApiRateLimitOperation.TENANT", "/api/tenants/internal/probe");
        assertThat(security).contains("/tenants", "TENANT_READ", "TENANT_ADMIN");
        assertThat(audit).contains("TENANT_CREATED", "TENANT_EXPORT_COMPLETED");
        assertThat(frontendApi).contains("tenantDashboard", "requestTenantExport");
        assertThat(frontendView).contains("Tenant Command Center", "Generate bill", "Request export");
        assertThat(router).contains("TenantAdminView");
        assertThat(verify).contains("WS12 SaaS verification completed successfully");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
