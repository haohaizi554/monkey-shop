param(
    [int]$MySqlPort = 3306,
    [int]$RedisPort = 6379,
    [int]$BackendPort = 8888,
    [int]$FrontendPort = 5173,
    [switch]$AllowPartial
)

. (Join-Path $PSScriptRoot "local-runtime-common.ps1")

$checks = @(
    [ordered]@{
        Service = "MySQL"
        Endpoint = "127.0.0.1:$MySqlPort"
        Up = Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $MySqlPort
        ProcessId = Get-LocalRuntimeListenerProcessId -Port $MySqlPort
    },
    [ordered]@{
        Service = "Redis"
        Endpoint = "127.0.0.1:$RedisPort"
        Up = Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $RedisPort
        ProcessId = Get-LocalRuntimeListenerProcessId -Port $RedisPort
    },
    [ordered]@{
        Service = "Backend"
        Endpoint = "http://127.0.0.1:$BackendPort/actuator/health"
        Up = Test-LocalRuntimeHttp -Uri "http://127.0.0.1:$BackendPort/actuator/health"
        ProcessId = Get-LocalRuntimeListenerProcessId -Port $BackendPort
    },
    [ordered]@{
        Service = "Frontend"
        Endpoint = "http://127.0.0.1:$FrontendPort/shop"
        Up = Test-LocalRuntimeHttp -Uri "http://127.0.0.1:$FrontendPort/shop"
        ProcessId = Get-LocalRuntimeListenerProcessId -Port $FrontendPort
    }
)

$checks |
    ForEach-Object {
        [pscustomobject]@{
            Service = $_.Service
            Status = if ($_.Up) { "UP" } else { "DOWN" }
            ProcessId = $_.ProcessId
            Endpoint = $_.Endpoint
        }
    } |
    Format-Table -AutoSize

$failed = @($checks | Where-Object { -not $_.Up })
if ($failed.Count -gt 0 -and -not $AllowPartial) {
    throw "Local runtime is incomplete: $($failed.Service -join ', ')"
}
