param(
    [int]$MySqlPort = 3306,
    [int]$MySqlXPort = 33060,
    [int]$RedisPort = 6379,
    [int]$BackendPort = 8888,
    [int]$FrontendPort = 5173,
    [switch]$AllowPartial
)

. (Join-Path $PSScriptRoot "local-runtime-common.ps1")
Add-LocalRuntimeNoProxy

$checks = @(
    [ordered]@{
        Service = "MySQL"
        Port = $MySqlPort
        Endpoint = "127.0.0.1:$MySqlPort"
        Up = Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $MySqlPort
        ProcessId = Get-LocalRuntimeListenerProcessId -Port $MySqlPort
    },
    [ordered]@{
        Service = "Redis"
        Port = $RedisPort
        Endpoint = "127.0.0.1:$RedisPort"
        Up = Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $RedisPort
        ProcessId = Get-LocalRuntimeListenerProcessId -Port $RedisPort
    },
    [ordered]@{
        Service = "Backend"
        Port = $BackendPort
        Endpoint = "http://127.0.0.1:$BackendPort/actuator/health"
        Up = Test-LocalRuntimeHttp -Uri "http://127.0.0.1:$BackendPort/actuator/health"
        ProcessId = Get-LocalRuntimeListenerProcessId -Port $BackendPort
    },
    [ordered]@{
        Service = "Frontend"
        Port = $FrontendPort
        Endpoint = "http://127.0.0.1:$FrontendPort/shop"
        Up = Test-LocalRuntimeHttp -Uri "http://127.0.0.1:$FrontendPort/shop"
        ProcessId = Get-LocalRuntimeListenerProcessId -Port $FrontendPort
    }
)

foreach ($check in $checks | Where-Object { $_.Up }) {
    Assert-LocalRuntimeLoopbackListener -Name $check.Service -Port $check.Port
}
Assert-LocalRuntimeLoopbackListener -Name "MySQL X Protocol" -Port $MySqlXPort -AllowAbsent

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
