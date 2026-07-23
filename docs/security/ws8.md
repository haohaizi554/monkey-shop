# WS8 Anti-Abuse and Data Encryption

WS8 adds production anti-automation controls, PII field encryption, and retention hooks without changing the existing order, stock, and return semantics.

## API Rate Limits

`ApiRateLimitFilter` enforces Redis-backed counters in staging and production, with local Bucket4j buckets for dev/test fallback:

- login: 5 requests per minute
- registration: 3 requests per hour
- order creation: 10 requests per minute
- product search/list: 30 requests per minute
- uploads: 10 requests per minute
- default API traffic: 120 requests per minute

Each request is charged against IP, authenticated user, and endpoint policy dimensions. Rejections return HTTP 429 with `Retry-After`.

## Human Verification

Production and staging use Cloudflare Turnstile via:

- `APP_AUTH_CAPTCHA_PROVIDER=turnstile`
- `APP_TURNSTILE_SITE_KEY`
- `APP_TURNSTILE_SECRET_KEY`
- `APP_TURNSTILE_EXPECTED_HOSTNAME`

The backend validates tokens through Siteverify, checks the expected action, and burns every token for 60 seconds before verification so failed tokens cannot be replayed. Current action names are:

- `login`
- `register`
- `change-password`
- `password-reset-request`
- `password-reset`

Dev keeps `APP_AUTH_CAPTCHA_PROVIDER=local` so legacy image captcha tests and local demos remain runnable.

## PII Encryption

The JPA `EncryptedStringAttributeConverter` stores configured PII fields as Tink-backed AES-GCM ciphertext with per-write IVs:

- `User.phone`
- `User.email`
- `User.totpSecret` (`user.totp_secret`)
- `Address.receiverName`
- `Address.phone`
- `Address.detailAddress`
- `Order.buyerName`
- `Order.receiverName`
- `Order.receiverPhone`
- `Order.addressSnapshot`
- `OrderReview.content` (`order_review.content`)

Phone-like fields also maintain HMAC-SHA256 blind indexes:

- `user.phone_hmac`
- `address.phone_hmac`
- `orders.receiver_phone_hmac`

Required runtime key contract:

- `APP_PII_KEY_PROVIDER=env` reads `APP_PII_AES_KEY_BASE64` and `APP_PII_HMAC_KEY_BASE64`; this mode is for dev and one-off migration tools.
- `APP_PII_PREVIOUS_AES_KEYS_BASE64` accepts optional `version=base64` entries separated by commas or semicolons so rotated deployments can decrypt old ciphertext while writing only the active `APP_PII_KEY_VERSION`.
- `APP_PII_KEY_PROVIDER=vault-transit` asks Vault Transit to decrypt `APP_PII_VAULT_AES_CIPHERTEXT` and `APP_PII_VAULT_HMAC_CIPHERTEXT` into memory at startup. The KEK remains in Vault/KMS.
- `APP_PII_VAULT_ADDR`, `APP_PII_VAULT_TOKEN`, and `APP_PII_VAULT_TRANSIT_KEY` identify the Transit key-release endpoint.
- `APP_PII_VAULT_PREVIOUS_AES_CIPHERTEXTS` accepts optional `version=vault-ciphertext` entries; each value is unwrapped through Vault Transit and retained only in memory for legacy decryption.
- `APP_PII_KEY_VERSION`: key label used in ciphertext metadata
- `APP_PII_KEY_CREATED_AT`: creation timestamp for the active DEK
- `APP_PII_ROTATION_ENFORCE=true` and `APP_PII_ROTATION_MAX_AGE=PT2160H` enforce the 90-day rotation window in staging and production

Staging and production set `APP_PII_ENCRYPTION_ENABLED=true`, `APP_PII_KEY_PROVIDER=vault-transit`, `APP_PII_ALLOW_PLAINTEXT_READ=false`, and rotation enforcement on. Key rotation is versioned by `APP_PII_KEY_VERSION`; mint a new wrapped DEK in Vault/KMS, update the ExternalSecret values and creation timestamp, run a backfill rewrite with the previous AES key configured, then retire the old version after verification. Existing legacy `enc:v1:<version>:<iv>:<ciphertext>` rows remain readable during migration; new writes use `enc:v1:<version>:tink:<ciphertext>`.

