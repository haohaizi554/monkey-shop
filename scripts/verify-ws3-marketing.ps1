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

Write-Host "==> WS3 marketing artifacts"
$docs = Read-Text "docs/marketing/ws3.md"
$couponMigration = Read-Text "src/main/resources/db/migration/V24__marketing_coupon.sql"
$seckillMigration = Read-Text "src/main/resources/db/migration/V25__marketing_seckill.sql"
$groupMigration = Read-Text "src/main/resources/db/migration/V26__marketing_group_buy.sql"
$service = Read-Text "src/main/java/com/example/monkey/marketing/application/MarketingApplicationService.java"
$controller = Read-Text "src/main/java/com/example/monkey/marketing/interfaces/MarketingController.java"
$lock = Read-Text "src/main/java/com/example/monkey/marketing/infrastructure/RedissonMarketingLockManager.java"
$idempotency = Read-Text "src/main/java/com/example/monkey/marketing/infrastructure/RedisMarketingIdempotencyStore.java"
$rateLimit = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$workflowTest = Read-Text "src/test/java/com/example/monkey/marketing/Ws3MarketingWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/marketing/application/MarketingApplicationServiceTest.java"
$infrastructureTest = Read-Text "src/test/java/com/example/monkey/marketing/infrastructure/MarketingInfrastructureTest.java"
$frontendApi = Read-Text "frontend/src/api/marketing.ts"
$frontendView = Read-Text "frontend/src/views/MarketingView.vue"

Assert-Matches "docs" $docs "1000 concurrent seckill attempts"
Assert-Matches "coupon migration" $couponMigration "CREATE TABLE marketing_coupon"
Assert-Matches "coupon migration" $couponMigration "uk_marketing_user_coupon_user_coupon"
Assert-Matches "seckill migration" $seckillMigration "CREATE TABLE marketing_seckill_activity"
Assert-Matches "seckill migration" $seckillMigration "uk_marketing_seckill_user_idempotency"
Assert-Matches "group migration" $groupMigration "CREATE TABLE marketing_group_buy_team"
Assert-Matches "group migration" $groupMigration "uk_marketing_group_buy_member_user"
Assert-Matches "service" $service "@WithSpan\(""marketing\.seckill\.order""\)"
Assert-Matches "service" $service "captchaService\.externalProviderEnabled\(\)"
Assert-Matches "service" $service "AuditService\.MARKETING_SECKILL_ORDERED"
Assert-Matches "service" $service "name\s*=\s*""marketing-expire-group-buy-teams"""
Assert-Matches "controller" $controller "@RequestMapping\(\{""/api/marketing"", ""/api/v1/marketing""\}\)"
Assert-Matches "lock" $lock "marketing:seckill:activity:"
Assert-Matches "lock" $lock "tryLock\(WAIT_TIME\.toMillis\(\), LEASE_TIME\.toMillis\(\), TimeUnit\.MILLISECONDS\)"
Assert-Matches "idempotency" $idempotency "marketing:idempotency:"
Assert-Matches "rate limit" $rateLimit "/api/seckill/internal/active"
Assert-Matches "rate limit" $rateLimit "ApiRateLimitOperation\.SECKILL"
Assert-Matches "workflow test" $workflowTest "marketingArtifactsWireCouponSeckillGroupBuyToSharedGuards"
Assert-Matches "application test" $applicationTest "oneThousandConcurrentSeckillOrdersOnlySellTenUnits"
Assert-Matches "infrastructure test" $infrastructureTest "jpaMarketingStoreMapsCouponUserCouponSeckillAndGroupBuyModels"
Assert-Matches "frontend api" $frontendApi "createSeckillOrder"
Assert-Matches "frontend view" $frontendView "runJoinGroup"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS3 marketing tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=MarketingDomainTest,MarketingApplicationServiceTest,MarketingInfrastructureTest,Ws3MarketingWorkflowTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS3 marketing tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS3 marketing verification completed successfully"
