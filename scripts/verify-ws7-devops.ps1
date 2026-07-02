param(
    [string]$ChartDir = "helm/monkeyshop",
    [string]$OutputDir = "target/ws7-devops",
    [string]$HelmPath = "",
    [switch]$RequireHelm,
    [switch]$DownloadHelmIfMissing,
    [string]$HelmVersion = "v3.21.2",
    [string]$ToolDir = "target/tools",
    [string]$HelmWindowsAmd64Sha256 = "5f346e3338617e9fd1b8c216065383061bdb3bde26cb6b3abc8ce0481354a513"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure {
    param([string]$Message)
    [void]$script:Failures.Add($Message)
}

function Assert-File {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        Add-Failure "Missing required file: $Path"
        return $false
    }
    return $true
}

function Read-RequiredFile {
    param([string]$Path)
    if (Assert-File -Path $Path) {
        return Get-Content -LiteralPath $Path -Raw
    }
    return ""
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

function Resolve-OptionalTool {
    param(
        [string]$ExplicitPath,
        [string]$CommandName
    )

    if ($ExplicitPath) {
        if (Test-Path -LiteralPath $ExplicitPath) {
            return (Resolve-Path -LiteralPath $ExplicitPath).Path
        }
        Add-Failure "Configured path for $CommandName does not exist: $ExplicitPath"
        return $null
    }

    $command = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    return $null
}

function Install-Helm {
    param(
        [string]$Version,
        [string]$InstallRoot,
        [string]$WindowsAmd64Sha256
    )

    $isWindows = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Windows)
    $architecture = [System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()
    if ((-not $isWindows) -or ($architecture -ne "X64")) {
        Add-Failure "Automatic Helm download currently supports Windows x64 only. Install Helm yourself or pass -HelmPath."
        return $null
    }

    $normalizedVersion = $Version
    if (-not $normalizedVersion.StartsWith("v")) {
        $normalizedVersion = "v$normalizedVersion"
    }

    $extractRoot = Join-Path $InstallRoot "helm-$normalizedVersion"
    $helmExe = Join-Path $extractRoot "windows-amd64/helm.exe"
    if (Test-Path -LiteralPath $helmExe) {
        return (Resolve-Path -LiteralPath $helmExe).Path
    }

    New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
    $archive = Join-Path $InstallRoot "helm-$normalizedVersion-windows-amd64.zip"
    $downloadUrl = "https://get.helm.sh/helm-$normalizedVersion-windows-amd64.zip"

    Write-Host "==> Download Helm $normalizedVersion"
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archive

    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $WindowsAmd64Sha256.ToLowerInvariant()) {
        Add-Failure "Helm archive checksum mismatch for $downloadUrl. Expected $WindowsAmd64Sha256, got $actualHash."
        return $null
    }

    if (Test-Path -LiteralPath $extractRoot) {
        Remove-Item -LiteralPath $extractRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $extractRoot -Force

    if (-not (Test-Path -LiteralPath $helmExe)) {
        Add-Failure "Downloaded Helm archive did not contain expected executable: $helmExe"
        return $null
    }
    return (Resolve-Path -LiteralPath $helmExe).Path
}

function Invoke-Helm {
    param(
        [string]$Helm,
        [string[]]$Arguments,
        [string]$Name
    )

    Write-Host "==> $Name"
    $output = & $Helm @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        Add-Failure "$Name failed with exit code $LASTEXITCODE`n$output"
        return ""
    }
    return ($output -join [Environment]::NewLine)
}

function Assert-RequiredSecretKeys {
    param(
        [string]$Name,
        [string]$Text
    )

    $requiredKeys = @(
        "DB_PASSWORD",
        "ADMIN_INIT_PASSWORD",
        "ADMIN_TOTP_SECRET",
        "APP_JWT_SECRET",
        "APP_TURNSTILE_SITE_KEY",
        "APP_TURNSTILE_SECRET_KEY",
        "APP_PII_KEY_CREATED_AT",
        "APP_PII_VAULT_TOKEN",
        "APP_PII_VAULT_AES_CIPHERTEXT",
        "APP_PII_VAULT_HMAC_CIPHERTEXT",
        "APP_PII_VAULT_PREVIOUS_AES_CIPHERTEXTS",
        "APP_PASSWORD_RESET_SMS_WEBHOOK_URL",
        "APP_PASSWORD_RESET_EMAIL_WEBHOOK_URL",
        "APP_PASSWORD_RESET_WEBHOOK_SECRET",
        "APP_STORAGE_MINIO_ENDPOINT",
        "APP_STORAGE_MINIO_ACCESS_KEY",
        "APP_STORAGE_MINIO_SECRET_KEY",
        "SENTRY_DSN"
    )

    foreach ($key in $requiredKeys) {
        Assert-Match -Name $Name -Text $Text -Pattern "secretKey:\s+$([regex]::Escape($key))\b" -Message "must map ExternalSecret key $key"
    }
}