## Native Windows Vault Acceptance

The Docker-free workstation path uses persistent Vault 2.0.3 file storage on
`127.0.0.1:8200`. Bootstrap artifacts are pinned and SHA-256 checked. Operator
material and generated service credentials live under
`%LOCALAPPDATA%\MonkeyShop\support\secrets` with inheritance removed and access
limited to the current Windows user and `SYSTEM`.

```powershell
.\scripts\bootstrap-local-support.ps1 -ProxyUri http://127.0.0.1:7890
.\scripts\start-local.ps1 -WithProductionSupport -StartupTimeoutSeconds 600
.\scripts\status-local-support.ps1
.\scripts\verify-local-support.ps1 -SkipStart
```

On first use, `start-local.ps1` passes `-AdoptEnvironmentPiiKeys`. The support
script validates the existing `APP_PII_AES_KEY_BASE64` and
`APP_PII_HMAC_KEY_BASE64` values as 32-byte keys and wraps them through Transit.
This preserves readability of the existing encrypted MySQL rows. It records only
the Vault ciphertexts and a key-source marker, then removes both raw key variables
before launching Spring.

The generated application token is bound only to
`transit/decrypt/monkeyshop-pii`; an encrypt request must return HTTP 403. The
semantic verifier also unwraps both 32-byte DEKs, performs SeaweedFS S3 object
write/stat/read/presigned-read/delete, creates and removes a fresh random startup
bucket, and requires ClamAV to accept a clean sample and reject EICAR.
`APP_INTEGRATIONS_STARTUP_READINESS_REQUIRED=true` adds a Spring startup probe
for the S3 bucket and ClamAV PONG. The workstation launcher additionally sets
`APP_INTEGRATIONS_STARTUP_CREATE_STORAGE_BUCKET=true`; its application default is
`false`, so production infrastructure remains fail-closed. An unavailable
required dependency aborts startup without leaving an HTTP listener.

This local Vault is workstation acceptance evidence, not production KMS or
separation-of-duties evidence. Production still requires TLS, audited unseal and
recovery procedures, managed identity, external secret delivery, and independent
operator custody.

## MicroK8s Development Acceptance

`scripts/verify-microk8s-dev-runtime.ps1` and `scripts/verify-argocd-microk8s-gitops.ps1` keep PII encryption enabled in their disposable development namespaces. On the first deployment they generate independent 256-bit AES and HMAC material and store it, the key version, and its creation timestamp in the namespace runtime Secret. Later deployments read and reuse that material before applying the Secret, so a routine redeployment cannot silently orphan existing ciphertext. After Helm or Argo reports the workload ready, each verifier reads the effective Pod environment and fails unless encryption is on, plaintext reads and backfill are off, and a key version is present. Secret values are never written to verifier output.

This `env` provider is limited to the development acceptance path. Staging and production continue to require Vault Transit through ExternalSecret. If a development database predates encryption and contains plaintext, do not bypass the fail-closed checks: take a backup and run the controlled backfill above, or explicitly recreate only disposable development data after operator approval.

## Legacy Plaintext Backfill

Before setting `APP_PII_ALLOW_PLAINTEXT_READ=false` in an environment that already has customer data, run one controlled deployment with `APP_PII_ENCRYPTION_ENABLED=true`, `APP_PII_ALLOW_PLAINTEXT_READ=true`, and `APP_PII_BACKFILL_ENABLED=true`. The startup runner rewrites legacy plaintext values in `user`, `address`, `orders`, and `order_review` through `PiiPlaintextBackfillService`, including `user.totp_secret` and `order_review.content`. It authenticates existing ciphertext with the configured current or previous key before leaving it unchanged; an unavailable historical key aborts the run instead of double-encrypting data. Rows are read with stable-ID keyset pagination and every bounded page commits in an independent transaction, avoiding an unbounded heap load or one database-wide migration transaction. Phone blind indexes are recalculated only from plaintext phone values.

After the runner logs zero remaining plaintext updates in a follow-up run, switch `APP_PII_BACKFILL_ENABLED=false` and then enforce `APP_PII_ALLOW_PLAINTEXT_READ=false`. Keep the database snapshot and the backfill log counts with the release evidence so PIPL/GDPR reviewers can verify that exported PII is ciphertext-only.

