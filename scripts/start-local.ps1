param(
    [string]$EnvPath = "",
    [string]$LocalEnvPath = "",
    [string]$MemuraiExecutable = "",
    [int]$MySqlPort = 3306,
    [int]$RedisPort = 6379,
    [int]$BackendPort = 8888,
    [int]$FrontendPort = 5173,
    [int]$StartupTimeoutSeconds = 120
)

. (Join-Path $PSScriptRoot "local-runtime-common.ps1")

$EnvPath = Resolve-LocalRuntimePath -Path $EnvPath -DefaultRelativePath ".env"
if ([string]::IsNullOrWhiteSpace($LocalEnvPath)) {
    $LocalEnvPath = Join-Path $Script:LocalRuntimeRoot "local-runtime.env"
} else {
    $LocalEnvPath = Resolve-LocalRuntimePath -Path $LocalEnvPath -DefaultRelativePath ""
}
Import-LocalRuntimeEnvironment -Path $EnvPath -Required
Import-LocalRuntimeEnvironment -Path $LocalEnvPath -Required

$requiredEnvironment = @(
    "DB_URL",
    "DB_USERNAME",
    "DB_PASSWORD",
    "ADMIN_INIT_USERNAME",
    "ADMIN_INIT_PASSWORD",
    "ADMIN_TOTP_SECRET",
    "APP_JWT_SECRET",
    "APP_PII_AES_KEY_BASE64",
    "APP_PII_HMAC_KEY_BASE64",
    "APP_PAYMENT_CALLBACK_SECRET",
    "APP_LOGISTICS_WEBHOOK_SECRET"
)
foreach ($name in $requiredEnvironment) {
    $value = [Environment]::GetEnvironmentVariable($name)
    Assert-LocalRuntime (-not [string]::IsNullOrWhiteSpace($value)) "$name is required in .env or the workstation runtime environment"
}

$redisHost = [Environment]::GetEnvironmentVariable("SPRING_DATA_REDIS_HOST")
Assert-LocalRuntime ($env:DB_URL -match "jdbc:mysql://(127\.0\.0\.1|localhost):") "DB_URL must target local MySQL"
Assert-LocalRuntime ($redisHost -in @("127.0.0.1", "localhost")) "SPRING_DATA_REDIS_HOST must target local Redis"

$logsPath = Join-Path $Script:LocalRuntimeRoot "logs"
$redisDataPath = Join-Path $Script:LocalRuntimeRoot "data\redis"
$uploadPath = Join-Path $Script:LocalRuntimeRoot "uploads\images"
New-Item -ItemType Directory -Path $logsPath, $redisDataPath, $uploadPath -Force | Out-Null

$env:SPRING_PROFILES_ACTIVE = "dev"
$env:SERVER_PORT = [string]$BackendPort
$env:SPRING_DATA_REDIS_PORT = [string]$RedisPort
$env:APP_UPLOAD_PATH = $uploadPath
$env:APP_STORAGE_PROVIDER = "local"
$env:APP_UPLOAD_VIRUS_SCAN_ENABLED = "false"
$env:APP_JWT_REQUIRE_REDIS_TOKEN_STORE = "true"
$env:APP_AUTH_REQUIRE_REDIS_STATE = "true"
$env:APP_RATE_LIMIT_REQUIRE_REDIS_STATE = "true"
$env:SESSION_COOKIE_SECURE = "false"
$env:APP_JWT_COOKIE_SECURE = "false"
$env:APP_AUTH_CAPTCHA_COOKIE_SECURE = "false"
$env:OTEL_TRACES_EXPORTER = "none"
$env:OTEL_METRICS_EXPORTER = "none"
$env:OTEL_LOGS_EXPORTER = "none"

$previousState = Read-LocalRuntimeState
if ($null -ne $previousState) {
    Assert-LocalRuntime `
        ($previousState.repositoryRoot -eq $Script:LocalRuntimeRepoRoot) `
        "Runtime state belongs to another repository"
}
$state = [ordered]@{
    version = 1
    repositoryRoot = $Script:LocalRuntimeRepoRoot
    updatedAtUtc = [DateTime]::UtcNow.ToString("O")
    services = [ordered]@{}
}