Write-Host "==> WS7 static artifact checks"

$requiredFiles = @(
    "Dockerfile",
    "$ChartDir/Chart.yaml",
    "$ChartDir/values.yaml",
    "$ChartDir/values-dev.yaml",
    "$ChartDir/values-staging.yaml",
    "$ChartDir/values-prod.yaml",
    "$ChartDir/templates/_helpers.tpl",
    "$ChartDir/templates/_pod.tpl",
    "$ChartDir/templates/deployment.yaml",
    "$ChartDir/templates/rollout.yaml",
    "$ChartDir/templates/service.yaml",
    "$ChartDir/templates/ingress.yaml",
    "$ChartDir/templates/configmap.yaml",
    "$ChartDir/templates/externalsecret.yaml",
    "$ChartDir/templates/external-services.yaml",
    "$ChartDir/templates/hpa.yaml",
    "$ChartDir/templates/pdb.yaml",
    "$ChartDir/templates/networkpolicy.yaml",
    "$ChartDir/templates/namespace.yaml",
    "$ChartDir/templates/analysis-template.yaml",
    "$ChartDir/templates/servicemonitor.yaml",
    "$ChartDir/templates/prometheusrule.yaml",
    "$ChartDir/templates/grafana-dashboard.yaml",
    "deploy/cert-manager/clusterissuer-letsencrypt-dns01.yaml",
    "deploy/argocd/applications/monkeyshop-dev.yaml",
    "deploy/argocd/applications/monkeyshop-staging.yaml",
    "deploy/argocd/applications/monkeyshop-prod.yaml",
    "scripts/verify-runtime-image-supply-chain.ps1",
    "deploy/kyverno/monkeyshop-image-policy.yaml",
    "deploy/kyverno/monkeyshop-pod-security.yaml"
)

foreach ($file in $requiredFiles) {
    [void](Assert-File -Path $file)
}

$dockerfile = Read-RequiredFile -Path "Dockerfile"
$values = Read-RequiredFile -Path "$ChartDir/values.yaml"
$valuesDev = Read-RequiredFile -Path "$ChartDir/values-dev.yaml"
$valuesStaging = Read-RequiredFile -Path "$ChartDir/values-staging.yaml"
$valuesProd = Read-RequiredFile -Path "$ChartDir/values-prod.yaml"
$podTemplate = Read-RequiredFile -Path "$ChartDir/templates/_pod.tpl"
$networkPolicy = Read-RequiredFile -Path "$ChartDir/templates/networkpolicy.yaml"
$ingress = Read-RequiredFile -Path "$ChartDir/templates/ingress.yaml"
$externalSecret = Read-RequiredFile -Path "$ChartDir/templates/externalsecret.yaml"
$externalServices = Read-RequiredFile -Path "$ChartDir/templates/external-services.yaml"
$rollout = Read-RequiredFile -Path "$ChartDir/templates/rollout.yaml"
$analysis = Read-RequiredFile -Path "$ChartDir/templates/analysis-template.yaml"
$prometheusRule = Read-RequiredFile -Path "$ChartDir/templates/prometheusrule.yaml"
$grafanaDashboard = Read-RequiredFile -Path "$ChartDir/templates/grafana-dashboard.yaml"
$clusterIssuer = Read-RequiredFile -Path "deploy/cert-manager/clusterissuer-letsencrypt-dns01.yaml"
$kyvernoImage = Read-RequiredFile -Path "deploy/kyverno/monkeyshop-image-policy.yaml"
$kyvernoPod = Read-RequiredFile -Path "deploy/kyverno/monkeyshop-pod-security.yaml"
$runtimeImageGate = Read-RequiredFile -Path "scripts/verify-runtime-image-supply-chain.ps1"

Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "(?m)^FROM\s+maven:3\.9-eclipse-temurin-21\s+AS\s+build\r?$" -Message "must use a Maven/Java 21 build stage"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "(?m)^FROM\s+eclipse-temurin:21-jre-jammy\s+AS\s+extract\r?$" -Message "must extract Spring Boot layers in a separate stage"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "(?m)^FROM\s+eclipse-temurin:21-jre-jammy\s*\r?$" -Message "must use a Java 21 JRE runtime stage"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "java\s+-Djarmode=tools\s+-jar\s+app\.jar\s+extract\s+--layers" -Message "must extract layered jar content"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "COPY\s+--from=extract\s+--chown=app:app\s+/workspace/app\.jar\s+\./app\.jar" -Message "must copy the executable jar into the runtime stage used by ENTRYPOINT"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "fontconfig" -Message "must install fontconfig for image/font rendering"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "libfreetype6" -Message "must install freetype runtime support"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "fonts-dejavu" -Message "must install a minimal DejaVu font set"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "apt-get\s+install\s+-y\s+--only-upgrade[\s\S]+libssl3[\s\S]+openssl" -Message "must explicitly upgrade OpenSSL packages in the runtime image"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "rm\s+-rf\s+/var/lib/apt/lists/\*" -Message "must remove apt package indexes from the runtime image"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "(?m)^USER\s+app\r?$" -Message "must run the app process as the non-root app user"
Assert-Match -Name "Dockerfile" -Text $dockerfile -Pattern "HEALTHCHECK[\s\S]+/actuator/health" -Message "must define an actuator healthcheck"

