param(
    [string]$ProxyUri = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { "http://127.0.0.1:7890" }),
    [switch]$Force
)

. (Join-Path $PSScriptRoot "local-support-common.ps1")

$vaultVersion = "2.0.3"
$seaweedVersion = "4.29"
$clamAvVersion = "1.5.3"
$downloadRoot = Join-Path $Script:LocalSupportRoot "downloads"
$stagingRoot = Join-Path $Script:LocalSupportRoot "staging"
New-Item -ItemType Directory -Path $Script:LocalSupportToolsRoot, $downloadRoot, $stagingRoot -Force | Out-Null

function Assert-SafeLocalSupportChildPath {
    param(
        [string]$Path,
        [string]$Parent
    )
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $resolvedParent = [IO.Path]::GetFullPath($Parent).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    Assert-LocalRuntime ($resolvedPath.StartsWith($resolvedParent, [StringComparison]::OrdinalIgnoreCase)) "Refusing to modify path outside $resolvedParent"
}

function Invoke-PinnedDownload {
    param(
        [string]$Uri,
        [string]$ArtifactName,
        [string]$ExpectedSha256
    )
    $archivePath = Join-Path $downloadRoot $ArtifactName
    $needsDownload = $Force -or -not (Test-Path -LiteralPath $archivePath)
    if (-not $needsDownload) {
        $currentHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
        $needsDownload = $currentHash -ne $ExpectedSha256
    }
    if ($needsDownload) {
        $partialPath = "$archivePath.partial"
        Remove-Item -LiteralPath $partialPath -Force -ErrorAction SilentlyContinue
        $curl = Get-RequiredLocalRuntimeCommand -Name "curl.exe"
        $arguments = @(
            "-fL",
            "--silent",
            "--show-error",
            "--retry", "5",
            "--retry-all-errors",
            "--connect-timeout", "30",
            "-o", $partialPath
        )
        if (-not [string]::IsNullOrWhiteSpace($ProxyUri)) {
            $arguments += @("--proxy", $ProxyUri)
        }
        $arguments += $Uri
        Write-Host "Downloading $ArtifactName"
        & $curl @arguments
        Assert-LocalRuntime ($LASTEXITCODE -eq 0) "Download failed for $ArtifactName"
        $actualHash = (Get-FileHash -LiteralPath $partialPath -Algorithm SHA256).Hash
        Assert-LocalRuntime ($actualHash -eq $ExpectedSha256) "SHA256 mismatch for $ArtifactName"
        Move-Item -LiteralPath $partialPath -Destination $archivePath -Force
    }
    return $archivePath
}

function Install-LocalSupportArchive {
    param(
        [string]$Name,
        [string]$Version,
        [string]$ArchivePath,
        [string]$Executable
    )
    $target = Join-Path $Script:LocalSupportToolsRoot "$Name-$Version"
    $installed = Get-ChildItem -LiteralPath $target -File -Filter $Executable -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $Force -and $null -ne $installed) {
        return
    }
    $staging = Join-Path $stagingRoot "$Name-$Version"
    Assert-SafeLocalSupportChildPath -Path $target -Parent $Script:LocalSupportToolsRoot
    Assert-SafeLocalSupportChildPath -Path $staging -Parent $stagingRoot
    Remove-Item -LiteralPath $target, $staging -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $target, $staging -Force | Out-Null
    Expand-Archive -LiteralPath $ArchivePath -DestinationPath $staging -Force
    $executableFile = Get-ChildItem -LiteralPath $staging -File -Filter $Executable -Recurse |
        Select-Object -First 1
    Assert-LocalRuntime ($null -ne $executableFile) "$Executable was not found in $ArchivePath"
    Get-ChildItem -LiteralPath $executableFile.Directory.FullName -Force |
        Copy-Item -Destination $target -Recurse -Force
    Remove-Item -LiteralPath $staging -Recurse -Force
}

$artifacts = @(
    [ordered]@{
        Name = "vault"
        Version = $vaultVersion
        Artifact = "vault-${vaultVersion}-windows-amd64.zip"
        Uri = "https://releases.hashicorp.com/vault/${vaultVersion}/vault_${vaultVersion}_windows_amd64.zip"
        Sha256 = "02da9f383256606db9717d29f2d26d0aafd9af951d51263bdee38dd98d38cbaa"
        Executable = "vault.exe"
    },
    [ordered]@{
        Name = "seaweedfs"
        Version = $seaweedVersion
        Artifact = "seaweedfs-${seaweedVersion}-windows-amd64.zip"
        Uri = "https://github.com/seaweedfs/seaweedfs/releases/download/${seaweedVersion}/windows_amd64.zip"
        Sha256 = "a5a343f2e2249b4e709842b846e596330a316e064b15f9d77899581ea545cb9b"
        Executable = "weed.exe"
    },
    [ordered]@{
        Name = "clamav"
        Version = $clamAvVersion
        Artifact = "clamav-${clamAvVersion}.win.x64.zip"
        Uri = "https://github.com/Cisco-Talos/clamav/releases/download/clamav-${clamAvVersion}/clamav-${clamAvVersion}.win.x64.zip"
        Sha256 = "e998b3b98c2812726ca7f4db06bf89c4b52a7eb7160ab93403c3ec790a9be6b6"
        Executable = "clamd.exe"
    }
)

foreach ($artifact in $artifacts) {
    $archive = Invoke-PinnedDownload -Uri $artifact.Uri -ArtifactName $artifact.Artifact -ExpectedSha256 $artifact.Sha256
    Install-LocalSupportArchive -Name $artifact.Name -Version $artifact.Version -ArchivePath $archive -Executable $artifact.Executable
}

$manifest = [ordered]@{
    installedAtUtc = [DateTime]::UtcNow.ToString("O")
    tools = @($artifacts | ForEach-Object {
        [ordered]@{
            name = $_.Name
            version = $_.Version
            artifact = $_.Artifact
            sha256 = $_.Sha256.ToLowerInvariant()
        }
    })
}
[IO.File]::WriteAllText(
    (Join-Path $Script:LocalSupportToolsRoot "versions.json"),
    ($manifest | ConvertTo-Json -Depth 5),
    [Text.UTF8Encoding]::new($false)
)
Write-Host "Local Vault, SeaweedFS, and ClamAV tools are installed under $Script:LocalSupportToolsRoot"
