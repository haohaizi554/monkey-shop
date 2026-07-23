$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Script:LocalRuntimeRepoRoot = Split-Path -Parent $PSScriptRoot
$Script:LocalRuntimeRoot = Join-Path $env:LOCALAPPDATA "MonkeyShop"
$Script:LocalRuntimeStatePath = Join-Path $Script:LocalRuntimeRoot "local-runtime-state.json"

function Assert-LocalRuntime {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Add-LocalRuntimeNoProxy {
    param([string[]]$Hosts = @("127.0.0.1", "localhost", "::1"))

    $current = [Environment]::GetEnvironmentVariable(
        "NO_PROXY",
        [EnvironmentVariableTarget]::Process
    )
    $entries = @(
        $current -split "," |
            ForEach-Object { $_.Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    foreach ($hostName in $Hosts) {
        if (-not [string]::IsNullOrWhiteSpace($hostName) -and $entries -notcontains $hostName) {
            $entries += $hostName
        }
    }
    [Environment]::SetEnvironmentVariable(
        "NO_PROXY",
        ($entries -join ","),
        [EnvironmentVariableTarget]::Process
    )
}

function Stop-LocalRuntimeProcessTree {
    param(
        [int]$ProcessId,
        [string]$Name = "process",
        [int]$TimeoutSeconds = 10,
        [int]$TaskkillTimeoutSeconds = 90
    )
    if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
        return
    }

    $taskkill = Start-Process `
        -FilePath "taskkill.exe" `
        -ArgumentList @("/PID", [string]$ProcessId, "/T", "/F") `
        -WindowStyle Hidden `
        -PassThru
    if (-not $taskkill.WaitForExit($TaskkillTimeoutSeconds * 1000)) {
        try {
            $taskkill.Kill()
        } catch {
        }
        throw "Timed out while terminating $Name process tree $ProcessId"
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            return
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Failed to stop $Name process tree $ProcessId"
}

function Resolve-LocalRuntimePath {
    param(
        [string]$Path,
        [string]$DefaultRelativePath
    )
    $candidate = $Path
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = Join-Path $Script:LocalRuntimeRepoRoot $DefaultRelativePath
    } elseif (-not [IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $Script:LocalRuntimeRepoRoot $candidate
    }
    return [IO.Path]::GetFullPath($candidate)
}

function Import-LocalRuntimeEnvironment {
    param(
        [string]$Path,
        [switch]$Required
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        if ($Required) {
            throw "Environment file was not found: $Path"
        }
        return
    }

    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            continue
        }
        if ($line.StartsWith("export ")) {
            $line = $line.Substring(7).Trim()
        }
        $separator = $line.IndexOf("=")
        Assert-LocalRuntime ($separator -gt 0) "Invalid environment assignment in ${Path}: $rawLine"
        $name = $line.Substring(0, $separator).Trim()
        Assert-LocalRuntime ($name -match "^[A-Za-z_][A-Za-z0-9_]*$") "Invalid environment name: $name"
        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2) {
            $doubleQuoted = $value.StartsWith('"') -and $value.EndsWith('"')
            $singleQuoted = $value.StartsWith("'") -and $value.EndsWith("'")
            if ($doubleQuoted -or $singleQuoted) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        Set-Item -LiteralPath "Env:$name" -Value $value
    }
}

function Get-RequiredLocalRuntimeCommand {
    param([string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    Assert-LocalRuntime ($null -ne $command) "$Name was not found on PATH"
    return $command.Source
}

function Test-LocalRuntimeTcp {
    param(
        [string]$Address,
        [int]$Port,
        [int]$TimeoutMilliseconds = 750
    )
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.ConnectAsync($Address, $Port)
        if (-not $pending.Wait($TimeoutMilliseconds)) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-LocalRuntimeTcp {
    param(
        [string]$Address,
        [int]$Port,
        [int]$TimeoutSeconds,
        [string]$Name
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-LocalRuntimeTcp -Address $Address -Port $Port) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Name did not listen on ${Address}:$Port within $TimeoutSeconds seconds"
}

function Test-LocalRuntimeHttp {
    param(
        [string]$Uri,
        [int]$TimeoutSeconds = 3
    )
    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec $TimeoutSeconds
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 400
    } catch {
        return $false
    }
}

function Wait-LocalRuntimeHttp {
    param(
        [string]$Uri,
        [int]$TimeoutSeconds,
        [string]$Name
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-LocalRuntimeHttp -Uri $Uri) {
            return
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Name did not become ready at $Uri within $TimeoutSeconds seconds"
}

function Get-LocalRuntimeListenerProcessId {
    param([int]$Port)
    $output = & netstat.exe -ano -p tcp 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "netstat.exe failed while resolving listener port $Port"
    }
    $escapedPort = [regex]::Escape([string]$Port)
    $pattern = "^\s*TCP\s+\S+:${escapedPort}\s+\S+\s+LISTENING\s+(\d+)\s*$"
    foreach ($line in $output) {
        $match = [regex]::Match([string]$line, $pattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($match.Success) {
            return [int]$match.Groups[1].Value
        }
    }
    return $null
}

function Get-LocalRuntimeListenerAddresses {
    param([int]$Port)
    $output = & netstat.exe -ano -p tcp 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "netstat.exe failed while resolving listener addresses for port $Port"
    }
    $escapedPort = [regex]::Escape([string]$Port)
    $pattern = "^\s*TCP\s+(\S+):${escapedPort}\s+\S+\s+LISTENING\s+\d+\s*$"
    $addresses = @()
    foreach ($line in $output) {
        $match = [regex]::Match([string]$line, $pattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($match.Success -and $addresses -notcontains $match.Groups[1].Value) {
            $addresses += $match.Groups[1].Value
        }
    }
    return $addresses
}

function Assert-LocalRuntimeLoopbackListener {
    param(
        [string]$Name,
        [int]$Port,
        [switch]$AllowAbsent
    )
    $addresses = @(Get-LocalRuntimeListenerAddresses -Port $Port)
    if ($addresses.Count -eq 0 -and $AllowAbsent) {
        return
    }
    Assert-LocalRuntime ($addresses.Count -gt 0) "$Name has no TCP listener on port $Port"
    $unsafeAddresses = @($addresses | Where-Object { $_ -notin @("127.0.0.1", "[::1]", "::1") })
    Assert-LocalRuntime `
        ($unsafeAddresses.Count -eq 0) `
        "$Name must listen only on loopback; port $Port also listens on $($unsafeAddresses -join ', ')"
}

function Get-LocalRuntimeProcessIdentity {
    param([int]$ProcessId)
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return $null
    }
    return [ordered]@{
        pid = $process.Id
        startedAtUtc = $process.StartTime.ToUniversalTime().ToString("O")
        executable = $process.Path
    }
}

function New-LocalRuntimeServiceRecord {
    param(
        [Diagnostics.Process]$Launcher,
        [int]$Port,
        [string]$StandardOutput = "",
        [string]$StandardError = ""
    )
    $listenerProcessId = Get-LocalRuntimeListenerProcessId -Port $Port
    return [ordered]@{
        managed = $true
        launcher = Get-LocalRuntimeProcessIdentity -ProcessId $Launcher.Id
        listener = if ($null -eq $listenerProcessId) {
            $null
        } else {
            Get-LocalRuntimeProcessIdentity -ProcessId $listenerProcessId
        }
        standardOutput = $StandardOutput
        standardError = $StandardError
    }
}

function New-UnmanagedLocalRuntimeServiceRecord {
    param([int]$Port)
    $listenerProcessId = Get-LocalRuntimeListenerProcessId -Port $Port
    return [ordered]@{
        managed = $false
        launcher = $null
        listener = if ($null -eq $listenerProcessId) {
            $null
        } else {
            Get-LocalRuntimeProcessIdentity -ProcessId $listenerProcessId
        }
        standardOutput = ""
        standardError = ""
    }
}

function Read-LocalRuntimeState {
    if (-not (Test-Path -LiteralPath $Script:LocalRuntimeStatePath)) {
        return $null
    }
    return Get-Content -LiteralPath $Script:LocalRuntimeStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Save-LocalRuntimeState {
    param([object]$State)
    New-Item -ItemType Directory -Path $Script:LocalRuntimeRoot -Force | Out-Null
    $State.updatedAtUtc = [DateTime]::UtcNow.ToString("O")
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Script:LocalRuntimeStatePath -Encoding UTF8
}

function Test-LocalRuntimeProcessIdentity {
    param([object]$Identity)
    if ($null -eq $Identity -or $null -eq $Identity.pid -or $null -eq $Identity.startedAtUtc) {
        return $false
    }
    $process = Get-Process -Id ([int]$Identity.pid) -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return $false
    }
    $expected = [DateTime]::Parse([string]$Identity.startedAtUtc).ToUniversalTime()
    $actual = $process.StartTime.ToUniversalTime()
    return [Math]::Abs(($actual - $expected).TotalSeconds) -le 2
}
