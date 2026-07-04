param(
    [switch]$SkipMaven
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Read-Text {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required file is missing: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Assert-Matches {
    param(
        [string]$Name,
        [string]$Content,
        [string]$Pattern
    )
    if ($Content -notmatch $Pattern) {
        throw "$Name is missing pattern: $Pattern"
    }
}

Write-Host "==> WS12 SaaS artifacts"
$docs = Read-Text "docs/saas/ws12.md"
$isolationMigration = Read-Text "src/main/resources/db/migration/V44__tenant_isolation.sql"
$managementMigration = Read-Text "src/main/resources/db/migration/V45__tenant_management.sql"
$billingMigration = Read-Text "src/main/resources/db/migration/V46__tenant_billing.sql"
$tenantContext = Read-Text "src/main/java/com/example/monkey/shared/application/tenant/TenantContext.java"
$tenantFilter = Read-Text "src/main/java/com/example/monkey/shared/interfaces/web/TenantContextFilter.java"
$entityListener = Read-Text "src/main/java/com/example/monkey/shared/infrastructure/tenant/TenantScopedEntityListener.java"
$tenantScopedJpaEntity = Read-Text "src/main/java/com/example/monkey/shared/infrastructure/tenant/TenantScopedJpaEntity.java"
$currentTenantIdSupplier = Read-Text "src/main/java/com/example/monkey/shared/infrastructure/tenant/CurrentTenantIdSupplier.java"
$jwt = Read-Text "src/main/java/com/example/monkey/user/infrastructure/JwtTokenService.java"
$jwtAuthenticationFilter = Read-Text "src/main/java/com/example/monkey/user/infrastructure/JwtAuthenticationFilter.java"
$userAccountStore = Read-Text "src/main/java/com/example/monkey/user/domain/UserAccountStore.java"
$authPrincipal = Read-Text "src/main/java/com/example/monkey/user/domain/AuthPrincipal.java"
$loginService = Read-Text "src/main/java/com/example/monkey/user/application/LoginApplicationService.java"
$sessionUser = Read-Text "src/main/java/com/example/monkey/shared/application/security/SessionUser.java"
$service = Read-Text "src/main/java/com/example/monkey/tenant/application/TenantApplicationService.java"
$store = Read-Text "src/main/java/com/example/monkey/tenant/infrastructure/JpaTenantStore.java"
$controller = Read-Text "src/main/java/com/example/monkey/tenant/interfaces/TenantAdminController.java"
$task = Read-Text "src/main/java/com/example/monkey/tenant/infrastructure/TenantDataExportTask.java"
$rateFilter = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$ratePolicy = Read-Text "src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java"
$securityConfig = Read-Text "src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java"
$audit = Read-Text "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$frontendApi = Read-Text "frontend/src/api/tenant.ts"
$frontendView = Read-Text "frontend/src/views/TenantAdminView.vue"
$router = Read-Text "frontend/src/router/index.ts"
$workflowTest = Read-Text "src/test/java/com/example/monkey/tenant/Ws12SaaSWorkflowTest.java"
$orderRepository = Read-Text "src/main/java/com/example/monkey/order/infrastructure/OrderRepository.java"
$monkeyRepository = Read-Text "src/main/java/com/example/monkey/product/infrastructure/MonkeyRepository.java"
$idempotencyRepository = Read-Text "src/main/java/com/example/monkey/order/infrastructure/IdempotencyRecordRepository.java"
$stockLogRepository = Read-Text "src/main/java/com/example/monkey/order/infrastructure/StockLogRepository.java"
$addressRepository = Read-Text "src/main/java/com/example/monkey/user/infrastructure/AddressRepository.java"
$membershipProfileRepository = Read-Text "src/main/java/com/example/monkey/membership/infrastructure/MembershipProfileRepository.java"
$pointsWalletRepository = Read-Text "src/main/java/com/example/monkey/membership/infrastructure/PointsWalletRepository.java"
$tenantScopedEntityPaths = @(
    "src/main/java/com/example/monkey/user/infrastructure/User.java",
    "src/main/java/com/example/monkey/user/infrastructure/Address.java",
    "src/main/java/com/example/monkey/order/infrastructure/Order.java",
    "src/main/java/com/example/monkey/order/infrastructure/OrderFulfillmentItemEntity.java",
    "src/main/java/com/example/monkey/order/infrastructure/OrderShipmentBatchEntity.java",
    "src/main/java/com/example/monkey/order/infrastructure/OrderShipmentLineEntity.java",
    "src/main/java/com/example/monkey/order/infrastructure/OrderReviewEntity.java",
    "src/main/java/com/example/monkey/order/infrastructure/IdempotencyRecord.java",
    "src/main/java/com/example/monkey/order/infrastructure/StockLog.java",
    "src/main/java/com/example/monkey/product/infrastructure/Monkey.java",
    "src/main/java/com/example/monkey/product/infrastructure/ProductSpu.java",
    "src/main/java/com/example/monkey/product/infrastructure/ProductSku.java",
    "src/main/java/com/example/monkey/product/infrastructure/ProductCategory.java",
    "src/main/java/com/example/monkey/product/infrastructure/ProductAttributeTemplate.java",
    "src/main/java/com/example/monkey/inventory/infrastructure/InventoryWarehouse.java",
    "src/main/java/com/example/monkey/inventory/infrastructure/InventoryStock.java",
    "src/main/java/com/example/monkey/inventory/infrastructure/InventoryReservationEntity.java",
    "src/main/java/com/example/monkey/inventory/infrastructure/InventoryStockLedger.java",
    "src/main/java/com/example/monkey/marketing/infrastructure/MarketingCouponEntity.java",
    "src/main/java/com/example/monkey/marketing/infrastructure/MarketingUserCouponEntity.java",
    "src/main/java/com/example/monkey/marketing/infrastructure/MarketingSeckillActivityEntity.java",
    "src/main/java/com/example/monkey/marketing/infrastructure/MarketingSeckillOrderEntity.java",
    "src/main/java/com/example/monkey/marketing/infrastructure/MarketingGroupBuyActivityEntity.java",
    "src/main/java/com/example/monkey/marketing/infrastructure/MarketingGroupBuyTeamEntity.java",
    "src/main/java/com/example/monkey/marketing/infrastructure/MarketingGroupBuyMemberEntity.java",
    "src/main/java/com/example/monkey/cart/infrastructure/CartCheckoutEntity.java",
    "src/main/java/com/example/monkey/cart/infrastructure/CartSubOrderEntity.java",
    "src/main/java/com/example/monkey/cart/infrastructure/CartCheckoutLineEntity.java",
    "src/main/java/com/example/monkey/payment/infrastructure/PaymentOrderEntity.java",
    "src/main/java/com/example/monkey/payment/infrastructure/PaymentLedgerEntity.java",
    "src/main/java/com/example/monkey/payment/infrastructure/PaymentCallbackLogEntity.java",
    "src/main/java/com/example/monkey/payment/infrastructure/PaymentReconciliationReportEntity.java",
    "src/main/java/com/example/monkey/logistics/infrastructure/LogisticsTrackingEntity.java",
    "src/main/java/com/example/monkey/logistics/infrastructure/LogisticsTrackingEventEntity.java",
    "src/main/java/com/example/monkey/logistics/infrastructure/LogisticsWebhookLogEntity.java",
    "src/main/java/com/example/monkey/logistics/infrastructure/FreightTemplateEntity.java",
    "src/main/java/com/example/monkey/membership/infrastructure/MembershipProfileEntity.java",
    "src/main/java/com/example/monkey/membership/infrastructure/MembershipLevelHistoryEntity.java",
    "src/main/java/com/example/monkey/membership/infrastructure/PointsWalletEntity.java",
    "src/main/java/com/example/monkey/membership/infrastructure/PointsLedgerEntity.java",
    "src/main/java/com/example/monkey/membership/infrastructure/MembershipCheckInEntity.java",
    "src/main/java/com/example/monkey/membership/infrastructure/MemberCollectionEntity.java",
    "src/main/java/com/example/monkey/membership/infrastructure/PriceDropEventEntity.java",
    "src/main/java/com/example/monkey/search/infrastructure/SearchHistoryEntity.java",
    "src/main/java/com/example/monkey/search/infrastructure/UserSearchProfileEntity.java",
    "src/main/java/com/example/monkey/risk/infrastructure/RiskDeviceFingerprintEntity.java",
    "src/main/java/com/example/monkey/risk/infrastructure/RiskScoreEntity.java",
    "src/main/java/com/example/monkey/risk/infrastructure/RiskReviewCaseEntity.java",
    "src/main/java/com/example/monkey/tracking/infrastructure/TrackingEventEntity.java",
    "src/main/java/com/example/monkey/tracking/infrastructure/UserProfileTagEntity.java",
    "src/main/java/com/example/monkey/tracking/infrastructure/ProductProfileEntity.java",
    "src/main/java/com/example/monkey/shared/infrastructure/observability/AuditLog.java",
    "src/main/java/com/example/monkey/shared/infrastructure/observability/VisitLog.java"
)

Assert-Matches "docs" $docs "TenantContextFilter"
Assert-Matches "docs" $docs "ShedLock"
Assert-Matches "docs" $docs "PiiCryptoService"
Assert-Matches "docs" $docs "tenant-aware unique constraints"
Assert-Matches "docs" $docs "webhook event keys"
Assert-Matches "tenant isolation migration" $isolationMigration "CREATE TABLE tenant"
Assert-Matches "tenant isolation migration" $isolationMigration 'ALTER TABLE `orders` ADD COLUMN tenant_id'
Assert-Matches "tenant isolation migration" $isolationMigration "ALTER TABLE tracking_event ADD COLUMN tenant_id"
Assert-Matches "tenant isolation migration" $isolationMigration "fk_orders_tenant"
Assert-Matches "tenant isolation migration" $isolationMigration "idx_tracking_event_tenant_type_time"
Assert-Matches "tenant isolation migration" $isolationMigration "uk_inventory_reservation_key UNIQUE \(tenant_id, reservation_key\)"
Assert-Matches "tenant isolation migration" $isolationMigration "uk_inventory_ledger_idempotency UNIQUE \(tenant_id, idempotency_key\)"
Assert-Matches "tenant isolation migration" $isolationMigration "uk_marketing_coupon_code UNIQUE \(tenant_id, code\)"
Assert-Matches "tenant isolation migration" $isolationMigration "uk_cart_checkout_line_reservation UNIQUE \(tenant_id, reservation_key\)"
Assert-Matches "tenant isolation migration" $isolationMigration "uk_payment_callback_provider_id UNIQUE \(tenant_id, provider, callback_id\)"
Assert-Matches "tenant isolation migration" $isolationMigration "uk_logistics_webhook_carrier_event UNIQUE \(tenant_id, carrier, event_id\)"
Assert-Matches "tenant isolation migration" $isolationMigration "uk_logistics_freight_template UNIQUE \(tenant_id, carrier, province, charge_mode\)"
Assert-Matches "tenant management migration" $managementMigration "CREATE TABLE tenant_config"
Assert-Matches "tenant management migration" $managementMigration "CREATE TABLE tenant_rollout_policy"
Assert-Matches "tenant management migration" $managementMigration "TENANT_ADMIN"
Assert-Matches "tenant billing migration" $billingMigration "CREATE TABLE tenant_bill"
Assert-Matches "tenant billing migration" $billingMigration "CREATE TABLE tenant_data_export_job"
Assert-Matches "tenant billing migration" $billingMigration "tenant_billing_reconciliation"
Assert-Matches "tenant context" $tenantContext "ThreadLocal<Long>"
Assert-Matches "tenant filter" $tenantFilter "X-Tenant-Id"
Assert-Matches "tenant filter" $tenantFilter "TenantContext\.setTenantId"
Assert-Matches "tenant entity listener" $entityListener "@PrePersist"
Assert-Matches "tenant entity listener" $entityListener "TenantContext\.currentTenantIdOrDefault"
Assert-Matches "tenant scoped JPA entity" $tenantScopedJpaEntity "@MappedSuperclass"
Assert-Matches "tenant scoped JPA entity" $tenantScopedJpaEntity "@FilterDef"
Assert-Matches "tenant scoped JPA entity" $tenantScopedJpaEntity "autoEnabled = true"
Assert-Matches "tenant scoped JPA entity" $tenantScopedJpaEntity "applyToLoadByKey = true"
Assert-Matches "tenant scoped JPA entity" $tenantScopedJpaEntity "tenant_id = :tenantId"
Assert-Matches "tenant supplier" $currentTenantIdSupplier "TenantContext\.currentTenantIdOrDefault"
Assert-Matches "jwt service" $jwt "tenant_id"
Assert-Matches "jwt authentication filter" $jwtAuthenticationFilter "TenantContext\.setTenantId\(token\.tenantId\(\)\)"
Assert-Matches "jwt authentication filter" $jwtAuthenticationFilter "previousTenant\.ifPresentOrElse\(TenantContext::setTenantId, TenantContext::clear\)"
Assert-Matches "user account store" $userAccountStore "tenantId"
Assert-Matches "auth principal" $authPrincipal "tenantId"
Assert-Matches "login service" $loginService "principal\.tenantId\(\)"
Assert-Matches "session user" $sessionUser "tenantId"
Assert-Matches "service" $service "@WithSpan\(""tenant\.create""\)"
Assert-Matches "service" $service "idGenerator\.nextId\(\)"
Assert-Matches "service" $service "TENANT_EXPORT_REQUESTED"
Assert-Matches "store" $store "PiiCryptoService"
Assert-Matches "store" $store "app\.tenant\.store"
Assert-Matches "store" $store "countOrdersForTenant"
Assert-Matches "controller" $controller "@RequestMapping\(\{""/api/tenants"""
Assert-Matches "controller" $controller "TENANT_ADMIN"
Assert-Matches "task" $task "@SchedulerLock\(name = ""tenant-data-export"""
Assert-Matches "rate filter" $rateFilter "ApiRateLimitOperation\.TENANT"
Assert-Matches "rate policy" $ratePolicy 'TENANT\("tenant",\s*30,\s*Duration\.ofSeconds\(1\)\)'
Assert-Matches "security config" $securityConfig "/tenants"
Assert-Matches "security config" $securityConfig "TENANT_READ"
Assert-Matches "security config" $securityConfig "TENANT_ADMIN"
Assert-Matches "audit" $audit "TENANT_CREATED"
Assert-Matches "audit" $audit "TENANT_BILL_GENERATED"
Assert-Matches "frontend api" $frontendApi "tenantDashboard"
Assert-Matches "frontend view" $frontendView "Tenant Command Center"
Assert-Matches "router" $router "TenantAdminView"
Assert-Matches "workflow test" $workflowTest "ws12SaaSArtifactsWireTenantIsolationBillingExportsAndFrontend"
Assert-Matches "order repository" $orderRepository "o\.tenantId = :tenantId"
Assert-Matches "monkey repository" $monkeyRepository "m\.tenantId = :tenantId"
Assert-Matches "idempotency repository" $idempotencyRepository "tenant_id, user_id"
Assert-Matches "idempotency repository" $idempotencyRepository "record\.tenantId = :tenantId"
Assert-Matches "stock log repository" $stockLogRepository "tenant_id, order_id"
Assert-Matches "address repository" $addressRepository "a\.tenantId = \?2"
Assert-Matches "membership profile repository" $membershipProfileRepository "p\.tenantId = :tenantId"
Assert-Matches "points wallet repository" $pointsWalletRepository "w\.tenantId = :tenantId"

foreach ($entityPath in $tenantScopedEntityPaths) {
    $entity = Read-Text $entityPath
    Assert-Matches $entityPath $entity "extends TenantScopedJpaEntity"
    Assert-Matches $entityPath $entity "import com\.example\.monkey\.shared\.infrastructure\.tenant\.TenantScopedJpaEntity;"
}

if (-not $SkipMaven) {
    Write-Host "==> Maven WS12 SaaS tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=TenantApplicationServiceTest,JpaTenantStoreTest,TenantContextFilterTest,TenantScopedEntityListenerTest,TenantScopedJpaEntityTest,Ws12SaaSWorkflowTest,SchemaMigrationTest,ArchitectureBoundaryTest,ApiRateLimitFilterTest,ApiRateLimitApplicationServiceTest,SecurityConfigTest,JwtTokenServiceTest,JwtAuthenticationFilterTest,AuthenticationApplicationServiceTest,LoginApplicationServiceTest,RefreshTokenApplicationServiceTest,SessionTokenApplicationServiceTest,UserServiceTest,JpaUserAccountStoreTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS12 SaaS tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS12 SaaS verification completed successfully"
