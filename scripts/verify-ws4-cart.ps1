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

Write-Host "==> WS4 cart artifacts"
$docs = Read-Text "docs/cart/ws4.md"
$migration = Read-Text "src/main/resources/db/migration/V27__cart.sql"
$service = Read-Text "src/main/java/com/example/monkey/cart/application/CartApplicationService.java"
$controller = Read-Text "src/main/java/com/example/monkey/cart/interfaces/CartController.java"
$redisStore = Read-Text "src/main/java/com/example/monkey/cart/infrastructure/RedisCartStore.java"
$checkoutStore = Read-Text "src/main/java/com/example/monkey/cart/infrastructure/JpaCartCheckoutStore.java"
$lock = Read-Text "src/main/java/com/example/monkey/cart/infrastructure/RedissonCartLockManager.java"
$rateLimit = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$audit = Read-Text "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$workflowTest = Read-Text "src/test/java/com/example/monkey/cart/Ws4CartWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/cart/application/CartApplicationServiceTest.java"
$infrastructureTest = Read-Text "src/test/java/com/example/monkey/cart/infrastructure/CartInfrastructureTest.java"
$frontendApi = Read-Text "frontend/src/api/cart.ts"
$cartView = Read-Text "frontend/src/views/CartView.vue"
$checkoutView = Read-Text "frontend/src/views/CheckoutView.vue"

Assert-Matches "docs" $docs "cross-shop cart"
Assert-Matches "docs" $docs "Idempotency-Key"
Assert-Matches "migration" $migration "CREATE TABLE cart_checkout"
Assert-Matches "migration" $migration "CREATE TABLE cart_sub_order"
Assert-Matches "migration" $migration "CREATE TABLE cart_checkout_line"
Assert-Matches "migration" $migration "uk_cart_checkout_user_idempotency"
Assert-Matches "service" $service "@WithSpan\(""cart\.checkout""\)"
Assert-Matches "service" $service "InventoryApplicationService"
Assert-Matches "service" $service "MarketingApplicationService"
Assert-Matches "service" $service "removeItems"
Assert-Matches "controller" $controller "@RequestMapping\(\{""/api/cart"", ""/api/v1/cart""\}\)"
Assert-Matches "redis store" $redisStore "cart:user:"
Assert-Matches "redis store" $redisStore "opsForHash\(\)"
Assert-Matches "checkout store" $checkoutStore "findByUserIdAndIdempotencyKey"
Assert-Matches "lock" $lock "cart:checkout:"
Assert-Matches "lock" $lock "tryLock"
Assert-Matches "rate limit" $rateLimit "ApiRateLimitOperation\.CART"
Assert-Matches "audit" $audit "CART_CHECKOUT_CREATED"
Assert-Matches "workflow test" $workflowTest "cartArtifactsWireRedisSplitCheckoutInventoryAndMarketing"
Assert-Matches "application test" $applicationTest "checkoutSplitsByShopReservesInventoryAndClearsSelectedItems"
Assert-Matches "infrastructure test" $infrastructureTest "redisCartStoreFallsBackToLocalHashWhenRedisIsUnavailable"
Assert-Matches "frontend api" $frontendApi "checkoutCart"
Assert-Matches "cart view" $cartView "addCartItem"
Assert-Matches "checkout view" $checkoutView "previewCartCheckout"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS4 cart tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=CartDomainTest,CartApplicationServiceTest,CartInfrastructureTest,Ws4CartWorkflowTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS4 cart tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS4 cart verification completed successfully"
