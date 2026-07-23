param([switch]$AllowPartial)

. (Join-Path $PSScriptRoot "local-support-common.ps1")
Add-LocalRuntimeNoProxy

$vaultUp = $false
try {
    $vaultStatus = Invoke-RestMethod -Uri "http://127.0.0.1:8200/v1/sys/seal-status" -Method Get -TimeoutSec 3
    $vaultUp = [bool]$vaultStatus.initialized -and -not [bool]$vaultStatus.sealed
} catch {
    $vaultUp = $false
}
$s3Up = Test-LocalRuntimeTcp -Address "127.0.0.1" -Port 8333
$clamUp = Test-LocalSupportClamAv
$checks = @(
    [ordered]@{ Service = "Vault"; Up = $vaultUp; Port = 8200; Endpoint = "http://127.0.0.1:8200" },
    [ordered]@{ Service = "SeaweedFS S3"; Up = $s3Up; Port = 8333; Endpoint = "http://127.0.0.1:8333" },
    [ordered]@{ Service = "ClamAV"; Up = $clamUp; Port = 3310; Endpoint = "tcp://127.0.0.1:3310" }
)

foreach ($check in $checks | Where-Object { $_.Up }) {
    Assert-LocalRuntimeLoopbackListener -Name $check.Service -Port $check.Port
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
    throw "Local production support is incomplete: $($failed.Service -join ', ')"
}
