. (Join-Path $PSScriptRoot "local-runtime-common.ps1")

$Script:LocalObservabilityRoot = Join-Path $Script:LocalRuntimeRoot "observability"
$Script:LocalObservabilityToolsRoot = Join-Path $Script:LocalRuntimeRoot "tools\observability"
$Script:LocalObservabilityStatePath = Join-Path $Script:LocalObservabilityRoot "state.json"

function Read-LocalObservabilityState {
    if (-not (Test-Path -LiteralPath $Script:LocalObservabilityStatePath)) {
        return $null
    }
    return Get-Content -LiteralPath $Script:LocalObservabilityStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Save-LocalObservabilityState {
    param([object]$State)
    New-Item -ItemType Directory -Path $Script:LocalObservabilityRoot -Force | Out-Null
    $State.updatedAtUtc = [DateTime]::UtcNow.ToString("O")
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Script:LocalObservabilityStatePath -Encoding UTF8
}

function Get-LocalObservabilityExecutable {
    param(
        [string]$Tool,
        [string]$Executable
    )
    $toolRoot = Get-ChildItem -LiteralPath $Script:LocalObservabilityToolsRoot -Directory -Filter "$Tool-*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    Assert-LocalRuntime ($null -ne $toolRoot) "$Tool is not installed; run scripts/bootstrap-local-observability.ps1"
    $candidate = Get-ChildItem -LiteralPath $toolRoot.FullName -File -Filter $Executable -Recurse |
        Select-Object -First 1
    Assert-LocalRuntime ($null -ne $candidate) "$Executable was not found under $($toolRoot.FullName)"
    return $candidate.FullName
}

function ConvertTo-LocalObservabilityPath {
    param([string]$Path)
    return ([IO.Path]::GetFullPath($Path) -replace '\\', '/')
}
