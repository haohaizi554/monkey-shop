param(
    [switch]$RunMaven,
    [string]$MavenTestPattern = "Ws6ObservabilityWorkflowTest,TraceIdFilterTest,UserMdcFilterTest,AuditServiceTest,JpaAuditLogStoreTest,VisitMetricsServiceTest,BusinessMetricsServiceTest,StatsControllerTest,ObservabilityConfigTest"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure {
    param([string]$Message)
    [void]$script:Failures.Add($Message)
}

function Assert-File {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        Add-Failure "Missing required file: $Path"
        return $false
    }
    return $true
}

function Read-RequiredFile {
    param([string]$Path)
    if (Assert-File -Path $Path) {
        return Get-Content -LiteralPath $Path -Raw
    }
    return ""
}

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Expected,
        [string]$Message
    )
    if (-not $Text.Contains($Expected)) {
        Add-Failure "${Name}: $Message"
    }
}

function Assert-Match {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern,
        [string]$Message
    )
    if ($Text -notmatch $Pattern) {
        Add-Failure "${Name}: $Message"
    }
}

function Invoke-CheckedCommand {
    param(
        [string]$Name,
        [string[]]$Arguments
    )
    Write-Host "==> $Name"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Arguments[0] $Arguments[1..($Arguments.Count - 1)] 2>&1 | ForEach-Object { Write-Host $_ }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($LASTEXITCODE -ne 0) {
        Add-Failure "$Name failed with exit code $LASTEXITCODE"
    }
}

function Assert-NoConsoleLogging {
    $forbiddenPatterns = @(
        ("System" + ".out"),
        ("System" + ".err"),
        ("print" + "StackTrace")
    )
    $javaFiles = Get-ChildItem -Path "src/main/java" -Recurse -Filter "*.java" -File
    foreach ($file in $javaFiles) {
        $content = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($pattern in $forbiddenPatterns) {
            if ($content.Contains($pattern)) {
                $relativePath = Resolve-Path -LiteralPath $file.FullName -Relative
                Add-Failure "$relativePath contains forbidden console logging pattern $pattern"
            }
        }
    }
}

Write-Host "==> WS6 observability checks"

$requiredFiles = @(
    "pom.xml",
    "README.md",
    ".github/workflows/ci.yaml",
    "src/main/resources/application.yml",
    "src/main/resources/application-prod.yml",
    "src/main/resources/logback-spring.xml",
    "src/main/resources/db/migration/V8__audit_log.sql",
    "src/main/resources/db/migration/V15__audit_trace_retention.sql",
    "src/main/java/com/example/monkey/shared/interfaces/web/TraceIdFilter.java",
    "src/main/java/com/example/monkey/shared/interfaces/web/UserMdcFilter.java",
    "src/main/java/com/example/monkey/shared/infrastructure/config/ObservabilityConfig.java",
    "src/main/java/com/example/monkey/shared/application/observability/TraceIds.java",
    "src/main/java/com/example/monkey/shared/application/observability/AuditService.java",
    "src/main/java/com/example/monkey/shared/infrastructure/observability/AuditLog.java",
    "src/main/java/com/example/monkey/shared/infrastructure/observability/AuditLogRepository.java",
    "src/main/java/com/example/monkey/shared/infrastructure/observability/JpaAuditLogStore.java",
    "src/main/java/com/example/monkey/order/application/observability/BusinessMetricsService.java",
    "src/main/java/com/example/monkey/shared/application/observability/VisitMetricsService.java",
    "src/main/java/com/example/monkey/shared/infrastructure/observability/JpaVisitLogRecorder.java",
    "src/main/java/com/example/monkey/order/application/OrderService.java",
    "src/main/java/com/example/monkey/admin/interfaces/StatsController.java",
    "helm/monkeyshop/values.yaml",
    "helm/monkeyshop/templates/servicemonitor.yaml",
    "helm/monkeyshop/templates/prometheusrule.yaml",
    "helm/monkeyshop/templates/grafana-dashboard.yaml",
    "docs/observability/ws6.md"
)

foreach ($file in $requiredFiles) {
    [void](Assert-File -Path $file)
}

