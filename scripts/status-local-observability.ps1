param([switch]$AllowPartial)

. (Join-Path $PSScriptRoot "local-observability-common.ps1")

$checks = @(
    [ordered]@{ Service = "OTel Collector"; Port = 13133; Endpoint = "http://127.0.0.1:13133/" },
    [ordered]@{ Service = "Prometheus"; Port = 9090; Endpoint = "http://127.0.0.1:9090/-/ready" },
    [ordered]@{ Service = "Loki"; Port = 3100; Endpoint = "http://127.0.0.1:3100/ready" },
    [ordered]@{ Service = "Tempo"; Port = 3200; Endpoint = "http://127.0.0.1:3200/ready" },
    [ordered]@{ Service = "Grafana"; Port = 3000; Endpoint = "http://127.0.0.1:3000/api/health" }
)

foreach ($check in $checks) {
    $check.Up = Test-LocalRuntimeHttp -Uri $check.Endpoint
}

$checks | ForEach-Object {
    [pscustomobject]@{
        Service = $_.Service
        Status = if ($_.Up) { "UP" } else { "DOWN" }
        ProcessId = Get-LocalRuntimeListenerProcessId -Port $_.Port
        Endpoint = $_.Endpoint
    }
} | Format-Table -AutoSize

$failed = @($checks | Where-Object { -not $_.Up })
if ($failed.Count -gt 0 -and -not $AllowPartial) {
    throw "Local observability is incomplete: $($failed.Service -join ', ')"
}
