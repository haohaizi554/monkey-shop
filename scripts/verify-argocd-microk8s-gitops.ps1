param(
    [string]$SshTarget = "lly@192.168.119.129",
    [string]$ChartDir = "helm/monkeyshop",
    [string]$RemoteWorkDir = "~/argocd-bootstrap/monkeyshop-gitops",
    [string]$RemoteGitBaseDir = "~/git",
    [string]$ApplicationName = "monkeyshop-gitops-dev",
    [string]$Namespace = "monkeyshop-gitops-dev",
    [string]$ReleaseName = "monkeyshop-gitops",
    [string]$DataNamespace = "monkeyshop-data",
    [string]$ImageRef = "monkey-shop-myshop:latest",
    [string]$ArgoCdVersion = "v2.13.3",
    [string]$HostRedisIp = "",
    [string]$DbPassword = $env:MONKEYSHOP_DEV_DB_PASSWORD,
    [int]$TimeoutSeconds = 600,
    [switch]$InstallArgoCd,
    [switch]$RunApiSecurityProbe,
    [switch]$SkipRuntimeSmoke
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

if ([string]::IsNullOrWhiteSpace($HostRedisIp)) {
    $HostRedisIp = Get-SshHost
}

$sshHost = Get-SshHost
$image = Split-ImageRef -Reference $ImageRef
$remoteChartDir = "$RemoteWorkDir/worktree/helm/monkeyshop"
$remoteValuesPath = "$remoteChartDir/values-microk8s-gitops.yaml"
$remoteRepoName = "monkeyshop-gitops.git"
$remoteRepoPath = "$RemoteGitBaseDir/$remoteRepoName"
$repoUrl = "git://$sshHost/$remoteRepoName"

Write-Host "==> Argo CD MicroK8s GitOps gate"

$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("monkeyshop-argocd-gitops-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
try {
    $valuesPath = Join-Path $temporaryRoot "values-microk8s-gitops.yaml"
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
    - host: monkeyshop-gitops.local
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

K="microk8s kubectl"
APP_NAME="$ApplicationName"
APP_NS="$Namespace"
RELEASE="$ReleaseName"
DATA_NS="$DataNamespace"
REMOTE_WORK_DIR="$RemoteWorkDir"
REMOTE_GIT_BASE="$RemoteGitBaseDir"
REPO_URL="$repoUrl"
ARGOCD_VERSION="$ArgoCdVersion"
ARGOCD_INSTALL_URL="https://raw.githubusercontent.com/argoproj/argo-cd/$ArgoCdVersion/manifests/install.yaml"
HOST_REDIS_IP="$HostRedisIp"
DB_PASSWORD_OVERRIDE=$(ConvertTo-ShellSingleQuoted $DbPassword)
INSTALL_ARGOCD=$(if ($InstallArgoCd) { "true" } else { "false" })
TIMEOUT_SECONDS="$TimeoutSeconds"

case "`$REMOTE_WORK_DIR" in
  "~/"*) REMOTE_WORK_DIR="`$HOME/`${REMOTE_WORK_DIR#\~/}" ;;
esac
case "`$REMOTE_GIT_BASE" in
  "~/"*) REMOTE_GIT_BASE="`$HOME/`${REMOTE_GIT_BASE#\~/}" ;;
esac
REMOTE_REPO_PATH="`$REMOTE_GIT_BASE/$remoteRepoName"

microk8s status --format short
`$K get nodes

mkdir -p "`$REMOTE_WORK_DIR" "`$REMOTE_GIT_BASE"

if [ "`$INSTALL_ARGOCD" = "true" ] || ! `$K -n argocd get deployment/argocd-server >/dev/null 2>&1; then
  mkdir -p "`$REMOTE_WORK_DIR"
  curl -fL --retry 3 --connect-timeout 20 --max-time 240 -o "`$REMOTE_WORK_DIR/argocd-install.yaml" "`$ARGOCD_INSTALL_URL"
  `$K create namespace argocd --dry-run=client -o yaml | `$K apply -f -
  `$K apply -n argocd -f "`$REMOTE_WORK_DIR/argocd-install.yaml"
fi

# Keep the GitOps control plane on its own in-cluster Redis so the app runtime does not contend with host Docker Redis.
`$K -n argocd patch service argocd-redis --type=merge -p '{"spec":{"selector":{"app.kubernetes.io/name":"argocd-redis"}}}' >/dev/null 2>&1 || true
`$K -n argocd delete endpoints argocd-redis --ignore-not-found=true >/dev/null 2>&1 || true
`$K -n argocd scale deployment argocd-redis --replicas=1 >/dev/null 2>&1 || true
cat <<ARGO_REDIS_PATCH > /tmp/argocd-redis-patch.yaml
spec:
  template:
    spec:
      containers:
        - name: redis
          imagePullPolicy: IfNotPresent
ARGO_REDIS_PATCH
`$K -n argocd patch deployment argocd-redis --type=strategic --patch-file /tmp/argocd-redis-patch.yaml >/dev/null
`$K -n argocd scale deployment argocd-dex-server --replicas=0 >/dev/null 2>&1 || true

for workload in \
  deployment/argocd-redis \
  deployment/argocd-server \
  deployment/argocd-repo-server \
  deployment/argocd-applicationset-controller \
  deployment/argocd-notifications-controller \
  statefulset/argocd-application-controller; do
  `$K -n argocd rollout status "`$workload" --timeout="`$TIMEOUT_SECONDS"s
done

if ! `$K -n "`$DATA_NS" get deployment/mysql >/dev/null 2>&1; then
  `$K create namespace "`$DATA_NS" --dry-run=client -o yaml | `$K apply -f -
  if [ -z "`$DB_PASSWORD_OVERRIDE" ]; then
    DB_PASSWORD_VALUE="Db`$(openssl rand -hex 12)aA1!"
  else
    DB_PASSWORD_VALUE="`$DB_PASSWORD_OVERRIDE"
  fi
  MYSQL_ROOT_PASSWORD="Root`$(openssl rand -hex 12)aA1!"
  `$K -n "`$DATA_NS" create secret generic mysql-secret \
    --from-literal=MYSQL_ROOT_PASSWORD="`$MYSQL_ROOT_PASSWORD" \
    --from-literal=MYSQL_PASSWORD="`$DB_PASSWORD_VALUE" \
    --dry-run=client -o yaml | `$K apply -f -
  cat <<MYSQL_MANIFEST | `$K apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mysql
  namespace: `$DATA_NS
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
  namespace: `$DATA_NS
  labels:
    app.kubernetes.io/name: mysql
spec:
  selector:
    app.kubernetes.io/name: mysql
  ports:
    - name: mysql
      port: 3306
      targetPort: mysql
MYSQL_MANIFEST
fi

`$K create namespace "`$DATA_NS" --dry-run=client -o yaml | `$K apply -f -
cat <<REDIS_MANIFEST | `$K apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: `$DATA_NS
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
  namespace: `$DATA_NS
  labels:
    app.kubernetes.io/name: redis
spec:
  selector:
    app.kubernetes.io/name: redis
  ports:
    - name: redis
      port: 6379
      targetPort: redis
REDIS_MANIFEST
`$K -n "`$DATA_NS" rollout status deployment/mysql --timeout="`$TIMEOUT_SECONDS"s
`$K -n "`$DATA_NS" rollout status deployment/redis --timeout="`$TIMEOUT_SECONDS"s

rm -rf "`$REMOTE_WORK_DIR/git-work" "`$REMOTE_REPO_PATH"
mkdir -p "`$REMOTE_WORK_DIR/git-work/helm"
cp -R "`$REMOTE_WORK_DIR/worktree/helm/monkeyshop" "`$REMOTE_WORK_DIR/git-work/helm/monkeyshop"
cd "`$REMOTE_WORK_DIR/git-work"
git init >/dev/null
git config user.email codex@local
git config user.name Codex
git add helm
git commit -m "feat: add microk8s gitops chart state" >/dev/null
git init --bare "`$REMOTE_REPO_PATH" >/dev/null
git remote add origin "`$REMOTE_REPO_PATH"
git push origin HEAD:main >/dev/null
touch "`$REMOTE_REPO_PATH/git-daemon-export-ok"

if [ -f "`$REMOTE_WORK_DIR/git-daemon.pid" ]; then
  kill "`$(cat "`$REMOTE_WORK_DIR/git-daemon.pid")" >/dev/null 2>&1 || true
fi
pkill -f "git daemon.*base-path=`$REMOTE_GIT_BASE" >/dev/null 2>&1 || true
nohup git daemon --reuseaddr --base-path="`$REMOTE_GIT_BASE" --export-all --informative-errors --verbose >"`$REMOTE_WORK_DIR/git-daemon.log" 2>&1 &
echo `$! > "`$REMOTE_WORK_DIR/git-daemon.pid"
for attempt in `$(seq 1 30); do
  TARGET_REVISION=`$( (git ls-remote "`$REPO_URL" main 2>/dev/null | awk '{print `$1}') || true )
  if [ -n "`$TARGET_REVISION" ]; then
    break
  fi
  sleep 1
done
if [ -z "`$TARGET_REVISION" ]; then
  cat "`$REMOTE_WORK_DIR/git-daemon.log" >&2 || true
  echo "git daemon did not serve `$REPO_URL within 30 seconds" >&2
  exit 1
fi
echo "TARGET_REVISION=`$TARGET_REVISION"

DB_PASSWORD_VALUE=`$(`$K -n "`$DATA_NS" get secret mysql-secret -o jsonpath='{.data.MYSQL_PASSWORD}' | base64 -d)
ADMIN_PASSWORD_VALUE="Admin`$(openssl rand -hex 12)aA1!"
ADMIN_TOTP_SECRET_VALUE="`$(head -c 20 /dev/urandom | base32 | tr -d '=')"
JWT_SECRET_VALUE="`$(openssl rand -base64 48 | tr -d '\n')"

