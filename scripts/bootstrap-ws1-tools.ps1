param(
    [string]$ToolRoot = (Join-Path $env:USERPROFILE ".cache\codex-tools\ws1-security"),
    [string]$GitleaksVersion = "",
    [string]$TrivyVersion = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-GitHubRelease {
    param(
        [string]$Repository,
        [string]$Version
    )

    $headers = @{
        "Accept" = "application/vnd.github+json"
        "User-Agent" = "MonkeyShop-WS1-Tool-Bootstrap"
    }

    if ($Version) {
        $tag = if ($Version.StartsWith("v")) { $Version } else { "v$Version" }
        return Invoke-RestMethod `
            -Headers $headers `
            -Uri "https://api.github.com/repos/$Repository/releases/tags/$tag"
    }

    Invoke-RestMethod `
        -Headers $headers `
        -Uri "https://api.github.com/repos/$Repository/releases/latest"
}

function Select-ReleaseAsset {
    param(
        [object]$Release,
        [string]$ToolName,
        [string]$AssetPattern
    )

    $assets = @($Release.assets) | Where-Object { $_.name -like $AssetPattern } | Sort-Object name
    if ($assets.Count -eq 0) {
        $available = (@($Release.assets) | Select-Object -ExpandProperty name) -join ", "
        throw "No $ToolName release asset matched '$AssetPattern'. Available assets: $available"
    }

    $assets[0]
}

function Install-ZipAssetTool {
    param(
        [string]$ToolName,
        [string]$Repository,
        [string]$Version,
        [string]$AssetPattern,
        [string]$ExecutableName,
        [string]$DestinationDirectory
    )

    $destinationExe = Join-Path $DestinationDirectory "$ExecutableName.exe"
    if ((Test-Path -LiteralPath $destinationExe) -and (-not $Force)) {
        Write-Host "Using cached $ToolName at $destinationExe"
        return $destinationExe
    }

    New-Item -ItemType Directory -Force -Path $DestinationDirectory | Out-Null
    $release = Get-GitHubRelease -Repository $Repository -Version $Version
    $asset = Select-ReleaseAsset -Release $release -ToolName $ToolName -AssetPattern $AssetPattern

    $tempRoot = [System.IO.Path]::GetTempPath()
    $tempDir = Join-Path $tempRoot ("monkeyshop-ws1-tools-" + [guid]::NewGuid().ToString("N"))
    $zipPath = Join-Path $tempDir $asset.name
    try {
        New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
        Write-Host "Downloading $ToolName $($release.tag_name) from $($asset.browser_download_url)"
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zipPath -UseBasicParsing
        Expand-Archive -LiteralPath $zipPath -DestinationPath $tempDir -Force

        $executable = Get-ChildItem -LiteralPath $tempDir -Recurse -File -Filter "$ExecutableName.exe" |
                Select-Object -First 1
        if (-not $executable) {
            throw "Downloaded $ToolName archive did not contain $ExecutableName.exe"
        }

        Copy-Item -LiteralPath $executable.FullName -Destination $destinationExe -Force
        Write-Host "Installed $ToolName to $destinationExe"
        return $destinationExe
    } finally {
        if ((Test-Path -LiteralPath $tempDir) -and ($tempDir.StartsWith($tempRoot))) {
            Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Add-ToolDirectoryToPath {
    param([string]$ToolDirectory)

    $resolved = (Resolve-Path -LiteralPath $ToolDirectory).Path
    $pathEntries = $env:PATH -split [System.IO.Path]::PathSeparator
    if ($pathEntries -notcontains $resolved) {
        $env:PATH = $resolved + [System.IO.Path]::PathSeparator + $env:PATH
    }

    if ($env:GITHUB_PATH) {
        Add-Content -LiteralPath $env:GITHUB_PATH -Value $resolved
    }
}

$gitleaksDir = Join-Path $ToolRoot "gitleaks"
$trivyDir = Join-Path $ToolRoot "trivy"

$gitleaksExe = Install-ZipAssetTool `
    -ToolName "gitleaks" `
    -Repository "gitleaks/gitleaks" `
    -Version $GitleaksVersion `
    -AssetPattern "gitleaks_*_windows_x64.zip" `
    -ExecutableName "gitleaks" `
    -DestinationDirectory $gitleaksDir

$trivyExe = Install-ZipAssetTool `
    -ToolName "trivy" `
    -Repository "aquasecurity/trivy" `
    -Version $TrivyVersion `
    -AssetPattern "trivy_*_windows-64bit.zip" `
    -ExecutableName "trivy" `
    -DestinationDirectory $trivyDir

Add-ToolDirectoryToPath -ToolDirectory $gitleaksDir
Add-ToolDirectoryToPath -ToolDirectory $trivyDir

Write-Host "==> gitleaks"
gitleaks version

Write-Host "==> trivy"
trivy --version

Write-Host "WS1 scanner tools are ready under $ToolRoot"
