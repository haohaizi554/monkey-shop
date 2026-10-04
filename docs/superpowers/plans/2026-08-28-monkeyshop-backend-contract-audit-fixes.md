# MonkeyShop Backend And Frontend Contract Audit Fix Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:test-driven-development` for every behavior change, `superpowers:systematic-debugging` for failures, and `superpowers:verification-before-completion` before any completion claim. This is a shared checkout; do not commit, push, reset, or stage changes unless the user separately authorizes it.

**Goal:** Review the backend business invariants and frontend/backend coordination boundaries, reproduce every confirmed defect with a focused behavioral test, apply the smallest durable fixes, and finish with fresh repository-wide verification.

**Architecture:** Keep the modular-monolith boundaries. Client input may identify a resource but may not assert authoritative ownership, price, shop, tenant, or risk facts. Tenant-scoped background work must execute in an explicit tenant context. Shared Redis and filesystem infrastructure must include the tenant in its isolation model. Java `long` identifiers cross the JSON boundary as losslessly preserved `ApiId` values in TypeScript.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, MySQL 8, Redis, Flyway, JUnit 5, Mockito, Vue 3, TypeScript, Vite, Vitest.

## Starting Evidence

- Baseline source commit was `a4b18206`; the current shared branch also contains unrelated user-owned commits `7ddf7b9a` and `5840626e`. Preserve both.
- Backend baseline: 1,730 tests discovered; 1 real assertion failure, 34 environment-only temp-directory errors, and 42 dependency-backed skips. The 34 temp-directory tests pass when `java.io.tmpdir` points inside `target`.
- Frontend baseline: 29 Vitest files / 151 tests, API contract checks, typecheck, lint, format check, and production build pass.
- The real baseline failure is an obsolete deployment assertion that requires an empty production digest even though the release workflow intentionally persists a signed SHA-256 digest.
- Docker-, Redis-, MySQL-, MinIO-, ClamAV-, and Vault-backed acceptance suites remain environment-gated; final reporting must separate verified code from unavailable external-runtime evidence.

## Global Constraints

- Inspect `git status --short --branch` before every edit batch. Preserve concurrent and unrelated work.
- Tests must exercise runtime behavior or an executable verification script. Do not add source-text mirror tests merely to make a gate green.
- Record RED evidence before production edits, then run focused GREEN checks and neighboring regressions.
- Never repair a test by weakening the underlying business invariant.
- Do not trust client-supplied `shopId`, `tenantId`, product identity, price, inventory, risk, or payment facts. In the current model catalog managers assign a tenant-local shop partition, while customer flows must resolve that value from the canonical SKU/SPU.
- Do not coerce Snowflake identifiers through JavaScript `number`, `parseInt`, unary `+`, or numeric input models.
- Scheduled tenant work must not rely on `TenantContext.currentTenantIdOrDefault()`.
- Orphan deletion must fail closed: incomplete reference discovery must result in no deletion.
- Cache, idempotency, and replay keys must align with database uniqueness and tenant boundaries.
- No completion claim until fresh verification results have been inspected, not inferred from exit code alone.

## Task 1: Repair The Stale Production-Digest Gate

**Files:**

- Modify: `src/test/java/com/example/monkey/security/Ws7DevOpsWorkflowTest.java`
- Modify: `scripts/verify-ws7-devops.ps1`
- Verify: `helm/monkeyshop/values-prod.yaml`
- Verify: `.github/workflows/ci.yaml`

- [ ] Preserve the currently pinned, non-zero `sha256:<64 hex>` production digest.
- [ ] Keep the existing failing Java test and executable PowerShell script as RED evidence.
- [ ] Change both gates to require a syntactically valid, non-zero pinned digest at rest, consistent with the signed-image release workflow.
- [ ] Run the focused JUnit test and `scripts/verify-ws7-devops.ps1`; Helm absence may skip render checks but must not hide the digest assertion.

### Task 1B: Align The Production Image Trust Contract