`$K create namespace "`$APP_NS" --dry-run=client -o yaml | `$K apply -f -
`$K label namespace "`$APP_NS" \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/warn=restricted \
  --overwrite
`$K -n "`$APP_NS" create secret generic "`$RELEASE-runtime" \
  --from-literal=DB_PASSWORD="`$DB_PASSWORD_VALUE" \
  --from-literal=ADMIN_INIT_PASSWORD="`$ADMIN_PASSWORD_VALUE" \
  --from-literal=ADMIN_TOTP_SECRET="`$ADMIN_TOTP_SECRET_VALUE" \
  --from-literal=APP_JWT_SECRET="`$JWT_SECRET_VALUE" \
  --dry-run=client -o yaml | `$K apply -f -

cat <<ARGO_APP | `$K apply -f -
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: `$APP_NAME
  namespace: argocd
  labels:
    app.kubernetes.io/part-of: monkeyshop
    app.kubernetes.io/environment: dev
spec:
  project: default
  source:
    repoURL: `$REPO_URL
    targetRevision: main
    path: helm/monkeyshop
    helm:
      releaseName: `$RELEASE
      valueFiles:
        - values-microk8s-gitops.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: `$APP_NS
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
ARGO_APP

`$K -n argocd annotate application "`$APP_NAME" argocd.argoproj.io/refresh=hard --overwrite >/dev/null

