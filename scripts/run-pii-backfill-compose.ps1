param(
    [string]$ComposeProject = "monkey-shop",
    [string]$AppService = "myshop",
    [string]$MysqlService = "mysql",
    [string]$EnvPath = ".env",
    [string]$BackupRoot = "monkey-shop-backups",
    [string]$AcknowledgeDataRewrite = "",
    [int]$HealthTimeoutSeconds = 120,
    [switch]$Execute,
    [switch]$GenerateMissingKeys,
    [switch]$SkipRestart
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RequiredAcknowledgement = "I understand this rewrites PII data"

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Invoke-Compose {
    param([string[]]$Arguments)
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ($output -join [Environment]::NewLine)
    }
    return $output
}

function Get-EnvFileValue {
    param(
        [string]$Path,
        [string]$Name
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        return ""
    }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match "^\s*#") {
            continue
        }
        if ($line -match ("^" + [regex]::Escape($Name) + "=(.*)$")) {
            return $Matches[1]
        }
    }
    return ""
}

function Set-EnvValue {
    param(
        [string]$Path,
        [string]$Name,
        [string]$Value
    )
    $lines = @()
    if (Test-Path -LiteralPath $Path) {
        $lines = @(Get-Content -LiteralPath $Path -Encoding UTF8)
    }

    $pattern = "^" + [regex]::Escape($Name) + "="
    $updated = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $pattern) {
            $lines[$i] = "$Name=$Value"
            $updated = $true
            break
        }
    }
    if (-not $updated) {
        $lines += "$Name=$Value"
    }
    Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}

function Set-EnvAssignments {
    param(
        [string]$Path,
        [string[]]$Assignments
    )
    foreach ($assignment in $Assignments) {
        $parts = $assignment -split "=", 2
        Assert-True ($parts.Count -eq 2) "Invalid env assignment: $assignment"
        Set-EnvValue -Path $Path -Name $parts[0] -Value $parts[1]
    }
}

function New-Base64Key {
    param([int]$Bytes = 32)
    $buffer = [byte[]]::new($Bytes)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    return [Convert]::ToBase64String($buffer)
}

function Ensure-KeyMaterial {
    param([string]$Path)
    foreach ($name in @("APP_PII_AES_KEY_BASE64", "APP_PII_HMAC_KEY_BASE64")) {
        $existing = Get-EnvFileValue -Path $Path -Name $name
        if (-not [string]::IsNullOrWhiteSpace($existing)) {
            Write-Host "$name is already present in $Path (value hidden)"
            continue
        }
        Assert-True $GenerateMissingKeys "$name is missing; rerun with -GenerateMissingKeys or set it in $Path first"
        Set-EnvValue -Path $Path -Name $name -Value (New-Base64Key)
        Write-Host "$name generated in $Path (value hidden)"
    }
}

function Get-AppRuntimeFlag {
    param([string]$Name)
    try {
        $lines = Invoke-Compose -Arguments @("compose", "-p", $ComposeProject, "exec", "-T", $AppService, "sh", "-c", "printenv '$Name' || true")
        $value = (($lines | Select-Object -First 1) -as [string]).Trim()
        if ([string]::IsNullOrWhiteSpace($value)) {
            return "<unset>"
        }
        return $value
    } catch {
        return "<unavailable>"
    }
}

function New-DatabaseBackup {
    $timestamp = Get-Date -Format "yyyyMMddHHmmss"
    $backupDir = Join-Path $BackupRoot "pre-pii-encryption-$timestamp"
    $backupFile = Join-Path $backupDir "monkeyshop.sql"
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null

    Write-Host "==> Creating MySQL backup at $backupFile"
    & docker compose -p $ComposeProject exec -T $MysqlService sh -c 'mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' > $backupFile
    if ($LASTEXITCODE -ne 0) {
        throw "mysqldump failed; backup is required before PII backfill"
    }

    $file = Get-Item -LiteralPath $backupFile
    Assert-True ($file.Length -gt 0) "mysqldump produced an empty backup file"
    Write-Host "Backup captured with non-zero size; contents not displayed"
}

function Wait-AppHealth {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-Compose -Arguments @("compose", "-p", $ComposeProject, "exec", "-T", $AppService, "sh", "-c", "curl -fsS http://127.0.0.1:8888/actuator/health || true")
            if (($health -join "`n").Contains('"status":"UP"')) {
                Write-Host "Application health is UP"
                return
            }
        } catch {
        }
        Start-Sleep -Seconds 3
    }
    throw "Application did not become healthy within $HealthTimeoutSeconds seconds"
}

