# WS1-WS8 Acceptance Evidence

Date: 2026-07-03

This file records the current verification evidence for the WS1-WS8 production hardening goal. It is a current-state acceptance log, not a replacement for the full objective. Items that require real public infrastructure or external SaaS gates remain listed as open proof.

## Verified Gates

### Umbrella Acceptance Entry Point

- `scripts/verify-ws1-ws8-acceptance.ps1` is the repeatable umbrella gate for WS1-WS8 evidence.
- By default it runs the local repository gates: Maven verify, quality reports, WS1 scanners, frontend checks, WS6/WS7/WS8 checks, and Kyverno.
- The VM runtime umbrella path passed with:
  - `scripts/verify-ws1-ws8-acceptance.ps1 -SkipBackendVerify -SkipFrontend -SkipWs1Scanners -RuntimeBaseUrl http://192.168.119.129:8888 -IncludeVmRuntime -SshTarget lly@192.168.119.129 -IncludeRuntimeImageScan -IncludeRuntimeDataProtection`
  - Passed gates: WS6, WS7, WS8, Kyverno, Compose runtime smoke/API security, MicroK8s dev runtime, Argo CD MicroK8s GitOps, runtime image supply-chain, and remote runtime data protection.
  - Backend Maven verify, WS1 scanners, and WS5 frontend were skipped in this umbrella run because their current evidence is recorded separately below.
- Optional flags attach runtime and external evidence without pretending those systems are always present:
  - `-RuntimeBaseUrl`
  - `-IncludeVmRuntime -SshTarget <user@host>`
  - `-IncludeRuntimeImageScan`
  - `-IncludeRuntimeDataProtection`
  - `-IncludePublicEdge -PublicBaseUrl https://...`
  - `-IncludeSonar`

### Backend Quality And Security

- `mvn -DautoUpdate=false verify` passed.
- JUnit/Surefire total: 964 tests, 0 failures, 0 errors, 0 skipped.
- JaCoCo report gate passed: 90.86% line coverage, 4525/4980 covered lines.
- PITest report gate passed: 85.75% mutation coverage, 704/821 killed mutations, 95.37% mutation line coverage.
- SpotBugs report gate passed: 0 BugInstance entries.
- OWASP dependency-check report gate passed: 0 blocking HIGH/CRITICAL or CVSS >= 7 findings.
- `scripts/verify-quality-reports.ps1 -RequireDependencyCheckReport` passed.

### WS1 Security Baseline

- `scripts/verify-ws1-security.ps1 -SkipMaven -SkipDependencyCheck -SkipTrivyDbUpdate -OutputDir target/ws1-security-final-check` passed.
- Literal risk pattern scan passed.
- Security header posture scan passed.
- Gitleaks current tree scan passed with no leaks.
- Gitleaks history scan passed across 73 commits with no leaks.
- Semgrep OWASP and secrets scan passed with 0 findings.
- Trivy filesystem HIGH/CRITICAL scan passed.

### WS4 Order Concurrency

- `OrderConcurrencyTest` was added to prove the data/transaction acceptance points for order creation concurrency.
- `concurrentUniqueOrderKeysDoNotOversellAvailableStock` proves concurrent unique idempotency keys cannot create more orders than available stock.
- `concurrentDuplicateIdempotencyKeyReturnsOneOrderWithoutSecondStockDeduction` proves a concurrent duplicate idempotency key returns one order without a second stock deduction.
- Focused order verification passed: 49 tests, 0 failures.
- Full Maven verification also included `OrderConcurrencyTest`: 2 tests, 0 failures.

### WS5 Frontend SPA

- `scripts/verify-ws5-frontend.ps1` passed.
- npm audit passed with 0 vulnerabilities at audit level high.
- Prettier format check passed.
- TypeScript/Vite production build passed.
- ESLint passed.
- API contract check passed.
- Playwright/axe accessibility passed: 3 tests, 0 failures.
- Lighthouse desktop gate passed:
  - Performance: 100
  - Accessibility: 100
  - Best practices: 100
  - SEO: 100
  - Largest Contentful Paint: 594 ms

### WS6 Observability

- `scripts/verify-ws6-observability.ps1` passed.
- Helm values define a 99.9% availability SLO over 30 days.
- PrometheusRule includes fast and slow error-budget burn alerts.
- Grafana dashboard includes SLO availability and error-budget burn-rate panels.
- Documentation covers the WS6 SLO and burn-rate posture.

### WS7 DevOps And GitOps

