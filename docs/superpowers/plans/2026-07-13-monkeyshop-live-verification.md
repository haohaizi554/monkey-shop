# MonkeyShop Live Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace fake-green checks with reproducible MySQL/Redis/Spring/browser evidence covering contracts, security, transactions, every route, accessibility, visuals, performance, and deployment integrity.

**Architecture:** Start one deterministic local integration stack, seed through supported APIs/migrations, generate OpenAPI from the running app, and run Playwright without API interception for critical journeys. Fast unit/mock suites remain, but CI completion requires the live lane plus migration, security, and visual matrices.

**Tech Stack:** Spring Boot, MySQL 8, Redis, Flyway, Maven, Node/npm, Playwright, Axe, Lighthouse, OpenAPI JSON, GitHub Actions, Helm/Kubernetes policy tests.

**Prerequisites:** Execute `2026-07-13-monkeyshop-auth-foundation-api-contracts.md` and `2026-07-13-monkeyshop-commerce-transaction-closure.md` first; they create `SecurityFilterChainMatrixTest.java` and `application-test.yml`, which this plan extends.

## Global Constraints

- Critical transaction tests must not use `page.route()` or mocked Spring services.
- MySQL starts from an empty schema and runs all Flyway migrations; an upgrade fixture tests the previous production version.
- Redis participates in login, captcha, rate-limit, idempotency, and cache tests.
- SecurityFilterChain tests load the real application chain and cover every canonical controller method/path.
- Visual widths are 320, 390, 768, 1024, and 1440px; themes are light and dark; states include loading, empty, error, success, and updating where applicable.
- CI fails on console errors, uncaught page errors, broken images, page-level horizontal overflow, duplicate `h1`, Axe serious/critical findings, or unexpected raw backend messages.
- No completion claim may rely only on build success, mocked browser tests, or source-string assertions.

---

## File Map

- `scripts/local-services.ps1`: starts/stops/checks MySQL, Redis, ClamAV, backend, frontend without Docker.
- `src/test/java/com/example/monkey/integration`: live infrastructure and transaction tests.
- `frontend/scripts/api-contract.mjs`: runtime OpenAPI comparison.
- `frontend/tests/live`: non-mocked browser journeys and visual assertions.
- `.github/workflows/ci.yaml`: fast and live verification lanes.

### Task 1: Make Native Local Services Deterministic

**Files:**
- Create: `scripts/local-services.ps1`
- Create: `scripts/wait-http.ps1`
- Modify: `src/main/resources/application-dev.yml`
- Create: `docs/runbooks/local-acceptance.md`
- Test: `scripts/tests/local-services.Tests.ps1`

**Interfaces:**
- Produces: `./scripts/local-services.ps1 start|stop|status|reset-test-data`.
- Expects: MySQL `127.0.0.1:3306`, Redis `127.0.0.1:6379`, ClamAV `127.0.0.1:3310`, app `127.0.0.1:8888`.
- Uses: MySQL root password from `MYSQL_ROOT_PASSWORD`; no password is committed.

- [ ] **Step 1: Write RED Pester checks**

```powershell
Describe 'local-services status' {
  It 'reports each dependency independently' {
    (& $script status --json | ConvertFrom-Json).services.Name | Should -Be @('mysql','redis','clamav','backend')
  }
}
```

- [ ] **Step 2: Run RED**

Run: `Invoke-Pester -Path scripts/tests/local-services.Tests.ps1`

Expected: scripts are absent.

- [ ] **Step 3: Implement idempotent process/service control**

Use Windows service APIs for MySQL/Redis/ClamAV when installed and PID files under `frontend/output/local-services` for spawned backend processes. `start` waits for TCP/health; `stop` targets only recorded PIDs and never kills unrelated Java/Node processes.

- [ ] **Step 4: Run Pester and smoke health**

Run: `Invoke-Pester -Path scripts/tests/local-services.Tests.ps1`

Run: `./scripts/local-services.ps1 start`

Run: `Invoke-RestMethod http://127.0.0.1:8888/actuator/health/readiness`

Expected: Pester passes and readiness status is `UP`.

- [ ] **Step 5: Commit**

```powershell
git add scripts/local-services.ps1 scripts/wait-http.ps1 scripts/tests/local-services.Tests.ps1 src/main/resources/application-dev.yml docs/runbooks/local-acceptance.md
git commit -m "test(platform): standardize native acceptance services"
```

