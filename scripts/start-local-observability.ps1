param(
    [switch]$SkipBootstrap,
    [int]$StartupTimeoutSeconds = 120
)

. (Join-Path $PSScriptRoot "local-observability-common.ps1")
Add-LocalRuntimeNoProxy

$versionsPath = Join-Path $Script:LocalObservabilityToolsRoot "versions.json"
if (-not $SkipBootstrap -and -not (Test-Path -LiteralPath $versionsPath)) {
    & (Join-Path $PSScriptRoot "bootstrap-local-observability.ps1")
}
Assert-LocalRuntime (Test-Path -LiteralPath $versionsPath) "Run scripts/bootstrap-local-observability.ps1 first"

$repoConfigRoot = Join-Path $Script:LocalRuntimeRepoRoot "ops\local\observability"
$dataRoot = Join-Path $Script:LocalObservabilityRoot "data"
$logRoot = Join-Path $Script:LocalObservabilityRoot "logs"
$effectiveRoot = Join-Path $Script:LocalObservabilityRoot "config"
$grafanaProvisioning = Join-Path $effectiveRoot "grafana\provisioning"
$grafanaDashboardRoot = Join-Path $repoConfigRoot "grafana\dashboards"
New-Item -ItemType Directory -Path $dataRoot, $logRoot, $effectiveRoot, $grafanaProvisioning -Force | Out-Null
foreach ($relative in @("loki\chunks", "loki\rules", "loki\compactor", "tempo\wal", "tempo\blocks", "tempo\generator\wal", "prometheus", "grafana")) {
    New-Item -ItemType Directory -Path (Join-Path $dataRoot $relative) -Force | Out-Null
}
foreach ($relative in @("alerting", "plugins", "access-control")) {
    New-Item -ItemType Directory -Path (Join-Path $grafanaProvisioning $relative) -Force | Out-Null
}

Copy-Item -Path (Join-Path $repoConfigRoot "grafana\provisioning\*") -Destination $grafanaProvisioning -Recurse -Force
$dashboardProviderPath = Join-Path $grafanaProvisioning "dashboards\monkeyshop.yml"
$dashboardPath = ConvertTo-LocalObservabilityPath -Path $grafanaDashboardRoot
$dashboardProvider = (Get-Content -LiteralPath $dashboardProviderPath -Raw -Encoding UTF8).Replace("__MONKEYSHOP_DASHBOARD_PATH__", $dashboardPath)
$dashboardProvider | Set-Content -LiteralPath $dashboardProviderPath -Encoding UTF8

$env:MONKEYSHOP_RUNTIME_ROOT = ConvertTo-LocalObservabilityPath -Path $Script:LocalObservabilityRoot
$env:MONKEYSHOP_LOG_ROOT = ConvertTo-LocalObservabilityPath -Path (Join-Path $Script:LocalRuntimeRoot "logs")
$env:GF_PATHS_DATA = Join-Path $dataRoot "grafana"
$env:GF_PATHS_LOGS = Join-Path $logRoot "grafana"
$env:GF_PATHS_PROVISIONING = $grafanaProvisioning
$env:GF_SERVER_HTTP_ADDR = "127.0.0.1"
$env:GF_AUTH_ANONYMOUS_ENABLED = "true"
$env:GF_AUTH_ANONYMOUS_ORG_ROLE = "Viewer"
$env:GF_USERS_ALLOW_SIGN_UP = "false"
$env:GF_SECURITY_ADMIN_USER = "admin"
$env:GF_ANALYTICS_CHECK_FOR_UPDATES = "false"
$env:GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES = "false"
$env:GF_PLUGINS_PREINSTALL_DISABLED = "true"
$env:GF_PLUGINS_PLUGIN_ADMIN_ENABLED = "false"
$env:GF_NEWS_NEWS_FEED_ENABLED = "false"

