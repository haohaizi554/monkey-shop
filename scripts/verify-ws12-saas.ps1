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
$jwt = Read-Text "src/main/java/com/example/monkey/user/infrastructure/JwtTokenService.java"
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

Assert-Matches "docs" $docs "TenantContextFilter"
Assert-Matches "docs" $docs "ShedLock"
Assert-Matches "docs" $docs "PiiCryptoService"
Assert-Matches "tenant isolation migration" $isolationMigration "CREATE TABLE tenant"
Assert-Matches "tenant isolation migration" $isolationMigration 'ALTER TABLE `orders` ADD COLUMN tenant_id'
Assert-Matches "tenant isolation migration" $isolationMigration "ALTER TABLE tracking_event ADD COLUMN tenant_id"
Assert-Matches "tenant isolation migration" $isolationMigration "fk_orders_tenant"
Assert-Matches "tenant isolation migration" $isolationMigration "idx_tracking_event_tenant_type_time"
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
Assert-Matches "jwt service" $jwt "tenant_id"
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

if (-not $SkipMaven) {
    Write-Host "==> Maven WS12 SaaS tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=TenantApplicationServiceTest,JpaTenantStoreTest,TenantContextFilterTest,TenantScopedEntityListenerTest,Ws12SaaSWorkflowTest,SchemaMigrationTest,ArchitectureBoundaryTest,ApiRateLimitFilterTest,ApiRateLimitApplicationServiceTest,SecurityConfigTest,JwtTokenServiceTest,JwtAuthenticationFilterTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS12 SaaS tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS12 SaaS verification completed successfully"