function Save-ServiceRecord {
    param(
        [string]$Name,
        [object]$Record
    )
    $state.services[$Name] = $Record
    Save-LocalRuntimeState -State $state
}

function Get-RunningServiceRecord {
    param(
        [string]$Name,
        [int]$Port
    )
    if ($null -ne $previousState) {
        $property = $previousState.services.PSObject.Properties[$Name]
        if ($null -ne $property) {
            $record = $property.Value
            $launcherActive = Test-LocalRuntimeProcessIdentity -Identity $record.launcher
            $listenerActive = Test-LocalRuntimeProcessIdentity -Identity $record.listener
            if ($record.managed -and ($launcherActive -or $listenerActive)) {
                return $record
            }
        }
    }
    return New-UnmanagedLocalRuntimeServiceRecord -Port $Port
}

Write-Host "==> MySQL"
$mysqlService = Get-Service -Name "MySQL" -ErrorAction SilentlyContinue
if ($null -ne $mysqlService -and $mysqlService.Status -ne "Running") {
    Start-Service -Name "MySQL"
}
Wait-LocalRuntimeTcp -Address "127.0.0.1" -Port $MySqlPort -TimeoutSeconds 30 -Name "MySQL"
Write-Host "MySQL is listening on 127.0.0.1:$MySqlPort"

Write-Host "==> Redis-compatible local service"
if (Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $RedisPort) {
    Write-Host "Redis is already listening on 127.0.0.1:$RedisPort"
    Save-ServiceRecord -Name "redis" -Record (Get-RunningServiceRecord -Name "redis" -Port $RedisPort)
} else {
    if ([string]::IsNullOrWhiteSpace($MemuraiExecutable)) {
        $MemuraiExecutable = [Environment]::GetEnvironmentVariable("MONKEYSHOP_MEMURAI_EXE")
    }
    if ([string]::IsNullOrWhiteSpace($MemuraiExecutable)) {
        $candidate = Get-ChildItem -LiteralPath $Script:LocalRuntimeRoot -Directory -Filter "memurai-*" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName "tools\memurai.exe" } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
        $MemuraiExecutable = $candidate
    }
    Assert-LocalRuntime (-not [string]::IsNullOrWhiteSpace($MemuraiExecutable)) "Memurai executable was not found"
    Assert-LocalRuntime (Test-Path -LiteralPath $MemuraiExecutable) "Memurai executable was not found: $MemuraiExecutable"
    $memuraiLog = Join-Path $logsPath "memurai.log"
    $memuraiArguments = @(
        "--bind", "127.0.0.1",
        "--port", [string]$RedisPort,
        "--protected-mode", "yes",
        "--appendonly", "yes",
        "--appendfsync", "everysec",
        "--dir", $redisDataPath,
        "--logfile", $memuraiLog
    )
    $memurai = Start-Process `
        -FilePath $MemuraiExecutable `
        -ArgumentList $memuraiArguments `
        -WorkingDirectory (Split-Path -Parent $MemuraiExecutable) `
        -WindowStyle Hidden `
        -PassThru
    Wait-LocalRuntimeTcp -Address "127.0.0.1" -Port $RedisPort -TimeoutSeconds 30 -Name "Redis"
    Save-ServiceRecord -Name "redis" -Record (
        New-LocalRuntimeServiceRecord -Launcher $memurai -Port $RedisPort -StandardOutput $memuraiLog
    )
}

Write-Host "==> Spring Boot backend"
$backendHealthUrl = "http://127.0.0.1:$BackendPort/actuator/health"
if (Test-LocalRuntimeHttp -Uri $backendHealthUrl) {
    Write-Host "Backend is already healthy at $backendHealthUrl"
    Save-ServiceRecord -Name "backend" -Record (Get-RunningServiceRecord -Name "backend" -Port $BackendPort)
} else {
    Assert-LocalRuntime (-not (Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $BackendPort)) "Port $BackendPort is occupied by an unhealthy process"
    $maven = Get-RequiredLocalRuntimeCommand -Name "mvn.cmd"
    $backendOutput = Join-Path $logsPath "backend.out.log"
    $backendError = Join-Path $logsPath "backend.err.log"
    $backend = Start-Process `
        -FilePath $maven `
        -ArgumentList @(
            "-B",
            "-Dmaven.test.skip=true",
            "-Dspotless.check.skip=true",
            "-Dcheckstyle.skip=true",
            "spring-boot:run"
        ) `
        -WorkingDirectory $Script:LocalRuntimeRepoRoot `
        -RedirectStandardOutput $backendOutput `
        -RedirectStandardError $backendError `
        -WindowStyle Hidden `
        -PassThru
    try {
        Wait-LocalRuntimeHttp -Uri $backendHealthUrl -TimeoutSeconds $StartupTimeoutSeconds -Name "Backend"
    } catch {
        Write-Host "Backend output tail:"
        Get-Content -LiteralPath $backendOutput -Tail 80 -ErrorAction SilentlyContinue
        Get-Content -LiteralPath $backendError -Tail 80 -ErrorAction SilentlyContinue
        throw
    }
    Save-ServiceRecord -Name "backend" -Record (
        New-LocalRuntimeServiceRecord `
            -Launcher $backend `
            -Port $BackendPort `
            -StandardOutput $backendOutput `
            -StandardError $backendError
    )
}

