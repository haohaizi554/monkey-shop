# WS1-WS8 Acceptance Evidence

Date: 2026-07-22
Updated: 2026-07-23

This is the current evidence log for the original WS1-WS8 objective. Local repository and workstation acceptance is green, and the upgrade branch has remote CI, security scanning, GHCR push, and cosign-signing evidence through the final tracking hotfix. The full production Definition of Done is not yet proven because public-edge, external SaaS, signed-image cluster admission, and real staging/production-cluster evidence is unavailable.

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
- [x] Stage 11-7: commit the post-delivery UI, account-security, asset-provenance, runtime, and CI-trigger audit patch as `9866c1db`.
- [x] Stage 11-8: make the aggregate local acceptance gate deterministic under Clash, native Windows services, and expected negative Helm checks; then run the complete local umbrella gate without manual skips.
- [x] Stage 11-9: remove the PII-encryption bypass from both MicroK8s acceptance paths, preserve development key material across redeployments, and guard the effective Pod flags through the WS8 gate.
- [x] Stage 11-10: deploy the native Windows Collector, Prometheus, Loki, Tempo, and Grafana stack; verify real Spring traces, logs, span metrics, service graphs, and provisioned data sources.
- [x] Stage 11-11: deploy native Windows Vault, SeaweedFS S3, and ClamAV; adopt the existing PII keys into Transit; prove semantic operations, application startup, and fail-closed dependency handling.
- [x] Stage 11-11A: stop recursive tracking-profile summary growth, add a 100-event regression, and repeat real-browser runtime, API, rate-limit, OpenAPI, and populated-PII acceptance.
- [x] Stage 11-12: push all post-delivery batches and verify CI/CD, WS1 Security Gate, and CodeQL through the final tracking hotfix.
- [ ] Stage 11-13: merge the verified remote upgrade branch into `main`, push `main`, and rerun the main-branch gates.

## Current Local Evidence

### Backend Quality

- The split final Maven gate completed successfully. The current Surefire report set contains 1,724 tests, 0 failures, 0 errors, and 41 skipped tests across 248 suites.
- `scripts/verify-quality-reports.ps1 -RequireDependencyCheckReport` passed:
  - JaCoCo line coverage: 84.83% (13,992/16,495).
  - PITest mutation coverage: 85.32% (1,116/1,308).
  - PITest report line coverage: 96.10% (1,257/1,308).
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

- npm audit: 0 vulnerabilities. The successful run used the approved Clash proxy at `127.0.0.1:7890` because the WLAN DNS path was unstable; the WS5 verifier now preserves configured proxy exclusions and always adds `127.0.0.1`, `localhost`, and `::1` for its local Playwright servers.
- Prettier, TypeScript/Vite production build, ESLint, and the API contract gate passed.
- API contract coverage: 19 modules and 113 UI-consumed clients.
- Vitest: 29 files and 151 tests passed.
- Playwright UI smoke: 57 route/viewport checks passed across desktop, tablet, and mobile.
- Playwright/axe: 34 WCAG checks passed; the 7 visual-only tests are excluded from the axe run.
- Visual regression: 7 groups and 33 stored snapshots passed without updating the expected images.
- The design-token contract scans every shared CSS file and every Vue style block; raw color literals are confined to the semantic token registry.
- Lighthouse desktop gate passed:
  - Performance: 95.
  - Accessibility: 100.
  - Best practices: 100.
  - SEO: 100.
  - Largest Contentful Paint: 1,475 ms.
- A finite retry handles only Chromium `net::ERR_NETWORK_CHANGED`. The original failures were correlated with Windows WLAN disconnect/reconnect events; route readiness and snapshot assertions remain unchanged.

### WS6-WS8 Repository Gates

- `scripts/verify-ws6-observability.ps1` passed.
- `scripts/verify-ws7-devops.ps1 -RequireHelm -DownloadHelmIfMissing` passed, including Helm lint and dev/staging/prod rendering.
- The WS7 verifier clears the native exit code left by its expected all-zero-digest rejection before returning success to an aggregate PowerShell caller.
- `scripts/verify-kyverno-supply-chain.ps1` passed.
- `scripts/verify-ws8-security.ps1` passed.
- Main, dev, staging, and production configuration now default PII encryption on and legacy plaintext reads off. Backfill remains disabled by default.
- Both direct Helm and Argo CD MicroK8s development verifiers now enable PII encryption, generate first-deploy 256-bit AES/HMAC keys, preserve the Secret-backed keys/version/timestamp across redeployments, and reject Pods whose effective encryption/plaintext/backfill flags are not fail-closed. The generated Bash scripts passed `bash -n`, and both generated values files passed Helm rendering without contacting a real host.
- Eager first-viewport mascot assets now advertise high fetch priority; the regression is covered by the mascot component test and the Lighthouse gate.

## Local Runtime Evidence

The current workstation deployment is direct, not Docker-based:

