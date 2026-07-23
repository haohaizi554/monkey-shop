param(
    [switch]$StopMySql
)

. (Join-Path $PSScriptRoot "local-runtime-common.ps1")

$state = Read-LocalRuntimeState
$stopObservability = $false
$stopProductionSupport = $false
if ($null -eq $state) {
    Write-Host "No managed local runtime state was found at $Script:LocalRuntimeStatePath"
} else {
    Assert-LocalRuntime ($state.repositoryRoot -eq $Script:LocalRuntimeRepoRoot) "Runtime state belongs to another repository"
    $observabilityProperty = $state.PSObject.Properties["observabilityEnabled"]
    $productionSupportProperty = $state.PSObject.Properties["productionSupportEnabled"]
    $stopObservability = $null -ne $observabilityProperty -and [bool]$observabilityProperty.Value
    $stopProductionSupport = $null -ne $productionSupportProperty -and [bool]$productionSupportProperty.Value

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

    foreach ($serviceName in @("frontend", "backend", "redis")) {
        $property = $state.services.PSObject.Properties[$serviceName]
        if ($null -eq $property) {
            continue
        }
        $record = $property.Value
        if (-not $record.managed) {
            Write-Host "$serviceName was already running before start-local.ps1 and will not be stopped"
            continue
        }
        Stop-TrackedIdentity -ServiceName $serviceName -Identity $record.launcher
        Stop-TrackedIdentity -ServiceName $serviceName -Identity $record.listener
    }

    Remove-Item -LiteralPath $Script:LocalRuntimeStatePath -Force
}

if ($stopProductionSupport) {
    & (Join-Path $PSScriptRoot "stop-local-support.ps1")
}
if ($stopObservability) {
    & (Join-Path $PSScriptRoot "stop-local-observability.ps1")
}

if ($StopMySql) {
    $mysqlService = Get-Service -Name "MySQL" -ErrorAction SilentlyContinue
    if ($null -ne $mysqlService -and $mysqlService.Status -eq "Running") {
        Write-Host "Stopping MySQL Windows service"
        Stop-Service -Name "MySQL"
    }
}

Write-Host "Managed MonkeyShop local processes have been stopped."
