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
SELECT 'user.phone', COUNT(*), SUM(``phone`` IS NOT NULL AND ``phone`` <> ''), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone`` LIKE 'enc:v1:%'), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone`` NOT LIKE 'enc:v1:%') FROM ``user``
UNION ALL
SELECT 'user.email', COUNT(*), SUM(``email`` IS NOT NULL AND ``email`` <> ''), SUM(``email`` IS NOT NULL AND ``email`` <> '' AND ``email`` LIKE 'enc:v1:%'), SUM(``email`` IS NOT NULL AND ``email`` <> '' AND ``email`` NOT LIKE 'enc:v1:%') FROM ``user``
UNION ALL
SELECT 'user.phone_hmac', COUNT(*), SUM(``phone`` IS NOT NULL AND ``phone`` <> ''), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone_hmac`` REGEXP '^[0-9a-f]{64}$'), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND (``phone_hmac`` IS NULL OR ``phone_hmac`` NOT REGEXP '^[0-9a-f]{64}$')) FROM ``user``
UNION ALL
SELECT 'address.receiver_name', COUNT(*), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> ''), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> '' AND ``receiver_name`` LIKE 'enc:v1:%'), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> '' AND ``receiver_name`` NOT LIKE 'enc:v1:%') FROM ``address``
UNION ALL
SELECT 'address.phone', COUNT(*), SUM(``phone`` IS NOT NULL AND ``phone`` <> ''), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone`` LIKE 'enc:v1:%'), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone`` NOT LIKE 'enc:v1:%') FROM ``address``
UNION ALL
SELECT 'address.phone_hmac', COUNT(*), SUM(``phone`` IS NOT NULL AND ``phone`` <> ''), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND ``phone_hmac`` REGEXP '^[0-9a-f]{64}$'), SUM(``phone`` IS NOT NULL AND ``phone`` <> '' AND (``phone_hmac`` IS NULL OR ``phone_hmac`` NOT REGEXP '^[0-9a-f]{64}$')) FROM ``address``
UNION ALL
SELECT 'address.detail_address', COUNT(*), SUM(``detail_address`` IS NOT NULL AND ``detail_address`` <> ''), SUM(``detail_address`` IS NOT NULL AND ``detail_address`` <> '' AND ``detail_address`` LIKE 'enc:v1:%'), SUM(``detail_address`` IS NOT NULL AND ``detail_address`` <> '' AND ``detail_address`` NOT LIKE 'enc:v1:%') FROM ``address``
UNION ALL
SELECT 'orders.buyer_name', COUNT(*), SUM(``buyer_name`` IS NOT NULL AND ``buyer_name`` <> ''), SUM(``buyer_name`` IS NOT NULL AND ``buyer_name`` <> '' AND ``buyer_name`` LIKE 'enc:v1:%'), SUM(``buyer_name`` IS NOT NULL AND ``buyer_name`` <> '' AND ``buyer_name`` NOT LIKE 'enc:v1:%') FROM ``orders``
UNION ALL
SELECT 'orders.receiver_name', COUNT(*), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> ''), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> '' AND ``receiver_name`` LIKE 'enc:v1:%'), SUM(``receiver_name`` IS NOT NULL AND ``receiver_name`` <> '' AND ``receiver_name`` NOT LIKE 'enc:v1:%') FROM ``orders``
UNION ALL
SELECT 'orders.receiver_phone', COUNT(*), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> ''), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> '' AND ``receiver_phone`` LIKE 'enc:v1:%'), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> '' AND ``receiver_phone`` NOT LIKE 'enc:v1:%') FROM ``orders``
UNION ALL
SELECT 'orders.receiver_phone_hmac', COUNT(*), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> ''), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> '' AND ``receiver_phone_hmac`` REGEXP '^[0-9a-f]{64}$'), SUM(``receiver_phone`` IS NOT NULL AND ``receiver_phone`` <> '' AND (``receiver_phone_hmac`` IS NULL OR ``receiver_phone_hmac`` NOT REGEXP '^[0-9a-f]{64}$')) FROM ``orders``
UNION ALL
SELECT 'orders.address_snapshot', COUNT(*), SUM(``address_snapshot`` IS NOT NULL AND ``address_snapshot`` <> ''), SUM(``address_snapshot`` IS NOT NULL AND ``address_snapshot`` <> '' AND ``address_snapshot`` LIKE 'enc:v1:%'), SUM(``address_snapshot`` IS NOT NULL AND ``address_snapshot`` <> '' AND ``address_snapshot`` NOT LIKE 'enc:v1:%') FROM ``orders``;
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

Write-Host "Runtime data protection gate completed successfully for compose project $ComposeProject"