deadline=`$((SECONDS + TIMEOUT_SECONDS))
while [ "`$SECONDS" -lt "`$deadline" ]; do
  sync=`$(`$K -n argocd get application "`$APP_NAME" -o jsonpath='{.status.sync.status}' 2>/dev/null || true)
  health=`$(`$K -n argocd get application "`$APP_NAME" -o jsonpath='{.status.health.status}' 2>/dev/null || true)
  revision=`$(`$K -n argocd get application "`$APP_NAME" -o jsonpath='{.status.sync.revision}' 2>/dev/null || true)
  echo "APP_STATUS sync=`$sync health=`$health revision=`$revision target=`$TARGET_REVISION"
  if [ "`$sync" = "Synced" ] && [ "`$health" = "Healthy" ] && [ "`$revision" = "`$TARGET_REVISION" ]; then
    break
  fi
  sleep 10
done

sync=`$(`$K -n argocd get application "`$APP_NAME" -o jsonpath='{.status.sync.status}')
health=`$(`$K -n argocd get application "`$APP_NAME" -o jsonpath='{.status.health.status}')
revision=`$(`$K -n argocd get application "`$APP_NAME" -o jsonpath='{.status.sync.revision}')
if [ "`$sync" != "Synced" ] || [ "`$health" != "Healthy" ] || [ "`$revision" != "`$TARGET_REVISION" ]; then
  `$K -n argocd describe application "`$APP_NAME" || true
  exit 1