**Files:**

- Modify: `scripts/verify-kyverno-supply-chain.ps1`
- Modify: `deploy/kyverno/monkeyshop-image-policy.yaml`
- Verify: `.github/workflows/ci.yaml`
- Verify: `helm/monkeyshop/values-prod.yaml`
- Verify: `deploy/argocd/applications/monkeyshop-prod.yaml`

- [ ] Add executable RED coverage showing the actual `ghcr.io/haohaizi554/monkey-shop@sha256:...` image is rejected by the current static Harbor-only check and is not matched by the current Kyverno GHCR glob.
- [ ] Align CI image naming, static verification, Kyverno image references, and GitHub OIDC subject to the checked-in repository/image contract.
- [ ] Render the production chart when Helm is available and verify that every workload image is digest-pinned and covered by the signature policy.
- [ ] Do not claim live signature verification without registry/cluster evidence.

### Task 1C: Keep Opt-In Migration Acceptance At The Latest Schema

**Files:**

- Modify: `src/test/java/com/example/monkey/payment/application/PaymentLocalMySqlAcceptanceTest.java`
- Modify: `src/test/java/com/example/monkey/tenant/infrastructure/TenantExportMigrationLocalMySqlTest.java`
- Verify: `src/main/resources/db/migration/V55__repair_payment_and_admin_bootstrap_states.sql`

- [ ] Replace the stale hard-coded V54 expectation with an authoritative latest-migration contract or V55 expectation.
- [ ] Add assertions for the V55 repair effect where the local-MySQL fixture makes it observable.
- [ ] Run source-level migration tests now; run the opt-in suites only against a fresh isolated MySQL schema and report them separately.

## Task 2: Make The Catalog Shop Partition Canonical

The current system has tenant-wide `PRODUCT_MANAGE` and no shop, merchant membership, or per-shop
authorization aggregate. Consequently `shopId` is a tenant-local sub-order/promotion partition, not
an ownership credential. True multi-merchant authorization is a separate schema and policy feature.

**Files:**

- Modify: `src/main/java/com/example/monkey/cart/domain/CartSkuSnapshot.java`
- Modify: `src/main/java/com/example/monkey/cart/infrastructure/JpaCartCatalogReader.java`
- Modify: `src/main/java/com/example/monkey/cart/application/CartApplicationService.java`
- Modify: `src/main/java/com/example/monkey/cart/application/dto/CartAddItemRequestDto.java`
- Modify: `src/main/java/com/example/monkey/cart/application/dto/CartDirectCheckoutRequestDto.java`
- Test: `src/test/java/com/example/monkey/cart/application/CartApplicationServiceTest.java`
- Add or modify the focused catalog-reader test selected from the current test layout.

- [x] Add a RED test proving a caller cannot attach an SKU to an arbitrary shop partition and cannot make an unrelated shop coupon eligible.
- [x] Add a RED direct-checkout test for the same forged-partition path.
- [x] Resolve canonical `shopId` from the server-owned product/catalog record and persist it as a first-class positive column; do not invent a customer fallback.
- [x] Require the compatibility request field to match the canonical value. Persist and group only the canonical value.
- [x] Run cart, catalog-reader, checkout, coupon-allocation, and API-contract regressions.

### Task 2B: Use The Catalog Pricing Policy During Checkout

**Files:**

- Modify: `src/main/java/com/example/monkey/cart/domain/CartCatalogReader.java`
- Modify: `src/main/java/com/example/monkey/cart/domain/CartSkuSnapshot.java`
- Modify: `src/main/java/com/example/monkey/cart/infrastructure/JpaCartCatalogReader.java`
- Modify: `src/main/java/com/example/monkey/cart/application/CartApplicationService.java`
- Reuse the product pricing policy under `src/main/java/com/example/monkey/product/domain/`
- Test: `src/test/java/com/example/monkey/cart/application/CartApplicationServiceTest.java`
- Test: focused cart catalog infrastructure tests.

