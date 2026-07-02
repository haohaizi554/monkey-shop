param(
    [string]$SshTarget = "lly@192.168.119.129",
    [string]$ChartDir = "helm/monkeyshop",
    [string]$RemoteWorkDir = "~/monkeyshop-k8s",
    [string]$ReleaseName = "monkeyshop-dev",
    [string]$Namespace = "monkeyshop-dev",
    [string]$DataNamespace = "monkeyshop-data",
    [string]$ImageRef = "monkey-shop-myshop:latest",
    [int]$TimeoutSeconds = 480,
    [string]$BaseUrl = "",
    [string]$DbPassword = $env:MONKEYSHOP_DEV_DB_PASSWORD,
    [string]$AdminInitPassword = $env:MONKEYSHOP_DEV_ADMIN_INIT_PASSWORD,
    [string]$AdminTotpSecret = $env:MONKEYSHOP_DEV_ADMIN_TOTP_SECRET,
    [string]$JwtSecret = $env:MONKEYSHOP_DEV_JWT_SECRET,
    [switch]$RecreateData,
    [switch]$SkipDeploy,
    [switch]$SkipRuntimeSmoke,
    [switch]$RunApiSecurityProbe
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-CheckedNative {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$Name
    )

    Write-Host "==> $Name"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $FilePath @Arguments 2>&1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE`n$($output -join [Environment]::NewLine)"
    }
    return ($output -join [Environment]::NewLine)
}

function Invoke-Remote {
    param(
        [string]$Command,
        [string]$Name
    )

    Invoke-CheckedNative `
        -FilePath "ssh" `
        -Arguments @("-o", "BatchMode=yes", "-o", "ConnectTimeout=10", $SshTarget, $Command) `
        -Name $Name
}

function Copy-ToRemote {
    param(
        [string[]]$Sources,
        [string]$Destination,
        [string]$Name
    )

    Invoke-CheckedNative `
        -FilePath "scp" `
        -Arguments (@("-q", "-r") + $Sources + @($Destination)) `
        -Name $Name | Out-Null
}

function ConvertTo-ShellSingleQuoted {
    param([string]$Value)

    if ($null -eq $Value) {
        $Value = ""
    }
    return "'" + $Value.Replace("'", "'`"`"'`"'") + "'"
}

function New-RandomBytes {
    param([int]$Length)

    $bytes = [byte[]]::new($Length)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return $bytes
}

function New-RuntimeSecretValue {
    param([string]$Prefix)

    $bytes = New-RandomBytes -Length 18
    $hex = [System.BitConverter]::ToString($bytes).Replace("-", "").ToLowerInvariant()
    return "$Prefix$($hex)aA1!"
}

function New-Base32Secret {
    $alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    $chars = New-Object char[] 32
    $bytes = New-RandomBytes -Length 32
    for ($i = 0; $i -lt $chars.Length; $i++) {
        $chars[$i] = $alphabet[$bytes[$i] % $alphabet.Length]
    }
    return -join $chars
}

function Get-SshHost {
    $target = $SshTarget
    if ($target.Contains("@")) {
        $target = $target.Substring($target.LastIndexOf("@") + 1)
    }
    if ($target.Contains(":")) {
        $target = $target.Split(":")[0]
    }
    return $target
}

function Split-ImageRef {
    param([string]$Reference)

    if ($Reference.Contains("@")) {
        $parts = $Reference.Split("@", 2)
        return [pscustomobject]@{
            Repository = $parts[0]
            Tag = ""
            Digest = $parts[1]
        }
    }

    $lastSlash = $Reference.LastIndexOf("/")
    $lastColon = $Reference.LastIndexOf(":")
    if ($lastColon -gt $lastSlash) {
        return [pscustomobject]@{
            Repository = $Reference.Substring(0, $lastColon)
            Tag = $Reference.Substring($lastColon + 1)
            Digest = ""
        }
    }

    return [pscustomobject]@{
        Repository = $Reference
        Tag = "latest"
        Digest = ""
    }
}

if (-not (Test-Path -LiteralPath $ChartDir)) {
    throw "Chart directory not found: $ChartDir"
}

