param(
    [string]$RenderedDir = "target/ws7-devops",
    [string]$PolicyDir = "deploy/kyverno"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure {
    param([string]$Message)
    [void]$script:Failures.Add($Message)
}

function Read-RequiredFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        Add-Failure "Missing required file: $Path"
        return ""
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Assert-Match {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern,
        [string]$Message
    )
    if ($Text -notmatch $Pattern) {
        Add-Failure "${Name}: $Message"
    }
}

function Assert-NotMatch {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern,
        [string]$Message
    )
    if ($Text -match $Pattern) {
        Add-Failure "${Name}: $Message"
    }
}
function Assert-ProdImagesDigestPinned {
    param([string]$Text)

    $imageLines = @($Text -split "\r?\n" | Where-Object { $_ -match "^\s*image:\s+" })
    if ($imageLines.Count -eq 0) {
        Add-Failure "rendered prod: must render at least one container image"
        return
    }
    foreach ($line in $imageLines) {
        if ($line -notmatch "@sha256:[a-f0-9]{64}") {
            Add-Failure "rendered prod: image line must use an immutable digest: $line"
        }
    }
}

Write-Host "==> Kyverno policy and rendered manifest checks"

$imagePolicy = Read-RequiredFile -Path (Join-Path $PolicyDir "monkeyshop-image-policy.yaml")
$podPolicy = Read-RequiredFile -Path (Join-Path $PolicyDir "monkeyshop-pod-security.yaml")
$dev = Read-RequiredFile -Path (Join-Path $RenderedDir "monkeyshop-dev.yaml")
$staging = Read-RequiredFile -Path (Join-Path $RenderedDir "monkeyshop-staging.yaml")
$prod = Read-RequiredFile -Path (Join-Path $RenderedDir "monkeyshop-prod.yaml")

Assert-Match -Name "monkeyshop-image-policy" -Text $imagePolicy -Pattern "validationFailureAction:\s+Enforce" -Message "must enforce image policy failures"
Assert-Match -Name "monkeyshop-image-policy" -Text $imagePolicy -Pattern "name:\s+disallow-latest-tag" -Message "must reject latest tags"
Assert-Match -Name "monkeyshop-image-policy" -Text $imagePolicy -Pattern "name:\s+require-prod-digest" -Message "must require digest-pinned prod images"
Assert-Match -Name "monkeyshop-image-policy" -Text $imagePolicy -Pattern "verifyImages:" -Message "must verify signed images"
Assert-Match -Name "monkeyshop-image-policy" -Text $imagePolicy -Pattern "issuer:\s+https://token\.actions\.githubusercontent\.com" -Message "must use GitHub OIDC keyless signing issuer"
Assert-Match -Name "monkeyshop-image-policy" -Text $imagePolicy -Pattern "rekor:\s*\r?\n\s+url:\s+https://rekor\.sigstore\.dev" -Message "must verify transparency log inclusion"

Assert-Match -Name "monkeyshop-pod-security" -Text $podPolicy -Pattern "validationFailureAction:\s+Enforce" -Message "must enforce pod security failures"
Assert-Match -Name "monkeyshop-pod-security" -Text $podPolicy -Pattern "runAsNonRoot:\s+true" -Message "must require non-root execution"
Assert-Match -Name "monkeyshop-pod-security" -Text $podPolicy -Pattern "readOnlyRootFilesystem:\s+true" -Message "must require read-only root filesystems"
Assert-Match -Name "monkeyshop-pod-security" -Text $podPolicy -Pattern "allowPrivilegeEscalation:\s+false" -Message "must forbid privilege escalation"
Assert-Match -Name "monkeyshop-pod-security" -Text $podPolicy -Pattern "drop:\s*\r?\n\s+-\s+ALL" -Message "must require dropped Linux capabilities"
Assert-Match -Name "monkeyshop-pod-security" -Text $podPolicy -Pattern "require-resource-requests-and-limits" -Message "must require resource requests and limits"

foreach ($entry in @(
        @{ Name = "rendered dev"; Text = $dev },
        @{ Name = "rendered staging"; Text = $staging },
        @{ Name = "rendered prod"; Text = $prod }
    )) {
    $name = $entry.Name
    $manifest = $entry.Text
    Assert-NotMatch -Name $name -Text $manifest -Pattern "image:\s+['""]?[^'""\r\n]+:latest['""]?" -Message "must not render latest image tags"
    Assert-Match -Name $name -Text $manifest -Pattern "runAsNonRoot:\s+true" -Message "must render non-root security contexts"
    Assert-Match -Name $name -Text $manifest -Pattern "readOnlyRootFilesystem:\s+true" -Message "must render read-only root filesystems"
    Assert-Match -Name $name -Text $manifest -Pattern "allowPrivilegeEscalation:\s+false" -Message "must render privilege escalation disabled"
    Assert-Match -Name $name -Text $manifest -Pattern "drop:\s*\r?\n\s+-\s+ALL" -Message "must render dropped Linux capabilities"
    Assert-Match -Name $name -Text $manifest -Pattern "requests:\s*\r?\n\s+cpu:\s+[^`r`n]+\s*\r?\n\s+memory:\s+[^`r`n]+" -Message "must render CPU and memory requests"
    Assert-Match -Name $name -Text $manifest -Pattern "limits:\s*\r?\n\s+cpu:\s+[^`r`n]+\s*\r?\n\s+memory:\s+[^`r`n]+" -Message "must render CPU and memory limits"
}

Assert-Match -Name "rendered prod" -Text $prod -Pattern "image:\s+['""]?harbor\.example\.com/monkeyshop/monkeyshop@sha256:[a-f0-9]{64}['""]?" -Message "must render the prod app image by immutable digest"
Assert-Match -Name "rendered prod" -Text $prod -Pattern "image:\s+['""]?busybox@sha256:[a-f0-9]{64}['""]?" -Message "must render prod init containers by immutable digest"
Assert-ProdImagesDigestPinned -Text $prod

if ($Failures.Count -gt 0) {
    Write-Host "Kyverno supply-chain gate failed:" -ForegroundColor Red
    foreach ($failure in $Failures) {
        Write-Host " - $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host "Kyverno supply-chain gate completed successfully."
