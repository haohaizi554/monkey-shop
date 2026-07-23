param(
    [string]$ProxyUri = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { "http://127.0.0.1:7890" }),
    [switch]$SkipBootstrap,
    [switch]$SkipSignatureUpdate,
    [switch]$AdoptEnvironmentPiiKeys,
    [int]$StartupTimeoutSeconds = 600
)

. (Join-Path $PSScriptRoot "local-support-common.ps1")
Add-LocalRuntimeNoProxy

$versionsPath = Join-Path $Script:LocalSupportToolsRoot "versions.json"
if (-not $SkipBootstrap -and -not (Test-Path -LiteralPath $versionsPath)) {
    & (Join-Path $PSScriptRoot "bootstrap-local-support.ps1") -ProxyUri $ProxyUri
}
Assert-LocalRuntime (Test-Path -LiteralPath $versionsPath) "Run scripts/bootstrap-local-support.ps1 first"

$configRoot = Join-Path $Script:LocalSupportRoot "config"
$dataRoot = Join-Path $Script:LocalSupportRoot "data"
$logRoot = Join-Path $Script:LocalSupportRoot "logs"
$vaultDataRoot = Join-Path $dataRoot "vault"
$seaweedDataRoot = Join-Path $dataRoot "seaweedfs"
$clamDatabaseRoot = Join-Path $dataRoot "clamav\database"
New-Item -ItemType Directory -Path $configRoot, $logRoot, $vaultDataRoot, $seaweedDataRoot, $clamDatabaseRoot, $Script:LocalSupportSecretsRoot -Force | Out-Null

$vaultAddress = "http://127.0.0.1:8200"
$vaultConfigPath = Join-Path $configRoot "vault.hcl"
$vaultPolicyPath = Join-Path $configRoot "monkeyshop-pii-decrypt.hcl"
$vaultOperatorPath = Join-Path $Script:LocalSupportSecretsRoot "vault-operator.json"
$supportSecretsPath = Join-Path $Script:LocalSupportSecretsRoot "support-secrets.json"
$seaweedS3ConfigPath = Join-Path $Script:LocalSupportSecretsRoot "seaweedfs-s3.json"
$clamConfigPath = Join-Path $configRoot "clamd.conf"
$freshClamConfigPath = Join-Path $configRoot "freshclam.conf"

$vaultDataPath = ConvertTo-LocalSupportPath -Path $vaultDataRoot
$vaultConfig = @"
ui = false
disable_mlock = true
api_addr = "http://127.0.0.1:8200"
cluster_addr = "http://127.0.0.1:8201"

storage "file" {
  path = "$vaultDataPath"
}

listener "tcp" {
  address = "127.0.0.1:8200"
  cluster_address = "127.0.0.1:8201"
  tls_disable = true
}
"@
[IO.File]::WriteAllText($vaultConfigPath, $vaultConfig, [Text.UTF8Encoding]::new($false))

$vaultPolicy = @'
path "transit/decrypt/monkeyshop-pii" {
  capabilities = ["update"]
}
'@
[IO.File]::WriteAllText($vaultPolicyPath, $vaultPolicy, [Text.UTF8Encoding]::new($false))

$clamDatabasePath = ConvertTo-LocalSupportPath -Path $clamDatabaseRoot
$clamLogPath = ConvertTo-LocalSupportPath -Path (Join-Path $logRoot "clamd.log")
$clamPidPath = ConvertTo-LocalSupportPath -Path (Join-Path $Script:LocalSupportRoot "clamd.pid")
$clamConfig = @"
LogFile $clamLogPath
LogTime yes
LogClean no
PidFile $clamPidPath
DatabaseDirectory $clamDatabasePath
TCPSocket 3310
TCPAddr 127.0.0.1
Foreground yes
ReadTimeout 30
CommandReadTimeout 5
SendBufTimeout 500
MaxThreads 8
MaxConnectionQueueLength 30
StreamMaxLength 6M
MaxFileSize 6M
MaxScanSize 20M
ExitOnOOM yes
ConcurrentDatabaseReload no
"@
[IO.File]::WriteAllText($clamConfigPath, $clamConfig, [Text.UTF8Encoding]::new($false))