Assert-Match -Name "values.yaml" -Text $values -Pattern "pod-security\.kubernetes\.io/enforce:\s+restricted" -Message "must enforce restricted Pod Security labels"
Assert-Match -Name "values.yaml" -Text $values -Pattern "runAsNonRoot:\s+true" -Message "must default pods and containers to non-root execution"
Assert-Match -Name "values.yaml" -Text $values -Pattern "runAsUser:\s+1000" -Message "must pin a non-root runtime UID"
Assert-Match -Name "values.yaml" -Text $values -Pattern "readOnlyRootFilesystem:\s+true" -Message "must default containers to a read-only root filesystem"
Assert-Match -Name "values.yaml" -Text $values -Pattern "capabilities:\s*\r?\n\s+drop:\s*\r?\n\s+-\s+ALL" -Message "must drop all Linux capabilities"
Assert-Match -Name "values.yaml" -Text $values -Pattern "useSSL=true&requireSSL=true&verifyServerCertificate=true" -Message "must require TLS verification for the default JDBC URL"
Assert-Match -Name "values.yaml" -Text $values -Pattern "startup:\s*\r?\n\s+path:\s+/actuator/health" -Message "must define startup probe defaults"
Assert-Match -Name "values.yaml" -Text $values -Pattern "liveness:\s*\r?\n\s+path:\s+/actuator/health/liveness" -Message "must define liveness probe defaults"
Assert-Match -Name "values.yaml" -Text $values -Pattern "readiness:\s*\r?\n\s+path:\s+/actuator/health/readiness" -Message "must define readiness probe defaults"
Assert-Match -Name "values.yaml" -Text $values -Pattern "autoscaling:\s*\r?\n\s+enabled:\s+true[\s\S]+minReplicas:\s+2[\s\S]+maxReplicas:\s+10[\s\S]+targetCPUUtilizationPercentage:\s+70" -Message "must default HPA to 2-10 replicas at 70 percent CPU"
Assert-Match -Name "values.yaml" -Text $values -Pattern "pdb:\s*\r?\n\s+enabled:\s+true[\s\S]+minAvailable:\s+1" -Message "must default PDB minAvailable to 1"
Assert-Match -Name "values.yaml" -Text $values -Pattern "networkPolicy:\s*\r?\n\s+enabled:\s+true" -Message "must enable NetworkPolicy by default"
Assert-Match -Name "values.yaml" -Text $values -Pattern "externalServices:\s*\r?\n\s+enabled:\s+false" -Message "must define ExternalName service support with a safe default"
Assert-Match -Name "values.yaml" -Text $values -Pattern "mysql:[\s\S]+port:\s+3306" -Message "must define the external MySQL service port"
Assert-Match -Name "values.yaml" -Text $values -Pattern "redis:[\s\S]+port:\s+6379" -Message "must define the external Redis service port"
Assert-Match -Name "values.yaml" -Text $values -Pattern "prometheusRule:\s*\r?\n\s+enabled:\s+false" -Message "must define PrometheusRule support with a safe default"
Assert-Match -Name "values.yaml" -Text $values -Pattern "grafanaDashboard:\s*\r?\n\s+enabled:\s+false" -Message "must define Grafana dashboard support with a safe default"
Assert-Match -Name "values.yaml" -Text $values -Pattern "errorRate:\s+0\.02" -Message "must define the HTTP 5xx alert threshold"
Assert-Match -Name "values.yaml" -Text $values -Pattern "p99LatencySeconds:\s+1\.5" -Message "must define the p99 latency alert threshold"
Assert-RequiredSecretKeys -Name "values.yaml" -Text $values
Assert-NotMatch -Name "values.yaml" -Text $values -Pattern "(?m)^\s*property:[^\r\n]*-[ \t]*secretKey:" -Message "must keep ExternalSecret data entries as separate YAML list items"

