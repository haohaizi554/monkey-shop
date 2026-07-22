. (Join-Path $PSScriptRoot "local-observability-common.ps1")

$state = Read-LocalObservabilityState
if ($null -eq $state) {
    Write-Host "No managed local observability state was found at $Script:LocalObservabilityStatePath"
    return
}
Assert-LocalRuntime ($state.repositoryRoot -eq $Script:LocalRuntimeRepoRoot) "Observability state belongs to another repository"

$stoppedProcessIds = [Collections.Generic.HashSet[int]]::new()
function Stop-TrackedIdentity {
    param(
        [string]$ServiceName,
        [object]$Identity
    )
    if (-not (Test-LocalRuntimeProcessIdentity -Identity $Identity)) {
        return
    }
    $processId = [int]$Identity.pid
    if (-not $stoppedProcessIds.Add($processId)) {
        return
    }
    Write-Host "Stopping $ServiceName process $processId"
    $null = & taskkill.exe /PID $processId /T /F 2>&1
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        if (-not (Test-LocalRuntimeProcessIdentity -Identity $Identity)) {
            return
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Failed to stop tracked $ServiceName process $processId"
}

foreach ($serviceName in @("grafana", "prometheus", "otelCollector", "loki", "tempo")) {
    $property = $state.services.PSObject.Properties[$serviceName]
    if ($null -eq $property) {
        continue
    }
    $record = $property.Value
    if (-not $record.managed) {
        Write-Host "$serviceName was not started by MonkeyShop and will not be stopped"
        continue
    }
    Stop-TrackedIdentity -ServiceName $serviceName -Identity $record.launcher
    Stop-TrackedIdentity -ServiceName $serviceName -Identity $record.listener
}

Remove-Item -LiteralPath $Script:LocalObservabilityStatePath -Force
Write-Host "Managed MonkeyShop observability processes have been stopped."