Assert-NoConsoleLogging

$pom = Read-RequiredFile -Path "pom.xml"
$readme = Read-RequiredFile -Path "README.md"
$workflow = Read-RequiredFile -Path ".github/workflows/ci.yaml"
$application = Read-RequiredFile -Path "src/main/resources/application.yml"
$prod = Read-RequiredFile -Path "src/main/resources/application-prod.yml"
$logback = Read-RequiredFile -Path "src/main/resources/logback-spring.xml"
$auditMigration = Read-RequiredFile -Path "src/main/resources/db/migration/V8__audit_log.sql"
$auditTraceMigration = Read-RequiredFile -Path "src/main/resources/db/migration/V15__audit_trace_retention.sql"
$traceFilter = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/interfaces/web/TraceIdFilter.java"
$userMdcFilter = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/interfaces/web/UserMdcFilter.java"
$observabilityConfig = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/config/ObservabilityConfig.java"
$traceIds = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/application/observability/TraceIds.java"
$auditService = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/application/observability/AuditService.java"
$auditLog = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/observability/AuditLog.java"
$auditRepository = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/observability/AuditLogRepository.java"
$auditStore = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/observability/JpaAuditLogStore.java"
$businessMetrics = Read-RequiredFile -Path "src/main/java/com/example/monkey/order/application/observability/BusinessMetricsService.java"
$visitMetrics = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/application/observability/VisitMetricsService.java"
$visitRecorder = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/observability/JpaVisitLogRecorder.java"
$orderService = Read-RequiredFile -Path "src/main/java/com/example/monkey/order/application/OrderService.java"
$statsController = Read-RequiredFile -Path "src/main/java/com/example/monkey/admin/interfaces/StatsController.java"
$helmValues = Read-RequiredFile -Path "helm/monkeyshop/values.yaml"
$serviceMonitor = Read-RequiredFile -Path "helm/monkeyshop/templates/servicemonitor.yaml"
$prometheusRule = Read-RequiredFile -Path "helm/monkeyshop/templates/prometheusrule.yaml"
$grafanaDashboard = Read-RequiredFile -Path "helm/monkeyshop/templates/grafana-dashboard.yaml"
$docs = Read-RequiredFile -Path "docs/observability/ws6.md"

foreach ($dependency in @(
    "spring-boot-starter-actuator",
    "micrometer-registry-prometheus",
    "logstash-logback-encoder",
    "opentelemetry-spring-boot-starter",
    "opentelemetry-instrumentation-annotations",
    "sentry-spring-boot-starter-jakarta"
)) {
    Assert-Contains -Name "pom.xml" -Text $pom -Expected $dependency -Message "must include $dependency"
}

Assert-Contains -Name "application.yml" -Text $application -Expected "include: health,prometheus,loggers" -Message "must expose health, Prometheus, and loggers endpoints"
Assert-Contains -Name "application.yml" -Text $application -Expected "show-values: NEVER" -Message "must avoid leaking env values through actuator"
Assert-Contains -Name "application.yml" -Text $application -Expected "percentiles-histogram:" -Message "must publish histogram buckets used by latency dashboards"
Assert-Contains -Name "application.yml" -Text $application -Expected "http.server.requests: true" -Message "must publish HTTP request latency buckets"
Assert-Contains -Name "application.yml" -Text $application -Expected "order.create: true" -Message "must publish order creation latency buckets"
Assert-Contains -Name "application.yml" -Text $application -Expected 'exporter: ${OTEL_TRACES_EXPORTER:none}' -Message "must default tracing exporter to safe local mode"
Assert-Contains -Name "application.yml" -Text $application -Expected "send-default-pii: false" -Message "must keep Sentry PII disabled"
Assert-Contains -Name "application.yml" -Text $application -Expected 'retention-days: ${APP_AUDIT_RETENTION_DAYS:180}' -Message "must default audit retention to 180 days"
Assert-Contains -Name "application-prod.yml" -Text $prod -Expected "show-sql: false" -Message "must disable SQL logging in production"
Assert-Contains -Name "application-prod.yml" -Text $prod -Expected "org.hibernate.SQL: WARN" -Message "must keep Hibernate SQL logging at WARN"
Assert-Contains -Name "application-prod.yml" -Text $prod -Expected 'exporter: ${OTEL_TRACES_EXPORTER:otlp}' -Message "must export traces with OTLP in production by default"
Assert-Contains -Name "application-prod.yml" -Text $prod -Expected "send-default-pii: false" -Message "must keep Sentry PII disabled in production"