### Task 2: Validate Empty And Upgrade MySQL Migrations

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/com/example/monkey/integration/MySqlMigrationIntegrationTest.java`
- Create: `src/test/resources/db/fixtures/pre_v49_schema.sql`
- Modify: `src/test/java/com/example/monkey/persistence/SchemaMigrationTest.java`

**Interfaces:**
- Produces: integration profile driven by `TEST_MYSQL_URL`, `TEST_MYSQL_USER`, `TEST_MYSQL_PASSWORD`.
- Verifies: empty migration to current and upgrade fixture from pre-V32 to current.

- [ ] **Step 1: Write RED migration tests**

```java
assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(expectedLatestVersion());
assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success = 0", Integer.class)).isZero();
```

- [ ] **Step 2: Run RED against local MySQL**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=MySqlMigrationIntegrationTest,SchemaMigrationTest' test`

Expected: test infrastructure or upgrade fixture support is absent.

- [ ] **Step 3: Implement isolated schemas and cleanup**

Create unique schema names `monkeyshop_it_<random>` through the admin connection, run Flyway, assert column/index/constraint contracts, then drop only that verified schema in `finally`.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add pom.xml src/test/java/com/example/monkey/integration/MySqlMigrationIntegrationTest.java src/test/resources/db/fixtures/pre_v49_schema.sql src/test/java/com/example/monkey/persistence/SchemaMigrationTest.java
git commit -m "test(db): verify MySQL migrations and upgrades"
```

### Task 3: Exercise Redis Security And Idempotency Contracts

**Files:**
- Create: `src/test/java/com/example/monkey/integration/RedisContractIntegrationTest.java`
- Modify: `src/test/resources/application-test.yml` (created by the commerce transaction plan)

**Interfaces:**
- Uses: isolated Redis key prefix `it:<runId>:` and deletes only that prefix after tests.
- Verifies: captcha consume atomicity, login increments/TTL/decay, exact rate retry, order/payment idempotency, and cache tenant separation.

- [ ] **Step 1: Write RED Redis tests**

```java
assertThat(captcha.consume(id, answer)).isTrue();
assertThat(captcha.consume(id, answer)).isFalse();
assertThat(rateLimit.consume(policy, ip, user).retryAfterSeconds()).isBetween(1L, 300L);
assertThat(keysForTenant(1)).doesNotContainAnyElementsOf(keysForTenant(2));
```

- [ ] **Step 2: Run RED**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=RedisContractIntegrationTest' test`

- [ ] **Step 3: Wire real Redis profile and atomic scripts**

No in-memory substitute is allowed in this class. Use a unique key prefix property and assert TTL after every mutation.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add src/test/java/com/example/monkey/integration/RedisContractIntegrationTest.java src/test/resources/application-test.yml
git commit -m "test(redis): verify security and idempotency contracts"
```

### Task 4: Generate A Complete Runtime Security Matrix

**Files:**
- Create: `src/test/java/com/example/monkey/security/ControllerEndpointInventory.java`
- Modify: `src/test/java/com/example/monkey/security/SecurityFilterChainMatrixTest.java` (created by the auth foundation plan)
- Modify: `src/test/java/com/example/monkey/controller/ControllerAuthorizationDeclarationTest.java`

**Interfaces:**
- Produces: inventory from Spring `RequestMappingHandlerMapping` of method, canonical path, controller, and authorization category.
- Verifies: anonymous, USER, each permission authority, wrong tenant, missing CSRF, and owner/non-owner where applicable.

- [ ] **Step 1: Write RED completeness assertion**

```java
Set<EndpointKey> mapped = endpointInventory.canonicalApiEndpoints();
assertThat(testCases.keySet()).containsExactlyInAnyOrderElementsOf(mapped);
```

- [ ] **Step 2: Run RED**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=SecurityFilterChainMatrixTest,ControllerAuthorizationDeclarationTest' test`

Expected: unlisted endpoints make completeness fail.

- [ ] **Step 3: Classify every endpoint and assert outcomes**

Legacy aliases inherit the canonical endpoint category. Machine callbacks use their signature/source test fixture and are never treated as browser user endpoints.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add src/test/java/com/example/monkey/security/ControllerEndpointInventory.java src/test/java/com/example/monkey/security/SecurityFilterChainMatrixTest.java src/test/java/com/example/monkey/controller/ControllerAuthorizationDeclarationTest.java
git commit -m "test(security): cover every controller through real filters"
```

### Task 5: Replace Source Scans With Runtime OpenAPI Comparison

**Files:**
- Modify: `frontend/scripts/api-contract.mjs`
- Create: `frontend/scripts/openapi-client-contract.mjs`
- Create: `frontend/tests/fixtures/openapi-baseline.json`
- Modify: `frontend/package.json`
- Test: `frontend/scripts/openapi-client-contract.test.mjs`

**Interfaces:**
- Consumes: `OPENAPI_URL`, default `http://127.0.0.1:8888/v3/api-docs`.
- Produces: normalized method/path/request/response/security/idempotency schema and compares every frontend API call.