$freshClamLogPath = ConvertTo-LocalSupportPath -Path (Join-Path $logRoot "freshclam.log")
$freshClamLines = @(
    "DatabaseDirectory $clamDatabasePath",
    "UpdateLogFile $freshClamLogPath",
    "LogTime yes",
    "DatabaseMirror database.clamav.net",
    "Checks 12",
    "TestDatabases yes"
)
if (-not [string]::IsNullOrWhiteSpace($ProxyUri)) {
    $proxy = [Uri]$ProxyUri
    $freshClamLines += "HTTPProxyServer $($proxy.Host)"
    $freshClamLines += "HTTPProxyPort $($proxy.Port)"
}
[IO.File]::WriteAllLines($freshClamConfigPath, $freshClamLines, [Text.UTF8Encoding]::new($false))

$vault = Get-LocalSupportExecutable -Tool "vault" -Executable "vault.exe"
$seaweed = Get-LocalSupportExecutable -Tool "seaweedfs" -Executable "weed.exe"
$clamd = Get-LocalSupportExecutable -Tool "clamav" -Executable "clamd.exe"
$freshClam = Get-LocalSupportExecutable -Tool "clamav" -Executable "freshclam.exe"

$previousState = Read-LocalSupportState
if ($null -ne $previousState) {
    Assert-LocalRuntime ($previousState.repositoryRoot -eq $Script:LocalRuntimeRepoRoot) "Local support state belongs to another repository"
}
$state = [ordered]@{
    version = 1
    repositoryRoot = $Script:LocalRuntimeRepoRoot
    updatedAtUtc = [DateTime]::UtcNow.ToString("O")
    services = [ordered]@{}
}
$startedThisRun = [Collections.Generic.List[object]]::new()

function Save-ServiceRecord {
    param(
        [string]$Name,
        [object]$Record
    )
    $state.services[$Name] = $Record
    Save-LocalSupportState -State $state
}

function Get-ExistingServiceRecord {
    param(
        [string]$Name,
        [int]$Port
    )
    if ($null -ne $previousState) {
        $property = $previousState.services.PSObject.Properties[$Name]
        if ($null -ne $property -and $property.Value.managed) {
            $record = $property.Value
            if ((Test-LocalRuntimeProcessIdentity -Identity $record.launcher) -or
                (Test-LocalRuntimeProcessIdentity -Identity $record.listener)) {
                return $record
            }
        }
    }
    return New-UnmanagedLocalRuntimeServiceRecord -Port $Port
}

function Start-LocalSupportProcess {
    param(
        [string]$Name,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [int]$Port
    )
    Write-Host "==> $Name"
    if (Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $Port) {
        Write-Host "$Name is already listening on 127.0.0.1:$Port"
        Save-ServiceRecord -Name $Name -Record (Get-ExistingServiceRecord -Name $Name -Port $Port)
        return
    }
    $stdout = Join-Path $logRoot "$Name.out.log"
    $stderr = Join-Path $logRoot "$Name.err.log"
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $startedThisRun.Add([ordered]@{ name = $Name; processId = $process.Id })
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
        do {
            if (Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $Port) {
                Save-ServiceRecord -Name $Name -Record (New-LocalRuntimeServiceRecord -Launcher $process -Port $Port -StandardOutput $stdout -StandardError $stderr)
                return
            }
            if ($process.HasExited) {
                throw "$Name exited before listening on 127.0.0.1:$Port"
            }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        throw "$Name did not listen on 127.0.0.1:$Port within $StartupTimeoutSeconds seconds"
    } catch {
        Get-Content -LiteralPath $stdout -Tail 80 -ErrorAction SilentlyContinue
        Get-Content -LiteralPath $stderr -Tail 80 -ErrorAction SilentlyContinue
        Stop-LocalRuntimeProcessTree -ProcessId $process.Id -Name $Name
        throw
    }
}

