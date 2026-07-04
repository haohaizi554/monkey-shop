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
        String tenantScopedJpaEntity =
                read("src/main/java/com/example/monkey/shared/infrastructure/tenant/TenantScopedJpaEntity.java");
        String currentTenantIdSupplier =
                read("src/main/java/com/example/monkey/shared/infrastructure/tenant/CurrentTenantIdSupplier.java");
        String jwt = read("src/main/java/com/example/monkey/user/infrastructure/JwtTokenService.java");
        String jwtAuthenticationFilter =
                read("src/main/java/com/example/monkey/user/infrastructure/JwtAuthenticationFilter.java");
        String userAccountStore = read("src/main/java/com/example/monkey/user/domain/UserAccountStore.java");
        String authPrincipal = read("src/main/java/com/example/monkey/user/domain/AuthPrincipal.java");
        String loginService = read("src/main/java/com/example/monkey/user/application/LoginApplicationService.java");
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
        String orderRepository = read("src/main/java/com/example/monkey/order/infrastructure/OrderRepository.java");
        String monkeyRepository = read("src/main/java/com/example/monkey/product/infrastructure/MonkeyRepository.java");
        String idempotencyRepository =
                read("src/main/java/com/example/monkey/order/infrastructure/IdempotencyRecordRepository.java");
        String stockLogRepository =
                read("src/main/java/com/example/monkey/order/infrastructure/StockLogRepository.java");
        String addressRepository = read("src/main/java/com/example/monkey/user/infrastructure/AddressRepository.java");
        String membershipProfileRepository =
                read("src/main/java/com/example/monkey/membership/infrastructure/MembershipProfileRepository.java");
        String pointsWalletRepository =
                read("src/main/java/com/example/monkey/membership/infrastructure/PointsWalletRepository.java");

        assertThat(docs)
                .contains("tenant_id", "TenantContextFilter", "PiiCryptoService", "ShedLock")
                .contains("tenant-aware unique constraints", "webhook event keys");
        assertThat(isolationMigration)
                .contains("CREATE TABLE tenant", "ALTER TABLE `orders` ADD COLUMN tenant_id")
                .contains("ALTER TABLE tracking_event ADD COLUMN tenant_id")
                .contains("fk_orders_tenant", "idx_tracking_event_tenant_type_time")
                .contains(
                        "uk_inventory_reservation_key UNIQUE (tenant_id, reservation_key)",
                        "uk_inventory_ledger_idempotency UNIQUE (tenant_id, idempotency_key)",
                        "uk_marketing_coupon_code UNIQUE (tenant_id, code)",
                        "uk_cart_checkout_line_reservation UNIQUE (tenant_id, reservation_key)",
                        "uk_payment_callback_provider_id UNIQUE (tenant_id, provider, callback_id)",
                        "uk_logistics_webhook_carrier_event UNIQUE (tenant_id, carrier, event_id)",
                        "uk_logistics_freight_template UNIQUE (tenant_id, carrier, province, charge_mode)");
        assertThat(managementMigration).contains("CREATE TABLE tenant_config", "tenant_rollout_policy", "TENANT_ADMIN");
        assertThat(billingMigration)
                .contains("CREATE TABLE tenant_bill", "CREATE TABLE tenant_data_export_job")
                .contains("tenant_billing_reconciliation");
        assertThat(tenantContext).contains("ThreadLocal<Long>", "currentTenantIdOrDefault");
        assertThat(tenantFilter).contains("X-Tenant-Id", "TenantContext.setTenantId");
        assertThat(tenantListener).contains("@PrePersist", "TenantContext.currentTenantIdOrDefault");
        assertThat(tenantScopedJpaEntity)
                .contains("@MappedSuperclass", "@FilterDef", "autoEnabled = true", "applyToLoadByKey = true")
                .contains("CurrentTenantIdSupplier.class", "tenant_id = :tenantId");
        assertThat(currentTenantIdSupplier).contains("TenantContext.currentTenantIdOrDefault");
        assertThat(jwt).contains("tenant_id", "tenantId()");
        assertThat(jwtAuthenticationFilter)
                .contains("TenantContext.setTenantId(token.tenantId())")
                .contains("previousTenant.ifPresentOrElse(TenantContext::setTenantId, TenantContext::clear)");
        assertThat(userAccountStore).contains("tenantId");
        assertThat(authPrincipal).contains("tenantId");
        assertThat(loginService).contains("principal.tenantId()");
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
        assertThat(orderRepository).contains("o.tenantId = :tenantId");
        assertThat(monkeyRepository).contains("m.tenantId = :tenantId");
        assertThat(idempotencyRepository).contains("tenant_id, user_id", "record.tenantId = :tenantId");
        assertThat(stockLogRepository).contains("tenant_id, order_id");
        assertThat(addressRepository).contains("a.tenantId = ?2");
        assertThat(membershipProfileRepository).contains("p.tenantId = :tenantId");
        assertThat(pointsWalletRepository).contains("w.tenantId = :tenantId");
        assertTenantScopedEntities(
                "src/main/java/com/example/monkey/user/infrastructure/User.java",
                "src/main/java/com/example/monkey/user/infrastructure/Address.java",
                "src/main/java/com/example/monkey/order/infrastructure/Order.java",
                "src/main/java/com/example/monkey/order/infrastructure/IdempotencyRecord.java",
                "src/main/java/com/example/monkey/order/infrastructure/StockLog.java",
                "src/main/java/com/example/monkey/product/infrastructure/Monkey.java",
                "src/main/java/com/example/monkey/product/infrastructure/ProductSpu.java",
                "src/main/java/com/example/monkey/product/infrastructure/ProductSku.java",
                "src/main/java/com/example/monkey/inventory/infrastructure/InventoryStock.java",
                "src/main/java/com/example/monkey/marketing/infrastructure/MarketingCouponEntity.java",
                "src/main/java/com/example/monkey/cart/infrastructure/CartCheckoutEntity.java",
                "src/main/java/com/example/monkey/payment/infrastructure/PaymentOrderEntity.java",
                "src/main/java/com/example/monkey/logistics/infrastructure/LogisticsTrackingEntity.java",
                "src/main/java/com/example/monkey/membership/infrastructure/MembershipProfileEntity.java",
                "src/main/java/com/example/monkey/search/infrastructure/SearchHistoryEntity.java",
                "src/main/java/com/example/monkey/risk/infrastructure/RiskScoreEntity.java",
                "src/main/java/com/example/monkey/tracking/infrastructure/TrackingEventEntity.java",
                "src/main/java/com/example/monkey/shared/infrastructure/observability/AuditLog.java",
                "src/main/java/com/example/monkey/shared/infrastructure/observability/VisitLog.java");
        assertThat(verify).contains("TenantScopedJpaEntity", "WS12 SaaS verification completed successfully");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void assertTenantScopedEntities(String... paths) throws IOException {
        for (String path : paths) {
            assertThat(read(path))
                    .as(path)
                    .contains("extends TenantScopedJpaEntity")
                    .contains("import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;");
        }
    }
}
