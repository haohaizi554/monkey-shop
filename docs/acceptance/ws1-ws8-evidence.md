# WS1-WS8 Acceptance Evidence

Date: 2026-07-22

This is the current evidence log for the original WS1-WS8 objective. Local repository and workstation acceptance is green. The full production Definition of Done is not yet proven because public-edge, external SaaS, image-signing, and real staging/production-cluster evidence is unavailable.

## Umbrella Acceptance Entry Point

Run `scripts/verify-ws1-ws8-acceptance.ps1 -IncludeRuntimeDataProtection` for the repeatable local repository, frontend, security, runtime, and encrypted-data gates. Public-edge, Sonar, VM, and cluster evidence remains opt-in through the script's explicit external-proof switches and must only be reported when those integrations are available.

## Delivery Stage Checklist

- [x] Stage 9C-9D: replace the legacy UI with the Vite/Vue/TypeScript application, responsive consumer/admin workspaces, dark mode, i18n, and mascot assets.
- [x] Stage 10: run MySQL, Redis-compatible state, Spring Boot, and Vite directly on the workstation.
- [x] Stage 11-1: cover the consumer/admin route surface and UI-consumed API clients.
- [x] Stage 11-2: harden request, authorization, pagination, identifier, streaming, and workflow boundaries.
- [x] Stage 11-3: satisfy local quality, mutation, dependency, and security scanner gates.
- [x] Stage 11-4: verify the local runtime, accessibility, visual baselines, performance, rate limiting, and encrypted PII.
- [x] Stage 11-5: refresh this requirement-by-requirement evidence log.
- [x] Stage 11-5A: map the first 68 incremental commits into five contiguous push batches with per-stage tasks and verification checkpoints in [phased-push-checklist.md](./phased-push-checklist.md).
- [x] Stage 11-6: fetch the trusted remote and verify that all five delivery targets plus the one-commit UI follow-up are present through `d6a0b99d` (69 incremental commits from `6a7a7ce4`).
- [ ] Stage 11-7: commit and push the post-delivery audit patch, then verify its newly enabled branch CI workflows on GitHub.

## Current Local Evidence

### Backend Quality

- Maven `test` completed successfully. After the focused acceptance additions, the current Surefire report set contains 1,714 tests, 0 failures, 0 errors, and 41 skipped tests.
- `scripts/verify-quality-reports.ps1 -RequireDependencyCheckReport` passed:
  - JaCoCo line coverage: 84.88% (13,958/16,444).
  - PITest mutation coverage: 85.36% (1,102/1,291).
  - PITest mutated-class line coverage: 96.13% (1,241/1,291).
  - SpotBugs findings: 0.
  - OWASP dependency-check blocking vulnerabilities: 0.
- Spring Boot is 3.5.16 and source/bytecode compatibility is Java 21.

### WS1 Security Baseline

- `scripts/verify-ws1-security.ps1 -SkipMaven -SkipDependencyCheck -SkipTrivyDbUpdate -OutputDir target/ws1-security-acceptance` passed.
- The current-tree scanner now snapshots only tracked and non-ignored repository candidates. Ignored workstation secrets, databases, logs, and generated output are not mistaken for committable source.
- Scanner reports contain:
  - Gitleaks current-tree findings: 0.
  - Gitleaks Git-history findings: 0.
  - Semgrep findings: 0.
  - Trivy HIGH/CRITICAL vulnerabilities: 0.
  - Trivy HIGH/CRITICAL misconfigurations: 0.
  - Trivy secrets: 0.

### WS5 Frontend

- npm audit: 0 vulnerabilities. The successful run used the approved Clash proxy at `127.0.0.1:7890` because the WLAN DNS path was unstable.
- Prettier, TypeScript/Vite production build, ESLint, and the API contract gate passed.
- API contract coverage: 19 modules and 113 UI-consumed clients.
- Vitest: 29 files and 151 tests passed.
- Playwright UI smoke: 57 route/viewport checks passed across desktop, tablet, and mobile.
- Playwright/axe: 34 WCAG checks passed; the 7 visual-only tests are excluded from the axe run.
- Visual regression: 7 groups and 33 stored snapshots passed without updating the expected images.
- The design-token contract scans every shared CSS file and every Vue style block; raw color literals are confined to the semantic token registry.
- Lighthouse desktop gate passed:
  - Performance: 96.
  - Accessibility: 100.
  - Best practices: 100.
  - SEO: 100.
  - Largest Contentful Paint: 1,336 ms.
- A finite retry handles only Chromium `net::ERR_NETWORK_CHANGED`. The original failures were correlated with Windows WLAN disconnect/reconnect events; route readiness and snapshot assertions remain unchanged.

### WS6-WS8 Repository Gates

