param(
    [string]$RuntimeBaseUrl = "",
    [string]$SshTarget = "",
    [string]$ImageRef = "monkey-shop-myshop:latest",
    [string]$PublicBaseUrl = $env:MONKEYSHOP_PUBLIC_URL,
    [switch]$SkipBackendVerify,
    [switch]$SkipFrontend,
    [switch]$SkipWs1Scanners,
    [switch]$SkipRuntimeImageScan,
    [switch]$IncludeVmRuntime,
    [switch]$IncludeRuntimeImageScan,
    [switch]$IncludeRuntimeDataProtection,
    [switch]$IncludePublicEdge,
    [switch]$IncludeSonar
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$script:Passed = [System.Collections.Generic.List[string]]::new()
$script:Skipped = [System.Collections.Generic.List[string]]::new()

function Add-Skipped {
    param([string]$Message)
    [void]$script:Skipped.Add($Message)
    Write-Host "==> SKIP: $Message"
}

function Invoke-AcceptanceStep {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    Write-Host ""
    Write-Host "==> $Name"
    & $Action
    $lastExit = Get-Variable -Name LASTEXITCODE -ErrorAction SilentlyContinue
    if ($null -ne $lastExit -and $null -ne $lastExit.Value -and [int]$lastExit.Value -ne 0) {
        throw "$Name failed with exit code $($lastExit.Value)"
    }
    [void]$script:Passed.Add($Name)
}

function Invoke-RepoScript {
    param(
        [string]$RelativePath,
        [hashtable]$Parameters = @{}
    )

    $scriptPath = Join-Path $script:RepositoryRoot $RelativePath
    & $scriptPath @Parameters
    $lastExit = Get-Variable -Name LASTEXITCODE -ErrorAction SilentlyContinue
    if ($null -ne $lastExit -and $null -ne $lastExit.Value -and [int]$lastExit.Value -ne 0) {
        throw "$RelativePath failed with exit code $($lastExit.Value)"
    }
}

function Invoke-NativeChecked {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$Name
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Get-SshHost {
    param([string]$Target)
    $hostName = $Target
    if ($hostName.Contains("@")) {
        $hostName = $hostName.Substring($hostName.LastIndexOf("@") + 1)
    }
    if ($hostName.Contains(":")) {
        $hostName = $hostName.Split(":")[0]
    }
    return $hostName
}

$script:RepositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path

Push-Location $script:RepositoryRoot
try {
    Write-Host "==> WS1-WS8 acceptance gate"

    if ($SkipBackendVerify) {
        Add-Skipped "backend Maven verify was skipped by request"
    } else {
        Invoke-AcceptanceStep -Name "Backend Maven verify" -Action {
            & mvn "-DautoUpdate=false" verify
            if ($LASTEXITCODE -ne 0) {
                throw "mvn verify failed with exit code $LASTEXITCODE"
            }
        }
        Invoke-AcceptanceStep -Name "Quality report gate" -Action {
            Invoke-RepoScript `
                -RelativePath "scripts/verify-quality-reports.ps1" `
                -Parameters @{ RequireDependencyCheckReport = $true }
        }
    }

    if ($SkipWs1Scanners) {
        Add-Skipped "WS1 scanner gate was skipped by request"
    } else {
        Invoke-AcceptanceStep -Name "WS1 security scanner gate" -Action {
            Invoke-RepoScript `
                -RelativePath "scripts/verify-ws1-security.ps1" `
                -Parameters @{
                    SkipMaven = $true
                    SkipDependencyCheck = $true
                    SkipTrivyDbUpdate = $true
                    OutputDir = "target/ws1-security-acceptance"
                }
        }
    }

    if ($SkipFrontend) {
        Add-Skipped "WS5 frontend gate was skipped by request"
    } else {
        Invoke-AcceptanceStep -Name "WS5 frontend gate" -Action {
            Invoke-RepoScript -RelativePath "scripts/verify-ws5-frontend.ps1"
        }
    }

    Invoke-AcceptanceStep -Name "WS6 observability gate" -Action {
        Invoke-RepoScript -RelativePath "scripts/verify-ws6-observability.ps1"
    }
    Invoke-AcceptanceStep -Name "WS7 DevOps manifest gate" -Action {
        Invoke-RepoScript `
            -RelativePath "scripts/verify-ws7-devops.ps1" `
            -Parameters @{ RequireHelm = $true; DownloadHelmIfMissing = $true }
    }
    Invoke-AcceptanceStep -Name "WS8 anti-abuse and data-protection gate" -Action {
        Invoke-RepoScript -RelativePath "scripts/verify-ws8-security.ps1"
    }
    Invoke-AcceptanceStep -Name "Kyverno supply-chain gate" -Action {
        Invoke-RepoScript -RelativePath "scripts/verify-kyverno-supply-chain.ps1"
    }

    if ([string]::IsNullOrWhiteSpace($RuntimeBaseUrl)) {
        Add-Skipped "runtime smoke/API gates require -RuntimeBaseUrl"
    } else {
        Invoke-AcceptanceStep -Name "Runtime smoke gate" -Action {
            Invoke-RepoScript -RelativePath "scripts/verify-runtime-smoke.ps1" -Parameters @{ BaseUrl = $RuntimeBaseUrl; TimeoutSeconds = 30 }
        }
        Invoke-AcceptanceStep -Name "Runtime API security gate" -Action {
            Invoke-RepoScript -RelativePath "scripts/verify-runtime-api-security.ps1" -Parameters @{ BaseUrl = $RuntimeBaseUrl; TimeoutSeconds = 30 }
        }
    }

    if ($IncludeVmRuntime) {
        if ([string]::IsNullOrWhiteSpace($SshTarget)) {
            throw "-IncludeVmRuntime requires -SshTarget"
        }
        Invoke-AcceptanceStep -Name "MicroK8s dev runtime gate" -Action {
            Invoke-RepoScript `
                -RelativePath "scripts/verify-microk8s-dev-runtime.ps1" `
                -Parameters @{ SshTarget = $SshTarget; SkipDeploy = $true; RunApiSecurityProbe = $true }
        }
        Invoke-AcceptanceStep -Name "Argo CD MicroK8s GitOps gate" -Action {
            Invoke-RepoScript `
                -RelativePath "scripts/verify-argocd-microk8s-gitops.ps1" `
                -Parameters @{ SshTarget = $SshTarget; RunApiSecurityProbe = $true }
        }
    } else {
        Add-Skipped "VM MicroK8s/Argo CD runtime gates require -IncludeVmRuntime -SshTarget <user@host>"
    }

    if ($IncludeRuntimeImageScan -and -not $SkipRuntimeImageScan) {
        $imageScanParameters = @{
            ImageRef = $ImageRef
            SkipDbUpdate = $true
        }
        if (-not [string]::IsNullOrWhiteSpace($SshTarget)) {
            $imageScanParameters["SshTarget"] = $SshTarget
        }
        Invoke-AcceptanceStep -Name "Runtime image supply-chain gate" -Action {
            Invoke-RepoScript `
                -RelativePath "scripts/verify-runtime-image-supply-chain.ps1" `
                -Parameters $imageScanParameters
        }
    } else {
        Add-Skipped "runtime image scan requires -IncludeRuntimeImageScan"
    }

    if ($IncludeRuntimeDataProtection) {
        if ([string]::IsNullOrWhiteSpace($SshTarget)) {
            Invoke-AcceptanceStep -Name "Runtime data-protection gate" -Action {
                Invoke-RepoScript -RelativePath "scripts/verify-runtime-data-protection.ps1"
            }
        } else {
            Invoke-AcceptanceStep -Name "Remote runtime data-protection gate" -Action {
                Invoke-NativeChecked `
                    -FilePath "scp" `
                    -Arguments @("-q", "scripts/verify-runtime-data-protection.sh", "${SshTarget}:/tmp/verify-runtime-data-protection.sh") `
                    -Name "copy runtime data-protection verifier"
                Invoke-NativeChecked `
                    -FilePath "ssh" `
                    -Arguments @(
                        "-o",
                        "BatchMode=yes",
                        "-o",
                        "ConnectTimeout=10",
                        $SshTarget,
                        "chmod +x /tmp/verify-runtime-data-protection.sh && /tmp/verify-runtime-data-protection.sh --compose-project monkey-shop --app-service myshop --mysql-service mysql --minimum-flyway-version 18"
                    ) `
                    -Name "remote runtime data-protection verifier"
            }
        }
    } else {
        Add-Skipped "runtime data-protection gate requires -IncludeRuntimeDataProtection"
    }

    if ($IncludePublicEdge) {
        if ([string]::IsNullOrWhiteSpace($PublicBaseUrl)) {
            throw "-IncludePublicEdge requires -PublicBaseUrl or MONKEYSHOP_PUBLIC_URL"
        }
        Invoke-AcceptanceStep -Name "Public TLS and security headers gate" -Action {
            Invoke-RepoScript `
                -RelativePath "scripts/verify-public-edge-security.ps1" `
                -Parameters @{ BaseUrl = $PublicBaseUrl }
        }
    } else {
        Add-Skipped "public TLS/SecurityHeaders gate requires -IncludePublicEdge -PublicBaseUrl https://..."
    }

    if ($IncludeSonar) {
        Invoke-AcceptanceStep -Name "SonarQube Quality Gate" -Action {
            Invoke-RepoScript -RelativePath "scripts/verify-sonarqube-quality-gate.ps1"
        }
    } else {
        Add-Skipped "SonarQube Quality Gate requires -IncludeSonar and SONAR_* configuration"
    }

    Write-Host ""
    Write-Host "WS1-WS8 acceptance gate completed."
    Write-Host "Passed gates:"
    foreach ($item in $script:Passed) {
        Write-Host " - $item"
    }

    if ($script:Skipped.Count -gt 0) {
        Write-Host ""
        Write-Host "Open proof not executed in this run:"
        foreach ($item in $script:Skipped) {
            Write-Host " - $item"
        }
    }
} finally {
    Pop-Location
}
