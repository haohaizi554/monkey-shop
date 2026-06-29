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
