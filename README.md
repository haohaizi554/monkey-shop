# MonkeyShop

MonkeyShop is a Spring Boot 3.5.16 / Java 21 demo shop that is being hardened toward a modular, production-grade e-commerce system.

## Current Runtime

- Backend: Spring Boot 3.5.16, Java 21, Spring Security, Spring Data JPA, MySQL 8
- Frontend: Vite Vue 3 SPA with TypeScript, Pinia, Vue Router, Element Plus, vue-i18n, dark mode, and accessibility checks
- Container: layered multi-stage Docker build with a non-root runtime user, actuator healthcheck, and read-only-rootfs-ready writable mounts

## Required Local Environment

Do not commit secrets. Provide runtime values with environment variables or a secret manager.

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:DB_URL = "jdbc:mysql://localhost:3306/monkeyshop?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=true&requireSSL=true&verifyServerCertificate=true"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "<local-db-password>"
$env:ADMIN_INIT_PASSWORD = "<strong-initial-admin-password>"
$env:ADMIN_TOTP_SECRET = "<base32-totp-secret>"
$env:APP_JWT_SECRET = "<at-least-32-byte-jwt-signing-secret>"
$env:APP_JWT_REQUIRE_REDIS_TOKEN_STORE = "false"
$env:APP_AUTH_REQUIRE_REDIS_STATE = "false"
$env:APP_PASSWORD_RESET_DELIVERY_MODE = "logging"
$env:SESSION_COOKIE_SECURE = "false"
$env:APP_UPLOAD_PATH = "uploads/images"
$env:APP_UPLOAD_VIRUS_SCAN_ENABLED = "false"
$env:NVD_API_KEY = "<optional-but-recommended-for-dependency-check>"
```

`ADMIN_INIT_PASSWORD` and `ADMIN_TOTP_SECRET` are only used when no administrator exists. The application refuses to bootstrap a default administrator without both variables, and it refuses to start with existing administrator rows unless each admin has TOTP MFA enabled with a valid Base32 secret.
`APP_JWT_SECRET` must be at least 32 bytes in staging and production. The dev profile can generate an ephemeral local secret only when explicitly enabled. Staging and production require Redis-backed JWT refresh-token storage and revocation by default with `APP_JWT_REQUIRE_REDIS_TOKEN_STORE=true`; if Redis is unavailable, token validation and issuance fail closed instead of falling back to a single-node map. Staging and production also require Redis-backed auth challenge state with `APP_AUTH_REQUIRE_REDIS_STATE=true` so login rate limits, lockouts, and captcha challenges stay shared across replicas.
Use `APP_PASSWORD_RESET_DELIVERY_MODE=webhook` outside local development and set `APP_PASSWORD_RESET_SMS_WEBHOOK_URL`, `APP_PASSWORD_RESET_EMAIL_WEBHOOK_URL`, and `APP_PASSWORD_RESET_WEBHOOK_SECRET`; reset challenges fail closed when delivery is disabled or misconfigured.
Set `APP_UPLOAD_VIRUS_SCAN_ENABLED=true` only when `CLAMAV_HOST:CLAMAV_PORT` is reachable; enabled scanning rejects uploads if ClamAV is unavailable.
New passwords must be at least 10 characters and include lowercase, uppercase, digit, and special characters with no whitespace.

Schema changes are managed with Flyway from `src/main/resources/db/migration` before Hibernate validates the schema. New databases run from `V1__init_schema.sql`; existing manually-created demo schemas should be backed up and migrated deliberately. Only set `FLYWAY_BASELINE_ON_MIGRATE=true` after confirming the current schema already matches the baseline.

Encrypted secret material belongs under `secrets/*.enc.yaml` using the SOPS/age convention in `secrets/README.md`; plaintext secret files under `secrets/` are ignored.

## Build And Test

```powershell
mvn clean verify
```

The Maven `verify` phase includes OWASP dependency-check and fails the build on CVSS >= 7. If the local vulnerability database has not been hydrated, the first run can take a long time.
Set `NVD_API_KEY` before running `mvn clean verify`; unauthenticated NVD updates are rate-limited and can fail with HTTP 429.

For a quick compile/test/package cycle without the external dependency-check update:

```powershell
mvn "-Ddependency-check.skip=true" clean verify
```

Run the WS1 security gate with:

```powershell
.\scripts\verify-ws1-security.ps1
```

For local iteration when NVD is rate-limited, use:

```powershell
.\scripts\verify-ws1-security.ps1 -SkipDependencyCheck
```

Without `NVD_API_KEY`, the full gate passes `-DnvdApiDelay=8000` by default. Override with `-UnauthenticatedNvdDelayMs` if your network needs a different cadence. If the dependency-check data cache is already hydrated but NVD is unreachable, use `-SkipDependencyCheckUpdate` to run the OWASP dependency-check gate against the cached database. The Maven phase has a 30 minute timeout so dependency-check/NVD hangs fail clearly instead of leaving background Java processes; override with `-MavenTimeoutSeconds` for a planned fresh database hydration.
If Trivy's remote vulnerability database is temporarily unreachable but a local Trivy DB cache is already hydrated, use `-SkipTrivyDbUpdate` for a cached local scan. CI should keep the default fresh-DB behavior.

GitHub Actions runs `.github/workflows/ws1-security.yml` on pushes and pull requests. The fast job runs Maven with dependency-check skipped plus literal-risk scanning, gitleaks current/history, Semgrep, and Trivy. The full dependency-check job requires a repository secret named `NVD_API_KEY`; it fails clearly when that secret is not configured.

## Docker

Create a local `.env` file outside version control or export the variables in your shell:

```powershell
$env:MYSQL_ROOT_PASSWORD = "<strong-root-password>"
$env:MYSQL_PASSWORD = "<strong-app-db-password>"
$env:ADMIN_INIT_PASSWORD = "<strong-initial-admin-password>"
$env:ADMIN_TOTP_SECRET = "<base32-totp-secret>"
$env:APP_JWT_SECRET = "<at-least-32-byte-jwt-signing-secret>"
$env:APP_JWT_REQUIRE_REDIS_TOKEN_STORE = "false"
$env:APP_AUTH_REQUIRE_REDIS_STATE = "false"
$env:APP_PASSWORD_RESET_DELIVERY_MODE = "logging"
$env:SESSION_COOKIE_SECURE = "false"
docker compose up -d --build
```

The compose file requires database and admin bootstrap secrets and does not provide default passwords. It also starts ClamAV and enables upload virus scanning for the app container by default.

## Kubernetes And GitOps

WS7 deployment assets live under `helm/monkeyshop`, `deploy/argocd`, and `deploy/kyverno`; the full operator notes are in `docs/deployment/ws7.md`.

WS8 anti-abuse and PII encryption controls are documented in `docs/security/ws8.md`, including Turnstile actions, rate-limit quotas, PII key requirements, and retention behavior.

```powershell
helm template monkeyshop .\helm\monkeyshop -f .\helm\monkeyshop\values-dev.yaml
helm template monkeyshop .\helm\monkeyshop -f .\helm\monkeyshop\values-staging.yaml
helm template monkeyshop .\helm\monkeyshop -f .\helm\monkeyshop\values-prod.yaml
```

The chart supports standard Deployment mode for dev and Argo Rollouts canary mode for staging/prod, with External Secrets, HPA, PDB, NetworkPolicy, cert-manager Ingress annotations, ServiceMonitor, restricted pod security context, and explicit writable volumes for `/tmp`, `/app/logs`, and `/data/images`. The CI/CD workflow builds, scans, pushes, and cosign-signs images on non-PR refs.

## TLS Edge

Spring Boot listens on the internal application port. Terminate public TLS 1.3 at a managed load balancer, ingress controller, or the Nginx baseline in `deploy/nginx/monkeyshop.conf`. Production and staging profiles trust forwarded headers so secure cookies and generated URLs reflect the original HTTPS request.

## Security Baseline Notes

- `app.jar`, `code.txt`, local uploads, private keys, and `.env*` files are ignored and must not be committed.
- Runtime configuration lives in YAML profiles plus environment variables or SOPS-managed encrypted secret files.
- Uploaded images are stored outside the source tree through `APP_UPLOAD_PATH` and served from `/images/**` with packaged default images as fallback.
- CSRF is enabled with a cookie token, and legacy pages attach `X-XSRF-TOKEN` for unsafe same-origin requests.
- API authorization is centralized in Spring Security with JWT HttpOnly cookies, rotating refresh tokens, replayed refresh-token invalidation, RBAC permission authorities, and method-level `@PreAuthorize` declarations.
- Staging and production require Redis for refresh-token rotation, logout revocation, and password-reset token invalidation; Redis read/write failures fail closed.
- Login rate-limit, lockout, captcha, and password-reset OTP/email-token state is Redis-backed in staging and production; auth flows fail closed if the shared state store is unavailable.
- Image upload now validates upload type, size, magic number, Tika-detected MIME type, ClamAV scan results when enabled, image dimensions, and normalized destination paths.
- Admin bootstrap, registration, and password changes enforce the shared password complexity policy before hashing.
- Password reset uses short-lived SMS OTP plus optional email-token challenges through `PasswordResetDeliveryService`; staging and production use HTTP webhook delivery and fail closed if the provider URLs or shared secret are absent.
