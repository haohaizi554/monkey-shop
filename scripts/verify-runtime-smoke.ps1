param(
    [string]$BaseUrl = "http://localhost:8888",
    [string]$SpaPath = "/shop",
    [int]$TimeoutSeconds = 10,
    [switch]$RequireHttps
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Join-SmokeUrl {
    param([string]$Path)
    $base = $BaseUrl.TrimEnd("/")
    if ($Path.StartsWith("/")) {
        return "$base$Path"
    }
    return "$base/$Path"
}

function Invoke-SmokeRequest {
    param(
        [string]$Path,
        [string]$Method = "GET",
        [hashtable]$Headers = @{}
    )

    Invoke-WebRequest `
        -Uri (Join-SmokeUrl $Path) `
        -Method $Method `
        -Headers $Headers `
        -UseBasicParsing `
        -TimeoutSec $TimeoutSeconds `
        -MaximumRedirection 0
}

function Get-SmokeHeader {
    param(
        [object]$Response,
        [string]$Name
    )
    $value = $Response.Headers[$Name]
    if ($null -eq $value) {
        return ""
    }
    if ($value -is [array]) {
        return ($value -join ", ")
    }
    return [string]$value
}


function Get-SmokeContent {
    param([object]$Response)
    if ($Response.Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Response.Content)
    }
    return [string]$Response.Content
}
function Assert-StatusUp {
    param(
        [string]$Path,
        [string]$Name
    )
    $response = Invoke-SmokeRequest -Path $Path
    Assert-True ($response.StatusCode -eq 200) "$Name must return HTTP 200"
    $json = Get-SmokeContent -Response $response | ConvertFrom-Json
    $status = $json.PSObject.Properties["status"].Value
    Assert-True ($status -eq "UP") "$Name must report UP"
}

function Assert-HeaderContains {
    param(
        [object]$Response,
        [string]$Name,
        [string]$Expected
    )
    $value = Get-SmokeHeader -Response $Response -Name $Name
    Assert-True ($value.Contains($Expected)) "$Name must contain '$Expected' but was '$value'"
}

function Assert-HeaderMissingText {
    param(
        [object]$Response,
        [string]$Name,
        [string]$Forbidden
    )
    $value = Get-SmokeHeader -Response $Response -Name $Name
    Assert-True (-not $value.Contains($Forbidden)) "$Name must not contain '$Forbidden'"
}

$baseUri = [Uri]$BaseUrl
if ($RequireHttps) {
    Assert-True ($baseUri.Scheme -eq "https") "-RequireHttps expects an https BaseUrl"
}

Write-Host "==> Runtime health checks"
Assert-StatusUp -Path "/actuator/health" -Name "health"
Assert-StatusUp -Path "/actuator/health/liveness" -Name "liveness"
Assert-StatusUp -Path "/actuator/health/readiness" -Name "readiness"

Write-Host "==> SPA route, trace, and security headers"
$traceId = [guid]::NewGuid().ToString()
$spaResponse = Invoke-SmokeRequest -Path $SpaPath -Headers @{ "X-Trace-Id" = $traceId }
Assert-True ($spaResponse.StatusCode -eq 200) "$SpaPath must return HTTP 200 without redirect"
Assert-HeaderContains -Response $spaResponse -Name "X-Trace-Id" -Expected $traceId
Assert-HeaderContains -Response $spaResponse -Name "X-Content-Type-Options" -Expected "nosniff"
Assert-HeaderContains -Response $spaResponse -Name "X-Frame-Options" -Expected "DENY"
Assert-HeaderContains -Response $spaResponse -Name "Referrer-Policy" -Expected "strict-origin-when-cross-origin"
Assert-HeaderContains -Response $spaResponse -Name "Permissions-Policy" -Expected "camera=(), microphone=(), geolocation=(), payment=()"
Assert-HeaderContains -Response $spaResponse -Name "Cross-Origin-Opener-Policy" -Expected "same-origin"
Assert-HeaderContains -Response $spaResponse -Name "Cross-Origin-Resource-Policy" -Expected "same-origin"
Assert-HeaderContains -Response $spaResponse -Name "X-Permitted-Cross-Domain-Policies" -Expected "none"
Assert-HeaderContains -Response $spaResponse -Name "Content-Security-Policy" -Expected "default-src 'self'"
Assert-HeaderContains -Response $spaResponse -Name "Content-Security-Policy" -Expected "script-src 'self' 'nonce-"
Assert-HeaderContains -Response $spaResponse -Name "Content-Security-Policy" -Expected "style-src 'self' 'nonce-"
Assert-HeaderContains -Response $spaResponse -Name "Content-Security-Policy" -Expected "object-src 'none'"
Assert-HeaderContains -Response $spaResponse -Name "Content-Security-Policy" -Expected "frame-ancestors 'none'"
Assert-HeaderMissingText -Response $spaResponse -Name "Content-Security-Policy" -Forbidden "unsafe-inline"
Assert-HeaderMissingText -Response $spaResponse -Name "Content-Security-Policy" -Forbidden "unpkg.com"
Assert-HeaderMissingText -Response $spaResponse -Name "Content-Security-Policy" -Forbidden "cdn.jsdelivr.net"

if ($RequireHttps) {
    Assert-HeaderContains -Response $spaResponse -Name "Strict-Transport-Security" -Expected "max-age=31536000"
    Assert-HeaderContains -Response $spaResponse -Name "Strict-Transport-Security" -Expected "includeSubDomains"
    Assert-HeaderContains -Response $spaResponse -Name "Strict-Transport-Security" -Expected "preload"
} else {
    Write-Host "==> HSTS strict check skipped; pass -RequireHttps for public TLS endpoints"
}

Write-Host "==> Static frontend asset"
$spaContent = Get-SmokeContent -Response $spaResponse
$assetMatch = [regex]::Match($spaContent, 'src="(/assets/index-[^"" ]+\.js)"')
Assert-True ($assetMatch.Success) "SPA index asset script was not found"
$assetPath = $assetMatch.Groups[1].Value
$assetResponse = Invoke-SmokeRequest -Path $assetPath
Assert-True ($assetResponse.StatusCode -eq 200) "$assetPath must return HTTP 200"
Assert-HeaderContains -Response $assetResponse -Name "Content-Type" -Expected "javascript"

Write-Host "==> Prometheus metrics"
$metricsResponse = Invoke-SmokeRequest -Path "/actuator/prometheus"
Assert-True ($metricsResponse.StatusCode -eq 200) "/actuator/prometheus must return HTTP 200"
$metricsContent = Get-SmokeContent -Response $metricsResponse
Assert-True ($metricsContent.Contains("jvm_memory_used_bytes")) "Prometheus output must include JVM memory metrics"
Assert-True ($metricsContent.Contains("http_server_requests_seconds_count")) "Prometheus output must include HTTP request metrics"

Write-Host "Runtime smoke gate completed successfully for $BaseUrl"