| Service | Endpoint | Current PID |
| --- | --- | ---: |
| MySQL Windows service | `127.0.0.1:3306` and `127.0.0.1:33060` | 9556 |
| Redis-compatible service | `127.0.0.1:6379` | 15976 |
| Spring Boot | `http://127.0.0.1:8888` | 45788 |
| Vite | `http://127.0.0.1:5173` | 43616 |
| Vault | `http://127.0.0.1:8200` | 43344 |
| SeaweedFS S3 | `http://127.0.0.1:8333` | 40468 |
| ClamAV | `tcp://127.0.0.1:3310` | 48716 |
| OpenTelemetry Collector | `http://127.0.0.1:13133` | 27584 |
| Prometheus | `http://127.0.0.1:9090` | 29348 |
| Loki | `http://127.0.0.1:3100` | 41192 |
| Tempo | `http://127.0.0.1:3200` | 37064 |
| Grafana | `http://127.0.0.1:3000` | 10084 |

All listed listeners are loopback-only. MySQL was validated and restarted with both `bind-address=127.0.0.1` and `mysqlx-bind-address=127.0.0.1`. Repository lifecycle and status scripts now fail closed if a healthy service also exposes a wildcard or non-loopback listener; a temporary `0.0.0.0` negative probe was rejected.

`scripts/verify-local-runtime.ps1 -RunRateLimitProbe` passed and proved:

- health, SPA/static assets, security headers, trace IDs, Prometheus metrics, anonymous catalog access, protected API rejection, captcha metadata, honeypot isolation, and an actual 429 rate-limit response;
- 124 OpenAPI operations;
- a real Chromium storefront render and bootstrap-admin authentication;
- strict authenticated PII ciphertext validation through `scripts/verify-local-data-protection.ps1 -RequirePopulatedPii`.

The gate was repeated on 2026-07-23 after fixing the live `/api/v1/tracking/events` failure caused by recursively growing encrypted profile summaries. The repeated run returned exit code 0, authenticated all 349 populated PII values with 0 unprotected rows and 0 blind-index mismatches, exposed 124 OpenAPI operations, and passed the real Chromium storefront plus bootstrap-admin flow. The focused Tracking/JPA/WS11/schema regression set contains 15 tests with 0 failures and 0 errors.

The aggregate command `scripts/verify-ws1-ws8-acceptance.ps1 -RuntimeBaseUrl http://127.0.0.1:8888 -IncludeRuntimeDataProtection` completed again with exit code 0 on 2026-07-22 in 1,257.8 seconds after the MicroK8s hardening. It ran backend Maven verification, quality reports, WS1 scanners, WS5, WS6, WS7, WS8, Kyverno, runtime smoke/API checks, and the populated local PII audit without Docker or manual skip flags. Its retained workstation log is `target/batch9-full-acceptance.log`; VM, runtime-image, public-edge, and Sonar proof were explicitly reported as open rather than silently treated as passed.

### Native Observability Evidence

`scripts/verify-local-observability.ps1 -TimeoutSeconds 90` passed against the restarted native Windows processes. The gate propagated one W3C trace ID through a real Spring `GET /api/v1/monkeys` server span, found that trace through exact TraceQL search, found a correlated Loki log, queried the Spring Prometheus target, and verified the provisioned Prometheus/Loki/Tempo Grafana data sources. The accepted trace ID was `83a68b859a7242608e85e6057459e408`.

Tempo metrics-generator remote-write is active: the post-gate Prometheus probe returned 30 `traces_spanmetrics_calls_total` series and 2 `traces_service_graph_request_total` series. Collector internal telemetry uses `127.0.0.1:18888`, avoiding the Spring `8888` listener, and Loki advertises only `127.0.0.1` for its single-process ring.

The lifecycle negative gate forced a one-second backend startup timeout. The launcher terminated its verified Maven/Java process tree, and a delayed probe found neither an `8888` listener nor a MonkeyShop backend process. The same bounded cleanup helper is used by Vite, each observability component, and both stop scripts.

### Native Security And Storage Evidence

The native support stack uses pinned Vault 2.0.3, SeaweedFS 4.29, and ClamAV 1.5.3 Windows artifacts with fixed SHA-256 values. Bootstrap downloads use the approved Clash proxy without mutating process-wide proxy variables. Runtime code preserves existing proxy exclusions and adds `127.0.0.1,localhost,::1` before local traffic.

The final WS1 scan reported 0 Gitleaks history findings, 0 Gitleaks current-tree findings, 0 Semgrep findings, and no blocking Trivy result. The ClamAV readiness socket uses the same narrow, documented clamd plaintext-protocol suppression as the existing upload scanner; both endpoints are constrained to local/private-network use.

`scripts/verify-local-support.ps1 -SkipStart` completed with exit code 0 on 2026-07-23. The real-service test first created and removed a fresh random bucket through the application startup verifier, then statted, read, presigned-read, and deleted an S3 object; accepted a clean ClamAV stream and rejected EICAR; decrypted both 32-byte wrapped PII keys; and proved that the application's decrypt-only Vault token receives HTTP 403 for Transit encrypt. The focused WS6, WS8, and startup-verifier run contains 23 tests, 0 failures, 0 errors, and 0 skipped tests.

