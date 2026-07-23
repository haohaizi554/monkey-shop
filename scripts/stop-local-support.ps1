. (Join-Path $PSScriptRoot "local-support-common.ps1")

$state = Read-LocalSupportState
if ($null -eq $state) {
    Write-Host "No managed local support state was found at $Script:LocalSupportStatePath"
    return
}
Assert-LocalRuntime ($state.repositoryRoot -eq $Script:LocalRuntimeRepoRoot) "Local support state belongs to another repository"

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
    Stop-LocalRuntimeProcessTree -ProcessId $processId -Name $ServiceName
}

foreach ($serviceName in @("clamav", "seaweedfs", "vault")) {
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

Remove-Item -LiteralPath $Script:LocalSupportStatePath -Force
Write-Host "Managed MonkeyShop Vault, SeaweedFS, and ClamAV processes have been stopped."
