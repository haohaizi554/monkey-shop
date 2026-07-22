param(
    [string]$EnvPath = "",
    [string]$LocalEnvPath = "",
    [string]$ApplicationJar = "",
    [switch]$RequirePopulatedPii
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "local-runtime-common.ps1")

$EnvPath = Resolve-LocalRuntimePath -Path $EnvPath -DefaultRelativePath ".env"
if ([string]::IsNullOrWhiteSpace($LocalEnvPath)) {
    $LocalEnvPath = Join-Path $Script:LocalRuntimeRoot "local-runtime.env"
} else {
    $LocalEnvPath = Resolve-LocalRuntimePath -Path $LocalEnvPath -DefaultRelativePath ""
}
Import-LocalRuntimeEnvironment -Path $EnvPath -Required
Import-LocalRuntimeEnvironment -Path $LocalEnvPath -Required

if ([string]::IsNullOrWhiteSpace($ApplicationJar)) {
    $ApplicationJar = Get-ChildItem -LiteralPath (Join-Path $Script:LocalRuntimeRepoRoot "target") -Filter "*.jar" -File |
        Where-Object { $_.Name -notmatch "-(exec|sources|javadoc)\.jar$" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
} else {
    $ApplicationJar = Resolve-LocalRuntimePath -Path $ApplicationJar -DefaultRelativePath ""
}
Assert-LocalRuntime (-not [string]::IsNullOrWhiteSpace($ApplicationJar)) "Run Maven package before the local data-protection gate"
Assert-LocalRuntime (Test-Path -LiteralPath $ApplicationJar -PathType Leaf) "Application jar was not found: $ApplicationJar"
Assert-LocalRuntime ($env:DB_URL -match "jdbc:mysql://(127\.0\.0\.1|localhost):") "DB_URL must target local MySQL"

foreach ($name in @("DB_PASSWORD", "APP_PII_AES_KEY_BASE64", "APP_PII_HMAC_KEY_BASE64")) {
    $value = [Environment]::GetEnvironmentVariable($name)
    Assert-LocalRuntime (-not [string]::IsNullOrWhiteSpace($value)) "$name is required for local PII verification"
}

$java = Get-RequiredLocalRuntimeCommand -Name "java"
$managedFlags = @(
    "APP_PII_ENCRYPTION_ENABLED",
    "APP_PII_ALLOW_PLAINTEXT_READ",
    "APP_PII_BACKFILL_ENABLED"
)
$previousFlags = @{}
foreach ($name in $managedFlags) {
    $previousFlags[$name] = [Environment]::GetEnvironmentVariable($name)
}

try {
    $env:APP_PII_ENCRYPTION_ENABLED = "true"
    $env:APP_PII_ALLOW_PLAINTEXT_READ = "false"
    $env:APP_PII_BACKFILL_ENABLED = "false"
    $requirePopulated = $RequirePopulatedPii.IsPresent.ToString().ToLowerInvariant()
    $arguments = @(
        "-Dloader.main=com.example.monkey.shared.infrastructure.privacy.PiiCiphertextAuditCli",
        "-cp",
        $ApplicationJar,
        "org.springframework.boot.loader.launch.PropertiesLauncher",
        "--spring.profiles.active=dev",
        "--app.pii.ciphertext-audit.require-populated=$requirePopulated"
    )

    $output = @(& $java @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "Local authenticated PII audit failed with exit code $exitCode"
    }
    $rendered = $output -join [Environment]::NewLine
    Assert-LocalRuntime `
        ($rendered.Contains("Authenticated PII ciphertext audit completed")) `
        "Local authenticated PII audit did not report completion"
} finally {
    foreach ($name in $managedFlags) {
        $value = $previousFlags[$name]
        if ($null -eq $value) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $value)
        }
    }
}

Write-Host "Local runtime data-protection gate completed successfully."
