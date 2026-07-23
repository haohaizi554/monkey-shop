. (Join-Path $PSScriptRoot "local-runtime-common.ps1")

$Script:LocalSupportRoot = Join-Path $Script:LocalRuntimeRoot "support"
$Script:LocalSupportToolsRoot = Join-Path $Script:LocalSupportRoot "tools"
$Script:LocalSupportStatePath = Join-Path $Script:LocalSupportRoot "local-support-state.json"
$Script:LocalSupportSecretsRoot = Join-Path $Script:LocalSupportRoot "secrets"
$Script:LocalSupportEnvironmentPath = Join-Path $Script:LocalSupportSecretsRoot "application.env"

function Read-LocalSupportState {
    if (-not (Test-Path -LiteralPath $Script:LocalSupportStatePath)) {
        return $null
    }
    return Get-Content -LiteralPath $Script:LocalSupportStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Save-LocalSupportState {
    param([object]$State)
    New-Item -ItemType Directory -Path $Script:LocalSupportRoot -Force | Out-Null
    $State.updatedAtUtc = [DateTime]::UtcNow.ToString("O")
    $temporaryPath = "$($Script:LocalSupportStatePath).$PID.tmp"
    [IO.File]::WriteAllText(
        $temporaryPath,
        ($State | ConvertTo-Json -Depth 8),
        [Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $temporaryPath -Destination $Script:LocalSupportStatePath -Force
}

function Protect-LocalSupportSecret {
    param([string]$Path)
    Assert-LocalRuntime (Test-Path -LiteralPath $Path) "Secret file was not found: $Path"
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $null = & icacls.exe $Path /inheritance:r /grant:r "*${sid}:(F)" "*S-1-5-18:(F)" 2>&1
    Assert-LocalRuntime ($LASTEXITCODE -eq 0) "Failed to restrict secret file ACL: $Path"
}

function Get-LocalSupportExecutable {
    param(
        [string]$Tool,
        [string]$Executable
    )
    $toolRoot = Get-ChildItem -LiteralPath $Script:LocalSupportToolsRoot -Directory -Filter "$Tool-*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    Assert-LocalRuntime ($null -ne $toolRoot) "$Tool is not installed; run scripts/bootstrap-local-support.ps1"
    $candidate = Get-ChildItem -LiteralPath $toolRoot.FullName -File -Filter $Executable -Recurse |
        Select-Object -First 1
    Assert-LocalRuntime ($null -ne $candidate) "$Executable was not found under $($toolRoot.FullName)"
    return $candidate.FullName
}

function ConvertTo-LocalSupportPath {
    param([string]$Path)
    return ([IO.Path]::GetFullPath($Path) -replace '\\', '/')
}

function Test-LocalSupportClamAv {
    param(
        [string]$Address = "127.0.0.1",
        [int]$Port = 3310,
        [int]$TimeoutMilliseconds = 2000
    )
    $socket = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $socket.ConnectAsync($Address, $Port)
        if (-not $pending.Wait($TimeoutMilliseconds) -or -not $socket.Connected) {
            return $false
        }
        $socket.ReceiveTimeout = $TimeoutMilliseconds
        $socket.SendTimeout = $TimeoutMilliseconds
        $stream = $socket.GetStream()
        $request = [Text.Encoding]::ASCII.GetBytes("nPING`n")
        $stream.Write($request, 0, $request.Length)
        $stream.Flush()
        $response = [Collections.Generic.List[byte]]::new()
        while ($response.Count -lt 64) {
            $next = $stream.ReadByte()
            if ($next -lt 0 -or $next -eq 0 -or $next -eq 10) {
                break
            }
            $response.Add([byte]$next)
        }
        return [Text.Encoding]::ASCII.GetString($response.ToArray()).Trim() -eq "PONG"
    } catch {
        return $false
    } finally {
        $socket.Dispose()
    }
}

function New-LocalSupportRandomToken {
    param([int]$ByteLength = 32)
    $bytes = [byte[]]::new($ByteLength)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
        $random.Dispose()
    }
}