- [ ] Add RED scenarios for a BASIC user and member user across two regions, with original/member/regional prices deliberately different.
- [ ] Resolve membership identity and normalized destination region on the server and evaluate the same price policy used by catalog detail.
- [ ] Use identical authoritative pricing for cart preview, cart checkout, and direct checkout; persist the resulting immutable quote in checkout/order lines.
- [ ] Assert line totals, checkout totals, formal-order totals, and downstream payment amount conserve the same value.

## Task 3: Enforce Commercial Risk Inside The Application Boundary

**Files:**

- Add or modify a risk-domain application port under `src/main/java/com/example/monkey/risk/domain/`
- Add an adapter under `src/main/java/com/example/monkey/risk/infrastructure/`
- Modify: `src/main/java/com/example/monkey/marketing/application/MarketingApplicationService.java`
- Modify: `src/main/java/com/example/monkey/marketing/interfaces/MarketingController.java`
- Test: `src/test/java/com/example/monkey/marketing/application/MarketingApplicationServiceTest.java`
- Test: `src/test/java/com/example/monkey/marketing/interfaces/MarketingControllerTest.java`

- [ ] Add RED tests proving a denied seckill request performs no stock reservation/order write and a denied group-buy request performs no team/member write.
- [ ] Build the risk request from authoritative activity data after lookup but before the first side effect. Do not use `activityId` as a substitute for `productId`.
- [ ] Forward request-only signals such as client IP/device fingerprint without granting them authority over resource identity.
- [ ] Keep HTTP concerns in the controller while ensuring direct application-service callers cannot bypass the commercial gate.
- [ ] Run marketing, risk, inventory-reservation, and controller regressions.

### Task 3B: Close Marketing Team And Idempotency Invariants

**Files:**

- Modify: `src/main/java/com/example/monkey/marketing/application/MarketingApplicationService.java`
- Modify marketing domain/store/entity contracts as required.
- Add a Flyway migration only if durable request fingerprints or uniqueness require schema changes.
- Test: `src/test/java/com/example/monkey/marketing/application/MarketingApplicationServiceTest.java`
- Test: marketing JPA/integration tests where uniqueness is involved.

- [ ] Add RED tests proving a team from another activity, a succeeded/cancelled team, and an expired open team reject joins with zero member/team mutation.
- [ ] Recheck activity, SKU, status, and expiry while holding the team lock.
- [ ] Add RED tests proving group-team creation retries return the original team and changed payloads conflict.
- [ ] Bind seckill idempotency to a canonical fingerprint containing activity, user, order, and quantity; identical replay succeeds and changed replay conflicts.
- [ ] Back durable semantics with tenant-aware database uniqueness; Redis may accelerate but may not be the only source of truth.

### Task 3C: Bind Inventory Reservation Idempotency To The Full Request

**Files:**

- Modify inventory domain/store/entity/service contracts under `src/main/java/com/example/monkey/inventory/`
- Add a Flyway migration if the fingerprint is persisted.
- Test: `src/test/java/com/example/monkey/inventory/application/InventoryApplicationServiceTest.java`
- Test: focused inventory JPA/concurrency tests.

- [ ] Add RED replay tests for changed SKU, warehouse, order, province, and quantity under the same key.
- [ ] Persist and compare a canonical request fingerprint; only identical replays return the prior reservation.
- [ ] Add a coordinated two-thread same-key/different-payload test and prove exactly one stock mutation survives.
- [ ] Ensure a losing save-if-absent race cannot leave speculative stock reserved.

### Task 3D: Validate Payment Provider And Gateway Truth Before Side Effects

**Files:**

- Modify: `src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java`
- Modify provider-verification configuration only if required by the existing adapter contract.
- Test: `src/test/java/com/example/monkey/payment/application/PaymentApplicationServiceTest.java`