fi

NODE_PORT=`$(`$K -n "`$APP_NS" get svc "`$RELEASE" -o jsonpath='{.spec.ports[0].nodePort}')
REVISION=`$(`$K -n argocd get application "`$APP_NAME" -o jsonpath='{.status.sync.revision}')
echo "APPLICATION=`$APP_NAME"
echo "REPO_URL=`$REPO_URL"
echo "REVISION=`$REVISION"
echo "NODE_PORT=`$NODE_PORT"
`$K -n argocd get application "`$APP_NAME" -o wide
`$K -n "`$APP_NS" get deploy,svc,ingress,pods -o wide
"@

    $remoteScriptPath = Join-Path $temporaryRoot "verify-argocd-microk8s-gitops.sh"
    Set-Content -LiteralPath $remoteScriptPath -Value $remoteScript -NoNewline -Encoding ascii

    Invoke-Remote -Name "prepare remote GitOps workspace" -Command "rm -rf $RemoteWorkDir/worktree && mkdir -p $RemoteWorkDir/worktree/helm"
    Copy-ToRemote -Sources @($ChartDir) -Destination "$SshTarget`:$RemoteWorkDir/worktree/helm/" -Name "copy Helm chart for GitOps"
    Copy-ToRemote -Sources @($valuesPath) -Destination "$SshTarget`:$remoteValuesPath" -Name "copy MicroK8s GitOps values"
    Copy-ToRemote -Sources @($remoteScriptPath) -Destination "$SshTarget`:$RemoteWorkDir/verify-argocd-microk8s-gitops.sh" -Name "copy Argo CD GitOps verifier"

    $output = Invoke-Remote -Name "verify Argo CD GitOps sync" -Command "bash $RemoteWorkDir/verify-argocd-microk8s-gitops.sh"
    Write-Host $output

    $nodePortMatch = [regex]::Match($output, "(?m)^NODE_PORT=(\d+)\s*$")
    if (-not $nodePortMatch.Success) {
        throw "Could not determine GitOps NodePort from verifier output"
    }
    $baseUrl = "http://${sshHost}:$($nodePortMatch.Groups[1].Value)"

    if (-not $SkipRuntimeSmoke) {
        & (Join-Path $PSScriptRoot "verify-runtime-smoke.ps1") -BaseUrl $baseUrl -TimeoutSeconds 30
        if ($LASTEXITCODE -ne 0) {
            throw "Runtime smoke failed for $baseUrl"
        }
        if ($RunApiSecurityProbe) {
            & (Join-Path $PSScriptRoot "verify-runtime-api-security.ps1") -BaseUrl $baseUrl -TimeoutSeconds 30 -RunRateLimitProbe
            if ($LASTEXITCODE -ne 0) {
                throw "Runtime API security smoke failed for $baseUrl"
            }
        }
    }

    Write-Host "Argo CD MicroK8s GitOps gate completed successfully for $baseUrl"
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