function Invoke-VaultJson {
    param(
        [string]$Path,
        [string]$Method = "GET",
        [object]$Body = $null,
        [string]$Token = ""
    )
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers["X-Vault-Token"] = $Token
    }
    $parameters = @{
        Uri = "$vaultAddress/v1/$Path"
        Method = $Method
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = 10
    }
    if ($null -ne $Body) {
        $parameters["ContentType"] = "application/json"
        $parameters["Body"] = $Body | ConvertTo-Json -Depth 6 -Compress
    }
    return Invoke-RestMethod @parameters
}

function Test-VaultRequest {
    param(
        [string]$Path,
        [string]$Token = ""
    )
    try {
        $null = Invoke-VaultJson -Path $Path -Token $Token
        return $true
    } catch {
        return $false
    }
}

function Invoke-VaultKeyWrap {
    param(
        [string]$RootToken,
        [string]$PlaintextBase64
    )
    Assert-LocalRuntime (-not [string]::IsNullOrWhiteSpace($PlaintextBase64)) "Vault key plaintext is required"
    $result = Invoke-VaultJson -Path "transit/encrypt/monkeyshop-pii" -Method "POST" -Token $RootToken -Body @{ plaintext = $PlaintextBase64 }
    Assert-LocalRuntime (-not [string]::IsNullOrWhiteSpace($result.data.ciphertext)) "Vault did not return a wrapped key"
    return [string]$result.data.ciphertext
}

function New-VaultWrappedKey {
    param([string]$RootToken)
    $bytes = [byte[]]::new(32)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
        $plaintext = [Convert]::ToBase64String($bytes)
        return Invoke-VaultKeyWrap -RootToken $RootToken -PlaintextBase64 $plaintext
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
        $random.Dispose()
    }
}

function Write-ApplicationEnvironment {
    param([object]$Secrets)
    $lines = @(
        "APP_PII_ENCRYPTION_ENABLED=true",
        "APP_PII_KEY_PROVIDER=vault-transit",
        "APP_PII_VAULT_ADDR=$vaultAddress",
        "APP_PII_VAULT_TOKEN=$($Secrets.vaultApplicationToken)",
        "APP_PII_VAULT_TRANSIT_KEY=monkeyshop-pii",
        "APP_PII_VAULT_AES_CIPHERTEXT=$($Secrets.vaultAesCiphertext)",
        "APP_PII_VAULT_HMAC_CIPHERTEXT=$($Secrets.vaultHmacCiphertext)",
        "APP_PII_VAULT_PREVIOUS_AES_CIPHERTEXTS=",
        "APP_PII_VAULT_TIMEOUT=PT3S",
        "APP_PII_ALLOW_PLAINTEXT_READ=false",
        "APP_PII_BACKFILL_ENABLED=false",
        "APP_PII_ROTATION_ENFORCE=true",
        "APP_PII_ROTATION_MAX_AGE=PT2160H",
        "APP_PII_KEY_VERSION=v1",
        "APP_PII_KEY_CREATED_AT=$($Secrets.piiKeyCreatedAt)",
        "APP_STORAGE_PROVIDER=minio",
        "APP_STORAGE_MINIO_ENDPOINT=http://127.0.0.1:8333",
        "APP_STORAGE_MINIO_ACCESS_KEY=$($Secrets.s3AccessKey)",
        "APP_STORAGE_MINIO_SECRET_KEY=$($Secrets.s3SecretKey)",
        "APP_STORAGE_MINIO_BUCKET=monkeyshop",
        "APP_UPLOAD_VIRUS_SCAN_ENABLED=true",
        "CLAMAV_HOST=127.0.0.1",
        "CLAMAV_PORT=3310",
        "CLAMAV_TIMEOUT_MILLIS=5000",
        "APP_INTEGRATIONS_STARTUP_READINESS_REQUIRED=true"
    )
    [IO.File]::WriteAllLines($Script:LocalSupportEnvironmentPath, $lines, [Text.UTF8Encoding]::new($false))
    Protect-LocalSupportSecret -Path $Script:LocalSupportEnvironmentPath
}