$image = Split-ImageRef -Reference $ImageRef
$remoteScriptPath = "$RemoteWorkDir/deploy-microk8s-dev.sh"
$remoteValuesPath = "$RemoteWorkDir/values-microk8s-dev.yaml"
$remoteChartDir = "$RemoteWorkDir/helm/monkeyshop"

if (-not $AdminInitPassword) {
    $AdminInitPassword = New-RuntimeSecretValue -Prefix "Admin"
}
if (-not $AdminTotpSecret) {
    $AdminTotpSecret = New-Base32Secret
}
if (-not $JwtSecret) {
    $JwtSecret = [Convert]::ToBase64String((New-RandomBytes -Length 48))
}

Write-Host "==> MicroK8s dev runtime gate"
Invoke-Remote -Name "microk8s status" -Command "microk8s status --format short && microk8s kubectl get nodes" | Write-Host

if (-not $SkipDeploy) {
    $temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("monkeyshop-microk8s-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    try {
        $valuesPath = Join-Path $temporaryRoot "values-microk8s-dev.yaml"
        $values = @"
global:
  environment: dev

namespace:
  create: false
  name: $Namespace

image:
  repository: $($image.Repository)
  tag: $($image.Tag)
  digest: $($image.Digest)
  pullPolicy: IfNotPresent

replicaCount: 1

config:
  SPRING_PROFILES_ACTIVE: dev
  SESSION_COOKIE_SECURE: "false"
  DB_URL: jdbc:mysql://mysql.$DataNamespace.svc.cluster.local:3306/monkeyshop?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&sslMode=REQUIRED&allowPublicKeyRetrieval=true
  DB_USERNAME: monkeyuser
  SPRING_DATA_REDIS_HOST: "redis.$DataNamespace.svc.cluster.local"
  SPRING_DATA_REDIS_PORT: "6379"
  APP_AUTH_REQUIRE_REDIS_STATE: "true"
  APP_RATE_LIMIT_REQUIRE_REDIS_STATE: "true"
  APP_JWT_REQUIRE_REDIS_TOKEN_STORE: "true"
  APP_AUTH_CAPTCHA_PROVIDER: local
  APP_UPLOAD_VIRUS_SCAN_ENABLED: "false"
  APP_STORAGE_PROVIDER: local
  APP_PII_ENCRYPTION_ENABLED: "false"
  OTEL_TRACES_EXPORTER: none
  SENTRY_TRACES_SAMPLE_RATE: "0.0"

secret:
  existingSecret: ${ReleaseName}-runtime

mysql:
  host: mysql.$DataNamespace.svc.cluster.local
  port: 3306

redis:
  host: "redis.$DataNamespace.svc.cluster.local"
  port: 6379

initContainers:
  waitForMysql:
    enabled: false

externalSecret:
  enabled: false

ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/force-ssl-redirect: "false"
    nginx.ingress.kubernetes.io/hsts: "false"
    nginx.ingress.kubernetes.io/limit-rps: "20"
  hosts:
    - host: monkeyshop-dev.local
      paths:
        - path: /
          pathType: Prefix
  tls: []

service:
  type: NodePort
  port: 80

autoscaling:
  enabled: false

pdb:
  enabled: false

rollout:
  enabled: false

persistence:
  enabled: false

networkPolicy:
  enabled: false
"@
        Set-Content -LiteralPath $valuesPath -Value $values -NoNewline -Encoding ascii

        $remoteScript = @"
set -euo pipefail

export PATH="`$HOME/bin:`$PATH"
K="microk8s kubectl"
H="microk8s helm3"
DB_PASSWORD_VALUE=$(ConvertTo-ShellSingleQuoted $DbPassword)
ADMIN_PASSWORD_VALUE=$(ConvertTo-ShellSingleQuoted $AdminInitPassword)
ADMIN_TOTP_SECRET_VALUE=$(ConvertTo-ShellSingleQuoted $AdminTotpSecret)
JWT_SECRET_VALUE=$(ConvertTo-ShellSingleQuoted $JwtSecret)
RECREATE_DATA=$(if ($RecreateData) { "true" } else { "false" })

microk8s status --format short
`$K get nodes

`$K create namespace $DataNamespace --dry-run=client -o yaml | `$K apply -f -
if [ "`$RECREATE_DATA" = "true" ]; then
  `$K -n $DataNamespace delete deployment/mysql service/mysql secret/mysql-secret deployment/redis service/redis --ignore-not-found=true
fi

if `$K -n $DataNamespace get secret mysql-secret >/dev/null 2>&1; then
  EXISTING_DB_PASSWORD=`$(`$K -n $DataNamespace get secret mysql-secret -o jsonpath='{.data.MYSQL_PASSWORD}' | base64 -d)
  EXISTING_ROOT_PASSWORD=`$(`$K -n $DataNamespace get secret mysql-secret -o jsonpath='{.data.MYSQL_ROOT_PASSWORD}' | base64 -d)
  if [ -n "`$DB_PASSWORD_VALUE" ] && [ "`$DB_PASSWORD_VALUE" != "`$EXISTING_DB_PASSWORD" ]; then
    echo "Existing MySQL secret is present; keeping its database password. Pass -RecreateData to replace the local data dependency." >&2
  fi
  DB_PASSWORD_VALUE="`$EXISTING_DB_PASSWORD"
  MYSQL_ROOT_PASSWORD="`$EXISTING_ROOT_PASSWORD"
