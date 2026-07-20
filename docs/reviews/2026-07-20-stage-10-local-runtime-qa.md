# Stage 10 Native Local Runtime QA

Date: 2026-07-20

## Scope

Stage 10 closes two runtime correctness gaps and makes the complete MonkeyShop
stack repeatable on a native Windows workstation without Docker.

## Correctness fixes

- A successful payment callback or provider query now advances a pending order
  from `PENDING_PAYMENT` to `PAID`. The optimistic transition is idempotent and
  rejects a missing or still-pending order after a failed transition.
- A bootstrap administrator without a phone number no longer enters an
  impossible forced-password-change flow.
- Flyway V55 repairs existing paid-payment/pending-order rows and clears the
  impossible forced-password-change flag for phone-less administrators.
- The administrator password in the local database was reconciled with the
  externalized local configuration using BCrypt strength 12. Password history
  and the last-change timestamp were updated together.

## Native topology

| Service | Local endpoint | Runtime |
| --- | --- | --- |
| MySQL | `127.0.0.1:3306` | MySQL 8.0.41 Windows service |
| Redis API | `127.0.0.1:6379` | Memurai 4.1.8 / Redis API 7.2.12 |
| Backend | `http://127.0.0.1:8888` | Spring Boot on Java 21 |
| Frontend | `http://127.0.0.1:5173` | Vite on Node.js 24 |

Memurai uses loopback-only binding, protected mode, and AOF with
`appendfsync everysec`. Runtime data, logs, uploads, process state, and the
workstation-only environment override live under `%LOCALAPPDATA%\MonkeyShop`.
ClamAV remains disabled for the native development runtime.

## Operator commands

```powershell
.\scripts\start-local.ps1
.\scripts\status-local.ps1
.\scripts\verify-local-runtime.ps1
.\scripts\stop-local.ps1
```

`start-local.ps1` is idempotent and preserves ownership of processes it already
started. `stop-local.ps1` validates recorded process identities and only stops
managed frontend, backend, and Redis processes. MySQL is left running unless
`-StopMySql` is explicitly supplied.

## Verification evidence

- `PaymentApplicationServiceTest,DataInitializerTest`: 79 tests passed.
- Targeted contract/security regression:
  `ApiVersioningContractTest,Ws1SecurityWorkflowTest,MembershipControllerAdminTest`
  passed.
- Maven Surefire: 1,621 tests passed with no test failure or error.
- Frontend ESLint passed.
- Frontend production TypeScript/Vite build passed.
- Frontend Vitest: 23 files and 119 tests passed.
- Member operations Playwright: 4 tests passed.
- Native runtime smoke passed health, liveness, readiness, static assets,
  Prometheus, trace propagation, and security headers.
- Runtime API security passed anonymous reads, authentication barriers, captcha
  configuration, honeypot isolation, and the explicit 429/Retry-After probe.
- OpenAPI exposed 123 operations.
- Real-browser acceptance passed public storefront desktop/mobile checks,
  administrator password plus TOTP login, and the admin, order, payment,
  logistics, and member workspaces without API 5xx responses.

## Remaining quality baseline

The complete Maven verify reaches PIT after all 1,621 tests and preceding
quality checks pass, but the current mutation target now contains 1,365
mutations and scores 79 percent against the unchanged 85 percent threshold.
The last recorded passing baseline covered 821 mutations at 85.75 percent.
Later package growth under the broad `com.example.monkey.shared.*` target
expanded the mutation surface; the Stage 10 payment and bootstrap classes are
outside that PIT target. The threshold was not reduced. The mutation target and
tests need a dedicated baseline refresh before claiming the complete Maven
verify gate as green.
