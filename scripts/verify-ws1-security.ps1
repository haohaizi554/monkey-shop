param(
    [switch]$SkipDependencyCheck,
    [switch]$SkipDependencyCheckUpdate,
    [string]$OutputDir = "target/ws1-security",
    [string]$GitleaksPath = "",
    [string]$TrivyPath = "",
    [switch]$SkipTrivyDbUpdate,
    [string[]]$TrivyDbRepository = @("ghcr.io/aquasecurity/trivy-db:2", "mirror.gcr.io/aquasec/trivy-db:2"),
    [string[]]$TrivyJavaDbRepository = @("ghcr.io/aquasecurity/trivy-java-db:1", "mirror.gcr.io/aquasec/trivy-java-db:1"),
    [int]$UnauthenticatedNvdDelayMs = 8000,
    [int]$MavenTimeoutSeconds = 1800
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
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 0
    )

    Write-Host "==> $Name"
    if ($TimeoutSeconds -gt 0) {
        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $FilePath
        $startInfo.Arguments = ($Arguments | ForEach-Object {
            if ($_ -match '[\s"]') {
                '"' + ($_ -replace '"', '\"') + '"'
            } else {
                $_
            }
        }) -join " "
        $startInfo.WorkingDirectory = (Get-Location).Path
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true

        $process = [System.Diagnostics.Process]::Start($startInfo)
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()

        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            & taskkill.exe /PID $process.Id /T /F | Out-Null
            Write-Output $stdoutTask.GetAwaiter().GetResult()
            Write-Output $stderrTask.GetAwaiter().GetResult()
            throw "$Name timed out after $TimeoutSeconds seconds"
        }
        $process.WaitForExit()

        Write-Output $stdoutTask.GetAwaiter().GetResult()
        Write-Output $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "$Name failed with exit code $($process.ExitCode)"
        }
        return
    }

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
        "--glob" "!frontend/node_modules/**" `
        "--glob" "!frontend/dist/**" `
        "--glob" "!frontend/dist-ssr/**" `
        "--glob" "!frontend/test-results/**" `
        "--glob" "!frontend/playwright-report/**" `
        "--glob" "!frontend/coverage/**" `
        "--glob" "!frontend/lighthouse-report.json" `
        "--glob" "!*.jpg" `
        "--glob" "!*.png"

    if ($LASTEXITCODE -eq 0) {
        throw "Literal risk patterns were found"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "Literal risk scan failed with exit code $LASTEXITCODE"
    }
}

function Assert-TextContains {
    param(
        [string]$Name,
        [string]$Content,
        [string]$Expected
    )

    if (-not $Content.Contains($Expected)) {
        throw "$Name is missing required security posture text: $Expected"
    }
}

function Assert-TextDoesNotContain {
    param(
        [string]$Name,
        [string]$Content,
        [string]$Forbidden
    )

    if ($Content.Contains($Forbidden)) {
        throw "$Name contains forbidden security posture text: $Forbidden"
    }
}