- [ ] Add RED callback test: a validly signed ALIPAY callback cannot confirm a persisted WECHAT payment.
- [ ] Add RED query/create tests: a `PAID` gateway result with a mismatched amount does not confirm payment/order or deduct inventory.
- [ ] Add a RED refund test: a successful gateway result with a mismatched amount does not complete the refund ledger/state.
- [ ] Require provider equality and normalized amount equality before every paid/refunded state transition; keep the operation retryable or explicitly suspended according to the existing state machine.

### Task 3E: Prevent Untrusted Risk And Tracking Inputs From Mutating Authority

**Files:**

- Modify: `src/main/java/com/example/monkey/risk/application/RiskApplicationService.java`
- Modify: `src/main/java/com/example/monkey/risk/interfaces/RiskController.java`
- Modify: `src/main/java/com/example/monkey/tracking/application/TrackingApplicationService.java`
- Modify: `src/main/java/com/example/monkey/tracking/infrastructure/JpaTrackingStore.java`
- Modify: `src/main/java/com/example/monkey/tracking/interfaces/TrackingController.java`
- Modify: `src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java`
- Test: risk and tracking application/controller/infrastructure tests.

- [ ] Add RED test proving an ordinary user cannot fabricate before/after prices to unlist a product.
- [ ] Restrict auto-unlisting to an authorized system/catalog-management path using server-loaded product facts.
- [ ] Add RED test proving anonymous `PAYMENT_SUCCESS`/`ORDER_CREATED` ingestion cannot inflate payment totals, sales counts, or authoritative profiles.
- [ ] Separate observational public analytics from trusted domain-event ingestion, or require authenticated/signed internal producers for authoritative event types.
- [ ] Validate optional product/order references with both ID and current tenant; reject or discard foreign-tenant references.

### Task 3F: Consume Password Reset Factors Atomically

**Files:**

- Modify: `src/main/java/com/example/monkey/user/application/PasswordResetOtpService.java`
- Test: focused password-reset OTP/token unit and Redis integration tests.

- [ ] Add a coordinated RED test where two consumers race on one valid OTP/token and both currently succeed.
- [ ] Replace GET-then-DELETE with an atomic compare-and-consume operation (`GETDEL` where sufficient, otherwise Lua).
- [ ] Assert exactly one consumer succeeds and the factor remains single-use across process instances.

## Task 4: Execute Tenant-Scoped Background Jobs For Every Eligible Tenant

**Files:**

- Modify: `src/main/java/com/example/monkey/shared/application/tenant/ActiveTenantIterator.java`
- Modify only as needed: `src/main/java/com/example/monkey/tenant/domain/ActiveTenantReader.java`
- Modify only as needed: `src/main/java/com/example/monkey/tenant/infrastructure/JpaActiveTenantReader.java`
- Move scheduling responsibility out of application services or add focused scheduled adapters for marketing, inventory, membership, order, payment reconciliation, and search.
- Modify the affected application services only to expose current-tenant transactional operations.
- Test: existing feature service tests plus new scheduler tests under each affected module or a shared scheduler package.

- [ ] Define two explicit populations: serviceable tenants for customer-facing notification/cache work, and every retained tenant for state settlement, finance, compliance, and destructive-cleanup safety.
- [ ] Add RED tests that set no ambient tenant and prove tenants 1 and 2 are both processed.
- [ ] Prove one tenant failure is isolated and the prior `TenantContext` is restored.
- [ ] Keep global scheduler locks around one cluster-wide iteration, not one implicit default-tenant call.
- [ ] Preserve the existing custom all-tenant payment query/recovery and cart-cleanup behavior; do not double-iterate it.
- [ ] Use retained-tenant iteration for inventory expiry, group-buy expiry, order auto-receive, and financial reconciliation; use serviceable tenants for price-drop notifications and search snapshots.
- [ ] Decide and test whether PII retention and audit purge include suspended/expired retained tenants; do not inherit this policy accidentally from `ActiveTenantReader`.
- [ ] Attribute asynchronous tenant-export completion audit records to the export job's explicit tenant rather than the scheduler thread's default tenant.
- [ ] Run focused scheduler tests plus `ActiveTenantIteratorJpaIntegrationTest`.

