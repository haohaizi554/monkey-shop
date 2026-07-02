param(
    [string]$SonarHostUrl = $(if ($env:SONAR_HOST_URL) { $env:SONAR_HOST_URL } else { "https://sonarcloud.io" }),
    [string]$ProjectKey = $env:SONAR_PROJECT_KEY,
    [string]$Organization = $env:SONAR_ORGANIZATION,
    [string]$Token = $env:SONAR_TOKEN,
    [string]$SonarMavenPluginVersion = "5.7.0.6970",
    [string]$JacocoReport = "target/site/jacoco/jacoco.xml",
    [string]$SpotBugsReport = "target/spotbugsXml.xml",
    [switch]$GenerateReports
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

function Invoke-MavenChecked {
    param(
        [string[]]$Arguments,
        [string]$Name
    )

    Write-Host "==> $Name"
    & mvn @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

Assert-True (-not [string]::IsNullOrWhiteSpace($Token)) "SONAR_TOKEN is required for the blocking SonarQube Quality Gate"
Assert-True (-not [string]::IsNullOrWhiteSpace($ProjectKey)) "SONAR_PROJECT_KEY is required for the blocking SonarQube Quality Gate"
Assert-True (-not [string]::IsNullOrWhiteSpace($SonarHostUrl)) "SONAR_HOST_URL is required for the blocking SonarQube Quality Gate"

if ($SonarHostUrl.Contains("sonarcloud.io")) {
    Assert-True (-not [string]::IsNullOrWhiteSpace($Organization)) "SONAR_ORGANIZATION is required when SONAR_HOST_URL points to SonarCloud"
}

if ($GenerateReports) {
    Invoke-MavenChecked `
        -Name "Generate SonarQube input reports" `
        -Arguments @(
            "--batch-mode",
            "-Ddependency-check.skip=true",
            "test",
            "jacoco:report",
            "spotbugs:spotbugs"
        )
}

Assert-True (Test-Path -LiteralPath $JacocoReport) "JaCoCo XML report was not found: $JacocoReport. Run mvn verify or pass -GenerateReports."
Assert-True (Test-Path -LiteralPath $SpotBugsReport) "SpotBugs XML report was not found: $SpotBugsReport. Run mvn verify or pass -GenerateReports."

$sonarArgs = @(
    "--batch-mode",
    "-DskipTests",
    "-Ddependency-check.skip=true",
    "org.sonarsource.scanner.maven:sonar-maven-plugin:$SonarMavenPluginVersion`:sonar",
    "-Dsonar.host.url=$SonarHostUrl",
    "-Dsonar.token=$Token",
    "-Dsonar.projectKey=$ProjectKey",
    "-Dsonar.qualitygate.wait=true",
    "-Dsonar.coverage.jacoco.xmlReportPaths=$JacocoReport",
    "-Dsonar.java.spotbugs.reportPaths=$SpotBugsReport",
    "-Dsonar.sources=src/main/java,src/main/resources,frontend/src",
    "-Dsonar.tests=src/test/java,frontend/tests",
    "-Dsonar.exclusions=frontend/dist/**,frontend/node_modules/**,target/**,uploads/**"
)

if (-not [string]::IsNullOrWhiteSpace($Organization)) {
    $sonarArgs += "-Dsonar.organization=$Organization"
}

Invoke-MavenChecked -Name "Run SonarQube quality gate" -Arguments $sonarArgs

Write-Host "SonarQube Quality Gate completed successfully for project $ProjectKey at $SonarHostUrl"