Assert-Match -Name "values-dev.yaml" -Text $valuesDev -Pattern "name:\s+monkeyshop-dev" -Message "must create the dev namespace"
Assert-Match -Name "values-dev.yaml" -Text $valuesDev -Pattern "ingress:\s*\r?\n\s+enabled:\s+true" -Message "must enable dev ingress for preview validation"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "name:\s+monkeyshop-staging" -Message "must create the staging namespace"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "externalSecret:\s*\r?\n\s+enabled:\s+true" -Message "must use ExternalSecret in staging"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "externalServices:\s*\r?\n\s+enabled:\s+true" -Message "must use ExternalName services in staging"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "DB_URL:\s+jdbc:mysql://monkeyshop-mysql\.monkeyshop-staging\.svc\.cluster\.local:3306/monkeyshop" -Message "must route staging MySQL through an in-cluster ExternalName service"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "SPRING_DATA_REDIS_HOST:\s+monkeyshop-redis\.monkeyshop-staging\.svc\.cluster\.local" -Message "must route staging Redis through an in-cluster ExternalName service"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "externalName:\s+staging-mysql\.internal" -Message "must map staging MySQL ExternalName to the internal managed database hostname"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "externalName:\s+staging-redis\.internal" -Message "must map staging Redis ExternalName to the internal managed cache hostname"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "rollout:\s*\r?\n\s+enabled:\s+true" -Message "must enable Argo Rollouts in staging"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "serviceMonitor:\s*\r?\n\s+enabled:\s+true" -Message "must enable Prometheus ServiceMonitor in staging"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "prometheusRule:\s*\r?\n\s+enabled:\s+true" -Message "must enable PrometheusRule alerts in staging"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "grafanaDashboard:\s*\r?\n\s+enabled:\s+true" -Message "must enable the Grafana dashboard in staging"
Assert-RequiredSecretKeys -Name "values-staging.yaml" -Text $valuesStaging
Assert-NotMatch -Name "values-staging.yaml" -Text $valuesStaging -Pattern "(?m)^\s*property:[^\r\n]*-[ \t]*secretKey:" -Message "must keep ExternalSecret data entries as separate YAML list items"
Assert-Match -Name "values-staging.yaml" -Text $valuesStaging -Pattern "externalSecret:[\s\S]+data:[\s\S]+key:\s+monkeyshop/staging" -Message "must source staging ExternalSecret data from the staging secret path"
Assert-NotMatch -Name "values-staging.yaml" -Text $valuesStaging -Pattern "key:\s+monkeyshop/prod" -Message "must not source staging ExternalSecret data from the prod secret path"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "name:\s+monkeyshop-prod" -Message "must create the prod namespace"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "digest:\s+sha256:[a-fA-F0-9]{64}" -Message "must pin production image by digest to satisfy Kyverno"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "externalSecret:\s*\r?\n\s+enabled:\s+true" -Message "must use ExternalSecret in prod"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "externalServices:\s*\r?\n\s+enabled:\s+true" -Message "must use ExternalName services in prod"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "DB_URL:\s+jdbc:mysql://monkeyshop-mysql\.monkeyshop-prod\.svc\.cluster\.local:3306/monkeyshop" -Message "must route prod MySQL through an in-cluster ExternalName service"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "SPRING_DATA_REDIS_HOST:\s+monkeyshop-redis\.monkeyshop-prod\.svc\.cluster\.local" -Message "must route prod Redis through an in-cluster ExternalName service"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "externalName:\s+prod-mysql\.internal" -Message "must map prod MySQL ExternalName to the internal managed database hostname"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "externalName:\s+prod-redis\.internal" -Message "must map prod Redis ExternalName to the internal managed cache hostname"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "waitForMysql:[\s\S]+image:\s+busybox@sha256:[a-fA-F0-9]{64}" -Message "must pin the production MySQL wait init container by digest"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "replicaCount:\s+3" -Message "must run at least three prod replicas"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "rollout:\s*\r?\n\s+enabled:\s+true" -Message "must enable Argo Rollouts in prod"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "serviceMonitor:\s*\r?\n\s+enabled:\s+true" -Message "must enable Prometheus ServiceMonitor in prod"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "prometheusRule:\s*\r?\n\s+enabled:\s+true" -Message "must enable PrometheusRule alerts in prod"
Assert-Match -Name "values-prod.yaml" -Text $valuesProd -Pattern "grafanaDashboard:\s*\r?\n\s+enabled:\s+true" -Message "must enable the Grafana dashboard in prod"

