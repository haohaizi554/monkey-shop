# WS7 DevOps and Kubernetes Pipeline

This deployment lane turns MonkeyShop into a Kubernetes-native service while keeping runtime secrets outside the repository.

## Artifacts

- `Dockerfile` builds a layered Spring Boot image on Java 21, runs as the `app` user, and supports Kubernetes read-only root filesystems through `/tmp`, `/app/logs`, and `/data/images` mounts.
- `helm/monkeyshop` contains the application chart with Deployment or Argo Rollouts canary mode, Service, Ingress, ConfigMap, ExternalSecret, HPA, PDB, NetworkPolicy, PVC fallback, ServiceMonitor, PrometheusRule alerts, and a Grafana sidecar dashboard.
- `deploy/argocd/applications` contains dev, staging, and production Argo CD Applications with automated prune and self-heal.
- `deploy/kyverno` contains admission policies for non-root pods, resource requirements, immutable production images, and keyless cosign verification.
- `.github/workflows/ci.yaml` runs backend and frontend gates, builds the container image, scans it with Trivy, pushes it, and signs it with cosign on non-PR refs.

## Required Platform Dependencies

- ingress-nginx or equivalent Ingress controller
- cert-manager with a `letsencrypt-dns01` ClusterIssuer
- External Secrets Operator with a `vault-cluster-store` ClusterSecretStore, or override `secret.existingSecret`
- Prometheus Operator for `ServiceMonitor` and `PrometheusRule`
- Grafana sidecar dashboard discovery using the `grafana_dashboard=1` ConfigMap label
- Argo CD and Argo Rollouts for GitOps and canary promotion
- Kyverno with cosign keyless verification support
- MySQL, Redis, ClamAV, object storage, and OpenTelemetry Collector reachable from the app namespace

## Canary Promotion

Staging and production render Argo Rollouts instead of a plain Deployment. The default canary starts at 10 percent, pauses, runs the `monkeyshop-http-5xx-rate` Prometheus analysis, advances to 50 percent with another analysis gate, and only then promotes to 100 percent. Rollouts also render `progressDeadlineAbort: true` and a three-revision `rollbackWindow`, so a failed progress deadline or failed analysis aborts promotion and keeps recent stable revisions available for rollback.

## Render Examples

```powershell
helm template monkeyshop .\helm\monkeyshop -f .\helm\monkeyshop\values-dev.yaml
helm template monkeyshop .\helm\monkeyshop -f .\helm\monkeyshop\values-staging.yaml
helm template monkeyshop .\helm\monkeyshop -f .\helm\monkeyshop\values-prod.yaml
```

## Secret Contract

The chart expects the runtime Secret to contain:

- `DB_PASSWORD`
- `ADMIN_INIT_PASSWORD`
- `ADMIN_TOTP_SECRET`
- `APP_JWT_SECRET`
- `APP_TURNSTILE_SITE_KEY`
- `APP_TURNSTILE_SECRET_KEY`
- `APP_PII_AES_KEY_BASE64`
- `APP_PII_HMAC_KEY_BASE64`
- `APP_PASSWORD_RESET_SMS_WEBHOOK_URL`
- `APP_PASSWORD_RESET_EMAIL_WEBHOOK_URL`
- `APP_PASSWORD_RESET_WEBHOOK_SECRET`
- `APP_STORAGE_MINIO_ENDPOINT`
- `APP_STORAGE_MINIO_ACCESS_KEY`
- `APP_STORAGE_MINIO_SECRET_KEY`
- `SENTRY_DSN`

Staging and production values enable External Secrets and map those keys from `monkeyshop/staging` or `monkeyshop/prod`.

## Promotion Flow

1. A branch or pull request runs Maven, frontend build/lint/a11y, and a local image Trivy scan.
2. A push to `main` or `v*` tag builds and pushes the image to the configured registry.
3. The pushed digest is scanned with Trivy and signed with cosign keyless signing.
4. Argo CD watches the chart path and self-heals drift; staging and production use Argo Rollouts canary steps with Prometheus 5xx analysis.
5. Kyverno enforces restricted pod posture, resource limits, no `latest` images, digest references in production, and image signature verification.

## Runtime GitOps Verification

The checked-in Argo CD Applications use the repository URL `https://github.com/haohaizi554/monkey-shop.git`. Once Argo CD is installed and the platform CRDs are present, run:

```powershell
.\scripts\verify-argocd-gitops-runtime.ps1 -RequireCluster
```

The verifier waits for `monkeyshop-dev`, `monkeyshop-staging`, and `monkeyshop-prod` and fails unless every Application is both `Synced` and `Healthy`.

## VM MicroK8s Runtime Verification

The development VM can host a lightweight MicroK8s cluster for runtime proof before a managed cluster is available. Once MicroK8s is installed and the application image has been imported into the MicroK8s container runtime, run:

```powershell
.\scripts\verify-microk8s-dev-runtime.ps1 -SshTarget lly@192.168.119.129 -SkipDeploy -RunApiSecurityProbe
```

Without `-SkipDeploy`, the verifier copies `helm/monkeyshop` to the VM, applies a local MySQL dependency in `monkeyshop-data`, reconciles the `monkeyshop-dev` Helm release with `microk8s helm3 upgrade --install`, exposes the app through a NodePort, and runs the runtime smoke gates from the workstation. Runtime secret values are read from `MONKEYSHOP_DEV_DB_PASSWORD`, `MONKEYSHOP_DEV_ADMIN_INIT_PASSWORD`, `MONKEYSHOP_DEV_ADMIN_TOTP_SECRET`, and `MONKEYSHOP_DEV_JWT_SECRET` when present; otherwise the script generates temporary values and keeps them out of the repository.

This VM path proves Helm rendering, Kubernetes probes, pod security labels, Ingress scheduling, NodePort reachability, actuator health, SPA security headers, Prometheus metrics, anonymous API behavior, protected API rejection, honeypot blocking, and optional rate-limit behavior. It does not replace the Argo CD, TLS, Kyverno, and managed-cluster gates for staging or production.

## VM Argo CD GitOps Verification

The VM can also prove the Argo CD reconciliation path before a managed GitOps cluster is available:

```powershell
.\scripts\verify-argocd-microk8s-gitops.ps1 -SshTarget lly@192.168.119.129 -InstallArgoCd -RunApiSecurityProbe
```

Use `-InstallArgoCd` for a fresh VM or when Argo CD is absent; omit it once Argo CD is already installed. The verifier publishes a local `git://<vm>/monkeyshop-gitops.git` repository, creates a dev Argo CD Application, waits for the exact pushed Git revision to become `Synced` and `Healthy`, reads the NodePort, and then runs the runtime smoke and API security smoke gates from the workstation. This proves the local GitOps control loop, but the checked-in dev, staging, and production Applications still need the managed-cluster GitOps gate, public TLS/SecurityHeaders validation, and rollout/canary validation before WS7 is fully production-complete.
