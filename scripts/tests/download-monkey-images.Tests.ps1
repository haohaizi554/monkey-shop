$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$testOutput = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'target\download-monkey-images-test'))
$repositoryPrefix = $repositoryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $testOutput.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Test output escaped the repository root.'
}
$global:monkeyShopMockContentType = 'image/gif'
$global:monkeyShopMockBytes = [byte[]](0..255) * 16

function Invoke-RestMethod {
  param([string]$Uri, [hashtable]$Headers, [int]$TimeoutSec)

  return [PSCustomObject]@{
    results = @([PSCustomObject]@{
      url = 'https://example.test/image.gif'
      filetype = 'gif'
      license = 'by'
      license_url = 'https://creativecommons.org/licenses/by/4.0/'
      foreign_landing_url = 'https://example.test/work'
      creator = 'Test creator'
      creator_url = 'https://example.test/creator'
      title = 'Test image'
      source = 'mock'
      attribution = 'Test attribution'
    })
  }
}

function Invoke-WebRequest {
  param(
    [string]$Uri,
    [string]$OutFile,
    [switch]$PassThru,
    [switch]$UseBasicParsing,
    [hashtable]$Headers,
    [int]$TimeoutSec
  )

  [IO.File]::WriteAllBytes($OutFile, $global:monkeyShopMockBytes)
  return [PSCustomObject]@{ Headers = @{ 'Content-Type' = $global:monkeyShopMockContentType } }
}

function Start-Sleep {
  param([int]$Seconds, [int]$Milliseconds)
}

function Invoke-DownloaderCase {
  if (Test-Path -LiteralPath $testOutput) {
    Remove-Item -LiteralPath $testOutput -Recurse -Force
  }
  & (Join-Path $repositoryRoot 'scripts\download-monkey-images.ps1') -OutDir $testOutput -Proxy '' | Out-Null
  return Get-Content -LiteralPath (Join-Path $testOutput 'attribution.json') -Raw | ConvertFrom-Json
}

try {
  $manifest = Invoke-DownloaderCase
  $incorrectJpegFiles = @(Get-ChildItem -LiteralPath $testOutput -Filter '*.jpg')
  if ($incorrectJpegFiles.Count -gt 0) {
    throw "Unsupported GIF content was saved as JPG ($($incorrectJpegFiles.Count) files)."
  }
  if (@($manifest.Items).Count -ne 0) {
    throw 'Unsupported image content was included in the attribution manifest.'
  }

  $global:monkeyShopMockContentType = 'image/jpeg'
  $manifest = Invoke-DownloaderCase
  if (@($manifest.Items).Count -ne 0) {
    throw 'A file with a forged JPEG content type was accepted.'
  }

  $global:monkeyShopMockContentType = 'image/png'
  $global:monkeyShopMockBytes =
    [byte[]](@(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + (New-Object byte[] 4096))
  $manifest = Invoke-DownloaderCase
  if (@($manifest.Items).Count -ne 0 -or @(Get-ChildItem -LiteralPath $testOutput -Filter '*.png').Count -ne 0) {
    throw 'PNG downloads were accepted even though product image URLs require JPG files.'
  }

  $global:monkeyShopMockContentType = 'image/jpeg'
  $global:monkeyShopMockBytes = [byte[]](@(0xFF, 0xD8, 0xFF, 0xE0) + (New-Object byte[] 4096))
  $manifest = Invoke-DownloaderCase
  $jpegFiles = @(Get-ChildItem -LiteralPath $testOutput -Filter '*.jpg')
  if ($jpegFiles.Count -ne 24 -or @($manifest.Items).Count -ne 24) {
    throw 'Valid JPEG downloads were not saved with complete attribution records.'
  }
  foreach ($item in $manifest.Items) {
    if ([string]$item.Sha256 -notmatch '^[0-9a-f]{64}$' -or -not $item.LicenseUrl -or -not $item.ForeignLandingUrl) {
      throw 'Attribution metadata is incomplete.'
    }
  }
}
finally {
  if (Test-Path -LiteralPath $testOutput) {
    Remove-Item -LiteralPath $testOutput -Recurse -Force
  }
  Remove-Variable -Name monkeyShopMockContentType -Scope Global -ErrorAction SilentlyContinue
  Remove-Variable -Name monkeyShopMockBytes -Scope Global -ErrorAction SilentlyContinue
}
