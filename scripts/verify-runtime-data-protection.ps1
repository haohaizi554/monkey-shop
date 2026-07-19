param(
    [string]$ComposeProject = "monkey-shop",
    [string]$AppService = "myshop",
    [string]$MysqlService = "mysql",
    [int]$MinimumFlywayVersion = 18,
    [switch]$RequirePopulatedPii
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}

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

function Invoke-MysqlQuery {
    param([string]$Sql)
    $encoded = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Sql))
    $shell = "printf '%s' '$encoded' | base64 -d | MYSQL_PWD=`"`$MYSQL_PASSWORD`" mysql -u`"`$MYSQL_USER`" -N -B `"`$MYSQL_DATABASE`""
    $lines = Invoke-Compose -Arguments @("compose", "-p", $ComposeProject, "exec", "-T", $MysqlService, "sh", "-c", $shell)
    return $lines | Where-Object { $_ -notmatch "^mysql: \\[Warning\\]" -and -not [string]::IsNullOrWhiteSpace($_) }
}

function Get-AppRuntimeFlag {
    param([string]$Name)
    $shell = "printenv '$Name' || true"
    $lines = Invoke-Compose -Arguments @("compose", "-p", $ComposeProject, "exec", "-T", $AppService, "sh", "-c", $shell)
    return (($lines | Select-Object -First 1) -as [string]).Trim()
}

function Invoke-AuthenticatedPiiAudit {
    $requirePopulated = $RequirePopulatedPii.IsPresent.ToString().ToLowerInvariant()
    $mainClass = "com.example.monkey.shared.infrastructure.privacy.PiiCiphertextAuditCli"
    $shell = "java -Dloader.main=$mainClass -cp /app/app.jar " +
        "org.springframework.boot.loader.launch.PropertiesLauncher " +
        "--app.pii.ciphertext-audit.require-populated=$requirePopulated"
    $output = Invoke-Compose -Arguments @(
        "compose", "-p", $ComposeProject, "exec", "-T", $AppService, "sh", "-c", $shell
    )
    $rendered = $output -join [Environment]::NewLine
    Assert-True (
        $rendered.Contains("Authenticated PII ciphertext audit completed")
    ) "authenticated PII ciphertext audit did not report completion"
    $output | ForEach-Object { Write-Host $_ }
}

function Convert-ToInt {
    param(
        [string]$Value,
        [string]$Name
    )
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "NULL") {
        return 0
    }
    $number = 0
    Assert-True ([int]::TryParse($Value, [ref]$number)) "$Name must be an integer but was '$Value'"
    return $number
}

$sql = @"
SELECT 'flyway.version' AS metric,
       COUNT(*) AS total_rows,
       COALESCE(MAX(CASE WHEN ``success`` = 1 THEN CAST(``version`` AS UNSIGNED) END), 0) AS populated,
       COALESCE(MAX(CASE WHEN ``success`` = 1 THEN CAST(``version`` AS UNSIGNED) END), 0) >= $MinimumFlywayVersion AS protected,
       COALESCE(MAX(CASE WHEN ``success`` = 1 THEN CAST(``version`` AS UNSIGNED) END), 0) < $MinimumFlywayVersion AS unprotected
