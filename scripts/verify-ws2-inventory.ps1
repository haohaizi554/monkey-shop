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

Write-Host "==> WS2 inventory artifacts"
$docs = Read-Text "docs/inventory/ws2.md"
$warehouseMigration = Read-Text "src/main/resources/db/migration/V22__inventory_multi_warehouse.sql"
$ledgerMigration = Read-Text "src/main/resources/db/migration/V23__inventory_stock_ledger.sql"
$service = Read-Text "src/main/java/com/example/monkey/inventory/application/InventoryApplicationService.java"
$controller = Read-Text "src/main/java/com/example/monkey/inventory/interfaces/InventoryController.java"
$stock = Read-Text "src/main/java/com/example/monkey/inventory/domain/WarehouseStock.java"
$store = Read-Text "src/main/java/com/example/monkey/inventory/infrastructure/JpaInventoryStore.java"
$lock = Read-Text "src/main/java/com/example/monkey/inventory/infrastructure/RedissonInventoryLockManager.java"
$rateLimit = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$workflowTest = Read-Text "src/test/java/com/example/monkey/inventory/Ws2InventoryWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/inventory/application/InventoryApplicationServiceTest.java"
$frontendApi = Read-Text "frontend/src/api/inventory.ts"
$frontendView = Read-Text "frontend/src/views/InventoryView.vue"

Assert-Matches "docs" $docs "available \+ locked \+ deducted \+ in_transit"
Assert-Matches "warehouse migration" $warehouseMigration "CREATE TABLE inventory_warehouse"
Assert-Matches "warehouse migration" $warehouseMigration "CREATE TABLE inventory_stock"
Assert-Matches "warehouse migration" $warehouseMigration "uk_inventory_stock_sku_warehouse"
Assert-Matches "ledger migration" $ledgerMigration "CREATE TABLE inventory_reservation"
Assert-Matches "ledger migration" $ledgerMigration "CREATE TABLE inventory_stock_ledger"
Assert-Matches "ledger migration" $ledgerMigration "uk_inventory_ledger_idempotency"
Assert-Matches "domain stock" $stock "totalQuantity\(\)"
Assert-Matches "domain stock" $stock "availableQuantity - quantity"
Assert-Matches "service" $service "@WithSpan\(""inventory\.reserve""\)"
Assert-Matches "service" $service "idGenerator\.nextId\(\)"
Assert-Matches "service" $service "name\s*=\s*""inventory-release-expired-reservations"""
Assert-Matches "service" $service "AuditService\.INVENTORY_RESERVED"
Assert-Matches "controller" $controller "@RequestMapping\(\{""/api/inventory"", ""/api/v1/inventory""\}\)"
Assert-Matches "controller" $controller "hasAuthority\('ORDER_CREATE'\)"
Assert-Matches "store" $store "implements InventoryStore"
Assert-Matches "store" $store "reconcile\(\)"
Assert-Matches "lock" $lock "inventory:sku:"
Assert-Matches "lock" $lock "tryLock\(WAIT_TIME\.toMillis\(\), LEASE_TIME\.toMillis\(\), TimeUnit\.MILLISECONDS\)"
Assert-Matches "rate limit" $rateLimit "path\.startsWith\(""/api/inventory""\)"
Assert-Matches "workflow test" $workflowTest "inventoryArtifactsWireMultiWarehouseStockToLocksAndSchedulers"
Assert-Matches "application test" $applicationTest "oneHundredConcurrentReservationsDoNotOversellTenUnits"
Assert-Matches "frontend api" $frontendApi "reserveInventory"
Assert-Matches "frontend view" $frontendView "reconcileInventory"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS2 inventory tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=WarehouseStockTest,InventoryApplicationServiceTest,Ws2InventoryWorkflowTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS2 inventory tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS2 inventory verification completed successfully"