else
  MYSQL_ROOT_PASSWORD="Root`$(openssl rand -hex 12)aA1!"
fi
if [ -z "`$DB_PASSWORD_VALUE" ]; then
  DB_PASSWORD_VALUE="Db`$(openssl rand -hex 12)aA1!"
fi

`$K -n $DataNamespace create secret generic mysql-secret \
  --from-literal=MYSQL_ROOT_PASSWORD="`$MYSQL_ROOT_PASSWORD" \
  --from-literal=MYSQL_PASSWORD="`$DB_PASSWORD_VALUE" \
  --dry-run=client -o yaml | `$K apply -f -

cat <<'YAML' | `$K apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mysql
  namespace: $DataNamespace
  labels:
    app.kubernetes.io/name: mysql
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: mysql
  template:
    metadata:
      labels:
        app.kubernetes.io/name: mysql
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
          imagePullPolicy: IfNotPresent
          ports:
            - name: mysql
              containerPort: 3306
          env:
            - name: MYSQL_DATABASE
              value: monkeyshop
            - name: MYSQL_USER
              value: monkeyuser
            - name: MYSQL_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: mysql-secret
                  key: MYSQL_PASSWORD
            - name: MYSQL_ROOT_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: mysql-secret
                  key: MYSQL_ROOT_PASSWORD
          readinessProbe:
            exec:
              command:
                - sh
                - -ec
                - mysqladmin ping -h 127.0.0.1 -uroot -p"`$MYSQL_ROOT_PASSWORD"
            initialDelaySeconds: 20
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 24
          volumeMounts:
            - name: mysql-data
              mountPath: /var/lib/mysql
      volumes:
        - name: mysql-data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: mysql
  namespace: $DataNamespace
  labels:
    app.kubernetes.io/name: mysql
spec:
  selector:
    app.kubernetes.io/name: mysql
  ports:
    - name: mysql
      port: 3306
      targetPort: mysql
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: $DataNamespace
  labels:
    app.kubernetes.io/name: redis
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: redis
  template:
    metadata:
      labels:
        app.kubernetes.io/name: redis
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          imagePullPolicy: IfNotPresent
          ports:
            - name: redis
              containerPort: 6379
          readinessProbe:
            exec:
              command:
                - redis-cli
                - ping
            initialDelaySeconds: 5
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 12
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 250m
              memory: 256Mi
          volumeMounts:
            - name: redis-data
              mountPath: /data
      volumes:
        - name: redis-data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: $DataNamespace
  labels:
    app.kubernetes.io/name: redis
spec:
  selector:
    app.kubernetes.io/name: redis
  ports:
    - name: redis
      port: 6379
      targetPort: redis
YAML

`$K -n $DataNamespace rollout status deployment/mysql --timeout=${TimeoutSeconds}s
`$K -n $DataNamespace rollout status deployment/redis --timeout=${TimeoutSeconds}s