Assert-Match -Name "_pod.tpl" -Text $podTemplate -Pattern "automountServiceAccountToken:\s+false" -Message "must disable automatic service account tokens"
Assert-Match -Name "_pod.tpl" -Text $podTemplate -Pattern "image:\s+`"{{\s*\.Values\.image\.repository\s*}}@{{\s*\.Values\.image\.digest\s*}}`"" -Message "must render digest-pinned images when image.digest is set"
Assert-Match -Name "_pod.tpl" -Text $podTemplate -Pattern "initContainers:\s*\r?\n\s+-\s+name:\s+wait-for-mysql" -Message "must include the MySQL wait init container"
Assert-Match -Name "_pod.tpl" -Text $podTemplate -Pattern "secretRef:\s*\r?\n\s+name:\s+{{\s*include\s+`"monkeyshop\.secretName`"" -Message "must inject runtime secrets through envFrom secretRef"
Assert-Match -Name "_pod.tpl" -Text $podTemplate -Pattern "startupProbe:" -Message "must configure startupProbe"
Assert-Match -Name "_pod.tpl" -Text $podTemplate -Pattern "livenessProbe:" -Message "must configure livenessProbe"
Assert-Match -Name "_pod.tpl" -Text $podTemplate -Pattern "readinessProbe:" -Message "must configure readinessProbe"
Assert-Match -Name "_pod.tpl" -Text $podTemplate -Pattern "emptyDir:\s+\{\}" -Message "must mount writable paths with ephemeral volumes when PVC is disabled"

