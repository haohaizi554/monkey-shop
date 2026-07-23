param(
    [string]$EnvironmentPath = "",
    [switch]$SkipStart
)

. (Join-Path $PSScriptRoot "local-support-common.ps1")
Add-LocalRuntimeNoProxy

if ([string]::IsNullOrWhiteSpace($EnvironmentPath)) {
    $EnvironmentPath = $Script:LocalSupportEnvironmentPath
} else {
    $EnvironmentPath = Resolve-LocalRuntimePath -Path $EnvironmentPath -DefaultRelativePath ""
}
if (-not $SkipStart) {
    & (Join-Path $PSScriptRoot "start-local-support.ps1") -SkipBootstrap
}
Import-LocalRuntimeEnvironment -Path $EnvironmentPath -Required

$required = @(
    "APP_PII_VAULT_ADDR",
    "APP_PII_VAULT_TOKEN",
    "APP_PII_VAULT_TRANSIT_KEY",
    "APP_PII_VAULT_AES_CIPHERTEXT",
    "APP_PII_VAULT_HMAC_CIPHERTEXT",
    "APP_STORAGE_MINIO_ENDPOINT",
    "APP_STORAGE_MINIO_ACCESS_KEY",
    "APP_STORAGE_MINIO_SECRET_KEY",
    "APP_STORAGE_MINIO_BUCKET",
    "CLAMAV_HOST",
    "CLAMAV_PORT"
)
foreach ($name in $required) {
    Assert-LocalRuntime (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) "$name is required"
}

$env:MONKEYSHOP_LOCAL_SUPPORT_ACCEPTANCE = "true"
$maven = Get-RequiredLocalRuntimeCommand -Name "mvn.cmd"
& $maven -q "-Dtest=LocalProductionSupportAcceptanceTest" test
Assert-LocalRuntime ($LASTEXITCODE -eq 0) "LocalProductionSupportAcceptanceTest failed"
Write-Host "Vault Transit, SeaweedFS S3, and ClamAV semantic acceptance completed successfully."
