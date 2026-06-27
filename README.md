# MonkeyShop

MonkeyShop is a Spring Boot 3.5 / Java 21 demo shop that is being hardened toward a modular, production-grade e-commerce system.

## Current Runtime

- Backend: Spring Boot 3.5.16, Java 21, Spring Security, Spring Data JPA, MySQL 8
- Frontend: legacy static HTML pages with Vue 3 CDN, pending migration to a Vite Vue 3 SPA
- Container: multi-stage Docker build with a non-root runtime user and an actuator healthcheck

## Required Local Environment

Do not commit secrets. Provide runtime values with environment variables or a secret manager.

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:DB_URL = "jdbc:mysql://localhost:3306/monkeyshop?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=true&requireSSL=true&verifyServerCertificate=true"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "<local-db-password>"
$env:ADMIN_INIT_PASSWORD = "<strong-initial-admin-password>"
$env:SESSION_COOKIE_SECURE = "false"
$env:APP_UPLOAD_PATH = "uploads/images"
$env:APP_UPLOAD_VIRUS_SCAN_ENABLED = "false"
$env:NVD_API_KEY = "<optional-but-recommended-for-dependency-check>"
```

`ADMIN_INIT_PASSWORD` is only used when the admin table is empty. The application refuses to bootstrap a default administrator without this variable.
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

Without `NVD_API_KEY`, the full gate passes `-DnvdApiDelay=8000` by default. Override with `-UnauthenticatedNvdDelayMs` if your network needs a different cadence. The Maven phase has a 30 minute timeout so dependency-check/NVD hangs fail clearly instead of leaving background Java processes; override with `-MavenTimeoutSeconds` for a planned fresh database hydration.

GitHub Actions runs `.github/workflows/ws1-security.yml` on pushes and pull requests. The fast job runs Maven with dependency-check skipped plus literal-risk scanning, gitleaks current/history, Semgrep, and Trivy. The full dependency-check job requires a repository secret named `NVD_API_KEY`; it fails clearly when that secret is not configured.

## Docker

Create a local `.env` file outside version control or export the variables in your shell:

```powershell
$env:MYSQL_ROOT_PASSWORD = "<strong-root-password>"
$env:MYSQL_PASSWORD = "<strong-app-db-password>"
$env:ADMIN_INIT_PASSWORD = "<strong-initial-admin-password>"
$env:SESSION_COOKIE_SECURE = "false"
docker compose up -d --build
```

The compose file requires database and admin bootstrap secrets and does not provide default passwords. It also starts ClamAV and enables upload virus scanning for the app container by default.

## TLS Edge

Spring Boot listens on the internal application port. Terminate public TLS 1.3 at a managed load balancer, ingress controller, or the Nginx baseline in `deploy/nginx/monkeyshop.conf`. Production and staging profiles trust forwarded headers so secure cookies and generated URLs reflect the original HTTPS request.

## Security Baseline Notes

- `app.jar`, `code.txt`, local uploads, private keys, and `.env*` files are ignored and must not be committed.
- Runtime configuration lives in YAML profiles plus environment variables or SOPS-managed encrypted secret files.
- Uploaded images are stored outside the source tree through `APP_UPLOAD_PATH` and served from `/images/**` with packaged default images as fallback.
- CSRF is enabled with a cookie token, and legacy pages attach `X-XSRF-TOKEN` for unsafe same-origin requests.
- API authorization is centralized in Spring Security using the existing session identity until the WS2 JWT/RBAC migration.
- Image upload now validates upload type, size, magic number, Tika-detected MIME type, ClamAV scan results when enabled, image dimensions, and normalized destination paths.
- Admin bootstrap, registration, and password changes enforce the shared password complexity policy before hashing.
- Password reset is fail-closed until an OTP provider is implemented.
