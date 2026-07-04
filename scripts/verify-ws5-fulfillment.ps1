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

Write-Host "==> WS5 fulfillment artifacts"
$docs = Read-Text "docs/order/ws5-fulfillment.md"
$shipmentMigration = Read-Text "src/main/resources/db/migration/V28__order_partial_shipment.sql"
$reviewMigration = Read-Text "src/main/resources/db/migration/V29__order_review.sql"
$service = Read-Text "src/main/java/com/example/monkey/order/application/OrderService.java"
$controller = Read-Text "src/main/java/com/example/monkey/order/interfaces/OrderController.java"
$store = Read-Text "src/main/java/com/example/monkey/order/infrastructure/JpaOrderFulfillmentStore.java"
$stateMachine = Read-Text "src/main/java/com/example/monkey/order/domain/OrderTransitionPolicy.java"
$audit = Read-Text "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$workflowTest = Read-Text "src/test/java/com/example/monkey/order/Ws5FulfillmentWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/order/application/OrderFulfillmentApplicationTest.java"
$infrastructureTest = Read-Text "src/test/java/com/example/monkey/order/infrastructure/JpaOrderFulfillmentStoreTest.java"
$frontendApi = Read-Text "frontend/src/api/orders.ts"
$reviewView = Read-Text "frontend/src/views/ReviewView.vue"

Assert-Matches "docs" $docs "partial shipment"
Assert-Matches "docs" $docs "automatic receipt"
Assert-Matches "shipment migration" $shipmentMigration "CREATE TABLE order_fulfillment_item"
Assert-Matches "shipment migration" $shipmentMigration "CREATE TABLE order_shipment_batch"
Assert-Matches "shipment migration" $shipmentMigration "CREATE TABLE order_shipment_line"
Assert-Matches "review migration" $reviewMigration "CREATE TABLE order_review"
Assert-Matches "review migration" $reviewMigration "uk_order_review_user_order_sku"
Assert-Matches "service" $service "@WithSpan\(""order\.shipment\.create""\)"
Assert-Matches "service" $service "OrderEvent\.SHIP_PARTIAL"
Assert-Matches "service" $service "OrderEvent\.RECEIVE_PARTIAL"
Assert-Matches "service" $service "@SchedulerLock\s*\(\s*name\s*=\s*""order-auto-receive-shipments"""
Assert-Matches "controller" $controller "@PostMapping\(""/shipments/\{id\}""\)"
Assert-Matches "controller" $controller "@PostMapping\(""/review/\{id\}""\)"
Assert-Matches "store" $store "app\.order\.fulfillment-store"
Assert-Matches "state machine" $stateMachine "PARTIALLY_SHIPPED"
Assert-Matches "audit" $audit "ORDER_REVIEWED"
Assert-Matches "workflow test" $workflowTest "fulfillmentArtifactsWirePartialShipmentAutoReceiptReviewAndFrontend"
Assert-Matches "application test" $applicationTest "partialShipmentCreatesBatchAndMovesOrderToPartiallyShipped"
Assert-Matches "infrastructure test" $infrastructureTest "saveShipmentPersistsBatchAndLinesWithSnowflakeIds"
Assert-Matches "frontend api" $frontendApi "reviewOrder"
Assert-Matches "review view" $reviewView "uploadImage"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS5 fulfillment tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=OrderFulfillmentDomainTest,OrderFulfillmentApplicationTest,JpaOrderFulfillmentStoreTest,Ws5FulfillmentWorkflowTest,OrderTransitionPolicyTest,SpringStateMachineOrderTransitionResolverTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS5 fulfillment tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS5 fulfillment verification completed successfully"