Assert-Contains -Name "logback-spring.xml" -Text $logback -Expected "net.logstash.logback.encoder.LogstashEncoder" -Message "must emit structured JSON logs"
Assert-Contains -Name "logback-spring.xml" -Text $logback -Expected "<includeMdcKeyName>traceId</includeMdcKeyName>" -Message "must include traceId MDC"
Assert-Contains -Name "logback-spring.xml" -Text $logback -Expected "<includeMdcKeyName>userId</includeMdcKeyName>" -Message "must include userId MDC"
Assert-Contains -Name "logback-spring.xml" -Text $logback -Expected "MaskingJsonGeneratorDecorator" -Message "must mask sensitive JSON log paths"
foreach ($path in @("password", "token", "authorization", "cookie", "phone", "addressSnapshot")) {
    Assert-Contains -Name "logback-spring.xml" -Text $logback -Expected "<path>$path</path>" -Message "must mask $path in logs"
}
Assert-Contains -Name "logback-spring.xml" -Text $logback -Expected "<maxFileSize>100MB</maxFileSize>" -Message "must cap individual log files"
Assert-Contains -Name "logback-spring.xml" -Text $logback -Expected "<maxHistory>30</maxHistory>" -Message "must keep 30 days of rolled logs"
Assert-Contains -Name "logback-spring.xml" -Text $logback -Expected "<totalSizeCap>10GB</totalSizeCap>" -Message "must cap retained log size"

Assert-Contains -Name "TraceIds.java" -Text $traceIds -Expected 'HEADER = "X-Trace-Id"' -Message "must standardize trace header name"
Assert-Contains -Name "TraceIdFilter.java" -Text $traceFilter -Expected "OncePerRequestFilter" -Message "must apply trace IDs once per request"
Assert-Contains -Name "TraceIdFilter.java" -Text $traceFilter -Expected "request.getHeader(TraceIds.HEADER)" -Message "must accept inbound X-Trace-Id"
Assert-Contains -Name "TraceIdFilter.java" -Text $traceFilter -Expected "response.setHeader(TraceIds.HEADER, traceId)" -Message "must return trace ID to clients"
Assert-Contains -Name "TraceIdFilter.java" -Text $traceFilter -Expected "MDC.put(TraceIds.MDC_KEY, traceId)" -Message "must store trace ID in MDC"
Assert-Contains -Name "TraceIdFilter.java" -Text $traceFilter -Expected "MDC.remove(TraceIds.MDC_KEY)" -Message "must clear trace ID MDC"
Assert-Contains -Name "UserMdcFilter.java" -Text $userMdcFilter -Expected "MDC.put(TraceIds.USER_ID_MDC_KEY" -Message "must add authenticated user ID to MDC"
Assert-Contains -Name "UserMdcFilter.java" -Text $userMdcFilter -Expected "MDC.remove(TraceIds.USER_ID_MDC_KEY)" -Message "must clear user ID MDC"
Assert-Contains -Name "ObservabilityConfig.java" -Text $observabilityConfig -Expected "@EnableAsync" -Message "must enable async audit work"
Assert-Contains -Name "ObservabilityConfig.java" -Text $observabilityConfig -Expected "observabilityTaskExecutor" -Message "must provide a dedicated observability executor"
Assert-Contains -Name "ObservabilityConfig.java" -Text $observabilityConfig -Expected "MDC.getCopyOfContextMap()" -Message "must propagate MDC into async tasks"

