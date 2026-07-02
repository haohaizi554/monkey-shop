param(
    [string]$BaseUrl = "http://localhost:8888",
    [int]$TimeoutSeconds = 10,
    [string]$ProbeIp = "",
    [switch]$RunRateLimitProbe,
    [int]$RateLimitAttempts = 35
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.Net.Http

$script:IssuedProbeIps = @{}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function New-ReservedTestIp {
    param([string[]]$Exclude = @())

    $ranges = @("192.0.2", "198.51.100", "203.0.113")
    for ($attempt = 1; $attempt -le 512; $attempt++) {
        $range = $ranges | Get-Random
        $candidate = "$range.$(Get-Random -Minimum 10 -Maximum 240)"
        if (($Exclude -notcontains $candidate) -and (-not $script:IssuedProbeIps.ContainsKey($candidate))) {
            $script:IssuedProbeIps[$candidate] = $true
            return $candidate
        }
    }
    throw "Could not allocate a unique reserved test IP address"
}

function Invoke-AnonymousOrdersProbe {
    param([int]$MaxAttempts = 8)

    $lastResponse = $null
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        $ordersIp = New-ReservedTestIp
        $response = Invoke-ApiSmokeRequest `
            -Path "/api/orders/my" `
            -Headers @{ "X-Forwarded-For" = $ordersIp; "X-Trace-Id" = ([guid]::NewGuid().ToString()) }
        if ($response.StatusCode -eq 401) {
            return $response
        }
        if ($response.StatusCode -eq 403) {
            $lastResponse = $response
            Write-Host "    Synthetic IP $ordersIp is already blocked; retrying anonymous auth probe"
            continue
        }
        return $response
    }

    throw "anonymous orders probe could not find an unblocked synthetic IP after $MaxAttempts attempts; last status $($lastResponse.StatusCode)"
}

function Join-ApiSmokeUrl {
    param([string]$Path)
    $base = $BaseUrl.TrimEnd("/")
    if ($Path.StartsWith("/")) {
        return "$base$Path"
    }
    return "$base/$Path"
}

function Invoke-ApiSmokeRequest {
    param(
        [string]$Path,
        [string]$Method = "GET",
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [string]$ContentType = "application/json"
    )

    $methodObject = [System.Net.Http.HttpMethod]::new($Method)
    $request = [System.Net.Http.HttpRequestMessage]::new($methodObject, [Uri](Join-ApiSmokeUrl $Path))
    foreach ($entry in $Headers.GetEnumerator()) {
        [void]$request.Headers.TryAddWithoutValidation([string]$entry.Key, [string]$entry.Value)
    }
    if (-not [string]::IsNullOrEmpty($Body)) {
        $request.Content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, $ContentType)
    }

    $response = $null
    try {
        $response = $script:HttpClient.SendAsync($request).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $headers = @{}
        foreach ($header in $response.Headers) {
            $headers[$header.Key] = ($header.Value -join ", ")
        }
        foreach ($header in $response.Content.Headers) {
            $headers[$header.Key] = ($header.Value -join ", ")
        }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Headers = $headers
            Content = $content
        }
    } finally {
        if ($null -ne $response) {
            $response.Dispose()
        }
        $request.Dispose()
    }
}

function Get-ApiSmokeHeader {
    param(
        [object]$Response,
        [string]$Name
    )
    foreach ($key in $Response.Headers.Keys) {
        if ($key.Equals($Name, [System.StringComparison]::OrdinalIgnoreCase)) {
            return [string]$Response.Headers[$key]
        }
    }
    return ""
}

function Read-ApiSmokeJson {
    param([object]$Response)
    Assert-True (-not [string]::IsNullOrWhiteSpace($Response.Content)) "response body must not be empty"
    return $Response.Content | ConvertFrom-Json
}

function Assert-Status {
    param(
        [object]$Response,
        [int]$Expected,
        [string]$Name
    )
    Assert-True ($Response.StatusCode -eq $Expected) "$Name must return HTTP $Expected but returned $($Response.StatusCode)"
}

function Assert-Problem {
    param(
        [object]$Response,
        [int]$ExpectedStatus,
        [string]$ExpectedCode,
        [string]$Name
    )
    Assert-Status -Response $Response -Expected $ExpectedStatus -Name $Name
    Assert-True ((Get-ApiSmokeHeader -Response $Response -Name "Content-Type").Contains("application/problem+json")) "$Name must return ProblemDetail JSON"
    $problem = Read-ApiSmokeJson -Response $Response
    Assert-True ($problem.status -eq $ExpectedStatus) "$Name problem status must be $ExpectedStatus"
    Assert-True ($problem.code -eq $ExpectedCode) "$Name problem code must be $ExpectedCode"
    Assert-True (-not [string]::IsNullOrWhiteSpace($problem.traceId)) "$Name problem must include traceId"
}