## Runtime Compose PII Migration Runbook

PII backfill changes existing database values and restarts the application, so it requires explicit operator approval before execution. Do not run it as part of routine smoke checks.

For compose-hosted environments, use `scripts/run-pii-backfill-compose.ps1` as the controlled wrapper. Its default mode is a dry-run that performs preflight checks and prints the plan; data rewrite is blocked unless `-Execute -AcknowledgeDataRewrite 'I understand this rewrites PII data'` is supplied after approval. The wrapper performs the backup, hidden key-material checks, one-time migration flags, app restart, strict-mode reset, and final `verify-runtime-data-protection.ps1` gate.

1. Capture a database backup before changing flags:

   ```bash
   backup_dir="/home/lly/monkey-shop-backups/pre-pii-encryption-$(date +%Y%m%d%H%M%S)"
   mkdir -p "$backup_dir"
   docker compose -p monkey-shop exec -T mysql sh -c 'mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
     > "$backup_dir/monkeyshop.sql"
   test -s "$backup_dir/monkeyshop.sql"
   ```

2. Generate `APP_PII_AES_KEY_BASE64` and `APP_PII_HMAC_KEY_BASE64` outside the repository. Store them only in `.env`, Vault, or the environment-specific secret manager; never print them in logs or commit them.

3. Run one migration deployment with:

   - `APP_PII_ENCRYPTION_ENABLED=true`
   - `APP_PII_KEY_PROVIDER=env` for compose migration, or `vault-transit` in managed environments
   - `APP_PII_ALLOW_PLAINTEXT_READ=true`
   - `APP_PII_BACKFILL_ENABLED=true`

4. Wait for the startup log line `PII plaintext backfill completed` and keep the users/addresses/orders/reviews update counts with release evidence.

5. Disable migration mode and restart:

   - `APP_PII_BACKFILL_ENABLED=false`
   - `APP_PII_ALLOW_PLAINTEXT_READ=false`

6. Run:

   ```powershell
   .\scripts\verify-runtime-data-protection.ps1 -ComposeProject monkey-shop -RequirePopulatedPii
   ```

   On Linux compose hosts or VMs without PowerShell, run:

   ```bash
   bash scripts/verify-runtime-data-protection.sh --compose-project monkey-shop --require-populated-pii
   ```

   These verifiers first check Flyway version, runtime flags, complete `enc:v1:` envelope structure, and blind-index shape. They then launch the minimal `PiiCiphertextAuditCli` inside the application runtime so every protected value, including `user.totp_secret` and `order_review.content`, must pass AEAD authentication with the configured current or historical key. The CLI decrypts authenticated phone ciphertext only in process memory and recomputes each blind index; it logs field-level aggregate counts without printing secrets or raw PII. A forged envelope, missing historical key, plaintext value, or mismatched blind index fails the gate.

## MySQL TDE and Backup Encryption

Production MySQL must enable InnoDB TDE through a Vault-backed keyring plugin or the managed database provider's KMS integration. Backup jobs must encrypt dumps with a GPG/KMS key that is separate from the PII KEK and from the production database TDE key. Store backup key access in a separate Vault path such as `monkeyshop/backup`, with restore approval/audit separate from application deploy access.

## WAF and Slowloris Guardrails

`deploy/nginx/monkeyshop.conf` sets TLS 1.3, HSTS preload, `client_body_timeout`, `client_header_timeout`, `limit_req`, `limit_conn`, and `limit_rate`. Honeypot endpoints `/api/.env` and `/admin/secret` return 403 at the edge; the app filter also blocks a tripping IP for 24 hours.

## Compliance Auditability and Retention

`PiiRetentionService` scrubs PII from completed/refunded order snapshots after `APP_PII_ORDER_RETENTION` (default about six months) and exposes `/api/user/forget-me` for authenticated users. The endpoint deletes profile phone/email/nickname, address PII, and order snapshot PII while preserving order financial/status records for auditability.

Administrators with `ADMIN_DASHBOARD_READ` can use `GET /api/stats/audit-trace?traceId=<traceId>` to retrieve sanitized audit events for an incident trace without exposing raw subject identifiers. This gives PIPL/GDPR audit reviewers a bounded investigation path across API responses, audit rows, Loki logs, and Tempo spans.