try {
    Start-LocalSupportProcess -Name "vault" -Executable $vault -Arguments @("server", "-config=$vaultConfigPath") -WorkingDirectory (Split-Path -Parent $vault) -Port 8200

    $initStatus = Invoke-VaultJson -Path "sys/init"
    if (-not [bool]$initStatus.initialized) {
        $initialization = Invoke-VaultJson -Path "sys/init" -Method "PUT" -Body @{ secret_shares = 1; secret_threshold = 1 }
        $operator = [ordered]@{
            unsealKey = [string]$initialization.keys_base64[0]
            rootToken = [string]$initialization.root_token
        }
        [IO.File]::WriteAllText($vaultOperatorPath, ($operator | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        Protect-LocalSupportSecret -Path $vaultOperatorPath
    }
    Assert-LocalRuntime (Test-Path -LiteralPath $vaultOperatorPath) "Vault operator material is missing: $vaultOperatorPath"
    $operator = Get-Content -LiteralPath $vaultOperatorPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $sealStatus = Invoke-VaultJson -Path "sys/seal-status"
    if ([bool]$sealStatus.sealed) {
        $sealStatus = Invoke-VaultJson -Path "sys/unseal" -Method "PUT" -Body @{ key = $operator.unsealKey }
    }
    Assert-LocalRuntime (-not [bool]$sealStatus.sealed) "Vault remains sealed"

    $rootToken = [string]$operator.rootToken
    $mounts = Invoke-VaultJson -Path "sys/mounts" -Token $rootToken
    if ($mounts.data.PSObject.Properties.Name -notcontains "transit/") {
        $null = Invoke-VaultJson -Path "sys/mounts/transit" -Method "POST" -Token $rootToken -Body @{ type = "transit" }
    }
    if (-not (Test-VaultRequest -Path "transit/keys/monkeyshop-pii" -Token $rootToken)) {
        $null = Invoke-VaultJson -Path "transit/keys/monkeyshop-pii" -Method "POST" -Token $rootToken -Body @{ type = "aes256-gcm96" }
    }
    $null = Invoke-VaultJson -Path "sys/policies/acl/monkeyshop-pii-decrypt" -Method "PUT" -Token $rootToken -Body @{ policy = $vaultPolicy }

    $secrets = if (Test-Path -LiteralPath $supportSecretsPath) {
        Get-Content -LiteralPath $supportSecretsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } else {
        [pscustomobject]@{}
    }
    $properties = @($secrets.PSObject.Properties | ForEach-Object { $_.Name })
    $tokenValid = $properties -contains "vaultApplicationToken" -and
        (Test-VaultRequest -Path "auth/token/lookup-self" -Token ([string]$secrets.vaultApplicationToken))
    if (-not $tokenValid) {
        $tokenResult = Invoke-VaultJson -Path "auth/token/create" -Method "POST" -Token $rootToken -Body @{
            policies = @("monkeyshop-pii-decrypt")
            no_parent = $true
            ttl = "720h"
            renewable = $true
            display_name = "monkeyshop-local"
        }
        $secrets | Add-Member -NotePropertyName vaultApplicationToken -NotePropertyValue ([string]$tokenResult.auth.client_token) -Force
    }
    $properties = @($secrets.PSObject.Properties | ForEach-Object { $_.Name })
    if ($AdoptEnvironmentPiiKeys -and
        ($properties -notcontains "piiKeySource" -or [string]$secrets.piiKeySource -ne "environment")) {
        $aesPlaintext = [Environment]::GetEnvironmentVariable("APP_PII_AES_KEY_BASE64")
        $hmacPlaintext = [Environment]::GetEnvironmentVariable("APP_PII_HMAC_KEY_BASE64")
        Assert-LocalRuntime (-not [string]::IsNullOrWhiteSpace($aesPlaintext)) "APP_PII_AES_KEY_BASE64 is required for PII key adoption"
        Assert-LocalRuntime (-not [string]::IsNullOrWhiteSpace($hmacPlaintext)) "APP_PII_HMAC_KEY_BASE64 is required for PII key adoption"
        $aesBytes = $null
        $hmacBytes = $null
        try {
            try {
                $aesBytes = [Convert]::FromBase64String($aesPlaintext)
                $hmacBytes = [Convert]::FromBase64String($hmacPlaintext)
            } catch [FormatException] {
                throw "PII keys must be valid Base64 before Vault adoption"
            }
            Assert-LocalRuntime ($aesBytes.Length -eq 32) "APP_PII_AES_KEY_BASE64 must decode to 32 bytes"
            Assert-LocalRuntime ($hmacBytes.Length -eq 32) "APP_PII_HMAC_KEY_BASE64 must decode to 32 bytes"
            $secrets | Add-Member -NotePropertyName vaultAesCiphertext -NotePropertyValue (Invoke-VaultKeyWrap -RootToken $rootToken -PlaintextBase64 $aesPlaintext) -Force
            $secrets | Add-Member -NotePropertyName vaultHmacCiphertext -NotePropertyValue (Invoke-VaultKeyWrap -RootToken $rootToken -PlaintextBase64 $hmacPlaintext) -Force
            $secrets | Add-Member -NotePropertyName piiKeyCreatedAt -NotePropertyValue ([DateTime]::UtcNow.ToString("O")) -Force
            $secrets | Add-Member -NotePropertyName piiKeySource -NotePropertyValue "environment" -Force
        } finally {
            if ($null -ne $aesBytes) {
                [Array]::Clear($aesBytes, 0, $aesBytes.Length)
            }
            if ($null -ne $hmacBytes) {
                [Array]::Clear($hmacBytes, 0, $hmacBytes.Length)
            }
        }
    }
    $properties = @($secrets.PSObject.Properties | ForEach-Object { $_.Name })
    if ($properties -notcontains "vaultAesCiphertext") {
        $secrets | Add-Member -NotePropertyName vaultAesCiphertext -NotePropertyValue (New-VaultWrappedKey -RootToken $rootToken) -Force
    }
    if ($properties -notcontains "vaultHmacCiphertext") {
        $secrets | Add-Member -NotePropertyName vaultHmacCiphertext -NotePropertyValue (New-VaultWrappedKey -RootToken $rootToken) -Force
    }
    if ($properties -notcontains "piiKeyCreatedAt") {
        $secrets | Add-Member -NotePropertyName piiKeyCreatedAt -NotePropertyValue ([DateTime]::UtcNow.ToString("O")) -Force
    }
    if ($properties -notcontains "piiKeySource") {
        $secrets | Add-Member -NotePropertyName piiKeySource -NotePropertyValue "generated" -Force
    }
    if ($properties -notcontains "s3AccessKey") {
        $secrets | Add-Member -NotePropertyName s3AccessKey -NotePropertyValue (New-LocalSupportRandomToken -ByteLength 18) -Force
    }
    if ($properties -notcontains "s3SecretKey") {
        $secrets | Add-Member -NotePropertyName s3SecretKey -NotePropertyValue (New-LocalSupportRandomToken -ByteLength 32) -Force
    }
    [IO.File]::WriteAllText($supportSecretsPath, ($secrets | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    Protect-LocalSupportSecret -Path $supportSecretsPath
    Write-ApplicationEnvironment -Secrets $secrets
    Import-LocalRuntimeEnvironment -Path $Script:LocalSupportEnvironmentPath -Required

    $s3Config = [ordered]@{
        identities = @(
            [ordered]@{
                name = "monkeyshop-local"
                credentials = @([ordered]@{
                    accessKey = [string]$secrets.s3AccessKey
                    secretKey = [string]$secrets.s3SecretKey
                })
                actions = @("Admin", "Read", "List", "Tagging", "Write")
            }
        )
    }
    [IO.File]::WriteAllText($seaweedS3ConfigPath, ($s3Config | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Protect-LocalSupportSecret -Path $seaweedS3ConfigPath
    Start-LocalSupportProcess -Name "seaweedfs" -Executable $seaweed -Arguments @(
        "server",
        "-dir=$seaweedDataRoot",
        "-ip=127.0.0.1",
        "-ip.bind=127.0.0.1",
        "-master.port=9333",
        "-master.telemetry=false",
        "-volume.port=9340",
        "-filer=true",
        "-filer.port=8887",
        "-filer.disableDirListing=true",
        "-filer.exposeDirectoryData=false",
        "-s3=true",
        "-s3.port=8333",
        "-s3.ip.bind=127.0.0.1",
        "-s3.port.iceberg=0",
        "-s3.externalUrl=http://127.0.0.1:8333",
        "-s3.config=$seaweedS3ConfigPath",
        "-webdav=false"
    ) -WorkingDirectory $seaweedDataRoot -Port 8333
    $databaseFiles = @(Get-ChildItem -LiteralPath $clamDatabaseRoot -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in @(".cvd", ".cld") })
    if (-not $SkipSignatureUpdate) {
        $freshStdout = Join-Path $logRoot "freshclam.out.log"
        $freshStderr = Join-Path $logRoot "freshclam.err.log"
        $freshProcess = Start-Process -FilePath $freshClam -ArgumentList @("--config-file=$freshClamConfigPath", "--stdout") -WorkingDirectory (Split-Path -Parent $freshClam) -RedirectStandardOutput $freshStdout -RedirectStandardError $freshStderr -WindowStyle Hidden -Wait -PassThru
        $databaseFiles = @(Get-ChildItem -LiteralPath $clamDatabaseRoot -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -in @(".cvd", ".cld") })
        if ($freshProcess.ExitCode -ne 0 -and $databaseFiles.Count -eq 0) {
            Get-Content -LiteralPath $freshStdout -Tail 80 -ErrorAction SilentlyContinue
            Get-Content -LiteralPath $freshStderr -Tail 80 -ErrorAction SilentlyContinue
            throw "FreshClam failed and no virus database is available"
        }
    }
    Assert-LocalRuntime ($databaseFiles.Count -gt 0) "ClamAV virus database is missing; run without -SkipSignatureUpdate"
    Start-LocalSupportProcess -Name "clamav" -Executable $clamd -Arguments @("--config-file=$clamConfigPath") -WorkingDirectory (Split-Path -Parent $clamd) -Port 3310
    $clamDeadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    while (-not (Test-LocalSupportClamAv) -and [DateTime]::UtcNow -lt $clamDeadline) {
        Start-Sleep -Seconds 1
    }
    Assert-LocalRuntime (Test-LocalSupportClamAv) "ClamAV did not answer PONG on 127.0.0.1:3310"

    & (Join-Path $PSScriptRoot "status-local-support.ps1")
} catch {
    for ($index = $startedThisRun.Count - 1; $index -ge 0; $index--) {
        $started = $startedThisRun[$index]
        if ($null -ne (Get-Process -Id ([int]$started.processId) -ErrorAction SilentlyContinue)) {
            Stop-LocalRuntimeProcessTree -ProcessId ([int]$started.processId) -Name ([string]$started.name)
        }
    }
    throw
}