Assert-Match -Name "networkpolicy.yaml" -Text $networkPolicy -Pattern "policyTypes:\s*\r?\n\s+-\s+Ingress\s*\r?\n\s+-\s+Egress" -Message "must enforce both ingress and egress policy types"
Assert-Match -Name "networkpolicy.yaml" -Text $networkPolicy -Pattern "port:\s+{{\s*\.Values\.mysql\.port\s*}}" -Message "must restrict MySQL egress"
Assert-Match -Name "networkpolicy.yaml" -Text $networkPolicy -Pattern "port:\s+{{\s*\.Values\.redis\.port\s*}}" -Message "must restrict Redis egress"
Assert-Match -Name "networkpolicy.yaml" -Text $networkPolicy -Pattern "port:\s+{{\s*\.Values\.clamav\.port\s*}}" -Message "must restrict ClamAV egress"
Assert-Match -Name "networkpolicy.yaml" -Text $networkPolicy -Pattern "port:\s+4318" -Message "must restrict OTLP egress"
Assert-Match -Name "ingress.yaml" -Text $ingress -Pattern "tls:" -Message "must template TLS configuration"
Assert-Match -Name "values.yaml" -Text $values -Pattern "cert-manager\.io/cluster-issuer:\s+letsencrypt-dns01" -Message "must route ingress certificates through the managed ClusterIssuer"
Assert-Match -Name "cert-manager ClusterIssuer" -Text $clusterIssuer -Pattern "kind:\s+ClusterIssuer" -Message "must define a cert-manager ClusterIssuer"
Assert-Match -Name "cert-manager ClusterIssuer" -Text $clusterIssuer -Pattern "name:\s+letsencrypt-dns01" -Message "must match the Helm ingress ClusterIssuer annotation"
Assert-Match -Name "cert-manager ClusterIssuer" -Text $clusterIssuer -Pattern "server:\s+https://acme-v02\.api\.letsencrypt\.org/directory" -Message "must use the Let's Encrypt production ACME directory"
Assert-Match -Name "cert-manager ClusterIssuer" -Text $clusterIssuer -Pattern "privateKeySecretRef:\s*\r?\n\s+name:\s+letsencrypt-dns01-account-key" -Message "must keep the ACME account key in a Kubernetes Secret"
Assert-Match -Name "cert-manager ClusterIssuer" -Text $clusterIssuer -Pattern "dns01:" -Message "must solve certificates with DNS-01"
Assert-Match -Name "cert-manager ClusterIssuer" -Text $clusterIssuer -Pattern "apiTokenSecretRef:" -Message "must read DNS provider credentials from a Secret reference"
Assert-Match -Name "cert-manager ClusterIssuer" -Text $clusterIssuer -Pattern "name:\s+cloudflare-api-token-secret" -Message "must reference the expected Cloudflare API token Secret"
Assert-Match -Name "cert-manager ClusterIssuer" -Text $clusterIssuer -Pattern "key:\s+api-token" -Message "must reference the Cloudflare API token key"
Assert-NotMatch -Name "cert-manager ClusterIssuer" -Text $clusterIssuer -Pattern "apiToken:\s+|password:\s+|secret:\s+[A-Za-z0-9+/=]{12,}" -Message "must not inline DNS provider secrets"
Assert-Match -Name "externalsecret.yaml" -Text $externalSecret -Pattern "kind:\s+ExternalSecret" -Message "must template ExternalSecret resources"
Assert-Match -Name "external-services.yaml" -Text $externalServices -Pattern "kind:\s+Service" -Message "must template ExternalName Service resources"
Assert-Match -Name "external-services.yaml" -Text $externalServices -Pattern "type:\s+ExternalName" -Message "must render services as ExternalName"
Assert-Match -Name "external-services.yaml" -Text $externalServices -Pattern "externalName:\s+{{\s*required" -Message "must require explicit external target hostnames"
Assert-Match -Name "external-services.yaml" -Text $externalServices -Pattern "monkeyshop\.openai\.com/external-service" -Message "must label external dependency service resources"
Assert-Match -Name "rollout.yaml" -Text $rollout -Pattern "kind:\s+Rollout" -Message "must template Argo Rollouts canary resources"
Assert-Match -Name "analysis-template.yaml" -Text $analysis -Pattern "kind:\s+AnalysisTemplate" -Message "must template canary analysis"
Assert-Match -Name "analysis-template.yaml" -Text $analysis -Pattern "http-5xx-rate" -Message "must analyze HTTP 5xx rate"
Assert-Match -Name "analysis-template.yaml" -Text $analysis -Pattern "prometheus:" -Message "must use Prometheus analysis provider"
Assert-Match -Name "prometheusrule.yaml" -Text $prometheusRule -Pattern "kind:\s+PrometheusRule" -Message "must template Alertmanager rules"
Assert-Match -Name "prometheusrule.yaml" -Text $prometheusRule -Pattern "MonkeyShopHighErrorRate" -Message "must alert on high HTTP 5xx rate"
Assert-Match -Name "prometheusrule.yaml" -Text $prometheusRule -Pattern "http_server_requests_seconds_bucket" -Message "must alert on p99 HTTP latency"
Assert-Match -Name "prometheusrule.yaml" -Text $prometheusRule -Pattern "hikaricp_connections_active" -Message "must alert on HikariCP saturation"
Assert-Match -Name "prometheusrule.yaml" -Text $prometheusRule -Pattern "stock_deduct_fail_total" -Message "must alert on stock deduction failures"
Assert-Match -Name "prometheusrule.yaml" -Text $prometheusRule -Pattern "order_pending" -Message "must alert on pending order backlog"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "kind:\s+ConfigMap" -Message "must package the Grafana dashboard as a ConfigMap"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "Values\.grafanaDashboard\.sidecarLabel" -Message "must include the Grafana sidecar discovery label"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "http_server_requests_seconds_count" -Message "must graph HTTP request volume and error rate"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "hikaricp_connections_max" -Message "must graph HikariCP saturation"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "jvm_memory_used_bytes" -Message "must graph JVM memory"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "order_created_total" -Message "must graph business order counters"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "stock_deduct_fail_total" -Message "must graph stock deduction failures"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "order_create_seconds_bucket" -Message "must graph order creation latency"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "order_pending" -Message "must graph pending order backlog"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "Audit trace API" -Message "must link Grafana trace drilldowns to audit lookup"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "api/stats/audit-trace" -Message "must expose an audit trace lookup URL"
Assert-Match -Name "grafana-dashboard.yaml" -Text $grafanaDashboard -Pattern "Tempo Trace Drilldown" -Message "must include a Tempo trace drilldown panel"