Assert-Contains -Name "AuditService.java" -Text $auditService -Expected '@Async("observabilityTaskExecutor")' -Message "must write audit events asynchronously"
Assert-Contains -Name "AuditService.java" -Text $auditService -Expected '@Value("${app.audit.retention-days:180}")' -Message "must bind audit retention"
Assert-Contains -Name "AuditService.java" -Text $auditService -Expected "DEFAULT_RETENTION_DAYS = 180" -Message "must keep default audit retention at 180 days"
Assert-Contains -Name "AuditService.java" -Text $auditService -Expected "SENSITIVE_DETAIL_PATTERN" -Message "must sanitize sensitive audit details"
Assert-Contains -Name "AuditService.java" -Text $auditService -Expected "hashSubject(subject)" -Message "must hash audit subjects"
Assert-Contains -Name "AuditService.java" -Text $auditService -Expected "findFirst50ByTraceId" -Message "must cap trace lookup results"
Assert-Contains -Name "AuditService.java" -Text $auditService -Expected "purgeExpiredAuditLogs()" -Message "must purge expired audit rows"
Assert-Contains -Name "AuditLog.java" -Text $auditLog -Expected '@Table(name = "audit_log")' -Message "must map the audit log table"
Assert-Contains -Name "AuditLog.java" -Text $auditLog -Expected "trace_id" -Message "must persist trace IDs"
Assert-Contains -Name "AuditLog.java" -Text $auditLog -Expected "subject_hash" -Message "must store hashed audit subjects"
Assert-Contains -Name "AuditLogRepository.java" -Text $auditRepository -Expected "findTop50ByTraceIdOrderByCreatedAtAsc" -Message "must cap trace lookups"
Assert-Contains -Name "JpaAuditLogStore.java" -Text $auditStore -Expected "deleteByCreatedAtBefore" -Message "must support retention purges"
Assert-Contains -Name "V8__audit_log.sql" -Text $auditMigration -Expected 'CREATE TABLE IF NOT EXISTS `audit_log`' -Message "must create audit_log"
Assert-Contains -Name "V15__audit_trace_retention.sql" -Text $auditTraceMigration -Expected "idx_audit_log_trace_id" -Message "must index audit trace lookups"

Assert-Contains -Name "BusinessMetricsService.java" -Text $businessMetrics -Expected 'Timer.builder("order.create")' -Message "must record order create latency"
Assert-Contains -Name "BusinessMetricsService.java" -Text $businessMetrics -Expected 'Counter.builder("order.created")' -Message "must count created orders"
Assert-Contains -Name "BusinessMetricsService.java" -Text $businessMetrics -Expected 'Counter.builder("stock.deduct.fail")' -Message "must count stock deduction failures"
Assert-Contains -Name "BusinessMetricsService.java" -Text $businessMetrics -Expected 'Gauge.builder("order.pending"' -Message "must expose pending orders gauge"
Assert-Contains -Name "VisitMetricsService.java" -Text $visitMetrics -Expected 'Counter.builder("visit.page.views")' -Message "must record page visits as metrics"
Assert-Contains -Name "JpaVisitLogRecorder.java" -Text $visitRecorder -Expected '@Async("observabilityTaskExecutor")' -Message "must keep visit persistence off the request thread"
Assert-Contains -Name "OrderService.java" -Text $orderService -Expected '@WithSpan("order.create")' -Message "must create order spans"
Assert-Contains -Name "OrderService.java" -Text $orderService -Expected "businessMetricsService.recordOrderCreate" -Message "must time order creation"
Assert-Contains -Name "OrderService.java" -Text $orderService -Expected "businessMetricsService.recordOrderCreated" -Message "must increment order created metric"
Assert-Contains -Name "OrderService.java" -Text $orderService -Expected "businessMetricsService.recordStockDeductFailure" -Message "must increment stock failure metric"
Assert-Contains -Name "OrderService.java" -Text $orderService -Expected "AuditService.ORDER_CREATED" -Message "must audit order creation"
Assert-Contains -Name "OrderService.java" -Text $orderService -Expected "AuditService.ORDER_REFUNDED" -Message "must audit refund completion"
Assert-Contains -Name "StatsController.java" -Text $statsController -Expected '@GetMapping("/audit-trace")' -Message "must expose administrator trace lookup"
Assert-Contains -Name "StatsController.java" -Text $statsController -Expected "ADMIN_DASHBOARD_READ" -Message "must protect audit trace lookup"

