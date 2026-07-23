param(
    [string]$BackendBaseUrl = "http://127.0.0.1:8888",
    [int]$TimeoutSeconds = 45
)

. (Join-Path $PSScriptRoot "local-observability-common.ps1")
Add-LocalRuntimeNoProxy

function Assert-Observability {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Wait-ObservabilityCondition {
    param(
        [scriptblock]$Condition,
        [string]$Message
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            if (& $Condition) {
                return
            }
        } catch {
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw $Message
}

& (Join-Path $PSScriptRoot "status-local-observability.ps1")

$targets = Invoke-RestMethod -Uri "http://127.0.0.1:9090/api/v1/targets" -TimeoutSec 10
$monkeyTarget = @($targets.data.activeTargets | Where-Object { $_.labels.job -eq "monkeyshop" })
Assert-Observability ($monkeyTarget.Count -eq 1 -and $monkeyTarget[0].health -eq "up") "Prometheus monkeyshop target is not up"
$query = [Uri]::EscapeDataString('up{job="monkeyshop"}')
$metric = Invoke-RestMethod -Uri "http://127.0.0.1:9090/api/v1/query?query=$query" -TimeoutSec 10
Assert-Observability ($metric.status -eq "success" -and @($metric.data.result).Count -gt 0) "Prometheus query returned no MonkeyShop samples"

$traceId = ([Guid]::NewGuid().ToString("N")).ToLowerInvariant()
$spanId = $traceId.Substring(16, 16)
$requestHeaders = @{
    "X-Trace-Id" = $traceId
    traceparent = "00-$traceId-$spanId-01"
}
$null = Invoke-WebRequest -UseBasicParsing -Uri "$BackendBaseUrl/api/v1/monkeys?page=0&size=1" -Headers $requestHeaders -TimeoutSec 10
$nowNanos = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000000
$resourceAttributes = @(
    [ordered]@{ key = "service.name"; value = [ordered]@{ stringValue = "monkeyshop-local-verifier" } },
    [ordered]@{ key = "deployment.environment.name"; value = [ordered]@{ stringValue = "local" } }
)
$logPayload = [ordered]@{
    resourceLogs = @([ordered]@{
        resource = [ordered]@{ attributes = $resourceAttributes }
        scopeLogs = @([ordered]@{
            scope = [ordered]@{ name = "monkeyshop.local.acceptance" }
            logRecords = @([ordered]@{
                timeUnixNano = [string]$nowNanos
                severityNumber = 9
                severityText = "INFO"
                body = [ordered]@{ stringValue = "MonkeyShop local observability traceId=$traceId" }
                traceId = $traceId
                spanId = $spanId
                attributes = @([ordered]@{ key = "traceId"; value = [ordered]@{ stringValue = $traceId } })
            })
        })
    })
}
$jsonHeaders = @{ "Content-Type" = "application/json" }
$null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:4318/v1/logs" -Headers $jsonHeaders -Body ($logPayload | ConvertTo-Json -Depth 20 -Compress) -TimeoutSec 10

Wait-ObservabilityCondition -Message "Tempo did not return a real http.server.request span for trace $traceId" -Condition {
    $trace = Invoke-RestMethod -Uri "http://127.0.0.1:3200/api/traces/$traceId" -TimeoutSec 5
    $serverSpans = @($trace.batches | ForEach-Object { $_.scopeSpans } | ForEach-Object { $_.spans } |
        Where-Object { $_.kind -eq "SPAN_KIND_SERVER" })
    return $serverSpans.Count -gt 0
}
$tempoExpression = '{ trace:id = "' + $traceId + '" }'
$tempoQuery = [Uri]::EscapeDataString($tempoExpression)
Wait-ObservabilityCondition -Message "Tempo search did not index trace $traceId" -Condition {
    $search = Invoke-RestMethod -Uri "http://127.0.0.1:3200/api/search?q=$tempoQuery&limit=20" -TimeoutSec 5
    return @($search.traces | Where-Object { $_.traceID -eq $traceId }).Count -gt 0
}
$spanMetricQuery = [Uri]::EscapeDataString("traces_spanmetrics_calls_total")
Wait-ObservabilityCondition -Message "Tempo metrics-generator did not remote-write span metrics" -Condition {
    $spanMetric = Invoke-RestMethod -Uri "http://127.0.0.1:9090/api/v1/query?query=$spanMetricQuery" -TimeoutSec 5
    return $spanMetric.status -eq "success" -and @($spanMetric.data.result).Count -gt 0
}
$lokiExpression = '{service_name="monkeyshop-local-verifier"} |= "' + $traceId + '"'
$lokiQuery = [Uri]::EscapeDataString($lokiExpression)
Wait-ObservabilityCondition -Message "Loki did not return log traceId $traceId" -Condition {
    $logs = Invoke-RestMethod -Uri "http://127.0.0.1:3100/loki/api/v1/query_range?query=$lokiQuery&limit=20" -TimeoutSec 5
    return $logs.status -eq "success" -and @($logs.data.result).Count -gt 0
}

$grafanaPasswordPath = Join-Path (Join-Path $Script:LocalRuntimeRoot "secrets") "grafana-admin-password"
Assert-Observability (Test-Path -LiteralPath $grafanaPasswordPath) "Grafana local admin password is missing"
$grafanaPassword = Get-Content -LiteralPath $grafanaPasswordPath -Raw -Encoding ASCII
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("admin:$grafanaPassword"))
$grafanaHeaders = @{ Authorization = "Basic $basic" }
$grafanaHealth = Invoke-RestMethod -Uri "http://127.0.0.1:3000/api/health" -TimeoutSec 10
Assert-Observability ($grafanaHealth.database -eq "ok") "Grafana database is not healthy"
$dataSources = @(Invoke-RestMethod -Uri "http://127.0.0.1:3000/api/datasources" -Headers $grafanaHeaders -TimeoutSec 10)
foreach ($uid in @("prometheus", "loki", "tempo")) {
    Assert-Observability (@($dataSources | Where-Object { $_.uid -eq $uid }).Count -eq 1) "Grafana datasource $uid is missing"
}

Write-Host "Local observability verification passed for traceId $traceId"
