param(
    [string]$ImageRef = "monkey-shop-myshop:latest",
    [string]$InputTar = "",
    [string]$SshTarget = "",
    [string[]]$SshOption = @(),
    [string]$RemoteExportDir = "/tmp/monkeyshop-runtime-image-export",
    [string]$OutputDir = "target/runtime-supply-chain",
    [string]$ReportName = "trivy-runtime-image.json",
    [string]$TrivyPath = "trivy",
    [string]$Severity = "HIGH,CRITICAL",
    [string]$PkgTypes = "os,library",
    [string[]]$TrivyDbRepository = @("ghcr.io/aquasecurity/trivy-db:2", "mirror.gcr.io/aquasec/trivy-db:2"),
    [string[]]$TrivyJavaDbRepository = @("ghcr.io/aquasecurity/trivy-java-db:1", "mirror.gcr.io/aquasec/trivy-java-db:1"),
    [int]$TimeoutMinutes = 15,
    [switch]$SkipDbUpdate,
    [switch]$KeepImageTar,
    [switch]$KeepRemoteImageTar
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function ConvertTo-SafeFileName {
    param([string]$Value)
    $safe = $Value -replace "[^A-Za-z0-9_.-]", "-"
    $safe = $safe.Trim("-")
    if ([string]::IsNullOrWhiteSpace($safe)) {
        return "runtime-image"
    }
    return $safe
}

function Quote-Posix {
    param([string]$Value)
    return "'" + ($Value -replace "'", "'\''") + "'"
}

function Invoke-NativeChecked {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$FailureMessage
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (exit code $LASTEXITCODE)"
    }
}

function Resolve-TrivyPath {
    param([string]$RequestedPath)

    if (Test-Path -LiteralPath $RequestedPath) {
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }

    $command = Get-Command $RequestedPath -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $cached = Join-Path $env:USERPROFILE ".cache/codex-tools/ws1-security/trivy/trivy.exe"
    if (Test-Path -LiteralPath $cached) {
        return $cached
    }

    throw "Trivy was not found. Run scripts/bootstrap-ws1-tools.ps1 or pass -TrivyPath."
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$trivy = Resolve-TrivyPath -RequestedPath $TrivyPath
$reportPath = Join-Path $OutputDir $ReportName
$safeImageName = ConvertTo-SafeFileName -Value $ImageRef
$localTarPath = $InputTar
$createdLocalTar = $false
$remoteTarPathForCleanup = ""

if ([string]::IsNullOrWhiteSpace($localTarPath)) {
    $localTarPath = Join-Path $OutputDir "$safeImageName.tar"
    $createdLocalTar = $true

    if ([string]::IsNullOrWhiteSpace($SshTarget)) {
        Write-Host "==> Exporting local Docker image $ImageRef"
        Invoke-NativeChecked `
            -FilePath "docker" `
            -Arguments @("save", $ImageRef, "-o", $localTarPath) `
            -FailureMessage "Failed to export local Docker image"
    } else {
        Write-Host "==> Exporting runtime Docker image $ImageRef on $SshTarget"
        $remoteTarName = "$safeImageName.tar"
        $remoteTarPath = "$($RemoteExportDir.TrimEnd('/'))/$remoteTarName"
        $remoteTarPathForCleanup = $remoteTarPath
        $remoteCommand = "set -e; mkdir -p $(Quote-Posix $RemoteExportDir); docker save $(Quote-Posix $ImageRef) -o $(Quote-Posix $remoteTarPath); sha256sum $(Quote-Posix $remoteTarPath); ls -lh $(Quote-Posix $remoteTarPath)"

        Invoke-NativeChecked `
            -FilePath "ssh" `
            -Arguments (@($SshOption) + @($SshTarget, $remoteCommand)) `
            -FailureMessage "Failed to export runtime image over SSH"

        Write-Host "==> Copying exported runtime image to $localTarPath"
        Invoke-NativeChecked `
            -FilePath "scp" `
            -Arguments (@($SshOption) + @("${SshTarget}:$remoteTarPath", $localTarPath)) `
            -FailureMessage "Failed to copy exported runtime image"
    }
}

Assert-True (Test-Path -LiteralPath $localTarPath) "Image tar was not found: $localTarPath"

$trivyArgs = @(
    "image",
    "--timeout", "$($TimeoutMinutes)m",
    "--scanners", "vuln,secret,misconfig",
    "--severity", $Severity,
    "--pkg-types", $PkgTypes,
    "--exit-code", "1",
    "--format", "json",
    "--output", $reportPath
)

foreach ($repository in $TrivyDbRepository) {
    $trivyArgs += @("--db-repository", $repository)
}
foreach ($repository in $TrivyJavaDbRepository) {
    $trivyArgs += @("--java-db-repository", $repository)
}

if ($SkipDbUpdate) {
    $trivyArgs += @("--skip-db-update", "--skip-java-db-update", "--skip-check-update", "--offline-scan", "--skip-version-check")
}

$trivyArgs += @("--input", $localTarPath)

$scanExitCode = 0
try {
    Write-Host "==> Trivy runtime image scan"
    & $trivy @trivyArgs
    $scanExitCode = $LASTEXITCODE
    if ($scanExitCode -ne 0) {
        Write-Host "Runtime image supply-chain gate failed; report: $reportPath" -ForegroundColor Red
    }
} finally {
    if ($createdLocalTar -and -not $KeepImageTar -and (Test-Path -LiteralPath $localTarPath)) {
        Remove-Item -LiteralPath $localTarPath -Force
    }
    if (-not [string]::IsNullOrWhiteSpace($remoteTarPathForCleanup) -and -not $KeepRemoteImageTar) {
        try {
            $cleanupCommand = "rm -f $(Quote-Posix $remoteTarPathForCleanup)"
            & ssh @SshOption $SshTarget $cleanupCommand
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "Remote image tar cleanup failed for $remoteTarPathForCleanup"
            }
        } catch {
            Write-Warning "Remote image tar cleanup failed for $remoteTarPathForCleanup"
        }
    }
}

if ($scanExitCode -ne 0) {
    throw "Runtime image supply-chain gate failed (exit code $scanExitCode); report: $reportPath"
}

Write-Host "Runtime image supply-chain gate completed successfully. Report: $reportPath"