Assert-Contains -Name "servicemonitor.yaml" -Text $serviceMonitor -Expected "path: /actuator/prometheus" -Message "must scrape Prometheus metrics"
Assert-Contains -Name "values.yaml" -Text $helmValues -Expected "targetAvailability: 0.999" -Message "must define a 99.9 percent availability SLO"
Assert-Contains -Name "values.yaml" -Text $helmValues -Expected "errorBudgetRatio: 0.001" -Message "must define the 0.1 percent error budget"
Assert-Contains -Name "values.yaml" -Text $helmValues -Expected "window: 30d" -Message "must define the SLO window"
Assert-Contains -Name "values.yaml" -Text $helmValues -Expected "fast: 14.4" -Message "must define the fast burn-rate threshold"
Assert-Contains -Name "values.yaml" -Text $helmValues -Expected "slow: 6" -Message "must define the slow burn-rate threshold"
Assert-Contains -Name "values.yaml" -Text $helmValues -Expected "sloFastBurn: 2m" -Message "must define a fast burn alert hold time"
Assert-Contains -Name "values.yaml" -Text $helmValues -Expected "sloSlowBurn: 15m" -Message "must define a slow burn alert hold time"
foreach ($signal in @("MonkeyShopHighErrorRate", "MonkeyShopSloFastBurn", "MonkeyShopSloSlowBurn", "MonkeyShopP99LatencyHigh", "MonkeyShopHikariPoolSaturation", "MonkeyShopDown", "MonkeyShopStockDeductFailures", "MonkeyShopPendingOrdersBacklog")) {
    Assert-Contains -Name "prometheusrule.yaml" -Text $prometheusRule -Expected $signal -Message "must include alert $signal"
}
foreach ($panel in @("HTTP RPS", "HTTP P99 Latency", "HTTP 5xx Error Rate", "HikariCP Saturation", "JVM Memory", "Orders Created", "Stock Deduct Failures", "Order Create P99", "Pending Orders", "Audit Events By TraceId", "Tempo Trace Drilldown", "SLO Availability 30d", "Error Budget Burn Rate")) {
    Assert-Contains -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Expected $panel -Message "must include dashboard panel $panel"
}
Assert-Contains -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Expected "order_total" -Message "must query the Prometheus-normalized order counter"
Assert-Contains -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Expected '"traceId"' -Message "must provide traceId dashboard variable"
Assert-Contains -Name "docs/observability/ws6.md" -Text $docs -Expected "TraceId Flow" -Message "must document trace ID flow"
Assert-Contains -Name "docs/observability/ws6.md" -Text $docs -Expected "99.9% Availability SLO" -Message "must document the production availability SLO"
Assert-Contains -Name "docs/observability/ws6.md" -Text $docs -Expected "MonkeyShopSloFastBurn" -Message "must document fast burn-rate alerting"
Assert-Contains -Name "docs/observability/ws6.md" -Text $docs -Expected "MonkeyShopSloSlowBurn" -Message "must document slow burn-rate alerting"
Assert-Contains -Name "docs/observability/ws6.md" -Text $docs -Expected "Production Drilldown" -Message "must document production drilldown"
Assert-Contains -Name "README.md" -Text $readme -Expected "Prometheus" -Message "must advertise Prometheus visibility"
Assert-Contains -Name "README.md" -Text $readme -Expected "fast and slow burn-rate alerts" -Message "must advertise 99.9 percent SLA burn-rate alerting"
Assert-Contains -Name ".github/workflows/ci.yaml" -Text $workflow -Expected ".\scripts\verify-ws6-observability.ps1" -Message "must run WS6 observability checks in CI"

if ($RunMaven) {
    Invoke-CheckedCommand -Name "Maven WS6 tests" -Arguments @("mvn", "-Ddependency-check.skip=true", "-Dtest=$MavenTestPattern", "test")
}

if ($Failures.Count -gt 0) {
    Write-Host ""
    Write-Host "WS6 observability verification failed:"
    foreach ($failure in $Failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "WS6 observability verification passed."