- `scripts/verify-ws6-observability.ps1` passed.
- `scripts/verify-ws7-devops.ps1 -RequireHelm -DownloadHelmIfMissing` passed, including Helm lint and dev/staging/prod rendering.
- `scripts/verify-kyverno-supply-chain.ps1` passed.
- `scripts/verify-ws8-security.ps1` passed.
- Main, dev, staging, and production configuration now default PII encryption on and legacy plaintext reads off. Backfill remains disabled by default.
- Eager first-viewport mascot assets now advertise high fetch priority; the regression is covered by the mascot component test and the Lighthouse gate.

## Local Runtime Evidence

The current workstation deployment is direct, not Docker-based:

| Service | Endpoint | Current PID |
| --- | --- | ---: |
| MySQL | `127.0.0.1:3306` | 7220 |
| Redis-compatible service | `127.0.0.1:6379` | 15976 |
| Spring Boot | `http://127.0.0.1:8888` | 14148 |
| Vite | `http://127.0.0.1:5173` | 40720 |

`scripts/verify-local-runtime.ps1 -RunRateLimitProbe` passed and proved:

- health, SPA/static assets, security headers, trace IDs, Prometheus metrics, anonymous catalog access, protected API rejection, captcha metadata, honeypot isolation, and an actual 429 rate-limit response;
- 124 OpenAPI operations;
- a real Chromium storefront render and bootstrap-admin authentication;
- strict authenticated PII ciphertext validation through `scripts/verify-local-data-protection.ps1 -RequirePopulatedPii`.

### Local PII Migration

- A pre-migration MySQL backup was created at `C:\Users\MemoryLeak\AppData\Local\MonkeyShop\backups\before-pii-backfill-clean-20260722-100539.sql`.
- Backup size: 409,765 bytes.
- Backup SHA-256: `EACB20BBC0A088D137D426A349A2133AC07B962B956AADFA5FE33B3B92DAC4D6`.
- The controlled one-time backfill rewrote 144 rows: users 2, addresses 42, orders 80, reviews 20.
- Post-backfill authenticated audit: 349 populated values, 349 authenticated ciphertexts, 0 unprotected values, and 0 blind-index mismatches.
- The backfill flag is off and plaintext reads fail closed after migration.

## Requirement Status

| Workstream | Locally proven | Still requires external proof |
| --- | --- | --- |
| WS1 | repository scanners, dependency gate, security configuration | public TLS 1.3, HSTS preload, and SecurityHeaders A+ |
| WS2 | centralized authorization and ownership/RBAC/MFA workflow tests | independent penetration test and live provider acceptance |
| WS3 | ArchUnit, Checkstyle, Spotless, SpotBugs, DTO/port boundaries | SonarQube/SonarCloud Quality Gate A |
| WS4 | concurrency/idempotency/precision/migration/upload tests | production-scale load and rollback exercise |
| WS5 | build, lint, contracts, unit, axe, visual, responsive, i18n, dark mode, Lighthouse | public CSP/TLS deployment check |
| WS6 | JSON logs, trace IDs, metrics, audit persistence, Helm dashboards/alerts | live Loki, Tempo/Jaeger, Sentry, and 30-day SLO evidence |
| WS7 | Docker/Helm/Argo/Kyverno artifacts and rendered manifests | real staging/prod reconciliation, pushed digest, cosign signature, canary rollback drill |
| WS8 | local 429 probe, encrypted database, blind indexes, key-rotation tests | live Turnstile, Vault/KMS, WAF/bot provider, TDE/backup-key integration |

## Open External Proof

The following configuration is absent from the current workstation environment: `MONKEYSHOP_PUBLIC_URL`, `SONAR_TOKEN`, `SONAR_HOST_URL`, `SENTRY_DSN`, `APP_TURNSTILE_SECRET_KEY`, `APP_PII_VAULT_TOKEN`, and `OTEL_EXPORTER_OTLP_ENDPOINT`.

Therefore these claims remain open and must not be represented as complete:

1. Public DNS/TLS 1.3, HTTPS redirect, HSTS preload, and SecurityHeaders A+.
2. Sonar Quality Gate A with 0 new bugs/vulnerabilities and duplication below 3%.
3. Live Vault/KMS, Turnstile, Sentry, OpenTelemetry collector, Loki, and Tempo/Jaeger integration.
4. Real staging and production Argo CD reconciliation, signed immutable image admission, and canary rollback.
5. A measured 99.9% availability window and MTTR drill.

## Acceptance Verdict

The workstation build is ready for local acceptance and the repeatable local gates are green. The initial staged delivery is present on the trusted remote through `d6a0b99d`; the post-delivery audit patch and its remote CI evidence are still pending. The original production Definition of Done remains incomplete until the external proof above is supplied and executed.
