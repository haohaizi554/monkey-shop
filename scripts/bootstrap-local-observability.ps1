param(
    [string]$ProxyUri = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { "http://127.0.0.1:7890" }),
    [switch]$Force
)

. (Join-Path $PSScriptRoot "local-observability-common.ps1")

$otelVersion = "0.153.0"
$prometheusVersion = "3.12.0"
$lokiVersion = "3.7.2"
$tempoVersion = "2.10.5"
$grafanaVersion = "13.1.1"
$grafanaBuild = "13.1.1_29761037902"

if (-not [string]::IsNullOrWhiteSpace($ProxyUri)) {
    $env:HTTPS_PROXY = $ProxyUri
    $env:HTTP_PROXY = $ProxyUri
}

$downloadRoot = Join-Path $Script:LocalObservabilityRoot "downloads"
$stagingRoot = Join-Path $Script:LocalObservabilityRoot "staging"
New-Item -ItemType Directory -Path $Script:LocalObservabilityToolsRoot, $downloadRoot, $stagingRoot -Force | Out-Null

function Assert-SafeObservabilityChildPath {
    param(
        [string]$Path,
        [string]$Parent
    )
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $resolvedParent = [IO.Path]::GetFullPath($Parent).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    Assert-LocalRuntime ($resolvedPath.StartsWith($resolvedParent, [StringComparison]::OrdinalIgnoreCase)) "Refusing to modify path outside $resolvedParent"
}

function Invoke-VerifiedDownload {
    param(
        [string]$Uri,
        [string]$ChecksumUri,
        [string]$ArtifactName
    )
    $archivePath = Join-Path $downloadRoot $ArtifactName
    $checksumPath = "$archivePath.sha256"
    Invoke-WebRequest -UseBasicParsing -Uri $ChecksumUri -OutFile $checksumPath -TimeoutSec 120
    $checksumText = Get-Content -LiteralPath $checksumPath -Raw -Encoding UTF8
    $escapedName = [regex]::Escape($ArtifactName)
    $match = [regex]::Match($checksumText, "(?im)^([0-9a-f]{64})\s+\*?(?:.*/)?$escapedName\s*$")
    if (-not $match.Success) {
        $hashes = [regex]::Matches($checksumText, "(?i)\b[0-9a-f]{64}\b")
        Assert-LocalRuntime ($hashes.Count -eq 1) "Could not resolve a unique SHA256 for $ArtifactName"
        $expectedHash = $hashes[0].Value.ToUpperInvariant()
    } else {
        $expectedHash = $match.Groups[1].Value.ToUpperInvariant()
    }

    $needsDownload = $Force -or -not (Test-Path -LiteralPath $archivePath)
    if (-not $needsDownload) {
        $currentHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
        $needsDownload = $currentHash -ne $expectedHash
    }
    if ($needsDownload) {
        $partialPath = "$archivePath.partial"
        Remove-Item -LiteralPath $partialPath -Force -ErrorAction SilentlyContinue
        Write-Host "Downloading $ArtifactName"
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $partialPath -TimeoutSec 900
        $actualHash = (Get-FileHash -LiteralPath $partialPath -Algorithm SHA256).Hash
        Assert-LocalRuntime ($actualHash -eq $expectedHash) "SHA256 mismatch for $ArtifactName"
        Move-Item -LiteralPath $partialPath -Destination $archivePath -Force
    }
    return $archivePath
}

function Install-ArchiveTool {
    param(
        [string]$Name,
        [string]$Version,
        [string]$ArchivePath,
        [string]$Executable,
        [switch]$GrafanaLayout
    )
    $target = Join-Path $Script:LocalObservabilityToolsRoot "$Name-$Version"
    $installedExecutable = Get-ChildItem -LiteralPath $target -File -Filter $Executable -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $Force -and $null -ne $installedExecutable) {
        return
    }
    $staging = Join-Path $stagingRoot "$Name-$Version"
    Assert-SafeObservabilityChildPath -Path $target -Parent $Script:LocalObservabilityToolsRoot
    Assert-SafeObservabilityChildPath -Path $staging -Parent $stagingRoot
    Remove-Item -LiteralPath $target, $staging -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $target, $staging -Force | Out-Null

    if ($ArchivePath.EndsWith(".zip", [StringComparison]::OrdinalIgnoreCase)) {
        Expand-Archive -LiteralPath $ArchivePath -DestinationPath $staging -Force
    } else {
        & tar.exe -xzf $ArchivePath -C $staging
        Assert-LocalRuntime ($LASTEXITCODE -eq 0) "Failed to extract $ArchivePath"
    }

    $executableFile = Get-ChildItem -LiteralPath $staging -File -Filter $Executable -Recurse | Select-Object -First 1
    Assert-LocalRuntime ($null -ne $executableFile) "$Executable was not found in $ArchivePath"
    $sourceRoot = $executableFile.Directory.FullName
    if ($GrafanaLayout) {
        $defaults = Get-ChildItem -LiteralPath $staging -File -Filter "defaults.ini" -Recurse | Select-Object -First 1
        Assert-LocalRuntime ($null -ne $defaults) "Grafana defaults.ini was not found in $ArchivePath"
        $sourceRoot = Split-Path -Parent $defaults.Directory.FullName
    }
    Get-ChildItem -LiteralPath $sourceRoot -Force | Copy-Item -Destination $target -Recurse -Force
    Remove-Item -LiteralPath $staging -Recurse -Force
}

