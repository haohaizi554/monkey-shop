param(
    [string]$BaseUrl = $env:MONKEYSHOP_PUBLIC_URL,
    [string]$Path = "/shop",
    [int]$TimeoutSeconds = 15,
    [int]$MinCertificateDays = 30,
    [switch]$SkipTlsProtocolProbe
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

function Join-PublicUrl {
    param([string]$RelativePath)
    $base = $BaseUrl.TrimEnd("/")
    if ($RelativePath.StartsWith("/")) {
        return "$base$RelativePath"
    }
    return "$base/$RelativePath"
}

function Get-HeaderValue {
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

function Assert-HeaderContains {
    param(
        [object]$Response,
        [string]$Name,
        [string]$Expected
    )
    $value = Get-HeaderValue -Response $Response -Name $Name
    Assert-True ($value.Contains($Expected)) "$Name must contain '$Expected' but was '$value'"
}

function Assert-HeaderEquals {
    param(
        [object]$Response,
        [string]$Name,
        [string]$Expected
    )
    $value = Get-HeaderValue -Response $Response -Name $Name
    Assert-True ($value -eq $Expected) "$Name must be '$Expected' but was '$value'"
}

function Assert-HeaderMissingText {
    param(
        [object]$Response,
        [string]$Name,
        [string]$Forbidden
    )
    $value = Get-HeaderValue -Response $Response -Name $Name
    Assert-True (-not $value.Contains($Forbidden)) "$Name must not contain '$Forbidden'"
}

function Test-Tls13 {
    param([Uri]$Uri)

    $tcp = [System.Net.Sockets.TcpClient]::new()
    try {
        $connection = $tcp.ConnectAsync($Uri.Host, 443)
        Assert-True ($connection.Wait([TimeSpan]::FromSeconds($TimeoutSeconds))) "TLS socket connection timed out"
        $stream = [System.Net.Security.SslStream]::new($tcp.GetStream(), $false, ({ $true } -as [System.Net.Security.RemoteCertificateValidationCallback]))
        try {
            $stream.AuthenticateAsClient($Uri.Host)
            $protocol = $stream.SslProtocol.ToString()
            Assert-True ($protocol -eq "Tls13") "TLS protocol must negotiate TLS 1.3 but was $protocol"
            $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($stream.RemoteCertificate)
            $daysRemaining = ($certificate.NotAfter.ToUniversalTime() - [DateTime]::UtcNow).TotalDays
            Assert-True ($daysRemaining -ge $MinCertificateDays) "TLS certificate must remain valid for at least $MinCertificateDays days"
        } finally {
            $stream.Dispose()
        }
    } finally {
        $tcp.Dispose()
    }
}

if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    throw "BaseUrl or MONKEYSHOP_PUBLIC_URL is required for public edge verification"
}

$baseUri = [Uri]$BaseUrl
Assert-True ($baseUri.Scheme -eq "https") "Public edge verification requires an https BaseUrl"
Assert-True ($baseUri.Host -notin @("localhost", "127.0.0.1", "::1")) "Public edge verification must target the public edge host"

if (-not $SkipTlsProtocolProbe) {
    Write-Host "==> TLS 1.3 and certificate validity"
    Test-Tls13 -Uri $baseUri
}

Write-Host "==> Public security headers"
$response = Invoke-WebRequest `
    -Uri (Join-PublicUrl -RelativePath $Path) `
    -Method GET `
    -UseBasicParsing `
    -TimeoutSec $TimeoutSeconds `
    -MaximumRedirection 0

Assert-True ($response.StatusCode -eq 200) "$Path must return HTTP 200 without redirect"
Assert-HeaderContains -Response $response -Name "Strict-Transport-Security" -Expected "max-age=31536000"
Assert-HeaderContains -Response $response -Name "Strict-Transport-Security" -Expected "includeSubDomains"
Assert-HeaderContains -Response $response -Name "Strict-Transport-Security" -Expected "preload"
Assert-HeaderEquals -Response $response -Name "X-Frame-Options" -Expected "DENY"
Assert-HeaderEquals -Response $response -Name "X-Content-Type-Options" -Expected "nosniff"
Assert-HeaderEquals -Response $response -Name "Referrer-Policy" -Expected "strict-origin-when-cross-origin"
Assert-HeaderEquals -Response $response -Name "Permissions-Policy" -Expected "camera=(), microphone=(), geolocation=(), payment=()"
Assert-HeaderEquals -Response $response -Name "Cross-Origin-Opener-Policy" -Expected "same-origin"
Assert-HeaderEquals -Response $response -Name "Cross-Origin-Resource-Policy" -Expected "same-origin"
Assert-HeaderEquals -Response $response -Name "X-Permitted-Cross-Domain-Policies" -Expected "none"

Assert-HeaderContains -Response $response -Name "Content-Security-Policy" -Expected "default-src 'self'"
Assert-HeaderContains -Response $response -Name "Content-Security-Policy" -Expected "script-src 'self' 'nonce-"
Assert-HeaderContains -Response $response -Name "Content-Security-Policy" -Expected "style-src 'self' 'nonce-"
Assert-HeaderContains -Response $response -Name "Content-Security-Policy" -Expected "object-src 'none'"
Assert-HeaderContains -Response $response -Name "Content-Security-Policy" -Expected "base-uri 'self'"
Assert-HeaderContains -Response $response -Name "Content-Security-Policy" -Expected "form-action 'self'"
Assert-HeaderContains -Response $response -Name "Content-Security-Policy" -Expected "frame-ancestors 'none'"
Assert-HeaderContains -Response $response -Name "Content-Security-Policy" -Expected "upgrade-insecure-requests"
Assert-HeaderMissingText -Response $response -Name "Content-Security-Policy" -Forbidden "unsafe-inline"
Assert-HeaderMissingText -Response $response -Name "Content-Security-Policy" -Forbidden "unpkg.com"
Assert-HeaderMissingText -Response $response -Name "Content-Security-Policy" -Forbidden "cdn.jsdelivr.net"

Write-Host "Public edge security gate completed successfully for $BaseUrl"