if ([string]::IsNullOrWhiteSpace($ProbeIp)) {
    $ProbeIp = New-ReservedTestIp
} else {
    $script:IssuedProbeIps[$ProbeIp] = $true
}

$script:HttpClient = [System.Net.Http.HttpClient]::new()
$script:HttpClient.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)

try {
    Write-Host "==> Anonymous product API"
    $traceId = [guid]::NewGuid().ToString()
    $productResponse = Invoke-ApiSmokeRequest `
        -Path "/api/monkeys?page=0&size=1" `
        -Headers @{ "X-Forwarded-For" = $ProbeIp; "X-Trace-Id" = $traceId }
    Assert-Status -Response $productResponse -Expected 200 -Name "product listing"
    Assert-True ((Get-ApiSmokeHeader -Response $productResponse -Name "Content-Type").Contains("application/json")) "product listing must return JSON"
    Assert-True ((Get-ApiSmokeHeader -Response $productResponse -Name "X-Trace-Id").Contains($traceId)) "product listing must echo X-Trace-Id"
    $productJson = Read-ApiSmokeJson -Response $productResponse
    Assert-True ($productJson.code -eq "OK") "product listing must use Result envelope"
    Assert-True ($productJson.traceId -eq $traceId) "product listing Result traceId must match X-Trace-Id"
    Assert-True ($null -ne $productJson.data.PSObject.Properties["content"]) "product listing page must include content"

    Write-Host "==> Captcha configuration"
    $captchaResponse = Invoke-ApiSmokeRequest `
        -Path "/api/auth/captcha/config" `
        -Headers @{ "X-Forwarded-For" = (New-ReservedTestIp) }
    Assert-Status -Response $captchaResponse -Expected 200 -Name "captcha config"
    $captchaJson = Read-ApiSmokeJson -Response $captchaResponse
    Assert-True ($captchaJson.code -eq "OK") "captcha config must use Result envelope"
    Assert-True (-not [string]::IsNullOrWhiteSpace($captchaJson.data.provider)) "captcha config must publish provider"
    Assert-True ($null -ne $captchaJson.data.PSObject.Properties["siteKey"]) "captcha config must include siteKey field"

    Write-Host "==> Protected API rejects anonymous access"
    $ordersResponse = Invoke-AnonymousOrdersProbe
    Assert-Problem -Response $ordersResponse -ExpectedStatus 401 -ExpectedCode "UNAUTHORIZED" -Name "anonymous orders"

    Write-Host "==> Honeypot blocks only the synthetic probe IP"
    $honeypotIp = New-ReservedTestIp
    $honeypotResponse = Invoke-ApiSmokeRequest `
        -Path "/api/.env" `
        -Headers @{ "X-Forwarded-For" = $honeypotIp }
    Assert-Problem -Response $honeypotResponse -ExpectedStatus 403 -ExpectedCode "FORBIDDEN" -Name "honeypot"
    $blockedResponse = Invoke-ApiSmokeRequest `
        -Path "/api/monkeys?page=0&size=1" `
        -Headers @{ "X-Forwarded-For" = $honeypotIp }
    Assert-Problem -Response $blockedResponse -ExpectedStatus 403 -ExpectedCode "FORBIDDEN" -Name "honeypot block"
    $controlResponse = Invoke-ApiSmokeRequest `
        -Path "/api/monkeys?page=0&size=1" `
        -Headers @{ "X-Forwarded-For" = (New-ReservedTestIp -Exclude @($honeypotIp)) }
    Assert-Status -Response $controlResponse -Expected 200 -Name "honeypot control"

    if ($RunRateLimitProbe) {
        Write-Host "==> Optional search rate-limit probe"
        $rateLimitIp = New-ReservedTestIp
        $limitedResponse = $null
        for ($attempt = 1; $attempt -le $RateLimitAttempts; $attempt++) {
            $response = Invoke-ApiSmokeRequest `
                -Path "/api/monkeys?page=0&size=1" `
                -Headers @{ "X-Forwarded-For" = $rateLimitIp }
            if ($response.StatusCode -eq 429) {
                $limitedResponse = $response
                break
            }
            Assert-Status -Response $response -Expected 200 -Name "rate-limit warmup attempt $attempt"
        }
        Assert-True ($null -ne $limitedResponse) "search API must return 429 within $RateLimitAttempts attempts"
        Assert-Problem -Response $limitedResponse -ExpectedStatus 429 -ExpectedCode "RATE_LIMIT" -Name "search rate limit"
        Assert-True (-not [string]::IsNullOrWhiteSpace((Get-ApiSmokeHeader -Response $limitedResponse -Name "Retry-After"))) "429 response must include Retry-After"
    } else {
        Write-Host "==> Rate-limit pressure probe skipped; pass -RunRateLimitProbe to exercise 429 behavior"
    }

    Write-Host "Runtime API security smoke gate completed successfully for $BaseUrl"
} finally {
    $script:HttpClient.Dispose()
}