Write-Host "==> Vite frontend"
$frontendUrl = "http://127.0.0.1:$FrontendPort/shop"
if (Test-LocalRuntimeHttp -Uri $frontendUrl) {
    Write-Host "Frontend is already available at $frontendUrl"
    Save-ServiceRecord -Name "frontend" -Record (Get-RunningServiceRecord -Name "frontend" -Port $FrontendPort)
} else {
    Assert-LocalRuntime (-not (Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $FrontendPort)) "Port $FrontendPort is occupied by an unhealthy process"
    Assert-LocalRuntime (Test-Path -LiteralPath (Join-Path $Script:LocalRuntimeRepoRoot "frontend\node_modules")) "Run npm ci in frontend before starting the local stack"
    $npm = Get-RequiredLocalRuntimeCommand -Name "npm.cmd"
    $frontendOutput = Join-Path $logsPath "frontend.out.log"
    $frontendError = Join-Path $logsPath "frontend.err.log"
    $frontend = Start-Process `
        -FilePath $npm `
        -ArgumentList @("run", "dev", "--", "--host", "127.0.0.1", "--port", [string]$FrontendPort, "--strictPort") `
        -WorkingDirectory (Join-Path $Script:LocalRuntimeRepoRoot "frontend") `
        -RedirectStandardOutput $frontendOutput `
        -RedirectStandardError $frontendError `
        -WindowStyle Hidden `
        -PassThru
    try {
        Wait-LocalRuntimeHttp -Uri $frontendUrl -TimeoutSeconds 60 -Name "Frontend"
    } catch {
        Write-Host "Frontend output tail:"
        Get-Content -LiteralPath $frontendOutput -Tail 80 -ErrorAction SilentlyContinue
        Get-Content -LiteralPath $frontendError -Tail 80 -ErrorAction SilentlyContinue
        throw
    }
    Save-ServiceRecord -Name "frontend" -Record (
        New-LocalRuntimeServiceRecord `
            -Launcher $frontend `
            -Port $FrontendPort `
            -StandardOutput $frontendOutput `
            -StandardError $frontendError
    )
}

Write-Host ""
Write-Host "MonkeyShop local stack is ready."
Write-Host "Frontend: http://127.0.0.1:$FrontendPort"
Write-Host "Backend:  http://127.0.0.1:$BackendPort"
Write-Host "Logs:     $logsPath"
