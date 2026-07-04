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

Write-Host "==> WS10 risk artifacts"
$docs = Read-Text "docs/risk/ws10.md"
$deviceMigration = Read-Text "src/main/resources/db/migration/V39__risk_device_fingerprint.sql"
$scoreMigration = Read-Text "src/main/resources/db/migration/V40__risk_score.sql"
$queueMigration = Read-Text "src/main/resources/db/migration/V41__risk_audit_queue.sql"
$policy = Read-Text "src/main/java/com/example/monkey/risk/domain/RiskPolicy.java"
$service = Read-Text "src/main/java/com/example/monkey/risk/application/RiskApplicationService.java"
$blindIndex = Read-Text "src/main/java/com/example/monkey/risk/infrastructure/PiiRiskBlindIndexService.java"
$controller = Read-Text "src/main/java/com/example/monkey/risk/interfaces/RiskController.java"
$store = Read-Text "src/main/java/com/example/monkey/risk/infrastructure/JpaRiskStore.java"
$cache = Read-Text "src/main/java/com/example/monkey/risk/infrastructure/RedisRiskCache.java"
$filter = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$rateLimitPolicy = Read-Text "src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java"
$securityConfig = Read-Text "src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java"
$audit = Read-Text "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$metrics = Read-Text "src/main/java/com/example/monkey/order/application/observability/BusinessMetricsService.java"
$prometheusRule = Read-Text "helm/monkeyshop/templates/prometheusrule.yaml"
$workflowTest = Read-Text "src/test/java/com/example/monkey/risk/Ws10RiskWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/risk/application/RiskApplicationServiceTest.java"
$domainTest = Read-Text "src/test/java/com/example/monkey/risk/domain/RiskPolicyTest.java"
$jpaTest = Read-Text "src/test/java/com/example/monkey/risk/infrastructure/JpaRiskStoreTest.java"
$redisTest = Read-Text "src/test/java/com/example/monkey/risk/infrastructure/RedisRiskCacheTest.java"
$frontendApi = Read-Text "frontend/src/api/risk.ts"
$riskView = Read-Text "frontend/src/views/RiskReviewView.vue"

Assert-Matches "docs" $docs "Device fingerprint"
Assert-Matches "docs" $docs "manual review"
Assert-Matches "device migration" $deviceMigration "CREATE TABLE risk_device_fingerprint"
Assert-Matches "device migration" $deviceMigration "phone_hmac"
Assert-Matches "score migration" $scoreMigration "CREATE TABLE risk_score"
Assert-Matches "score migration" $scoreMigration "signals_json"
Assert-Matches "queue migration" $queueMigration "CREATE TABLE risk_audit_queue"
Assert-Matches "queue migration" $queueMigration "RISK_REVIEW"
Assert-Matches "policy" $policy "PRICE_ANOMALY_RATE"
Assert-Matches "policy" $policy "SECKILL_SCALPER"
Assert-Matches "service" $service "@WithSpan\(""risk\.assess""\)"
Assert-Matches "service" $service "RiskBlindIndexService"
Assert-Matches "service" $service "revokeUserTokens"
Assert-Matches "service" $service "userMfaVerifier\.verifyCode"
Assert-Matches "blind index adapter" $blindIndex "PiiCryptoService"
Assert-Matches "blind index adapter" $blindIndex "blindIndexPhone"
Assert-Matches "controller" $controller "@RequestMapping\(\{""/api/risk"""
Assert-Matches "controller" $controller "@PostMapping\(""/assess""\)"
Assert-Matches "store" $store "ProductStatus\.UNLISTED"
Assert-Matches "store" $store "app\.risk\.store"
Assert-Matches "cache" $cache "risk:device:"
Assert-Matches "cache" $cache "risk:score:user:"
Assert-Matches "cache" $cache "risk:seckill:"
Assert-Matches "rate limit" $rateLimitPolicy 'RISK\("risk",\s*20,\s*Duration\.ofSeconds\(1\)\)'
Assert-Matches "rate filter" $filter "/api/risk/internal/probe"
Assert-Matches "rate filter" $filter "ApiRateLimitOperation\.RISK"
Assert-Matches "security config" $securityConfig "RISK_WRITE"
Assert-Matches "security config" $securityConfig "RISK_REVIEW"
Assert-Matches "audit" $audit "RISK_DECISION_RECORDED"
Assert-Matches "metrics" $metrics "risk\.high_score"
Assert-Matches "prometheus rule" $prometheusRule "MonkeyShopRiskHighScoreSpike"
Assert-Matches "workflow test" $workflowTest "riskArtifactsWireAntiFraudReviewCacheAndFrontend"
Assert-Matches "application test" $applicationTest "seckillScalperBlocksAndRevokesCurrentUserTokens"
Assert-Matches "domain test" $domainTest "priceAnomalyCreatesManualReviewSignal"
Assert-Matches "jpa test" $jpaTest "saveRiskScoreSerializesSignalsAndFindsLatestScore"
Assert-Matches "redis test" $redisTest "fallbackTracksDeviceUsersPhonesSeckillAndScores"
Assert-Matches "frontend api" $frontendApi "assessRisk"
Assert-Matches "risk view" $riskView "Review Queue"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS10 risk tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=RiskPolicyTest,RiskApplicationServiceTest,JpaRiskStoreTest,RedisRiskCacheTest,Ws10RiskWorkflowTest,SchemaMigrationTest,ArchitectureBoundaryTest,ApiRateLimitFilterTest,ApiRateLimitApplicationServiceTest,BusinessMetricsServiceTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS10 risk tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS10 risk verification completed successfully"