FROM flyway_schema_history
UNION ALL
SELECT 'user.phone', COUNT(*), SUM(``phone`` IS NOT NULL AND ``phone`` <> ''), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``user``
UNION ALL
SELECT 'user.email', COUNT(*), SUM(``email`` IS NOT NULL AND ``email`` <> ''), SUM(``email`` IS NOT NULL AND ``email`` <> '' AND ``email`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``email`` IS NOT NULL AND ``email`` <> '' AND ``email`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``user``
UNION ALL
SELECT 'user.totp_secret', COUNT(*), SUM(``totp_secret`` IS NOT NULL AND ``totp_secret`` <> ''), SUM(``totp_secret`` IS NOT NULL AND ``totp_secret`` <> '' AND ``totp_secret`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``totp_secret`` IS NOT NULL AND ``totp_secret`` <> '' AND ``totp_secret`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``user``
UNION ALL
SELECT 'user.phone_hmac', COUNT(*), SUM(``phone`` IS NOT NULL AND ``phone`` <> ''), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone_hmac`` REGEXP '^[0-9a-f]{64}$'), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND (``phone_hmac`` IS NULL OR ``phone_hmac`` NOT REGEXP '^[0-9a-f]{64}$')) FROM ``user``
UNION ALL
SELECT 'address.receiver_name', COUNT(*), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> ''), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> '' AND ``receiver_name`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> '' AND ``receiver_name`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``address``
UNION ALL
SELECT 'address.phone', COUNT(*), SUM(``phone`` IS NOT NULL AND ``phone`` <> ''), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``address``
UNION ALL
SELECT 'address.phone_hmac', COUNT(*), SUM(``phone`` IS NOT NULL AND ``phone`` <> ''), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone_hmac`` REGEXP '^[0-9a-f]{64}$'), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND (``phone_hmac`` IS NULL OR ``phone_hmac`` NOT REGEXP '^[0-9a-f]{64}$')) FROM ``address``
UNION ALL
SELECT 'address.detail_address', COUNT(*), SUM(``detail_address`` IS NOT NULL AND ``detail_address`` <> ''), SUM(``detail_address`` IS NOT NULL AND ``detail_address`` <> '' AND ``detail_address`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``detail_address`` IS NOT NULL AND ``detail_address`` <> '' AND ``detail_address`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``address``
UNION ALL
SELECT 'orders.buyer_name', COUNT(*), SUM(``buyer_name`` IS NOT NULL AND ``buyer_name`` <> ''), SUM(``buyer_name`` IS NOT NULL AND ``buyer_name`` <> '' AND ``buyer_name`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``buyer_name`` IS NOT NULL AND ``buyer_name`` <> '' AND ``buyer_name`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``orders``
UNION ALL
SELECT 'orders.receiver_name', COUNT(*), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> ''), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> '' AND ``receiver_name`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> '' AND ``receiver_name`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``orders``
UNION ALL
SELECT 'orders.receiver_phone', COUNT(*), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> ''), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> '' AND ``receiver_phone`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> '' AND ``receiver_phone`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``orders``
UNION ALL
SELECT 'orders.receiver_phone_hmac', COUNT(*), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> ''), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> '' AND ``receiver_phone_hmac`` REGEXP '^[0-9a-f]{64}$'), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> '' AND (``receiver_phone_hmac`` IS NULL OR ``receiver_phone_hmac`` NOT REGEXP '^[0-9a-f]{64}$')) FROM ``orders``
UNION ALL
SELECT 'orders.address_snapshot', COUNT(*), SUM(``address_snapshot`` IS NOT NULL AND ``address_snapshot`` <> ''), SUM(``address_snapshot`` IS NOT NULL AND ``address_snapshot`` <> '' AND ``address_snapshot`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``address_snapshot`` IS NOT NULL AND ``address_snapshot`` <> '' AND ``address_snapshot`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``orders``
UNION ALL
SELECT 'order_review.content', COUNT(*), SUM(``content`` IS NOT NULL AND ``content`` <> ''), SUM(``content`` IS NOT NULL AND ``content`` <> '' AND ``content`` REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$'), SUM(``content`` IS NOT NULL AND ``content`` <> '' AND ``content`` NOT REGEXP '^enc:v1:[^:]+:(tink:[A-Za-z0-9_-]+|[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)$') FROM ``order_review``;
"@

Write-Host "==> Runtime PII configuration"
$encryptionEnabled = Get-AppRuntimeFlag -Name "APP_PII_ENCRYPTION_ENABLED"
$allowPlaintextRead = Get-AppRuntimeFlag -Name "APP_PII_ALLOW_PLAINTEXT_READ"
$backfillEnabled = Get-AppRuntimeFlag -Name "APP_PII_BACKFILL_ENABLED"
$keyProvider = Get-AppRuntimeFlag -Name "APP_PII_KEY_PROVIDER"
$keyVersion = Get-AppRuntimeFlag -Name "APP_PII_KEY_VERSION"

Assert-True ($encryptionEnabled -eq "true") "APP_PII_ENCRYPTION_ENABLED must be true"
Assert-True ($allowPlaintextRead -eq "false") "APP_PII_ALLOW_PLAINTEXT_READ must be false after backfill"
Assert-True ($backfillEnabled -eq "false") "APP_PII_BACKFILL_ENABLED must be false outside one-time migration"
Assert-True (-not [string]::IsNullOrWhiteSpace($keyProvider)) "APP_PII_KEY_PROVIDER must be set"
Assert-True (-not [string]::IsNullOrWhiteSpace($keyVersion)) "APP_PII_KEY_VERSION must be set"

Write-Host "==> Runtime PII database aggregate"
$rows = Invoke-MysqlQuery -Sql $sql
$populatedTotal = 0
foreach ($row in $rows) {
    $parts = ([string]$row).Split("`t")
    Assert-True ($parts.Count -eq 5) "unexpected PII aggregate row: $row"
    $metric = $parts[0]
    $populated = Convert-ToInt -Value $parts[2] -Name "$metric populated"
    $unprotected = Convert-ToInt -Value $parts[4] -Name "$metric unprotected"
    $populatedTotal += $populated
    Assert-True ($unprotected -eq 0) "$metric has $unprotected unprotected values"
    Write-Host ("{0}: populated={1}, unprotected={2}" -f $metric, $populated, $unprotected)
}

if ($RequirePopulatedPii) {
    Assert-True ($populatedTotal -gt 0) "runtime database must contain at least one populated PII value"
}

Write-Host "==> Authenticated PII ciphertext and blind-index audit"
Invoke-AuthenticatedPiiAudit

Write-Host "Runtime data protection gate completed successfully for compose project $ComposeProject"