- [ ] **Step 1: Write RED Node contract test**

```js
assert.deepEqual(collectClientCalls('src/api'), collectOpenApiCalls(document))
assert.equal(document.servers[0].url, '/')
assert.equal(findHeader(document, 'POST', '/api/v1/orders', 'Idempotency-Key').required, true)
```

- [ ] **Step 2: Run RED**

Run: `node scripts/openapi-client-contract.test.mjs`

Working directory: `frontend`. Expected: current script scans only partial source and cannot compare runtime schemas.

- [ ] **Step 3: Implement AST/request extraction and stable normalization**

Parse `request({ method, url })` calls and resolve template path parameters. Ignore only documented machine-only endpoints; fail on undocumented frontend calls, missing required headers, or response-shape drift.

- [ ] **Step 4: Run GREEN and commit**

Run: `npm run test:api-contract`

```powershell
git add frontend/scripts/api-contract.mjs frontend/scripts/openapi-client-contract.mjs frontend/tests/fixtures/openapi-baseline.json frontend/package.json frontend/scripts/openapi-client-contract.test.mjs
git commit -m "test(api): compare clients with runtime OpenAPI"
```

### Task 6: Add Non-Mocked Authentication And Commerce Browser Journeys

**Files:**
- Create: `frontend/tests/live/live-auth.spec.ts`
- Create: `frontend/tests/live/live-commerce.spec.ts`
- Create: `frontend/tests/live/live-admin.spec.ts`
- Create: `frontend/tests/live/live-fixtures.ts`
- Create: `scripts/reset-acceptance-data.ps1`
- Modify: `frontend/playwright.config.ts`

**Interfaces:**
- Uses: supported admin/test seeding entry or deterministic Flyway seed profile; no `page.route()`.
- Verifies: register/login, discover/search/product, cart/checkout/order/payment callback fixture, inventory/coupon/points/tracking, return/refund, shipment/receipt, and admin reads.

- [ ] **Step 1: Write RED network guard**

```ts
test.beforeEach(async ({ page }) => {
  page.on('request', request => {
    if (request.url().includes('/api/') && request.resourceType() === 'fetch') observedApiCalls.push(request.url())
  })
})
test.afterEach(() => expect(interceptedApiCalls).toEqual([]))
```

- [ ] **Step 2: Run RED live journeys**

Run: `npx playwright test tests/live --project=chromium --workers=1`

Expected: live fixtures or complete transaction flow is missing.

- [ ] **Step 3: Implement deterministic data reset and real journeys**

Reset only the dedicated acceptance tenant, create unique usernames per run, read CSRF through the actual browser session, and use a signed local-provider callback fixture at the server boundary rather than browser-forged webhook UI.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add frontend/tests/live frontend/playwright.config.ts scripts/reset-acceptance-data.ps1
git commit -m "test(e2e): cover live auth and commerce journeys"
```

### Task 7: Create Full Route Visual And Accessibility Matrix

**Files:**
- Create: `frontend/tests/visual/route-matrix.ts`
- Create: `frontend/tests/visual/visual-regression.spec.ts`
- Create: `frontend/tests/visual/state-regression.spec.ts`
- Modify: `frontend/playwright.config.ts`
- Create: `frontend/tests/visual/__snapshots__/.gitkeep`

**Interfaces:**
- Produces: snapshots for auth, shop, product, search, cart, checkout, orders, payment, logistics, membership, profile, admin overview, catalog, inventory, order ops, payment ops, marketing, risk, data, tenants, files.
- Produces: assertions for broken images, overflow, overlap, duplicate headings, console errors, Axe, theme, keyboard, and reduced motion.

- [ ] **Step 1: Write RED route inventory check**

```ts
expect(routeMatrix.map(route => route.path).sort()).toEqual(appUserFacingRoutes.sort())
```

- [ ] **Step 2: Run RED matrix**

Run: `npx playwright test tests/visual --project=chromium`

- [ ] **Step 3: Capture deterministic states**

Freeze clock/animations for snapshots, but also run a separate motion-enabled interaction pass. Capture light/dark at 390 and 1440 for every route; capture 320/768/1024 geometry checks; capture loading/empty/error/success for stateful route families.

- [ ] **Step 4: Run GREEN and commit reviewed baselines**

Run: `npx playwright test tests/visual --project=chromium --update-snapshots`

Run: `npx playwright test tests/visual --project=chromium`

Expected: second run passes without baseline updates.

```powershell
git add frontend/tests/visual frontend/playwright.config.ts
git commit -m "test(visual): baseline every user-facing workspace"
```

### Task 8: Gate CI On Real Evidence And Deployment Integrity

**Files:**
- Modify: `.github/workflows/ci.yaml`
- Modify: `helm/monkeyshop/values-prod.yaml`
- Modify: `deploy/argocd/applications/monkeyshop-prod.yaml`
- Modify: `frontend/package.json`
- Create: `scripts/verify-prod-manifests.ps1`
- Test: `src/test/java/com/example/monkey/security/SupplyChainAutomationTest.java`

**Interfaces:**
- Produces CI jobs: `backend-fast`, `frontend-fast`, `mysql-redis-integration`, `live-e2e`, `visual-a11y`, `security-supply-chain`.
- Produces release update by immutable image digest and pinned Git revision; no `sha256:0000...` or `targetRevision: HEAD`.

- [ ] **Step 1: Add RED supply-chain assertions**

```java
assertThat(valuesProd).doesNotContain("sha256:0000");
assertThat(argocdProd).doesNotContain("targetRevision: HEAD");
assertThat(ciWorkflow).contains("npm run test:live", "verify-prod-manifests.ps1");
```

- [ ] **Step 2: Run RED**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=SupplyChainAutomationTest' test`