Assert-Match -Name "Kyverno image policy" -Text $kyvernoImage -Pattern "validationFailureAction:\s+Enforce" -Message "must enforce image policy"
Assert-Match -Name "Kyverno image policy" -Text $kyvernoImage -Pattern "name:\s+disallow-latest-tag" -Message "must disallow latest tags"
Assert-Match -Name "Kyverno image policy" -Text $kyvernoImage -Pattern "name:\s+require-prod-digest" -Message "must require immutable prod image digests"
Assert-Match -Name "Kyverno image policy" -Text $kyvernoImage -Pattern "request\.object\.spec\.\[containers,\s+initContainers\]\[\]" -Message "must apply image tag and digest rules to app and init containers"
Assert-Match -Name "Kyverno image policy" -Text $kyvernoImage -Pattern "verifyImages:" -Message "must verify cosign image signatures"
Assert-Match -Name "Kyverno pod policy" -Text $kyvernoPod -Pattern "validationFailureAction:\s+Enforce" -Message "must enforce pod policy"
Assert-Match -Name "Kyverno pod policy" -Text $kyvernoPod -Pattern "runAsNonRoot:\s+true" -Message "must require non-root pods"
Assert-Match -Name "Kyverno pod policy" -Text $kyvernoPod -Pattern "readOnlyRootFilesystem:\s+true" -Message "must require read-only root filesystems"
Assert-Match -Name "Kyverno pod policy" -Text $kyvernoPod -Pattern "require-resource-requests-and-limits" -Message "must require CPU and memory requests/limits"

Assert-Match -Name "Runtime image supply-chain gate" -Text $runtimeImageGate -Pattern "docker save" -Message "must export Docker images as tar files for scanning"
Assert-Match -Name "Runtime image supply-chain gate" -Text $runtimeImageGate -Pattern "scp" -Message "must support copying exported VM images for local scanning"
Assert-Match -Name "Runtime image supply-chain gate" -Text $runtimeImageGate -Pattern "--input" -Message "must scan exported image tar files instead of mounting Docker socket"
Assert-Match -Name "Runtime image supply-chain gate" -Text $runtimeImageGate -Pattern "--severity" -Message "must configure severity filtering"
Assert-Match -Name "Runtime image supply-chain gate" -Text $runtimeImageGate -Pattern "HIGH,CRITICAL" -Message "must fail on HIGH and CRITICAL findings"
Assert-Match -Name "Runtime image supply-chain gate" -Text $runtimeImageGate -Pattern "--pkg-types" -Message "must allow explicit OS/library scan scope"
Assert-Match -Name "Runtime image supply-chain gate" -Text $runtimeImageGate -Pattern "--skip-java-db-update" -Message "must support deterministic offline runtime scans"
Assert-NotMatch -Name "Runtime image supply-chain gate" -Text $runtimeImageGate -Pattern "/var/run/docker\.sock" -Message "must not require Docker socket mounts"
$vmPasswordCanary = "12" + "3456"
Assert-NotMatch -Name "Runtime image supply-chain gate" -Text $runtimeImageGate -Pattern $vmPasswordCanary -Message "must not store VM passwords"

foreach ($environment in @("dev", "staging", "prod")) {
    $app = Read-RequiredFile -Path "deploy/argocd/applications/monkeyshop-$environment.yaml"
    Assert-Match -Name "ArgoCD $environment" -Text $app -Pattern "path:\s+helm/monkeyshop" -Message "must point to the MonkeyShop Helm chart"
    Assert-Match -Name "ArgoCD $environment" -Text $app -Pattern "values-$environment\.yaml" -Message "must use values-$environment.yaml"
    Assert-Match -Name "ArgoCD $environment" -Text $app -Pattern "namespace:\s+monkeyshop-$environment" -Message "must target monkeyshop-$environment namespace"
    Assert-Match -Name "ArgoCD $environment" -Text $app -Pattern "automated:\s*\r?\n\s+prune:\s+true\s*\r?\n\s+selfHeal:\s+true" -Message "must enable automated prune and self-heal"
    Assert-Match -Name "ArgoCD $environment" -Text $app -Pattern "CreateNamespace=true" -Message "must create the target namespace"
}