$artifacts = @(
    [ordered]@{
        Name = "otel-collector"
        Version = $otelVersion
        Artifact = "otelcol-contrib_${otelVersion}_windows_amd64.tar.gz"
        Uri = "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v${otelVersion}/otelcol-contrib_${otelVersion}_windows_amd64.tar.gz"
        ChecksumUri = "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v${otelVersion}/opentelemetry-collector-releases_otelcol-contrib_windows_checksums.txt"
        Executable = "otelcol-contrib.exe"
        GrafanaLayout = $false
    },
    [ordered]@{
        Name = "prometheus"
        Version = $prometheusVersion
        Artifact = "prometheus-${prometheusVersion}.windows-amd64.zip"
        Uri = "https://github.com/prometheus/prometheus/releases/download/v${prometheusVersion}/prometheus-${prometheusVersion}.windows-amd64.zip"
        ChecksumUri = "https://github.com/prometheus/prometheus/releases/download/v${prometheusVersion}/sha256sums.txt"
        Executable = "prometheus.exe"
        GrafanaLayout = $false
    },
    [ordered]@{
        Name = "loki"
        Version = $lokiVersion
        Artifact = "loki-windows-amd64.exe.zip"
        Uri = "https://github.com/grafana/loki/releases/download/v${lokiVersion}/loki-windows-amd64.exe.zip"
        ChecksumUri = "https://github.com/grafana/loki/releases/download/v${lokiVersion}/SHA256SUMS"
        Executable = "loki-windows-amd64.exe"
        GrafanaLayout = $false
    },
    [ordered]@{
        Name = "tempo"
        Version = $tempoVersion
        Artifact = "tempo_${tempoVersion}_windows_amd64.tar.gz"
        Uri = "https://github.com/grafana/tempo/releases/download/v${tempoVersion}/tempo_${tempoVersion}_windows_amd64.tar.gz"
        ChecksumUri = "https://github.com/grafana/tempo/releases/download/v${tempoVersion}/SHA256SUMS"
        Executable = "tempo.exe"
        GrafanaLayout = $false
    },
    [ordered]@{
        Name = "grafana"
        Version = $grafanaVersion
        Artifact = "grafana_${grafanaBuild}_windows_amd64.tar.gz"
        Uri = "https://dl.grafana.com/grafana/release/${grafanaVersion}/grafana_${grafanaBuild}_windows_amd64.tar.gz"
        ChecksumUri = "https://dl.grafana.com/grafana/release/${grafanaVersion}/grafana_${grafanaBuild}_windows_amd64.tar.gz.sha256"
        Executable = "grafana.exe"
        GrafanaLayout = $true
    }
)

foreach ($artifact in $artifacts) {
    $archive = Invoke-VerifiedDownload -Uri $artifact.Uri -ChecksumUri $artifact.ChecksumUri -ArtifactName $artifact.Artifact
    Install-ArchiveTool -Name $artifact.Name -Version $artifact.Version -ArchivePath $archive -Executable $artifact.Executable -GrafanaLayout:$artifact.GrafanaLayout
}

$manifest = [ordered]@{
    installedAtUtc = [DateTime]::UtcNow.ToString("O")
    tools = @($artifacts | ForEach-Object { [ordered]@{ name = $_.Name; version = $_.Version; artifact = $_.Artifact } })
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Script:LocalObservabilityToolsRoot "versions.json") -Encoding UTF8
Write-Host "Local observability tools are installed under $Script:LocalObservabilityToolsRoot"
