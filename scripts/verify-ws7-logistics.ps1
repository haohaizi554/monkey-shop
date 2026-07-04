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

Write-Host "==> WS7 logistics artifacts"
$docs = Read-Text "docs/logistics/ws7.md"
$trackingMigration = Read-Text "src/main/resources/db/migration/V32__logistics_tracking.sql"
$freightMigration = Read-Text "src/main/resources/db/migration/V33__logistics_freight_template.sql"
$service = Read-Text "src/main/java/com/example/monkey/logistics/application/LogisticsApplicationService.java"
$controller = Read-Text "src/main/java/com/example/monkey/logistics/interfaces/LogisticsController.java"
$store = Read-Text "src/main/java/com/example/monkey/logistics/infrastructure/JpaLogisticsStore.java"
$replayGuard = Read-Text "src/main/java/com/example/monkey/logistics/infrastructure/RedisLogisticsWebhookReplayGuard.java"
$stateMachine = Read-Text "src/main/java/com/example/monkey/logistics/domain/LogisticsTransitionPolicy.java"
$addressParser = Read-Text "src/main/java/com/example/monkey/logistics/infrastructure/RuleBasedAddressParser.java"
$rateLimit = Read-Text "src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java"
$apiRateLimit = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$audit = Read-Text "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$workflowTest = Read-Text "src/test/java/com/example/monkey/logistics/Ws7LogisticsWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/logistics/application/LogisticsApplicationServiceTest.java"
$infrastructureTest = Read-Text "src/test/java/com/example/monkey/logistics/infrastructure/JpaLogisticsStoreTest.java"
$frontendApi = Read-Text "frontend/src/api/logistics.ts"
$logisticsView = Read-Text "frontend/src/views/LogisticsView.vue"

Assert-Matches "docs" $docs "tracking state machine"
Assert-Matches "docs" $docs "webhook replay"
Assert-Matches "docs" $docs "PII"
Assert-Matches "tracking migration" $trackingMigration "CREATE TABLE logistics_tracking"
Assert-Matches "tracking migration" $trackingMigration "CREATE TABLE logistics_tracking_event"
Assert-Matches "tracking migration" $trackingMigration "CREATE TABLE logistics_webhook_log"
Assert-Matches "tracking migration" $trackingMigration "recipient_phone_hmac"
Assert-Matches "tracking migration" $trackingMigration "version BIGINT"
Assert-Matches "freight migration" $freightMigration "CREATE TABLE logistics_freight_template"
Assert-Matches "freight migration" $freightMigration "'SF'"
Assert-Matches "freight migration" $freightMigration "'ZTO'"
Assert-Matches "freight migration" $freightMigration "'YTO'"
Assert-Matches "service" $service "@WithSpan\(""logistics\.create""\)"
Assert-Matches "service" $service "@WithSpan\(""logistics\.webhook""\)"
Assert-Matches "service" $service "idGenerator\.nextId"
Assert-Matches "service" $service "transitionResolver\.nextStatus"
Assert-Matches "controller" $controller "@PostMapping\(""/shipments""\)"
Assert-Matches "controller" $controller "@PostMapping\(""/freight/quote""\)"
Assert-Matches "controller" $controller "@PostMapping\(""/address/parse""\)"
Assert-Matches "controller" $controller "@PostMapping\(""/webhook""\)"
Assert-Matches "store" $store "piiCryptoService\.encrypt"
Assert-Matches "store" $store "piiCryptoService\.blindIndex"
Assert-Matches "replay guard" $replayGuard "setIfAbsent"
Assert-Matches "replay guard" $replayGuard "LogisticsWebhookLogRepository"
Assert-Matches "state machine" $stateMachine "PICKUP"
Assert-Matches "state machine" $stateMachine "DISPATCH"
Assert-Matches "state machine" $stateMachine "SIGN"
Assert-Matches "address parser" $addressParser "Hangzou"
Assert-Matches "address parser" $addressParser "ParsedAddress"
Assert-Matches "rate limit" $rateLimit "LOGISTICS\(""logistics"", 20"
Assert-Matches "api rate limit" $apiRateLimit "ApiRateLimitOperation\.LOGISTICS"
Assert-Matches "audit" $audit "LOGISTICS_SHIPMENT_CREATED"
Assert-Matches "audit" $audit "LOGISTICS_WEBHOOK_ACCEPTED"
Assert-Matches "workflow test" $workflowTest "logisticsArtifactsWireTrackingFreightAddressWebhookAndFrontend"
Assert-Matches "application test" $applicationTest "createShipmentCalculatesFreightAndIsIdempotent"
Assert-Matches "application test" $applicationTest "webhookAdvancesTrackingOnceForReplayProtectedEvent"
Assert-Matches "infrastructure test" $infrastructureTest "saveTrackingEncryptsRecipientPhoneAndAddressBlindIndexes"
Assert-Matches "frontend api" $frontendApi "createShipment"
Assert-Matches "frontend api" $frontendApi "quoteFreight"
Assert-Matches "frontend api" $frontendApi "pushWebhook"
Assert-Matches "logistics view" $logisticsView "logisticsApi\.createShipment"
Assert-Matches "logistics view" $logisticsView "logisticsApi\.pushWebhook"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS7 logistics tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=LogisticsDomainTest,LogisticsApplicationServiceTest,JpaLogisticsStoreTest,SpringStateMachineLogisticsTransitionResolverTest,Ws7LogisticsWorkflowTest,SchemaMigrationTest,ArchitectureBoundaryTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS7 logistics tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS7 logistics verification completed successfully"
