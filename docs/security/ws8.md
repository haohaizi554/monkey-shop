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
- `Address.receiverName`
- `Address.phone`
- `Address.detailAddress`
- `Order.buyerName`
- `Order.receiverName`
- `Order.receiverPhone`
- `Order.addressSnapshot`

Phone-like fields also maintain HMAC-SHA256 blind indexes:

- `user.phone_hmac`
- `address.phone_hmac`
- `orders.receiver_phone_hmac`

Required runtime key contract:

- `APP_PII_KEY_PROVIDER=env` reads `APP_PII_AES_KEY_BASE64` and `APP_PII_HMAC_KEY_BASE64`; this mode is for dev and one-off migration tools.
- `APP_PII_KEY_PROVIDER=vault-transit` asks Vault Transit to decrypt `APP_PII_VAULT_AES_CIPHERTEXT` and `APP_PII_VAULT_HMAC_CIPHERTEXT` into memory at startup. The KEK remains in Vault/KMS.
- `APP_PII_VAULT_ADDR`, `APP_PII_VAULT_TOKEN`, and `APP_PII_VAULT_TRANSIT_KEY` identify the Transit key-release endpoint.
- `APP_PII_KEY_VERSION`: key label used in ciphertext metadata
- `APP_PII_KEY_CREATED_AT`: creation timestamp for the active DEK
- `APP_PII_ROTATION_ENFORCE=true` and `APP_PII_ROTATION_MAX_AGE=PT2160H` enforce the 90-day rotation window in staging and production

Staging and production set `APP_PII_ENCRYPTION_ENABLED=true`, `APP_PII_KEY_PROVIDER=vault-transit`, `APP_PII_ALLOW_PLAINTEXT_READ=false`, and rotation enforcement on. Key rotation is versioned by `APP_PII_KEY_VERSION`; mint a new wrapped DEK in Vault/KMS, update the ExternalSecret values and creation timestamp, run a backfill rewrite, then retire the old version after verification. Existing legacy `enc:v1:<version>:<iv>:<ciphertext>` rows remain readable during migration; new writes use `enc:v1:<version>:tink:<ciphertext>`.

## MySQL TDE and Backup Encryption

Production MySQL must enable InnoDB TDE through a Vault-backed keyring plugin or the managed database provider's KMS integration. Backup jobs must encrypt dumps with a GPG/KMS key that is separate from the PII KEK and from the production database TDE key. Store backup key access in a separate Vault path such as `monkeyshop/backup`, with restore approval/audit separate from application deploy access.

## WAF and Slowloris Guardrails

`deploy/nginx/monkeyshop.conf` sets TLS 1.3, HSTS preload, `client_body_timeout`, `client_header_timeout`, `limit_req`, `limit_conn`, and `limit_rate`. Honeypot endpoints `/api/.env` and `/admin/secret` return 403 at the edge; the app filter also blocks a tripping IP for 24 hours.

## Compliance Auditability and Retention

`PiiRetentionService` scrubs PII from completed/refunded order snapshots after `APP_PII_ORDER_RETENTION` (default about six months) and exposes `/api/user/forget-me` for authenticated users. The endpoint deletes profile phone/email/nickname, address PII, and order snapshot PII while preserving order financial/status records for auditability.

Administrators with `ADMIN_DASHBOARD_READ` can use `GET /api/stats/audit-trace?traceId=<traceId>` to retrieve sanitized audit events for an incident trace without exposing raw subject identifiers. This gives PIPL/GDPR audit reviewers a bounded investigation path across API responses, audit rows, Loki logs, and Tempo spans.