`$K create namespace $Namespace --dry-run=client -o yaml | `$K apply -f -
`$K label namespace $Namespace \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/warn=restricted \
  --overwrite

`$K -n $Namespace create secret generic ${ReleaseName}-runtime \
  --from-literal=DB_PASSWORD="`$DB_PASSWORD_VALUE" \
  --from-literal=ADMIN_INIT_PASSWORD="`$ADMIN_PASSWORD_VALUE" \
  --from-literal=ADMIN_TOTP_SECRET="`$ADMIN_TOTP_SECRET_VALUE" \
  --from-literal=APP_JWT_SECRET="`$JWT_SECRET_VALUE" \
  --dry-run=client -o yaml | `$K apply -f -

`$H upgrade --install $ReleaseName $remoteChartDir \
  -n $Namespace \
  -f $remoteValuesPath \
  --wait --timeout ${TimeoutSeconds}s

NODE_PORT=`$(`$K -n $Namespace get svc $ReleaseName -o jsonpath='{.spec.ports[0].nodePort}')
echo "NODE_PORT=`$NODE_PORT"
`$K -n $Namespace get deploy,svc,ingress,pods -o wide
"@

        $scriptPath = Join-Path $temporaryRoot "deploy-microk8s-dev.sh"
        Set-Content -LiteralPath $scriptPath -Value $remoteScript -NoNewline -Encoding ascii

        Invoke-Remote -Name "prepare remote workspace" -Command "rm -rf $RemoteWorkDir/helm/monkeyshop && mkdir -p $RemoteWorkDir/helm"
        Copy-ToRemote -Sources @($ChartDir) -Destination "$SshTarget`:$RemoteWorkDir/helm/" -Name "copy Helm chart"
        Copy-ToRemote -Sources @($valuesPath, $scriptPath) -Destination "$SshTarget`:$RemoteWorkDir/" -Name "copy MicroK8s runtime inputs"

        $deployOutput = Invoke-Remote -Name "microk8s helm3 upgrade --install" -Command "chmod +x $remoteScriptPath && bash $remoteScriptPath"
        Write-Host $deployOutput

        if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
            $nodePortMatch = [regex]::Match($deployOutput, "(?m)^NODE_PORT=(\d+)\s*$")
            if (-not $nodePortMatch.Success) {
                throw "Could not determine NodePort from MicroK8s deployment output"
            }
            $BaseUrl = "http://$(Get-SshHost):$($nodePortMatch.Groups[1].Value)"
        }
    } finally {
        if (Test-Path -LiteralPath $temporaryRoot) {
            Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
        }
    }
} elseif ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    $nodePort = Invoke-Remote `
        -Name "read existing NodePort" `
        -Command "microk8s kubectl -n $Namespace get svc $ReleaseName -o jsonpath='{.spec.ports[0].nodePort}'"
    if ([string]::IsNullOrWhiteSpace($nodePort)) {
        throw "Could not read NodePort for existing service $Namespace/$ReleaseName"
    }
    $BaseUrl = "http://$(Get-SshHost):$nodePort"
}

Invoke-Remote `
    -Name "verify MicroK8s workload state" `
    -Command "microk8s kubectl -n $DataNamespace get pods,svc -o wide && microk8s kubectl -n $Namespace get deploy,svc,ingress,pods -o wide" | Write-Host

if (-not $SkipRuntimeSmoke) {
    & (Join-Path $PSScriptRoot "verify-runtime-smoke.ps1") -BaseUrl $BaseUrl -TimeoutSeconds 30
    if ($LASTEXITCODE -ne 0) {
        throw "Runtime smoke failed for $BaseUrl"
    }

    if ($RunApiSecurityProbe) {
        & (Join-Path $PSScriptRoot "verify-runtime-api-security.ps1") -BaseUrl $BaseUrl -TimeoutSeconds 30 -RunRateLimitProbe
        if ($LASTEXITCODE -ne 0) {
            throw "Runtime API security smoke failed for $BaseUrl"
        }
    }
}

Write-Host "MicroK8s dev runtime gate completed successfully for $BaseUrl"