`scripts/start-local.ps1 -WithProductionSupport -StartupTimeoutSeconds 600` then started Spring with `vault-transit`, SeaweedFS S3, ClamAV, and required startup readiness enabled. `GET /actuator/health` returned `UP`; runtime smoke, API security, and the isolated real 429 pressure probe all passed. A second Spring process on a spare port used an intentionally closed ClamAV port: startup exited nonzero with the expected readiness failure and left no HTTP listener, while the healthy instance remained available.

The first support start adopted the existing workstation AES/HMAC PII keys into Vault Transit, preserving all previously encrypted rows. Only wrapped ciphertexts are passed to Spring and the raw environment keys are removed first. Support secrets and persistent state remain outside Git below `%LOCALAPPDATA%\MonkeyShop\support` with restricted ACLs. After a full support-plus-observability start, invoking `start-local.ps1` again without switches completed in 8.6 seconds, preserved every service PID, and retained both runtime mode flags as `true`.

## Remote Branch Evidence

The upgrade branch is present remotely through `480f433485d72533c3073c9e0ab891f03556035d`. The following completed branch checkpoints are independently queryable in GitHub Actions:

| Checkpoint | Commit | CI/CD | WS1 | CodeQL |
| --- | --- | --- | --- | --- |
| CI and image-gate repair | `93e3d0c0` | `30003439046` | `30003439013` | `30003438999` |
| Batch 9 | `66857fc2` | `30005065330` | `30005065308` | `30005065296` |
| Batch 10A | `3e60d9f1` | `30006714248` | `30006714177` | `30006714212` |
| Batch 10B | `8641d187` | `30008413100` | `30008413158` | `30008413186` |
| Tracking hotfix | `480f4334` | `30010693037` | `30010692977` | `30010693024` |

Every listed run concluded successfully. The Batch 10B image job also completed the Trivy JSON/SARIF gates, code-scanning upload, GHCR push, and keyless cosign signing. SonarQube and Snyk workflows explicitly report credential-aware deferred status on this workstation-owned repository; they do not claim an external Quality Gate result without tokens.

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
| WS6 | JSON logs, trace IDs, metrics, audit persistence, Helm dashboards/alerts, plus a live native Collector/Prometheus/Loki/Tempo/Grafana stack with real Spring spans and service graph metrics | live Sentry and 30-day production SLO evidence |
| WS7 | Docker/Helm/Argo/Kyverno artifacts, rendered manifests, fail-closed MicroK8s generation paths, plus branch-image GHCR push and cosign signature | real staging/prod reconciliation, signed-image admission, production digest pin, and canary rollback drill |
| WS8 | local 429 probe, encrypted database, blind indexes, key rotation, native Vault Transit key release, S3/ClamAV operations, fail-closed startup, and MicroK8s Pod flag contracts | live production Vault/KMS custody, Turnstile, WAF/bot provider, and TDE/backup-key integration |

## Open External Proof

The following external-production configuration is absent from the current workstation environment: `MONKEYSHOP_PUBLIC_URL`, `SONAR_TOKEN`, `SONAR_HOST_URL`, `SENTRY_DSN`, and `APP_TURNSTILE_SECRET_KEY`. A local decrypt-only Vault token is proven for workstation acceptance, but no production Vault/KMS identity or custody evidence is available. Local OTLP is configured explicitly by `start-local.ps1 -WithObservability`.

The previously supplied VM addresses `192.168.147.128` and `192.168.119.129` both timed out on TCP/22 during the latest check, so the hardened MicroK8s scripts could not be executed against a real cluster. GitHub CLI authentication is valid; a fresh fetch confirms the remote upgrade branch at `480f4334`, with all required workflows green through the final tracking hotfix.

Therefore these claims remain open and must not be represented as complete:

1. Public DNS/TLS 1.3, HTTPS redirect, HSTS preload, and SecurityHeaders A+.
2. Sonar Quality Gate A with 0 new bugs/vulnerabilities and duplication below 3%.
3. Production Vault/KMS custody, Turnstile, and Sentry integration; native Vault/SeaweedFS/ClamAV and Collector/Loki/Tempo are locally proven but still require production-environment evidence.
4. Real staging and production Argo CD reconciliation, signed immutable image admission, production digest pinning, and canary rollback. Branch-image push/signing is proven, but cluster admission is not.
5. A measured 99.9% availability window and MTTR drill.

## Acceptance Verdict

The workstation build is ready for local acceptance and the split final local gates are green. All post-delivery work is present on the remote upgrade branch through `480f4334`, and every required branch workflow is green through the final tracking hotfix. The native workstation stack is loopback-complete and repeatable. The remaining delivery step is the fast-forward merge and main-branch verification; the original production Definition of Done remains incomplete until the external proof above is supplied and executed.