$secretRoot = Join-Path $Script:LocalRuntimeRoot "secrets"
$grafanaPasswordPath = Join-Path $secretRoot "grafana-admin-password"
New-Item -ItemType Directory -Path $secretRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $grafanaPasswordPath)) {
    $bytes = [byte[]]::new(24)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    } finally {
        $random.Dispose()
    }
    ([Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')) |
        Set-Content -LiteralPath $grafanaPasswordPath -Encoding ASCII -NoNewline
}
$env:GF_SECURITY_ADMIN_PASSWORD = Get-Content -LiteralPath $grafanaPasswordPath -Raw -Encoding ASCII

$previousState = Read-LocalObservabilityState
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
    Save-LocalObservabilityState -State $state
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

function Start-ObservedService {
    param(
        [string]$Name,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [int]$Port,
        [string]$ReadyUri
    )
    Write-Host "==> $Name"
    if (Test-LocalRuntimeHttp -Uri $ReadyUri) {
        Write-Host "$Name is already ready at $ReadyUri"
        Save-ServiceRecord -Name $Name -Record (Get-ExistingServiceRecord -Name $Name -Port $Port)
        return
    }
    Assert-LocalRuntime (-not (Test-LocalRuntimeTcp -Address "127.0.0.1" -Port $Port)) "Port $Port is occupied by an unhealthy process"
    $stdout = Join-Path $logRoot "$Name.out.log"
    $stderr = Join-Path $logRoot "$Name.err.log"
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
        $ready = $false
        do {
            if (Test-LocalRuntimeHttp -Uri $ReadyUri) {
                $ready = $true
                break
            }
            if ($process.HasExited) {
                throw "$Name exited before becoming ready at $ReadyUri"
            }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $ready) {
            throw "$Name did not become ready at $ReadyUri within $StartupTimeoutSeconds seconds"
        }
    } catch {
        Get-Content -LiteralPath $stdout -Tail 80 -ErrorAction SilentlyContinue
        Get-Content -LiteralPath $stderr -Tail 80 -ErrorAction SilentlyContinue
        Stop-LocalRuntimeProcessTree -ProcessId $process.Id -Name $Name
        throw
    }
    Save-ServiceRecord -Name $Name -Record (New-LocalRuntimeServiceRecord -Launcher $process -Port $Port -StandardOutput $stdout -StandardError $stderr)
}

$tempo = Get-LocalObservabilityExecutable -Tool "tempo" -Executable "tempo.exe"
$loki = Get-LocalObservabilityExecutable -Tool "loki" -Executable "loki-windows-amd64.exe"
$collector = Get-LocalObservabilityExecutable -Tool "otel-collector" -Executable "otelcol-contrib.exe"
$prometheus = Get-LocalObservabilityExecutable -Tool "prometheus" -Executable "prometheus.exe"
$grafana = Get-LocalObservabilityExecutable -Tool "grafana" -Executable "grafana.exe"
$grafanaHome = Split-Path -Parent (Split-Path -Parent $grafana)

Start-ObservedService -Name "tempo" -Executable $tempo -Arguments @("-config.file=$(Join-Path $repoConfigRoot 'tempo.yml')", "-config.expand-env=true") -WorkingDirectory (Split-Path -Parent $tempo) -Port 3200 -ReadyUri "http://127.0.0.1:3200/ready"
Start-ObservedService -Name "loki" -Executable $loki -Arguments @("-config.file=$(Join-Path $repoConfigRoot 'loki.yml')", "-config.expand-env=true") -WorkingDirectory (Split-Path -Parent $loki) -Port 3100 -ReadyUri "http://127.0.0.1:3100/ready"
Start-ObservedService -Name "otelCollector" -Executable $collector -Arguments @("--config=$(Join-Path $repoConfigRoot 'otel-collector.yml')") -WorkingDirectory (Split-Path -Parent $collector) -Port 13133 -ReadyUri "http://127.0.0.1:13133/"
Start-ObservedService -Name "prometheus" -Executable $prometheus -Arguments @("--config.file=$(Join-Path $repoConfigRoot 'prometheus.yml')", "--storage.tsdb.path=$(Join-Path $dataRoot 'prometheus')", "--storage.tsdb.retention.time=30d", "--web.listen-address=127.0.0.1:9090", "--web.enable-remote-write-receiver") -WorkingDirectory (Split-Path -Parent $prometheus) -Port 9090 -ReadyUri "http://127.0.0.1:9090/-/ready"
Start-ObservedService -Name "grafana" -Executable $grafana -Arguments @("server", "--homepath=$grafanaHome") -WorkingDirectory $grafanaHome -Port 3000 -ReadyUri "http://127.0.0.1:3000/api/health"

& (Join-Path $PSScriptRoot "status-local-observability.ps1")
