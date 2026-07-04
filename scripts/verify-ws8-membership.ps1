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

Write-Host "==> WS8 membership artifacts"
$docs = Read-Text "docs/membership/ws8.md"
$levelMigration = Read-Text "src/main/resources/db/migration/V34__membership_level.sql"
$walletMigration = Read-Text "src/main/resources/db/migration/V35__membership_points_wallet.sql"
$collectionMigration = Read-Text "src/main/resources/db/migration/V36__membership_collection.sql"
$service = Read-Text "src/main/java/com/example/monkey/membership/application/MembershipApplicationService.java"
$controller = Read-Text "src/main/java/com/example/monkey/membership/interfaces/MembershipController.java"
$store = Read-Text "src/main/java/com/example/monkey/membership/infrastructure/JpaMembershipStore.java"
$activityStore = Read-Text "src/main/java/com/example/monkey/membership/infrastructure/RedisMembershipActivityStore.java"
$stateMachine = Read-Text "src/main/java/com/example/monkey/membership/infrastructure/SpringStateMachineMembershipLevelTransitionResolver.java"
$rateLimit = Read-Text "src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java"
$apiRateLimit = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$audit = Read-Text "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$workflowTest = Read-Text "src/test/java/com/example/monkey/membership/Ws8MembershipWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/membership/application/MembershipApplicationServiceTest.java"
$jpaStoreTest = Read-Text "src/test/java/com/example/monkey/membership/infrastructure/JpaMembershipStoreTest.java"
$redisTest = Read-Text "src/test/java/com/example/monkey/membership/infrastructure/RedisMembershipActivityStoreTest.java"
$securityConfigTest = Read-Text "src/test/java/com/example/monkey/shared/infrastructure/config/SecurityConfigTest.java"
$frontendApi = Read-Text "frontend/src/api/membership.ts"
$membershipView = Read-Text "frontend/src/views/MembershipView.vue"

Assert-Matches "docs" $docs "points wallet"
Assert-Matches "docs" $docs "Browsing history"
Assert-Matches "docs" $docs "TOTP"
Assert-Matches "level migration" $levelMigration "CREATE TABLE membership_profile"
Assert-Matches "level migration" $levelMigration "real_name_hmac"
Assert-Matches "level migration" $levelMigration "id_card_hmac"
Assert-Matches "level migration" $levelMigration "MEMBERSHIP_READ"
Assert-Matches "wallet migration" $walletMigration "CREATE TABLE membership_points_wallet"
Assert-Matches "wallet migration" $walletMigration "CREATE TABLE membership_points_ledger"
Assert-Matches "wallet migration" $walletMigration "uk_membership_check_in_user_date"
Assert-Matches "collection migration" $collectionMigration "CREATE TABLE membership_collection"
Assert-Matches "collection migration" $collectionMigration "CREATE TABLE membership_price_drop_event"
Assert-Matches "collection migration" $collectionMigration "CREATE TABLE membership_browse_history"
Assert-Matches "service" $service "@WithSpan\(""membership\.check-in""\)"
Assert-Matches "service" $service "userMfaVerifier\.verifyCode"
Assert-Matches "service" $service "idGenerator\.nextId"
Assert-Matches "service" $service "scanPriceDrops"
Assert-Matches "controller" $controller "@RequestMapping\(\{""/api/membership"""
Assert-Matches "controller" $controller "@PostMapping\(""/check-in""\)"
Assert-Matches "controller" $controller "@PostMapping\(""/points/redeem""\)"
Assert-Matches "store" $store "piiCryptoService\.encrypt"
Assert-Matches "store" $store "piiCryptoService\.blindIndex"
Assert-Matches "activity store" $activityStore "membership:browse:user:"
Assert-Matches "activity store" $activityStore "StringRedisTemplate"
Assert-Matches "state machine" $stateMachine "StateMachineBuilder"
Assert-Matches "rate limit" $rateLimit "MEMBERSHIP\(""membership"", 10"
Assert-Matches "api rate limit" $apiRateLimit "ApiRateLimitOperation\.MEMBERSHIP"
Assert-Matches "audit" $audit "MEMBERSHIP_CHECKED_IN"
Assert-Matches "audit" $audit "MEMBERSHIP_POINTS_EARNED"
Assert-Matches "workflow test" $workflowTest "membershipArtifactsWireLevelWalletCollectionBrowsingAndFrontend"
Assert-Matches "application test" $applicationTest "checkInIsIdempotentAndPostsLedger"
Assert-Matches "application test" $applicationTest "levelChangeRequiresTotpAndCas"
Assert-Matches "jpa store test" $jpaStoreTest "profileWalletLedgerCheckInAndLevelHistoryRoundTripThroughJpaEntities"
Assert-Matches "jpa store test" $jpaStoreTest "collectionCouponProductAndPriceDropMappingRoundTripsThroughJpaEntities"
Assert-Matches "redis test" $redisTest "recordKeepsRecentBrowseHistoryWithFallbackTtl"
Assert-Matches "security config test" $securityConfigTest "MEMBERSHIP_READ"
Assert-Matches "security config test" $securityConfigTest "MEMBERSHIP_ADMIN"
Assert-Matches "frontend api" $frontendApi "membershipDashboard"
Assert-Matches "frontend api" $frontendApi "checkIn"
Assert-Matches "membership view" $membershipView "membershipApi\.checkIn"
Assert-Matches "membership view" $membershipView "scanPriceDrops"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS8 membership tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=MembershipDomainTest,MembershipApplicationServiceTest,JpaMembershipStoreTest,RedisMembershipActivityStoreTest,SpringStateMachineMembershipLevelTransitionResolverTest,Ws8MembershipWorkflowTest,SchemaMigrationTest,ArchitectureBoundaryTest,ApiRateLimitFilterTest,SecurityConfigTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS8 membership tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS8 membership verification completed successfully"
