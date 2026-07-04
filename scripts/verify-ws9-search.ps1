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

Write-Host "==> WS9 search artifacts"
$docs = Read-Text "docs/search/ws9.md"
$historyMigration = Read-Text "src/main/resources/db/migration/V37__search_history.sql"
$profileMigration = Read-Text "src/main/resources/db/migration/V38__user_search_profile.sql"
$service = Read-Text "src/main/java/com/example/monkey/search/application/SearchApplicationService.java"
$controller = Read-Text "src/main/java/com/example/monkey/search/interfaces/SearchController.java"
$store = Read-Text "src/main/java/com/example/monkey/search/infrastructure/JpaSearchStore.java"
$activityStore = Read-Text "src/main/java/com/example/monkey/search/infrastructure/RedisSearchActivityStore.java"
$filter = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$securityConfig = Read-Text "src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java"
$audit = Read-Text "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$metrics = Read-Text "src/main/java/com/example/monkey/order/application/observability/BusinessMetricsService.java"
$workflowTest = Read-Text "src/test/java/com/example/monkey/search/Ws9SearchWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/search/application/SearchApplicationServiceTest.java"
$domainTest = Read-Text "src/test/java/com/example/monkey/search/domain/RecommendationEngineTest.java"
$redisTest = Read-Text "src/test/java/com/example/monkey/search/infrastructure/RedisSearchActivityStoreTest.java"
$frontendApi = Read-Text "frontend/src/api/search.ts"
$searchView = Read-Text "frontend/src/views/SearchView.vue"
$recommendView = Read-Text "frontend/src/views/RecommendView.vue"

Assert-Matches "docs" $docs "Search suggestions"
Assert-Matches "docs" $docs "hot keyword"
Assert-Matches "docs" $docs "Tink encryption"
Assert-Matches "history migration" $historyMigration "CREATE TABLE search_history"
Assert-Matches "history migration" $historyMigration "idx_search_history_keyword_created"
Assert-Matches "history migration" $historyMigration "clicked_product_id"
Assert-Matches "profile migration" $profileMigration "CREATE TABLE user_search_profile"
Assert-Matches "profile migration" $profileMigration "encrypted_interest_profile"
Assert-Matches "profile migration" $profileMigration "interest_profile_hmac"
Assert-Matches "profile migration" $profileMigration "SEARCH_READ"
Assert-Matches "service" $service "@WithSpan\(""search\.products""\)"
Assert-Matches "service" $service "MembershipActivityStore"
Assert-Matches "service" $service "recentPurchases"
Assert-Matches "service" $service "recordSearchConversion"
Assert-Matches "controller" $controller "@RequestMapping\(\{""/api/search"""
Assert-Matches "controller" $controller "@GetMapping\(""/products""\)"
Assert-Matches "controller" $controller "@GetMapping\(""/recommendations""\)"
Assert-Matches "store" $store "piiCryptoService\.encrypt"
Assert-Matches "store" $store "piiCryptoService\.blindIndex"
Assert-Matches "store" $store "findByStatusOrderByIdDesc"
Assert-Matches "activity store" $activityStore "search:hot-keywords"
Assert-Matches "activity store" $activityStore "search:suggest:"
Assert-Matches "activity store" $activityStore "StringRedisTemplate"
Assert-Matches "rate filter" $filter "/api/search/internal/hot"
Assert-Matches "rate filter" $filter "ApiRateLimitOperation\.SEARCH"
Assert-Matches "security config" $securityConfig "SEARCH_READ"
Assert-Matches "security config" $securityConfig "SEARCH_WRITE"
Assert-Matches "audit" $audit "SEARCH_QUERY_RECORDED"
Assert-Matches "audit" $audit "SEARCH_CONVERSION_RECORDED"
Assert-Matches "metrics" $metrics "search\.conversion"
Assert-Matches "workflow test" $workflowTest "searchArtifactsWireDiscoveryRecommendationProfileAndFrontend"
Assert-Matches "application test" $applicationTest "searchRecordsHistoryHotKeywordAndSuggestions"
Assert-Matches "domain test" $domainTest "ranksBrowsePurchaseAndProfileSignals"
Assert-Matches "redis test" $redisTest "fallbackRecordsHotKeywordsAndCachedSuggestions"
Assert-Matches "frontend api" $frontendApi "searchProducts"
Assert-Matches "frontend api" $frontendApi "recommendations"
Assert-Matches "search view" $searchView "recordSearchConversion"
Assert-Matches "recommend view" $recommendView "updateSearchProfile"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS9 search tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=RecommendationEngineTest,SearchApplicationServiceTest,RedisSearchActivityStoreTest,Ws9SearchWorkflowTest,SchemaMigrationTest,ArchitectureBoundaryTest,ApiRateLimitFilterTest,SecurityConfigTest,BusinessMetricsServiceTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS9 search tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS9 search verification completed successfully"
