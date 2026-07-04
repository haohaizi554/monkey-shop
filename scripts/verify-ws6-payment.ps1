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

Write-Host "==> WS6 payment artifacts"
$docs = Read-Text "docs/payment/ws6.md"
$paymentMigration = Read-Text "src/main/resources/db/migration/V30__payment_order.sql"
$reconciliationMigration = Read-Text "src/main/resources/db/migration/V31__payment_reconciliation.sql"
$service = Read-Text "src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java"
$controller = Read-Text "src/main/java/com/example/monkey/payment/interfaces/PaymentController.java"
$store = Read-Text "src/main/java/com/example/monkey/payment/infrastructure/JpaPaymentStore.java"
$replayGuard = Read-Text "src/main/java/com/example/monkey/payment/infrastructure/RedisPaymentCallbackReplayGuard.java"
$stateMachine = Read-Text "src/main/java/com/example/monkey/payment/domain/PaymentTransitionPolicy.java"
$rateLimit = Read-Text "src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java"
$audit = Read-Text "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$workflowTest = Read-Text "src/test/java/com/example/monkey/payment/Ws6PaymentWorkflowTest.java"
$applicationTest = Read-Text "src/test/java/com/example/monkey/payment/application/PaymentApplicationServiceTest.java"
$infrastructureTest = Read-Text "src/test/java/com/example/monkey/payment/infrastructure/JpaPaymentStoreTest.java"
$frontendApi = Read-Text "frontend/src/api/payments.ts"
$paymentView = Read-Text "frontend/src/views/PaymentView.vue"

Assert-Matches "docs" $docs "callback replay"
Assert-Matches "docs" $docs "TOTP"
Assert-Matches "docs" $docs "bank-card PII"
Assert-Matches "docs" $docs "daily reconciliation"
Assert-Matches "payment migration" $paymentMigration "CREATE TABLE payment_order"
Assert-Matches "payment migration" $paymentMigration "CREATE TABLE payment_ledger"
Assert-Matches "payment migration" $paymentMigration "CREATE TABLE payment_callback_log"
Assert-Matches "payment migration" $paymentMigration "bank_card_hmac"
Assert-Matches "payment migration" $paymentMigration "version BIGINT"
Assert-Matches "reconciliation migration" $reconciliationMigration "CREATE TABLE payment_reconciliation_report"
Assert-Matches "reconciliation migration" $reconciliationMigration "encrypted_report_payload"
Assert-Matches "service" $service "@WithSpan\(""payment\.create""\)"
Assert-Matches "service" $service "verifySignature"
Assert-Matches "service" $service "callbackReplayGuard\.reserve"
Assert-Matches "service" $service "PaymentEvent\.REFUND_PARTIAL"
Assert-Matches "service" $service "userMfaVerifier\.verifyCode"
Assert-Matches "service" $service "@SchedulerLock\s*\(\s*name\s*=\s*""payment-query-timeout-orders"""
Assert-Matches "service" $service "@SchedulerLock\s*\(\s*name\s*=\s*""payment-daily-reconciliation"""
Assert-Matches "controller" $controller "@PostMapping\(""/pay""\)"
Assert-Matches "controller" $controller "@PostMapping\(""/callback""\)"
Assert-Matches "controller" $controller "@PostMapping\(""/refund""\)"
Assert-Matches "controller" $controller "@PostMapping\(""/reconciliation""\)"
Assert-Matches "store" $store "piiCryptoService\.encrypt"
Assert-Matches "store" $store "piiCryptoService\.blindIndex"
Assert-Matches "replay guard" $replayGuard "setIfAbsent"
Assert-Matches "replay guard" $replayGuard "PaymentCallbackLogRepository"
Assert-Matches "state machine" $stateMachine "REFUND_PARTIAL"
Assert-Matches "state machine" $stateMachine "SUSPEND"
Assert-Matches "rate limit" $rateLimit "PAYMENT\(""payment"", 5"
Assert-Matches "audit" $audit "PAYMENT_CREATED"
Assert-Matches "audit" $audit "PAYMENT_RECONCILED"
Assert-Matches "workflow test" $workflowTest "paymentArtifactsWireCallbacksRefundReconciliationAndFrontend"
Assert-Matches "application test" $applicationTest "callbackVerifiesSignatureAndIsIdempotent"
Assert-Matches "application test" $applicationTest "partialRefundCreatesLedgerAndReusesIdempotentResult"
Assert-Matches "application test" $applicationTest "reconciliationSuspendsMismatchedPlatformPaymentsAndBuildsReport"
Assert-Matches "infrastructure test" $infrastructureTest "savePaymentEncryptsBankCardAndStoresBlindIndex"
Assert-Matches "frontend api" $frontendApi "createPayment"
Assert-Matches "frontend api" $frontendApi "refundPayment"
Assert-Matches "frontend api" $frontendApi "reconcilePayment"
Assert-Matches "payment view" $paymentView "paymentsApi\.createPayment"
Assert-Matches "payment view" $paymentView "paymentsApi\.refundPayment"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS6 payment tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=PaymentDomainTest,PaymentApplicationServiceTest,JpaPaymentStoreTest,SpringStateMachinePaymentTransitionResolverTest,Ws6PaymentWorkflowTest,PiiCryptoServiceTest,SchemaMigrationTest,ArchitectureBoundaryTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS6 payment tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS6 payment verification completed successfully"