## Task 5: Make Image Cleanup Complete And Fail Closed

**Files:**

- Modify: `src/main/java/com/example/monkey/shared/infrastructure/storage/ImageTask.java`
- Modify: `src/main/java/com/example/monkey/shared/application/tenant/ActiveTenantIterator.java`
- Modify: `src/main/java/com/example/monkey/tenant/domain/ActiveTenantReader.java`
- Modify: `src/main/java/com/example/monkey/tenant/infrastructure/JpaActiveTenantReader.java`
- Test: `src/test/java/com/example/monkey/shared/infrastructure/storage/ImageTaskTest.java`

- [ ] Add a RED test proving a file referenced only by tenant 2 is retained.
- [ ] Add a RED test proving a reference-scan failure results in zero deletions and does not destroy the last known reference set.
- [ ] Scan every known retained tenant, including suspended/expired tenants whose data still exists, into a temporary in-memory set.
- [ ] Replace live reference counts only after all tenant scans succeed; otherwise abort the cleanup run.
- [ ] Delete only age-eligible files absent from the complete reference snapshot.
- [ ] Include modern `product_spu.image_url` and parsed order-review image arrays in addition to legacy `monkey` images, and retain/release them through their lifecycle where appropriate.
- [ ] Treat Redis reference-store read/write/clear failures as unknown state and abort deletion rather than converting them to zero references.
- [ ] Run `ImageTaskTest`, image reference source tests, and storage regressions with a writable task-local `java.io.tmpdir`.

## Task 6: Align Shared Infrastructure With Tenant And Transaction Boundaries

**Files:**

- Modify: `src/main/java/com/example/monkey/product/infrastructure/RedisCategoryTreeCache.java`
- Modify: `src/main/java/com/example/monkey/search/infrastructure/RedisSearchActivityStore.java`
- Modify only when confirmed by invariant evidence: marketing, membership, order, and risk Redis stores.
- Modify: `src/main/java/com/example/monkey/logistics/infrastructure/RedisLogisticsWebhookReplayGuard.java`
- Modify: `src/main/java/com/example/monkey/logistics/infrastructure/LogisticsWebhookLogRepository.java`
- Modify only as needed: `src/main/java/com/example/monkey/logistics/infrastructure/LogisticsWebhookLogEntity.java`
- Test: `src/test/java/com/example/monkey/search/infrastructure/RedisSearchActivityStoreTest.java`
- Test: `src/test/java/com/example/monkey/logistics/infrastructure/RedisLogisticsWebhookReplayGuardTest.java`
- Test: `src/test/java/com/example/monkey/logistics/application/LogisticsApplicationServiceTest.java`
- Add a behavioral category-cache isolation test.

- [ ] Add RED tests proving tenant A category/search data is invisible to tenant B, including the no-Redis fallback path.
- [ ] Namespace keys and in-memory fallback state by `tenantId`, and make scheduled hot-search snapshots use explicit tenant iteration.
- [ ] Add RED tests proving logistics database reservation failure does not publish a Redis marker, Redis is published only after commit, same-tenant duplicate binding is idempotent, conflicting binding is rejected, and the same carrier/event is independent across tenants.
- [ ] Reserve logistics webhook identity in the database first using explicit tenant-aware uniqueness. Publish the Redis acceleration marker only after transaction commit.
- [ ] Use the database row as replay authority; Redis must never be able to turn a rolled-back event into a permanent replay.
- [ ] Namespace the risk cache and all in-memory risk fallbacks by tenant unless a separately documented global anti-fraud signal is deliberately introduced.
- [ ] Add a migration replacing the historical order idempotency unique key with `(tenant_id, user_id, idempotency_key)` and test identical user/key values in two tenants.
- [ ] Re-run neighboring payment callback replay tests to ensure the proven pattern remains intact.

