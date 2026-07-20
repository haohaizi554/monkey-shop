param(
    [string]$BackendBaseUrl = "http://127.0.0.1:8888",
    [string]$FrontendBaseUrl = "http://127.0.0.1:5173",
    [switch]$RunRateLimitProbe
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Write-Host "==> Local service status"
& (Join-Path $PSScriptRoot "status-local.ps1")

Write-Host "==> Backend runtime smoke"
& (Join-Path $PSScriptRoot "verify-runtime-smoke.ps1") `
    -BaseUrl $BackendBaseUrl `
    -SpaPath "/shop" `
    -TimeoutSeconds 30

Write-Host "==> Runtime API security"
$securityArguments = @{
    BaseUrl = $BackendBaseUrl
    TimeoutSeconds = 30
}
if ($RunRateLimitProbe) {
    $securityArguments.RunRateLimitProbe = $true
}
& (Join-Path $PSScriptRoot "verify-runtime-api-security.ps1") @securityArguments

Write-Host "==> OpenAPI surface"
$openApi = Invoke-RestMethod -Uri "$($BackendBaseUrl.TrimEnd('/'))/api/v1/openapi" -TimeoutSec 30
$operationNames = @("get", "post", "put", "patch", "delete", "head", "options")
$operationCount = 0
foreach ($path in $openApi.paths.PSObject.Properties) {
    $operationCount += @($path.Value.PSObject.Properties | Where-Object { $_.Name -in $operationNames }).Count
}
if ($operationCount -lt 100) {
    throw "OpenAPI operation count was unexpectedly low: $operationCount"
}
Write-Host "OpenAPI exposes $operationCount operations"

Write-Host "==> Real-browser local runtime acceptance"
$frontendPath = Join-Path (Split-Path -Parent $PSScriptRoot) "frontend"
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$previousRuntimeBaseUrl = [Environment]::GetEnvironmentVariable("RUNTIME_BASE_URL")
try {
    $env:RUNTIME_BASE_URL = $FrontendBaseUrl
    Push-Location $frontendPath
    & $npm run test:runtime
    if ($LASTEXITCODE -ne 0) {
        throw "Playwright local runtime acceptance failed"
    }
} finally {
    Pop-Location
    if ($null -eq $previousRuntimeBaseUrl) {
        Remove-Item Env:RUNTIME_BASE_URL -ErrorAction SilentlyContinue
    } else {
        $env:RUNTIME_BASE_URL = $previousRuntimeBaseUrl
    }
}

Write-Host "MonkeyShop local runtime verification completed successfully."
