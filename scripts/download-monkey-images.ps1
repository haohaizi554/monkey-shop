# Downloads openly licensed monkey images from Openverse and preserves attribution metadata.
param(
  [string]$OutDir,
  [string]$Proxy = $env:HTTPS_PROXY
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutDir)) {
  $OutDir = Join-Path $PSScriptRoot '..\uploads\images\monkeys'
}
$OutDir = [IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$headers = @{
  'User-Agent' = 'MonkeyShop-AssetFetcher/1.0 (+https://github.com/haohaizi554/monkey-shop)'
  Accept = 'application/json'
}
$downloadHeaders = @{ 'User-Agent' = $headers['User-Agent'] }
$requestOptions = @{}
if (-not [string]::IsNullOrWhiteSpace($Proxy)) {
  $requestOptions.Proxy = $Proxy
}

$map = [ordered]@{
  '蜘蛛猴' = @('spider monkey', 'Ateles monkey')
  '卷尾猴' = @('capuchin monkey', 'Cebus capuchin')
  '松鼠猴' = @('squirrel monkey', 'Saimiri')
  '猕猴' = @('rhesus macaque', 'macaque monkey')
  '狒狒' = @('baboon', 'Papio baboon')
  '狨猴' = @('marmoset', 'Callithrix marmoset')
  '长尾猴' = @('vervet monkey', 'Cercopithecus guenon')
  '疣猴' = @('colobus monkey', 'colobus guereza')
  '叶猴' = @('langur monkey', 'Trachypithecus langur')
  '山魈' = @('mandrill', 'Mandrillus sphinx')
  '狐猴' = @('ring-tailed lemur', 'lemur Madagascar')
  '懒猴' = @('slow loris', 'Nycticebus loris')
  '指猴' = @('aye-aye', 'Daubentonia aye-aye')
  '夜猴' = @('night monkey', 'owl monkey Aotus')
  '绒猴' = @('emperor tamarin', 'tamarin monkey')
  '僧面猴' = @('saki monkey', 'Pithecia saki')
  '秃猴' = @('uakari', 'bald uakari monkey')
  '红吼猴' = @('red howler monkey', 'howler monkey')
  '白面猴' = @('white-faced saki monkey', 'white faced capuchin')
  '黑叶猴' = @('Francois langur', 'black langur')
  '金丝猴' = @('golden snub-nosed monkey', 'Rhinopithecus')
  '戴胜猴' = @('barbary macaque', 'macaque')
  '树熊猴' = @('potto primate', 'Perodicticus potto')
  '婴猴' = @('bushbaby galago', 'galago senegalensis')
}

function Search-Openverse([string]$Term, [int]$RetryCount) {
  $query = [uri]::EscapeDataString($Term)
  $url = "https://api.openverse.org/v1/images/?q=$query&page_size=5&mature=false"
  for ($attempt = 0; $attempt -lt $RetryCount; $attempt++) {
    try {
      $response = Invoke-RestMethod -Uri $url -Headers $headers -TimeoutSec 30 @requestOptions
      return $response.results
    }
    catch {
      $statusCode = $_.Exception.Response.StatusCode.value__
      if ($statusCode -eq 429) {
        Start-Sleep -Seconds (8 + $attempt * 6)
      }
      else {
        Start-Sleep -Seconds 2
      }
    }
  }
  return $null
}

function Resolve-ImageExtension([string]$ContentType) {
  $mediaType = ($ContentType -split ';')[0].Trim().ToLowerInvariant()
  switch ($mediaType) {
    'image/jpeg' { return '.jpg' }
    'image/jpg' { return '.jpg' }
    'image/pjpeg' { return '.jpg' }
    default { throw "Unsupported image content type: $mediaType" }
  }
}

function Test-ImageSignature([string]$Path, [string]$Extension) {
  $signature = New-Object byte[] 12
  $stream = [IO.File]::OpenRead($Path)
  try {
    $bytesRead = $stream.Read($signature, 0, $signature.Length)
  }
  finally {
    $stream.Dispose()
  }

  switch ($Extension) {
    '.jpg' {
      return $bytesRead -ge 3 -and $signature[0] -eq 0xFF -and $signature[1] -eq 0xD8 -and $signature[2] -eq 0xFF
    }
  }
  return $false
}

function Download-Image([object]$Image, [string]$Breed) {
  $temporaryPath = Join-Path $OutDir ('.' + [guid]::NewGuid().ToString('N') + '.download')
  try {
    $response = Invoke-WebRequest `
      -Uri $Image.url `
      -OutFile $temporaryPath `
      -PassThru `
      -UseBasicParsing `
      -Headers $downloadHeaders `
      -TimeoutSec 60 `
      @requestOptions
    $contentType = [string]$response.Headers['Content-Type']
    if (-not $contentType.StartsWith('image/', [StringComparison]::OrdinalIgnoreCase)) {
      throw "Unexpected content type: $contentType"
    }

    $bytes = (Get-Item -LiteralPath $temporaryPath).Length
    if ($bytes -le 3000) {
      throw "Downloaded file is too small: $bytes bytes"
    }

    $extension = Resolve-ImageExtension $contentType
    if (-not (Test-ImageSignature $temporaryPath $extension)) {
      throw "Downloaded file signature does not match $extension"
    }
    foreach ($oldExtension in @('.jpg', '.jpeg', '.png', '.webp')) {
      Remove-Item -LiteralPath (Join-Path $OutDir ($Breed + $oldExtension)) -Force -ErrorAction SilentlyContinue
    }
    $destination = Join-Path $OutDir ($Breed + $extension)
    Move-Item -LiteralPath $temporaryPath -Destination $destination -Force
    return [PSCustomObject]@{
      Path = $destination
      Bytes = $bytes
      ContentType = $contentType
      Sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    }
  }
  catch {
    Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    return $null
  }
}

$results = @()
$attributions = @()
foreach ($breed in $map.Keys) {
  $downloaded = $false
  $lastTerm = ''
  foreach ($term in $map[$breed]) {
    $lastTerm = $term
    $images = Search-Openverse $term 4
    if (-not $images) {
      continue
    }

    $image = $images |
      Where-Object { $_.url -and $_.license -and $_.license_url -and $_.foreign_landing_url } |
      Select-Object -First 1
    if (-not $image) {
      continue
    }

    $file = Download-Image $image $breed
    if (-not $file) {
      continue
    }

    $attributions += [PSCustomObject]@{
      Breed = $breed
      SearchTerm = $term
      FileName = [IO.Path]::GetFileName($file.Path)
      Bytes = $file.Bytes
      ContentType = $file.ContentType
      Sha256 = $file.Sha256
      Title = $image.title
      Creator = $image.creator
      CreatorUrl = $image.creator_url
      License = $image.license
      LicenseVersion = $image.license_version
      LicenseUrl = $image.license_url
      ForeignLandingUrl = $image.foreign_landing_url
      Source = $image.source
      SourceUrl = $image.url
      Attribution = $image.attribution
      DownloadedAt = [DateTimeOffset]::UtcNow.ToString('o')
    }
    $results += [PSCustomObject]@{
      Breed = $breed
      Term = $term
      Bytes = $file.Bytes
      OK = $true
      File = [IO.Path]::GetFileName($file.Path)
    }
    $downloaded = $true
    break
  }

  if (-not $downloaded) {
    $results += [PSCustomObject]@{ Breed = $breed; Term = $lastTerm; Bytes = 0; OK = $false; File = '' }
  }
  Start-Sleep -Milliseconds 1500
}

$manifestPath = Join-Path $OutDir 'attribution.json'
$temporaryManifestPath = "$manifestPath.tmp"
[ordered]@{
  SchemaVersion = 1
  GeneratedAt = [DateTimeOffset]::UtcNow.ToString('o')
  Source = 'Openverse API'
  Items = $attributions
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temporaryManifestPath -Encoding UTF8
Move-Item -LiteralPath $temporaryManifestPath -Destination $manifestPath -Force

$results | Format-Table -AutoSize
"OK: $(($results | Where-Object { $_.OK }).Count)/$($map.Count)"
"Attribution manifest: $manifestPath"
