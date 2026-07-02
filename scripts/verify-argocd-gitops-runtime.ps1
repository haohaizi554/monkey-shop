param(
    [string[]]$Applications = @("monkeyshop-dev", "monkeyshop-staging", "monkeyshop-prod"),
    [string]$ArgoNamespace = "argocd",
    [int]$TimeoutSeconds = 600,
    [switch]$RequireCluster,
    [switch]$SkipArgocdCli
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-CommandPath {
    param([string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        return ""
    }
    return $command.Source
}

function Invoke-NativeJson {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$FailureMessage
    )

    $output = & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (exit code $LASTEXITCODE)"
    }
    return ($output -join "`n") | ConvertFrom-Json
}

function Assert-State {
    param(
        [string]$Application,
        [object]$State
    )

    if ($State.status.sync.status -ne "Synced") {
        throw "$Application is not Synced; actual sync status: $($State.status.sync.status)"
    }
    if ($State.status.health.status -ne "Healthy") {
        throw "$Application is not Healthy; actual health status: $($State.status.health.status)"
    }
    if ([string]::IsNullOrWhiteSpace($State.spec.destination.namespace)) {
        throw "$Application does not declare a destination namespace"
    }
}

function Wait-WithKubectl {
    param(
        [string]$Kubectl,
        [string]$Application
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $state = Invoke-NativeJson `
            -FilePath $Kubectl `
            -Arguments @("-n", $ArgoNamespace, "get", "application.argoproj.io", $Application, "-o", "json") `
            -FailureMessage "Failed to read Argo CD Application $Application"

        if ($state.status.sync.status -eq "Synced" -and $state.status.health.status -eq "Healthy") {
            Assert-State -Application $Application -State $state
            $namespace = $state.spec.destination.namespace
            & $Kubectl get namespace $namespace | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "$Application destination namespace is missing: $namespace"
            }
            Write-Host "$Application is Synced and Healthy in namespace $namespace"
            return
        }

        Start-Sleep -Seconds 5
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    Assert-State -Application $Application -State $state
}

Write-Host "==> Argo CD runtime GitOps gate"

$argocd = if ($SkipArgocdCli) { "" } else { Resolve-CommandPath -Name "argocd" }
$kubectl = Resolve-CommandPath -Name "kubectl"

if ([string]::IsNullOrWhiteSpace($argocd) -and [string]::IsNullOrWhiteSpace($kubectl)) {
    $message = "Neither argocd nor kubectl was found. Install one of them and rerun with -RequireCluster to enforce runtime GitOps sync."
    if ($RequireCluster) {
        throw $message
    }
    Write-Warning $message
    return
}

if ([string]::IsNullOrWhiteSpace($argocd) -and -not $RequireCluster) {
    Write-Warning "argocd CLI was not found. kubectl-based runtime verification is available with -RequireCluster once Argo CD is installed in the target cluster."
    return
}

if (-not [string]::IsNullOrWhiteSpace($argocd)) {
    & $argocd app list -o name 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        $message = "argocd CLI is installed but cannot list applications. Log in to Argo CD and rerun with -RequireCluster."
        if ($RequireCluster) {
            throw $message
        }
        Write-Warning $message
        return
    }
} elseif (-not [string]::IsNullOrWhiteSpace($kubectl)) {
    & $kubectl -n $ArgoNamespace get applications.argoproj.io -o name 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        $message = "kubectl is installed but Argo CD Applications are not readable in namespace $ArgoNamespace. Configure the cluster and rerun with -RequireCluster."
        if ($RequireCluster) {
            throw $message
        }
        Write-Warning $message
        return
    }
}

foreach ($application in $Applications) {
    if (-not [string]::IsNullOrWhiteSpace($argocd)) {
        & $argocd app wait $application --sync --health --timeout $TimeoutSeconds
        if ($LASTEXITCODE -ne 0) {
            throw "Argo CD app wait failed for $application"
        }
        $state = Invoke-NativeJson `
            -FilePath $argocd `
            -Arguments @("app", "get", $application, "-o", "json") `
            -FailureMessage "Failed to read Argo CD app state for $application"
        Assert-State -Application $application -State $state
        Write-Host "$application is Synced and Healthy"
    } else {
        Wait-WithKubectl -Kubectl $kubectl -Application $application
    }
}

Write-Host "Argo CD runtime GitOps gate completed successfully."