function Wait-BackfillLog {
    $marker = "PII plaintext backfill completed"
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $logs = Invoke-Compose -Arguments @("compose", "-p", $ComposeProject, "logs", "--no-color", "--tail", "300", $AppService)
        if (($logs -join "`n").Contains($marker)) {
            Write-Host $marker
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "Timed out waiting for '$marker' in application logs"
}

function Restart-App {
    param([string]$Reason)
    if ($SkipRestart) {
        Write-Host "Skipping app restart for $Reason because -SkipRestart was supplied"
        return
    }
    Write-Host "==> Restarting $AppService for $Reason"
    Invoke-Compose -Arguments @("compose", "-p", $ComposeProject, "up", "-d", "--force-recreate", $AppService) | Out-Null
    Wait-AppHealth
}

$migrationMode = @(
    "APP_PII_ENCRYPTION_ENABLED=true",
    "APP_PII_KEY_PROVIDER=env",
    "APP_PII_ALLOW_PLAINTEXT_READ=true",
    "APP_PII_BACKFILL_ENABLED=true"
)

$strictMode = @(
    "APP_PII_ALLOW_PLAINTEXT_READ=false",
    "APP_PII_BACKFILL_ENABLED=false"
)

Write-Host "==> PII backfill compose preflight"
$services = Invoke-Compose -Arguments @("compose", "-p", $ComposeProject, "ps", "--services")
Assert-True ($services -contains $AppService) "Compose service '$AppService' was not found"
Assert-True ($services -contains $MysqlService) "Compose service '$MysqlService' was not found"

Write-Host "Runtime flags before migration (secret values are not read):"
foreach ($flag in @("APP_PII_ENCRYPTION_ENABLED", "APP_PII_KEY_PROVIDER", "APP_PII_ALLOW_PLAINTEXT_READ", "APP_PII_BACKFILL_ENABLED", "APP_PII_KEY_VERSION")) {
    Write-Host ("  {0}={1}" -f $flag, (Get-AppRuntimeFlag -Name $flag))
}

if (-not $Execute) {
    Write-Host "Dry run complete. Planned data-changing steps:"
    Write-Host "  1. Create a non-empty mysqldump backup under $BackupRoot."
    Write-Host "  2. Ensure PII keys exist in $EnvPath without printing them."
    Write-Host "  3. Enable migration mode: $($migrationMode -join ', ')."
    Write-Host "  4. Restart $AppService, wait for health, and wait for 'PII plaintext backfill completed'."
    Write-Host "  5. Restore strict mode: $($strictMode -join ', ')."
    Write-Host "  6. Run scripts\verify-runtime-data-protection.ps1 with -RequirePopulatedPii."
    Write-Host "Rerun with -Execute -AcknowledgeDataRewrite '$RequiredAcknowledgement' after explicit approval."
    return
}

Assert-True ($AcknowledgeDataRewrite -eq $RequiredAcknowledgement) "Execution requires -AcknowledgeDataRewrite '$RequiredAcknowledgement'"

New-DatabaseBackup
Ensure-KeyMaterial -Path $EnvPath

$existingKeyVersion = Get-EnvFileValue -Path $EnvPath -Name "APP_PII_KEY_VERSION"
if ([string]::IsNullOrWhiteSpace($existingKeyVersion)) {
    Set-EnvValue -Path $EnvPath -Name "APP_PII_KEY_VERSION" -Value "v1"
}
$existingKeyCreatedAt = Get-EnvFileValue -Path $EnvPath -Name "APP_PII_KEY_CREATED_AT"
if ([string]::IsNullOrWhiteSpace($existingKeyCreatedAt)) {
    Set-EnvValue -Path $EnvPath -Name "APP_PII_KEY_CREATED_AT" -Value ((Get-Date).ToUniversalTime().ToString("o"))
}

Set-EnvAssignments -Path $EnvPath -Assignments $migrationMode
Restart-App -Reason "PII plaintext backfill"
Wait-BackfillLog

Set-EnvAssignments -Path $EnvPath -Assignments $strictMode
Restart-App -Reason "strict PII protection"

$scriptRoot = Split-Path -Parent $PSCommandPath
$verifier = Join-Path $scriptRoot "verify-runtime-data-protection.ps1"
Assert-True (Test-Path -LiteralPath $verifier) "verify-runtime-data-protection.ps1 was not found next to this script"
& $verifier -ComposeProject $ComposeProject -AppService $AppService -MysqlService $MysqlService -RequirePopulatedPii
if ($LASTEXITCODE -ne 0) {
    throw "Runtime data protection verification failed"
}

Write-Host "PII backfill compose run completed; backup is under $BackupRoot and secrets/raw PII were not printed"