$helm = Resolve-OptionalTool -ExplicitPath $HelmPath -CommandName "helm"
if ((-not $helm) -and $DownloadHelmIfMissing) {
    $helm = Install-Helm -Version $HelmVersion -InstallRoot $ToolDir -WindowsAmd64Sha256 $HelmWindowsAmd64Sha256
}
if (-not $helm) {
    if ($RequireHelm) {
        Add-Failure "Helm is required but was not found."
    } else {
        Write-Warning "Helm was not found. Static WS7 checks ran; rendered manifest checks were skipped."
    }
} else {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    [void](Invoke-Helm -Helm $helm -Name "helm lint" -Arguments @("lint", $ChartDir))
    $rendered = @{}
    foreach ($environment in @("dev", "staging", "prod")) {
        $valueFile = Join-Path $ChartDir "values-$environment.yaml"
        $manifest = Invoke-Helm -Helm $helm -Name "helm template $environment" -Arguments @(
            "template",
            "monkeyshop",
            $ChartDir,
            "--namespace",
            "monkeyshop-$environment",
            "-f",
            $valueFile
        )
        $rendered[$environment] = $manifest
        Set-Content -LiteralPath (Join-Path $OutputDir "monkeyshop-$environment.yaml") -Value $manifest -Encoding utf8
    }

    Assert-Match -Name "rendered dev" -Text $rendered.dev -Pattern "kind:\s+Deployment" -Message "dev must render a Deployment"
    Assert-NotMatch -Name "rendered dev" -Text $rendered.dev -Pattern "kind:\s+Rollout" -Message "dev must not render a Rollout when rollout is disabled"
    foreach ($environment in @("staging", "prod")) {
        $manifest = $rendered[$environment]
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "kind:\s+Rollout" -Message "must render an Argo Rollout"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "kind:\s+AnalysisTemplate" -Message "must render a canary AnalysisTemplate"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "kind:\s+ExternalSecret" -Message "must render an ExternalSecret"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "key:\s+monkeyshop/$environment" -Message "must render ExternalSecret remoteRefs for the target environment"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "type:\s+ExternalName" -Message "must render ExternalName services for managed dependencies"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "externalName:\s+`"?$environment-mysql\.internal`"?" -Message "must render the managed MySQL ExternalName target"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "externalName:\s+`"?$environment-redis\.internal`"?" -Message "must render the managed Redis ExternalName target"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "kind:\s+ServiceMonitor" -Message "must render a ServiceMonitor"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "kind:\s+PrometheusRule" -Message "must render Prometheus alert rules"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "name:\s+monkeyshop-grafana-dashboard" -Message "must render the Grafana dashboard ConfigMap"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "grafana_dashboard:\s+`"1`"" -Message "must render Grafana sidecar discovery labels"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "MonkeyShopHighErrorRate" -Message "must render high error rate alerts"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "order_created_total" -Message "must render business metric dashboard panels"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "/api/stats/audit-trace\?traceId=\$\{traceId\}" -Message "must render the audit trace drilldown link"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "kind:\s+HorizontalPodAutoscaler" -Message "must render an HPA"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "kind:\s+PodDisruptionBudget" -Message "must render a PDB"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "kind:\s+NetworkPolicy" -Message "must render a NetworkPolicy"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "runAsNonRoot:\s+true" -Message "must render non-root security contexts"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "readOnlyRootFilesystem:\s+true" -Message "must render read-only root filesystems"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "allowPrivilegeEscalation:\s+false" -Message "must render privilege escalation disabled"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "drop:\s*\r?\n\s+-\s+ALL" -Message "must render dropped Linux capabilities"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "startupProbe:" -Message "must render startupProbe"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "livenessProbe:" -Message "must render livenessProbe"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "readinessProbe:" -Message "must render readinessProbe"
        Assert-Match -Name "rendered $environment" -Text $manifest -Pattern "pod-security\.kubernetes\.io/enforce:\s+restricted" -Message "must render restricted namespace labels"
    }
    Assert-Match -Name "rendered prod" -Text $rendered.prod -Pattern "image:\s+`"?harbor\.example\.com/monkeyshop/monkeyshop@sha256:[a-f0-9]{64}`"?" -Message "prod must render a digest-pinned image"
    Assert-NotMatch -Name "rendered prod" -Text $rendered.prod -Pattern "image:\s+`"?harbor\.example\.com/monkeyshop/monkeyshop:prod`"?" -Message "prod must not render a mutable tag image"
    Assert-Match -Name "rendered prod" -Text $rendered.prod -Pattern "image:\s+`"?busybox@sha256:[a-f0-9]{64}`"?" -Message "prod init containers must render digest-pinned images"
    Assert-NotMatch -Name "rendered prod" -Text $rendered.prod -Pattern "image:\s+`"?busybox:1\.37`"?" -Message "prod init containers must not render mutable tag images"
}

if ($Failures.Count -gt 0) {
    Write-Host "WS7 DevOps gate failed:" -ForegroundColor Red
    foreach ($failure in $Failures) {
        Write-Host " - $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host "WS7 DevOps gate completed successfully."
