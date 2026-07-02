param(
    [string]$JacocoReport = "target/site/jacoco/jacoco.xml",
    [string]$PitReport = "target/pit-reports/mutations.xml",
    [string]$SpotBugsReport = "target/spotbugsXml.xml",
    [double]$MinimumJacocoLineCoverage = 0.80,
    [double]$MinimumPitMutationCoverage = 0.85,
    [double]$MinimumPitLineCoverage = 0.80,
    [switch]$RequireDependencyCheckReport,
    [string]$DependencyCheckReport = "target/dependency-check-report.json"
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

function Format-Ratio {
    param([double]$Value)
    return ("{0:P2}" -f $Value)
}

function Get-OptionalProperty {
    param(
        [object]$InputObject,
        [string]$Name
    )
    if ($null -eq $InputObject) {
        return $null
    }
    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

Write-Host "==> JaCoCo report gate"
Assert-True (Test-Path -LiteralPath $JacocoReport) "JaCoCo XML report was not found: $JacocoReport"
[xml]$jacoco = Get-Content -LiteralPath $JacocoReport
$lineCounter = $jacoco.SelectSingleNode("/report/counter[@type='LINE']")
Assert-True ($null -ne $lineCounter) "JaCoCo report does not contain a LINE counter"
$lineMissed = [int]$lineCounter.missed
$lineCovered = [int]$lineCounter.covered
$lineTotal = $lineCovered + $lineMissed
Assert-True ($lineTotal -gt 0) "JaCoCo LINE counter has no measurable lines"
$jacocoLineCoverage = $lineCovered / $lineTotal
Assert-True ($jacocoLineCoverage -ge $MinimumJacocoLineCoverage) (
    "JaCoCo line coverage {0} is below required {1}" -f (Format-Ratio $jacocoLineCoverage), (Format-Ratio $MinimumJacocoLineCoverage))
Write-Host ("JaCoCo line coverage: {0} ({1}/{2})" -f (Format-Ratio $jacocoLineCoverage), $lineCovered, $lineTotal)

Write-Host "==> PITest report gate"
Assert-True (Test-Path -LiteralPath $PitReport) "PITest XML report was not found: $PitReport"
[xml]$pit = Get-Content -LiteralPath $PitReport
$mutations = @($pit.SelectNodes("/mutations/mutation"))
Assert-True ($mutations.Count -gt 0) "PITest report does not contain mutations"
$detected = @($mutations | Where-Object { $_.detected -eq "true" }).Count
$coveredMutations = @($mutations | Where-Object { $_.status -ne "NO_COVERAGE" }).Count
$pitMutationCoverage = $detected / $mutations.Count
$pitLineCoverage = $coveredMutations / $mutations.Count
Assert-True ($pitMutationCoverage -ge $MinimumPitMutationCoverage) (
    "PITest mutation coverage {0} is below required {1}" -f (Format-Ratio $pitMutationCoverage), (Format-Ratio $MinimumPitMutationCoverage))
Assert-True ($pitLineCoverage -ge $MinimumPitLineCoverage) (
    "PITest line coverage {0} is below required {1}" -f (Format-Ratio $pitLineCoverage), (Format-Ratio $MinimumPitLineCoverage))
Write-Host ("PITest mutation coverage: {0} ({1}/{2})" -f (Format-Ratio $pitMutationCoverage), $detected, $mutations.Count)
Write-Host ("PITest line coverage: {0} ({1}/{2})" -f (Format-Ratio $pitLineCoverage), $coveredMutations, $mutations.Count)

Write-Host "==> SpotBugs report gate"
Assert-True (Test-Path -LiteralPath $SpotBugsReport) "SpotBugs XML report was not found: $SpotBugsReport"
[xml]$spotbugs = Get-Content -LiteralPath $SpotBugsReport
$bugInstances = @($spotbugs.SelectNodes("/BugCollection/BugInstance"))
Assert-True ($bugInstances.Count -eq 0) "SpotBugs report contains $($bugInstances.Count) BugInstance entries"
Write-Host "SpotBugs BugInstance count: 0"

if ($RequireDependencyCheckReport) {
    Write-Host "==> OWASP dependency-check report gate"
    Assert-True (Test-Path -LiteralPath $DependencyCheckReport) "dependency-check JSON report was not found: $DependencyCheckReport"
    $dependencyCheck = Get-Content -LiteralPath $DependencyCheckReport -Raw | ConvertFrom-Json
    $vulnerabilities = @()
    foreach ($dependency in @($dependencyCheck.dependencies)) {
        $dependencyVulnerabilities = Get-OptionalProperty $dependency "vulnerabilities"
        foreach ($vulnerability in @($dependencyVulnerabilities)) {
            if ($null -ne $vulnerability) {
                $vulnerabilities += $vulnerability
            }
        }
    }
    $blocking = @($vulnerabilities | Where-Object {
            $cvssScore = 0.0
            $cvssv3 = Get-OptionalProperty $_ "cvssv3"
            $cvssv2 = Get-OptionalProperty $_ "cvssv2"
            $cvssv3BaseScore = Get-OptionalProperty $cvssv3 "baseScore"
            $cvssv2Score = Get-OptionalProperty $cvssv2 "score"
            if ($null -ne $cvssv3BaseScore) {
                $cvssScore = [double]$cvssv3BaseScore
            }
            if ($null -ne $cvssv2Score) {
                $cvssScore = [Math]::Max($cvssScore, [double]$cvssv2Score)
            }
            (Get-OptionalProperty $_ "severity") -in @("HIGH", "CRITICAL") -or $cvssScore -ge 7.0
        })
    Assert-True ($blocking.Count -eq 0) "dependency-check report contains $($blocking.Count) HIGH/CRITICAL or CVSS >= 7 findings"
    Write-Host "dependency-check blocking vulnerability count: 0"
}

Write-Host "Quality report gate completed successfully."
