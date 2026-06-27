param(
    [switch]$SkipDependencyCheck,
    [string]$OutputDir = "target/ws1-security",
    [string]$GitleaksPath = "",
    [string]$TrivyPath = "",
    [int]$UnauthenticatedNvdDelayMs = 8000
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-GateTool {
    param(
        [string]$ExplicitPath,
        [string]$CommandName,
        [string[]]$FallbackPaths = @()
    )

    if ($ExplicitPath) {
        if (Test-Path -LiteralPath $ExplicitPath) {
            return (Resolve-Path -LiteralPath $ExplicitPath).Path
        }
        throw "Configured path for $CommandName does not exist: $ExplicitPath"
    }

    $command = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    foreach ($fallback in $FallbackPaths) {
        if (Test-Path -LiteralPath $fallback) {
            return (Resolve-Path -LiteralPath $fallback).Path
        }
    }

    throw "Required tool not found: $CommandName"
}

function Invoke-GateCommand {
    param(
        [string]$Name,
        [string]$FilePath,
        [string[]]$Arguments
    )

    Write-Host "==> $Name"
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Invoke-LiteralRiskScan {
    param([string]$RipgrepPath)

    Write-Host "==> literal risk pattern scan"
    $riskPatterns = @(
        ("123" + "456"),
        ("monkey" + "pass"),
        ("root" + "password"),
        ("anystrong" + "password"),
        ("useSSL" + "=false"),
        ("ddl-auto" + "=update"),
        ("show-sql" + "=true"),
        ("SPRING" + "_DATASOURCE_"),
        ("csrf" + "\(AbstractHttpConfigurer::disable\)"),
        ("anyRequest\(\)" + "\.permitAll\(\)"),
        ("print" + "StackTrace"),
        ("System" + "\.out"),
        ("System" + "\.err")
    )
    $pattern = $riskPatterns -join "|"
    & $RipgrepPath `
        "-n" `
        $pattern `
        "." `
        "--glob" "!target/**" `
        "--glob" "!.git/**" `
        "--glob" "!uploads/**" `
        "--glob" "!*.jpg" `
        "--glob" "!*.png"

    if ($LASTEXITCODE -eq 0) {
        throw "Literal risk patterns were found"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "Literal risk scan failed with exit code $LASTEXITCODE"
    }
}

$mvn = Resolve-GateTool -CommandName "mvn"
$rg = Resolve-GateTool -CommandName "rg"
$uvx = Resolve-GateTool -CommandName "uvx"
$gitleaks = Resolve-GateTool `
    -ExplicitPath $GitleaksPath `
    -CommandName "gitleaks" `
    -FallbackPaths @("$env:USERPROFILE\.cache\codex-tools\ws1-security\gitleaks\gitleaks.exe")
$trivy = Resolve-GateTool `
    -ExplicitPath $TrivyPath `
    -CommandName "trivy" `
    -FallbackPaths @("$env:USERPROFILE\.cache\codex-tools\ws1-security\trivy\trivy.exe")

if ((-not $SkipDependencyCheck) -and (-not $env:NVD_API_KEY)) {
    Write-Warning "NVD_API_KEY is not set. dependency-check will use nvdApiDelay=$UnauthenticatedNvdDelayMs to reduce NVD API rate-limit failures."
}

$mavenArgs = @("clean", "verify")
if ($SkipDependencyCheck) {
    $mavenArgs = @("-Ddependency-check.skip=true", "clean", "verify")
} elseif (-not $env:NVD_API_KEY) {
    $mavenArgs = @("-DnvdApiDelay=$UnauthenticatedNvdDelayMs", "clean", "verify")
}
Invoke-GateCommand -Name "Maven verify" -FilePath $mvn -Arguments $mavenArgs

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
Invoke-LiteralRiskScan -RipgrepPath $rg

Invoke-GateCommand `
    -Name "gitleaks current tree" `
    -FilePath $gitleaks `
    -Arguments @(
        "dir", ".", "--redact", "--no-banner", "--exit-code", "1",
        "--report-format", "json", "--report-path", (Join-Path $OutputDir "gitleaks-current.json")
    )

Invoke-GateCommand `
    -Name "gitleaks git history" `
    -FilePath $gitleaks `
    -Arguments @(
        "git", ".", "--redact", "--no-banner", "--exit-code", "1",
        "--report-format", "json", "--report-path", (Join-Path $OutputDir "gitleaks-history.json")
    )

Invoke-GateCommand `
    -Name "Semgrep OWASP and secrets" `
    -FilePath $uvx `
    -Arguments @(
        "semgrep", "scan",
        "--config", "p/owasp-top-ten",
        "--config", "p/secrets",
        "--error",
        "--no-git-ignore",
        "--exclude", "target",
        "--exclude", ".git",
        "--exclude", "uploads",
        "--exclude", ".trae",
        "--exclude", "*.jpg",
        "--exclude", "*.png",
        "--json",
        "--output", (Join-Path $OutputDir "semgrep.json")
    )

Invoke-GateCommand `
    -Name "Trivy HIGH/CRITICAL filesystem scan" `
    -FilePath $trivy `
    -Arguments @(
        "fs",
        "--timeout", "30m",
        "--db-repository", "ghcr.io/aquasecurity/trivy-db:2",
        "--java-db-repository", "ghcr.io/aquasecurity/trivy-java-db:1",
        "--scanners", "vuln,secret,misconfig",
        "--severity", "HIGH,CRITICAL",
        "--exit-code", "1",
        "--skip-dirs", ".git",
        "--skip-dirs", "target",
        "--skip-dirs", "uploads",
        "--skip-dirs", ".trae",
        "--format", "json",
        "--output", (Join-Path $OutputDir "trivy.json"),
        "."
    )

Write-Host "WS1 security gate completed successfully. Reports: $OutputDir"
