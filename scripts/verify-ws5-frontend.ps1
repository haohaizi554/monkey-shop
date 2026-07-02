param(
    [string]$FrontendDir = "frontend",
    [switch]$InstallDependencies,
    [switch]$SkipAudit,
    [switch]$SkipFormat,
    [switch]$SkipApiContract,
    [switch]$SkipA11y,
    [switch]$SkipLighthouse,
    [string]$ChromePath = "",
    [double]$MinimumLighthouseScore = 0.95,
    [int]$MaximumLcpMilliseconds = 2500
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure {
    param([string]$Message)
    [void]$script:Failures.Add($Message)
}

function Invoke-FrontendCommand {
    param(
        [string]$Name,
        [string[]]$Arguments
    )

    Write-Host "==> $Name"
    Push-Location $script:FrontendPath
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & npm @Arguments 2>&1 | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) {
            Add-Failure "$Name failed with exit code $LASTEXITCODE"
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
    }
}

function Set-LighthouseChromePath {
    if ($ChromePath) {
        if (-not (Test-Path -LiteralPath $ChromePath)) {
            Add-Failure "Configured ChromePath does not exist: $ChromePath"
            return
        }
        $env:CHROME_PATH = (Resolve-Path -LiteralPath $ChromePath).Path
        return
    }

    if ($env:CHROME_PATH) {
        return
    }

    Push-Location $script:FrontendPath
    try {
        $detectedChromePath = & node -e "try { const { chromium } = require('playwright'); process.stdout.write(chromium.executablePath()); } catch { process.exit(0); }" 2>$null
        if ($LASTEXITCODE -eq 0 -and $detectedChromePath) {
            $env:CHROME_PATH = $detectedChromePath.Trim()
            Write-Host "==> CHROME_PATH detected from Playwright"
        }
    } finally {
        Pop-Location
    }
}

function Assert-LighthouseReport {
    $reportPath = Join-Path $script:FrontendPath "lighthouse-report.json"
    if (-not (Test-Path -LiteralPath $reportPath)) {
        Add-Failure "Missing Lighthouse report: $reportPath"
        return
    }

    $parser = @'
const fs = require('fs');

const [reportPath, minimumScoreRaw, maximumLcpRaw] = process.argv.slice(1);
const minimumScore = Number(minimumScoreRaw);
const maximumLcp = Number(maximumLcpRaw);
const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
const categories = report.categories || {};
const scores = [
  ['performance', categories.performance?.score],
  ['accessibility', categories.accessibility?.score],
  ['best-practices', categories['best-practices']?.score],
  ['seo', categories.seo?.score],
];
const lcp = report.audits?.['largest-contentful-paint']?.numericValue ?? Number.POSITIVE_INFINITY;
const failures = [];

for (const [name, score] of scores) {
  if (typeof score !== 'number' || score < minimumScore) {
    failures.push(`${name}=${typeof score === 'number' ? Math.round(score * 100) : 'missing'}`);
  }
}
if (!Number.isFinite(lcp) || lcp > maximumLcp) {
  failures.push(`largest-contentful-paint=${Number.isFinite(lcp) ? `${Math.round(lcp)}ms` : 'missing'}`);
}

console.log('Lighthouse summary:');
for (const [name, score] of scores) {
  console.log(`${name}: ${typeof score === 'number' ? Math.round(score * 100) : 'missing'}`);
}
console.log(`largest-contentful-paint: ${Number.isFinite(lcp) ? `${Math.round(lcp)}ms` : 'missing'}`);

if (failures.length > 0) {
  console.error(`Lighthouse gate failed: ${failures.join(', ')}`);
  process.exit(1);
}
'@

    Write-Host "==> Parse Lighthouse report"
    $output = & node -e $parser $reportPath $MinimumLighthouseScore $MaximumLcpMilliseconds 2>&1
    $output | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        Add-Failure "Lighthouse report gate failed"
    }
}

Write-Host "==> WS5 frontend checks"

$script:FrontendPath = Join-Path (Get-Location).Path $FrontendDir
if (-not (Test-Path -LiteralPath (Join-Path $script:FrontendPath "package.json"))) {
    Add-Failure "Missing frontend package.json under $script:FrontendPath"
}

if ($Failures.Count -eq 0) {
    if ($InstallDependencies) {
        Invoke-FrontendCommand -Name "npm ci" -Arguments @("ci")
    } elseif (-not (Test-Path -LiteralPath (Join-Path $script:FrontendPath "node_modules"))) {
        Add-Failure "Missing frontend/node_modules. Run with -InstallDependencies or run npm ci under $FrontendDir."
    }
}

if ($Failures.Count -eq 0 -and -not $SkipAudit) {
    Invoke-FrontendCommand -Name "npm run audit" -Arguments @("run", "audit")
}
if ($Failures.Count -eq 0 -and -not $SkipFormat) {
    Invoke-FrontendCommand -Name "npm run format" -Arguments @("run", "format")
}
if ($Failures.Count -eq 0) {
    Invoke-FrontendCommand -Name "npm run build" -Arguments @("run", "build")
}
if ($Failures.Count -eq 0) {
    Invoke-FrontendCommand -Name "npm run lint" -Arguments @("run", "lint")
}
if ($Failures.Count -eq 0 -and -not $SkipApiContract) {
    Invoke-FrontendCommand -Name "npm run test:api-contract" -Arguments @("run", "test:api-contract")
}
if ($Failures.Count -eq 0 -and -not $SkipA11y) {
    Invoke-FrontendCommand -Name "npm run test:a11y" -Arguments @("run", "test:a11y")
}
if ($Failures.Count -eq 0 -and -not $SkipLighthouse) {
    Set-LighthouseChromePath
    if ($Failures.Count -eq 0) {
        Invoke-FrontendCommand -Name "npm run test:lighthouse" -Arguments @("run", "test:lighthouse")
        if ($Failures.Count -eq 0) {
            Assert-LighthouseReport
        }
    }
}

if ($Failures.Count -gt 0) {
    Write-Host ""
    Write-Host "WS5 frontend verification failed:"
    foreach ($failure in $Failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "WS5 frontend verification passed."