- `scripts/verify-ws7-devops.ps1 -RequireHelm -DownloadHelmIfMissing` passed.
- Helm lint passed.
- Helm template rendering passed for dev, staging, and production values.
- `scripts/verify-kyverno-supply-chain.ps1` passed.
- Runtime image supply-chain scan passed:
  - Command: `scripts/verify-runtime-image-supply-chain.ps1 -SshTarget lly@192.168.119.129 -ImageRef monkey-shop-myshop:latest -SkipDbUpdate`
  - Exported VM runtime image digest: `85b125f729b92b1331c79dd66eea0af963e2899992416f4dffbf3e4ca0969a8d`
  - Report: `target/runtime-supply-chain/trivy-runtime-image.json`
  - Re-verified after VM runtime stabilization; Trivy completed without blocking HIGH/CRITICAL vulnerability, secret, or misconfiguration findings.

### WS8 Anti-Abuse And Data Protection

- `scripts/verify-ws8-security.ps1` passed.
- Runtime API security smoke passed for Compose at `http://192.168.119.129:8888`.
- Runtime API security smoke passed for MicroK8s direct NodePort at `http://192.168.119.129:30143`, including the optional rate-limit probe.
- Runtime API security smoke passed for Argo CD GitOps NodePort at `http://192.168.119.129:30209`, including the optional rate-limit probe.
- Runtime data-protection gate passed on the VM Compose deployment:
  - Flyway minimum successful version: 18
  - `APP_PII_ENCRYPTION_ENABLED=true`
  - `APP_PII_ALLOW_PLAINTEXT_READ=false`
  - `APP_PII_BACKFILL_ENABLED=false`
  - No unprotected populated PII values were found in the checked tables.
  - Populated phone blind indexes were valid 64-character HMAC values.

## Runtime Deployment Evidence

### Docker Compose VM

- SSH target: `lly@192.168.119.129`
- Docker Compose app container: `monkey-app`, status healthy.
- Public VM URL: `http://192.168.119.129:8888`
- Compose configuration renders with a default MySQL URL using `sslMode=REQUIRED`; the dev profile also requires encrypted MySQL transport.
- `scripts/verify-runtime-smoke.ps1 -BaseUrl http://192.168.119.129:8888` passed.
- `scripts/verify-runtime-api-security.ps1 -BaseUrl http://192.168.119.129:8888` passed.

### MicroK8s Direct Helm Runtime

- MicroK8s status: running.
- Node: `lly-vmware-virtual-platform`, status Ready.
- Namespace: `monkeyshop-dev`
- Deployment: `monkeyshop-dev`, 1/1 available.
- Data namespace `monkeyshop-data` includes in-cluster MySQL and Redis Services; the dev runtime points Redis-backed auth, JWT, and rate-limit state at `redis.monkeyshop-data.svc.cluster.local`.
- NodePort: `30143`
- `scripts/verify-microk8s-dev-runtime.ps1 -SshTarget lly@192.168.119.129 -SkipDeploy -RunApiSecurityProbe` passed.
- `scripts/verify-microk8s-dev-runtime.ps1 -SshTarget lly@192.168.119.129 -RunApiSecurityProbe` also passed after reconciling the runtime to the in-cluster Redis topology.

### Argo CD GitOps Runtime

- Application: `monkeyshop-gitops-dev`
- Sync status: Synced.
- Health status: Healthy.
- Synced revision: `efb0627186d1a1ca29e9aa88332bd8923ee49700`
- NodePort: `30209`
- `scripts/verify-argocd-microk8s-gitops.ps1 -SshTarget lly@192.168.119.129 -RunApiSecurityProbe` passed.
- The GitOps runtime also uses `redis.monkeyshop-data.svc.cluster.local` for Redis-backed app state; Argo CD's own Redis runs in-cluster with `imagePullPolicy: IfNotPresent` to avoid Docker Hub pull flakiness on the VM.

## Open External Proof

These items are not proven by the current local VM and repository evidence, and should not be claimed complete until the relevant external systems are available:

The operator has indicated that no additional external resources are available in this thread, so these proof items are blocked until an external DNS/TLS edge, SonarQube/SonarCloud configuration, production-like clusters, and live third-party provider credentials/endpoints exist.

- Public DNS and TLS edge verification with `scripts/verify-public-edge-security.ps1 -BaseUrl https://<public-domain>`:
  - HTTPS-only redirect
  - TLS 1.3 negotiation
  - HSTS preload posture
  - SecurityHeaders-style A+ response header posture
- External SonarQube or SonarCloud Quality Gate A:
  - Requires configured `SONAR_TOKEN`, project key, and reachable Sonar host.
  - Manual gate: `scripts/verify-sonarqube-quality-gate.ps1`.
- Real staging and production cluster reconciliation:
  - The Helm chart renders staging/prod and GitOps assets exist, but only the VM dev MicroK8s runtime has been live-verified here.
- Real Vault/KMS, External Secrets, Turnstile, Sentry, OTel collector, Loki, Tempo/Jaeger, and managed database integrations:
  - Repository config and tests cover the integration posture.
  - Live third-party provider credentials and production endpoints must be supplied and verified separately.