- [ ] **Step 3: Implement immutable release wiring**

CI writes the built/signed digest into the release values artifact and updates the GitOps revision through a commit/PR step. The repository default uses a syntactically valid non-placeholder digest fixture only in test overlays; production deployment requires the release-provided digest.

- [ ] **Step 4: Run complete local gate**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' verify`

Run: `npm ci`

Run: `npm run lint`

Run: `npm run typecheck`

Run: `npm run test:unit`

Run: `npm run build`

Run: `npm run test:api-contract`

Run: `npx playwright test tests/live tests/visual --project=chromium --workers=1`

Working directory for npm/Playwright commands: `frontend`.

Expected: every command exits 0 with no ignored failing suite.

- [ ] **Step 5: Commit**

```powershell
git add .github/workflows/ci.yaml helm/monkeyshop/values-prod.yaml deploy/argocd/applications/monkeyshop-prod.yaml frontend/package.json scripts/verify-prod-manifests.ps1 src/test/java/com/example/monkey/security/SupplyChainAutomationTest.java
git commit -m "ci: require live product verification"
```

### Task 9: Produce The Final Evidence Report

**Files:**
- Create: `docs/reports/2026-07-13-monkeyshop-full-acceptance.md`
- Create: `docs/reports/assets/2026-07-13/`

**Interfaces:**
- Produces: command/version/result/duration evidence, route matrix, security matrix count, migration versions, business transaction IDs, screenshots, Axe/Lighthouse summaries, known external-provider blockers.

- [ ] **Step 1: Collect fresh evidence**

Record exact Git SHA, Java/Maven/Node/npm/browser versions, local service health, test counts, and artifact hashes. Link screenshots for 390/1440 light/dark and representative state variants.

- [ ] **Step 2: Audit completion criteria against evidence**

For every design-spec completion bullet, cite one automated test/report section. Mark an item complete only when the cited command passed in this run. External live payment/logistics/SMS/email/export providers are recorded as explicit external blockers if credentials/capability are absent; sandbox behavior is not accepted as production evidence.

- [ ] **Step 3: Scan the report for unsupported claims**

Run: `rg -n "assume|probably|should pass|not run|mocked as live" docs/reports/2026-07-13-monkeyshop-full-acceptance.md`

Expected: no matches.

- [ ] **Step 4: Commit the report**

```powershell
git add docs/reports/2026-07-13-monkeyshop-full-acceptance.md docs/reports/assets/2026-07-13
git commit -m "docs: record full MonkeyShop acceptance evidence"
```

## Plan Acceptance

- Native MySQL, Redis, ClamAV, backend, and frontend startup/status are deterministic and scoped.
- Empty and upgrade MySQL migrations pass; Redis-backed security/idempotency tests pass.
- Every canonical controller endpoint appears in the real SecurityFilterChain matrix.
- Runtime OpenAPI and every frontend API call agree on method/path/schema/status/security/idempotency.
- Live auth/admin/transaction Playwright flows use the running Spring backend and no API route mocks.
- All user-facing routes pass visual, Axe, keyboard, reduced-motion, overflow, image, console, and state checks.
- CI requires live evidence and immutable deployment metadata.
- The final report contains only fresh, reproducible evidence and explicit external blockers.
