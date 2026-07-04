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

Write-Host "==> WS11 data tracking artifacts"
$docs = Read-Text "docs/tracking/ws11.md"
$eventMigration = Read-Text "src/main/resources/db/migration/V42__tracking_event.sql"
$profileMigration = Read-Text "src/main/resources/db/migration/V43__user_profile_tag.sql"
$service = Read-Text "src/main/java/com/example/monkey/tracking/application/TrackingApplicationService.java"
$store = Read-Text "src/main/java/com/example/monkey/tracking/infrastructure/JpaTrackingStore.java"
$controller = Read-Text "src/main/java/com/example/monkey/tracking/interfaces/TrackingController.java"
$filter = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$rateLimitPolicy = Read-Text "src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java"
$securityConfig = Read-Text "src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java"
$audit = Read-Text "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$metrics = Read-Text "src/main/java/com/example/monkey/order/application/observability/BusinessMetricsService.java"
$visits = Read-Text "src/main/java/com/example/monkey/shared/application/observability/VisitMetricsService.java"
$grafana = Read-Text "helm/monkeyshop/templates/grafana-dashboard.yaml"
$workflowTest = Read-Text "src/test/java/com/example/monkey/tracking/Ws11DataWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/tracking/application/TrackingApplicationServiceTest.java"
$jpaTest = Read-Text "src/test/java/com/example/monkey/tracking/infrastructure/JpaTrackingStoreTest.java"
$frontendApi = Read-Text "frontend/src/api/tracking.ts"
$trackingSdk = Read-Text "frontend/src/TrackingSdk.ts"
$dashboardView = Read-Text "frontend/src/views/DashboardView.vue"

Assert-Matches "docs" $docs "tracking SDK"
Assert-Matches "docs" $docs "funnel"
Assert-Matches "docs" $docs "Tink"
Assert-Matches "event migration" $eventMigration "CREATE TABLE tracking_event"
Assert-Matches "event migration" $eventMigration "TRACKING_ADMIN"
Assert-Matches "event migration" $eventMigration "idx_tracking_event"
Assert-Matches "profile migration" $profileMigration "CREATE TABLE user_profile_tag"
Assert-Matches "profile migration" $profileMigration "encrypted_profile_summary"
Assert-Matches "profile migration" $profileMigration "CREATE TABLE product_profile"
Assert-Matches "service" $service "@WithSpan\(""tracking\.event-record""\)"
Assert-Matches "service" $service "VisitMetricsService"
Assert-Matches "service" $service "BusinessMetricsService"
Assert-Matches "service" $service "idGenerator\.nextId\(\)"
Assert-Matches "store" $store "PiiCryptoService"
Assert-Matches "store" $store "app\.tracking\.store"
Assert-Matches "store" $store "piiCryptoService\.encrypt"
Assert-Matches "controller" $controller "@RequestMapping\(\{""/api/tracking"""
Assert-Matches "controller" $controller "@PostMapping\(""/events""\)"
Assert-Matches "controller" $controller "@GetMapping\(""/dashboard""\)"
Assert-Matches "rate filter" $filter "/api/tracking/internal/pixel"
Assert-Matches "rate filter" $filter "ApiRateLimitOperation\.TRACKING"
Assert-Matches "rate limit" $rateLimitPolicy 'TRACKING\("tracking",\s*60,\s*Duration\.ofSeconds\(1\)\)'
Assert-Matches "security config" $securityConfig "TRACKING_READ"
Assert-Matches "security config" $securityConfig "TRACKING_ADMIN"
Assert-Matches "audit" $audit "TRACKING_EVENT_RECORDED"
Assert-Matches "audit" $audit "USER_PROFILE_TAG_UPDATED"
Assert-Matches "audit" $audit "PRODUCT_PROFILE_UPDATED"
Assert-Matches "metrics" $metrics "tracking\.event"
Assert-Matches "metrics" $metrics "tracking\.funnel"
Assert-Matches "visits" $visits "recordClientPageView"
Assert-Matches "grafana" $grafana 'tracking_event_total'
Assert-Matches "grafana" $grafana 'tracking_funnel'
Assert-Matches "workflow test" $workflowTest "trackingArtifactsWireSdkProfilesDashboardMetricsAndFrontend"
Assert-Matches "application test" $applicationTest "dashboardAggregatesPvUvPaymentAndFunnelSnapshots"
Assert-Matches "jpa test" $jpaTest "saveUserProfileEncryptsSummaryAndRoundTripsTags"
Assert-Matches "frontend api" $frontendApi "recordTrackingEvent"
Assert-Matches "tracking sdk" $trackingSdk "installTracking"
Assert-Matches "tracking sdk" $trackingSdk "PRODUCT_VIEW"
Assert-Matches "dashboard view" $dashboardView "Realtime Dashboard"
Assert-Matches "dashboard view" $dashboardView "Funnel"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS11 data tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=TrackingApplicationServiceTest,JpaTrackingStoreTest,Ws11DataWorkflowTest,SchemaMigrationTest,ArchitectureBoundaryTest,ApiRateLimitFilterTest,ApiRateLimitApplicationServiceTest,SecurityConfigTest,BusinessMetricsServiceTest,VisitMetricsServiceTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS11 data tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS11 data verification completed successfully"