## Task 7: Preserve Every Snowflake ID Across The Vue Boundary

**Files:**

- Modify: `frontend/src/types.ts`
- Modify affected modules under `frontend/src/api/`
- Modify affected views, including `ProductDetailView.vue`, `InventoryView.vue`, `MembershipView.vue`, and `TenantAdminView.vue`
- Modify: `frontend/src/api/snowflakeIds.test.ts`
- Add focused component/helper tests when UI behavior cannot be exercised through API clients.

- [ ] Expand RED tests with identifiers larger than `Number.MAX_SAFE_INTEGER` for catalog, cart, inventory, marketing, membership, tenant administration, risk, tracking, and search/recommendation paths.
- [ ] Use the existing `ApiId`, normalization, and equality helpers consistently for all server IDs.
- [ ] Keep identifiers as exact strings in route params, URL segments, query parameters, request bodies, selection models, maps, and comparisons.
- [ ] Replace numeric input semantics for arbitrary IDs with validated decimal-string input; numeric quantities and money remain numeric.
- [ ] Remove `Number(...)`, `parseInt`, numeric sort/subtraction, and strict mixed-type equality from identifier flows.
- [ ] Run unit/API contract tests, typecheck, lint, format check, and production build.

### Task 7B: Unify Product Identity Across Search, Catalog, And Membership

**Files:**

- Modify: `src/main/java/com/example/monkey/search/infrastructure/JpaSearchStore.java`
- Modify membership product resolution under `src/main/java/com/example/monkey/membership/`
- Modify affected frontend search/product/membership flows.
- Test: search store/application/controller tests, membership application/JPA tests, and ProductDetail/SearchView tests.

- [ ] Add RED test proving every search result routes to a resolvable detail resource. Remove legacy-only results from the catalog search or expose an explicit source discriminator and valid legacy route.
- [ ] Add RED test proving a listed `product_spu` can be collected and recorded in browse history from Product Detail.
- [ ] Make membership resolve the same catalog product identity used by the current Product Detail UI; do not rely on coincident numeric IDs in the legacy `monkey` table.
- [ ] Preserve any deliberately supported legacy workflow behind an explicit type/adapter, not an ambiguous shared ID.

### Task 7C: Render The Canonical Audit Trace Contract

**Files:**

- Modify: `frontend/src/api/admin.ts`
- Modify: `frontend/src/views/AdminView.vue`
- Test: `frontend/src/views/AdminView.test.ts` or a focused API mapper test.

- [ ] Add RED UI behavior using backend fields `actorUserId` and `detail`; prove actor and description are currently blank.
- [ ] Align frontend types/rendering to the backend `AuditTraceEventDto` (`actorUserId`, `actorRole`, `outcome`, `detail`, `traceId`).
- [ ] Preserve unsafe event IDs through `ApiId` while correcting semantic field names.

## Task 8: Verification, Review, And Closure

- [ ] Run every focused RED/GREEN command and inspect the test names/counts.
- [ ] Run the full backend test suite with `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<workspace>\\target\\codex-tmp`.
- [ ] Run backend package/quality gates, including Spotless and Checkstyle. Run slower security/static-analysis gates where the environment permits and report each separately.
- [ ] Run frontend unit tests, API contract checks, typecheck, lint, format check, and production build.
- [ ] Run executable deployment verification scripts, not just their source tests.
- [ ] Obtain independent code review for commerce/security, tenant/data operations, and frontend contracts; root agent resolves findings and reruns affected checks.
- [ ] Inspect `git diff --check`, `git diff --stat`, and full task-scoped diff. Confirm unrelated commits/files remain untouched.
- [ ] Report exact passed/failed/skipped counts and identify every check blocked by unavailable Docker, MySQL, Redis, MinIO, ClamAV, Vault, Helm, browser, or credentials.
- [ ] Mark the persistent goal complete only when no known defect remains in the audited and executable scope. Do not equate unavailable integration evidence with a pass.