function Invoke-SecurityHeaderPostureScan {
    Write-Host "==> security header posture scan"

    $securityConfigPath = "src/main/java/com/example/monkey/config/SecurityConfig.java"
    $nginxConfigPath = "deploy/nginx/monkeyshop.conf"
    if (-not (Test-Path -LiteralPath $securityConfigPath)) {
        throw "SecurityConfig not found: $securityConfigPath"
    }
    if (-not (Test-Path -LiteralPath $nginxConfigPath)) {
        throw "Nginx edge config not found: $nginxConfigPath"
    }

    $securityConfig = Get-Content -LiteralPath $securityConfigPath -Raw
    $nginxConfig = Get-Content -LiteralPath $nginxConfigPath -Raw

    $springRequired = @(
        "Content-Security-Policy",
        "default-src 'self'",
        "script-src 'self' 'nonce-",
        "style-src 'self' 'nonce-",
        "https://challenges.cloudflare.com",
        "connect-src 'self' https://challenges.cloudflare.com",
        "frame-src https://challenges.cloudflare.com",
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self'",
        "frame-ancestors 'none'",
        "upgrade-insecure-requests",
        "includeSubDomains(true).preload(true).maxAgeInSeconds(31536000)",
        "frame.deny()",
        "ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN",
        "Permissions-Policy",
        "camera=(), microphone=(), geolocation=(), payment=()"
    )
    foreach ($expected in $springRequired) {
        Assert-TextContains -Name $securityConfigPath -Content $securityConfig -Expected $expected
    }

    $springForbidden = @(
        "unsafe-inline",
        "unpkg.com",
        "cdn.jsdelivr.net"
    )
    foreach ($forbidden in $springForbidden) {
        Assert-TextDoesNotContain -Name $securityConfigPath -Content $securityConfig -Forbidden $forbidden
    }

    $nginxRequired = @(
        'ssl_protocols TLSv1.3;',
        'return 301 https://monkeyshop.example.com$request_uri;',
        'add_header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload" always;',
        'add_header X-Frame-Options "DENY" always;',
        'add_header X-Content-Type-Options "nosniff" always;',
        'add_header Referrer-Policy "strict-origin-when-cross-origin" always;',
        'add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()" always;',
        'Content-Security-Policy is emitted by Spring Security',
        'proxy_set_header X-Forwarded-Proto https;',
        'proxy_set_header X-Forwarded-Port 443;'
    )
    foreach ($expected in $nginxRequired) {
        Assert-TextContains -Name $nginxConfigPath -Content $nginxConfig -Expected $expected
    }

    $nginxForbidden = @(
        "add_header Content-Security-Policy",
        "proxy_hide_header Content-Security-Policy"
    )
    foreach ($forbidden in $nginxForbidden) {
        Assert-TextDoesNotContain -Name $nginxConfigPath -Content $nginxConfig -Forbidden $forbidden
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

if ((-not $SkipDependencyCheck) -and (-not $SkipDependencyCheckUpdate) -and (-not $env:NVD_API_KEY)) {
    Write-Warning "NVD_API_KEY is not set. dependency-check will use nvdApiDelay=$UnauthenticatedNvdDelayMs to reduce NVD API rate-limit failures."
}

$mavenArgs = @("clean", "verify")
if ($SkipDependencyCheck) {
    $mavenArgs = @("-Ddependency-check.skip=true", "clean", "verify")
} elseif ($SkipDependencyCheckUpdate) {
    $mavenArgs = @("-DautoUpdate=false", "clean", "verify")
} elseif (-not $env:NVD_API_KEY) {
    $mavenArgs = @("-DnvdApiDelay=$UnauthenticatedNvdDelayMs", "clean", "verify")
}
Invoke-GateCommand -Name "Maven verify" -FilePath $mvn -Arguments $mavenArgs -TimeoutSeconds $MavenTimeoutSeconds

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
Invoke-LiteralRiskScan -RipgrepPath $rg
Invoke-SecurityHeaderPostureScan

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
        "--exclude", "frontend/node_modules",
        "--exclude", "frontend/dist",
        "--exclude", "frontend/dist-ssr",
        "--exclude", "frontend/test-results",
        "--exclude", "frontend/playwright-report",
        "--exclude", "frontend/coverage",
        "--exclude", "frontend/lighthouse-report.json",
        "--exclude", "mvnw",
        "--exclude", "*.jpg",
        "--exclude", "*.png",
        "--json",
        "--output", (Join-Path $OutputDir "semgrep.json")
    )

$trivyArgs = @("fs", "--timeout", "30m", "--no-progress")
foreach ($repository in $TrivyDbRepository) {
    $trivyArgs += @("--db-repository", $repository)
}
foreach ($repository in $TrivyJavaDbRepository) {
    $trivyArgs += @("--java-db-repository", $repository)
}
if ($SkipTrivyDbUpdate) {
    $trivyArgs += "--skip-db-update"
}
$trivyArgs += @(
    "--scanners", "vuln,secret,misconfig",
    "--severity", "HIGH,CRITICAL",
    "--exit-code", "1",
    "--skip-dirs", ".git",
    "--skip-dirs", "target",
    "--skip-dirs", "uploads",
    "--skip-dirs", ".trae",
    "--skip-dirs", "frontend/node_modules",
    "--skip-dirs", "frontend/dist",
    "--skip-dirs", "frontend/dist-ssr",
    "--skip-dirs", "frontend/test-results",
    "--skip-dirs", "frontend/playwright-report",
    "--skip-dirs", "frontend/coverage",
    "--skip-files", "frontend/lighthouse-report.json",
    "--format", "json",
    "--output", (Join-Path $OutputDir "trivy.json"),
    "."
)

Invoke-GateCommand `
    -Name "Trivy HIGH/CRITICAL filesystem scan" `
    -FilePath $trivy `
    -Arguments $trivyArgs

Write-Host "WS1 security gate completed successfully. Reports: $OutputDir"
