# MonkeyShop Admin Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a dense, mature admin platform over all appropriate operational APIs while closing tenant, PII, export, file-security, and scheduled-job P1 blockers.

**Architecture:** The admin shell owns grouped navigation and tenant context; each workspace composes shared toolbar/table/status/action primitives and permission-aware commands. Backend governance fixes remain within their owning modules and are proven by multi-tenant/security tests before UI exposure.

**Tech Stack:** Vue 3.5, TypeScript, Element Plus, Pinia, Spring Boot, JPA, MySQL, Redis, object storage, ClamAV, Vitest, Playwright.

## Global Constraints

- Admin desktop uses fixed left navigation, top command bar, and full-width work area; mobile navigation is a modal drawer with inert background, scroll lock, focus trap, and focus return.
- Navigation groups are Operations, Product Supply, Growth Marketing, Risk Data, and Platform Governance.
- Tables and workspaces are dense and scan-friendly; no oversized hero, decorative section cards, nested cards, or mascot in rows/metrics/toolbars.
- Sandbox-only and machine callback operations are never exposed as ordinary buttons.
- Tenant context is mandatory for tenant-scoped reads/writes; platform-admin cross-tenant actions are explicit and audited.
- Export status reflects actual jobs; unavailable provider capability is labeled unavailable, never complete.
- TOTP secrets and PII remain encrypted by default in every profile.

---

## File Map

- `frontend/src/components/shell/AdminSidebar.vue`, `AdminTopbar.vue`: shell ownership and tenant/command context.
- `frontend/src/components/admin`: dense toolbar, metric, table, and filter primitives.
- `frontend/src/views/admin`: focused workspace views created by this plan.
- `tenant`, `user`, `shared/privacy`, `shared/storage`: platform integrity fixes.

### Task 1: Rebuild Admin Shell, Route Groups, And Permission Metadata

**Files:**
- Modify: `frontend/src/components/shell/AdminSidebar.vue`
- Modify: `frontend/src/components/shell/AdminTopbar.vue`
- Modify: `frontend/src/components/AppShell.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/router/route-meta.d.ts`
- Modify: `frontend/src/styles/shell.css`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/admin-operations.spec.ts`
- Create: `frontend/src/components/shell/AdminShell.test.ts`

**Interfaces:**
- Produces: route meta `requiredPermissions: string[]`, `adminGroup`, `titleKey`, and optional `hideFromNavigation`.
- Produces: grouped routes `/admin`, `/admin/orders`, `/admin/returns`, `/admin/payments`, `/admin/catalog`, `/admin/inventory`, `/admin/logistics`, `/admin/marketing`, `/admin/members`, `/admin/risk`, `/admin/data`, `/admin/tenants`, `/admin/files`.

- [ ] **Step 1: Write RED route and drawer tests**

```ts
expect(adminRoutes.every(route => route.meta.area === 'admin')).toBe(true)
expect(route('/admin/payments').meta.requiredPermissions).toContain('PAYMENT_READ')
expect(background.attributes('inert')).toBeDefined()
expect(document.activeElement).toBe(menuButtonAfterClose)
```

- [ ] **Step 2: Run RED**

Run: `npm run test:unit -- src/components/shell/AdminShell.test.ts`

Run: `npx playwright test tests/admin-operations.spec.ts --project=chromium`

- [ ] **Step 3: Implement shell and grouped navigation**

The top command bar contains command search, active tenant, notifications, account, theme, and language. Each content route owns exactly one `PageHeader`/`h1`. Filter inaccessible routes before render and enforce the same permission in the router guard.

- [ ] **Step 4: Run tests and commit**

```powershell
git add frontend/src/components/shell/AdminSidebar.vue frontend/src/components/shell/AdminTopbar.vue frontend/src/components/AppShell.vue frontend/src/router/index.ts frontend/src/router/route-meta.d.ts frontend/src/styles/shell.css frontend/src/locales/index.ts frontend/tests/admin-operations.spec.ts frontend/src/components/shell/AdminShell.test.ts
git commit -m "feat(admin): rebuild platform shell and navigation"
```

### Task 2: Build Operations Overview And Trusted Analytics

**Files:**
- Modify: `frontend/src/views/AdminView.vue`
- Modify: `frontend/src/views/DashboardView.vue`
- Modify: `frontend/src/api/admin.ts`
- Modify: `frontend/src/api/tracking.ts`
- Modify: `src/main/java/com/example/monkey/admin/application/StatsService.java`
- Modify: `src/main/java/com/example/monkey/admin/infrastructure/JpaAdminStatsReader.java`
- Test: `src/test/java/com/example/monkey/admin/application/StatsServiceTest.java`
- Test: `frontend/tests/admin-dashboard.spec.ts`

**Interfaces:**
- Produces: one explicit `[startInclusive, endExclusive)` date contract for GMV/orders/visits/funnel/profile queries.
- Consumes: stats, tracking dashboard, profile, and audit trace APIs.

- [ ] **Step 1: Write RED date-boundary test**

```java
StatsResponseDto result = service.stats(tenant, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));
verify(reader).read(tenant, instant("2026-07-01T00:00:00+08:00"), instant("2026-07-02T00:00:00+08:00"));
```

- [ ] **Step 2: Run RED backend/UI tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=StatsServiceTest,JpaAdminStatsReaderTest' test`

Run: `npx playwright test tests/admin-dashboard.spec.ts --project=chromium`

- [ ] **Step 3: Normalize ranges and build dense overview**

Use a compact `MetricStrip`, unframed funnel/profile sections, operational queue summaries, and audit trace lookup. Dashboard mascot `pose="dashboard"` appears only in the first-run empty state, never beside live metrics.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add frontend/src/views/AdminView.vue frontend/src/views/DashboardView.vue frontend/src/api/admin.ts frontend/src/api/tracking.ts src/main/java/com/example/monkey/admin/application/StatsService.java src/main/java/com/example/monkey/admin/infrastructure/JpaAdminStatsReader.java src/test/java/com/example/monkey/admin/application/StatsServiceTest.java frontend/tests/admin-dashboard.spec.ts
git commit -m "feat(admin): connect trusted operations overview"
```

### Task 3: Build Catalog And File-Safe Product Operations

**Files:**
- Create: `frontend/src/views/admin/CatalogWorkspace.vue`
- Modify: `frontend/src/api/catalog.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `src/main/java/com/example/monkey/shared/interfaces/storage/UploadController.java`
- Modify: `src/main/java/com/example/monkey/shared/application/storage/FileService.java`
- Modify: `src/main/java/com/example/monkey/shared/infrastructure/storage/MinioObjectStorageService.java`
- Test: `src/test/java/com/example/monkey/shared/interfaces/storage/UploadControllerSecurityTest.java`
- Test: `src/test/java/com/example/monkey/shared/application/storage/FileServiceTest.java`
- Create: `frontend/tests/admin-catalog.spec.ts`

**Interfaces:**
- Consumes: Catalog category/SPU/SKU, Monkey CRUD, authenticated upload, image status.
- Produces: upload flow `request -> quarantine -> magic/Tika -> ClamAV -> EXIF strip/reencode -> publish`; presigned GET validates tenant ownership.

- [ ] **Step 1: Write RED upload bypass tests**

```java
assertThatThrownBy(() -> service.publishPresignedObject(unscannedObjectKey))
        .isInstanceOf(FileSecurityException.class);
assertThatThrownBy(() -> service.presignGet(otherTenantKey, currentTenant))
        .isInstanceOf(ResourceOwnershipException.class);
```

- [ ] **Step 2: Run RED backend and UI tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=UploadControllerSecurityTest,FileServiceTest,MinioObjectStorageServiceTest' test`

Run: `npx playwright test tests/admin-catalog.spec.ts --project=chromium`

- [ ] **Step 3: Secure storage and implement catalog workspace**

The workspace includes category tree, SPU/SKU table, price/stock status, image upload state, and status actions. Upload failure is inline and exposes trace ID, not scanner internals. Direct presigned publishing remains unavailable to UI.

- [ ] **Step 4: Run tests and commit**

```powershell
git add frontend/src/views/admin/CatalogWorkspace.vue frontend/src/api/catalog.ts frontend/src/router/index.ts src/main/java/com/example/monkey/shared/interfaces/storage/UploadController.java src/main/java/com/example/monkey/shared/application/storage/FileService.java src/main/java/com/example/monkey/shared/infrastructure/storage/MinioObjectStorageService.java src/test/java/com/example/monkey/shared/interfaces/storage/UploadControllerSecurityTest.java src/test/java/com/example/monkey/shared/application/storage/FileServiceTest.java frontend/tests/admin-catalog.spec.ts
git commit -m "feat(admin): add secure catalog operations"
```

### Task 4: Rebuild Inventory, Reservation, And Reconciliation Workspace

**Files:**
- Modify: `frontend/src/views/InventoryView.vue`
- Modify: `frontend/src/api/inventory.ts`
- Modify: `frontend/src/components/admin/AdminPageToolbar.vue`
- Test: `frontend/tests/admin-inventory.spec.ts`
- Test: `src/test/java/com/example/monkey/inventory/interfaces/InventoryControllerTest.java`

**Interfaces:**
- Consumes: warehouse stock, reservation/release/deduct/compensate/reconcile.
- Produces: permission-aware operations; compensation requires explicit reason and confirmation.

- [ ] **Step 1: Write RED row-state tests**

```ts
await releaseRow('reservation-1')
expect(row('reservation-1').getByRole('button', { name: '释放' })).toBeDisabled()
expect(row('reservation-2').getByRole('button', { name: '释放' })).toBeEnabled()
```

- [ ] **Step 2: Run RED**

Run: `npx playwright test tests/admin-inventory.spec.ts --project=chromium`

- [ ] **Step 3: Implement dense stock and reservation tabs**

Use stable table height, in-container horizontal scrolling, scoped pending keys, filters in URL, and a reconciliation result drawer. The warning mascot appears once only when mismatches require attention.

- [ ] **Step 4: Run browser/backend security tests and commit**

```powershell
git add frontend/src/views/InventoryView.vue frontend/src/api/inventory.ts frontend/src/components/admin/AdminPageToolbar.vue frontend/tests/admin-inventory.spec.ts src/test/java/com/example/monkey/inventory/interfaces/InventoryControllerTest.java
git commit -m "feat(admin): rebuild inventory operations"
```

### Task 5: Build Order, Return, Payment, And Logistics Operations

**Files:**
- Create: `frontend/src/views/admin/OrderOperationsView.vue`
- Create: `frontend/src/views/admin/ReturnOperationsView.vue`
- Create: `frontend/src/views/admin/PaymentOperationsView.vue`
- Create: `frontend/src/views/admin/LogisticsOperationsView.vue`
- Modify: `frontend/src/api/orders.ts`
- Modify: `frontend/src/api/payments.ts`
- Modify: `frontend/src/api/logistics.ts`
- Modify: `frontend/src/router/index.ts`
- Create: `frontend/tests/admin-commerce-operations.spec.ts`

**Interfaces:**
- Consumes: paged orders/shipments, return approval/confirmation, admin payment query/refund/reconciliation, fulfillment shipment creation/tracking.
- Excludes: callback/webhook push and provider-success simulation.

- [ ] **Step 1: Write RED permission/state tests**

```ts
expect(actionsFor(order('PENDING_PAYMENT'), operatorPermissions)).not.toContain('ship')
expect(actionsFor(order('RETURN_APPLIED'), operatorPermissions)).toContain('approveReturn')
expect(screen.queryByRole('button', { name: /模拟回调|推送Webhook/ })).toBeNull()
```

- [ ] **Step 2: Run RED**

Run: `npx playwright test tests/admin-commerce-operations.spec.ts --project=chromium`

- [ ] **Step 3: Implement four linked workspaces**

Use status tabs, URL filters, stable paged tables, detail drawers, and guarded actions. Reconciliation unavailable state uses inline notice and source status; it does not mark payments suspended or complete.

- [ ] **Step 4: Run tests and commit**

```powershell
git add frontend/src/views/admin/OrderOperationsView.vue frontend/src/views/admin/ReturnOperationsView.vue frontend/src/views/admin/PaymentOperationsView.vue frontend/src/views/admin/LogisticsOperationsView.vue frontend/src/api/orders.ts frontend/src/api/payments.ts frontend/src/api/logistics.ts frontend/src/router/index.ts frontend/tests/admin-commerce-operations.spec.ts
git commit -m "feat(admin): add transaction operations workspaces"
```

### Task 6: Rebuild Marketing, Membership, And Risk Operations

**Files:**
- Modify: `frontend/src/views/MarketingView.vue`
- Create: `frontend/src/views/admin/MemberOperationsView.vue`
- Modify: `frontend/src/views/RiskReviewView.vue`
- Modify: `frontend/src/api/marketing.ts`
- Modify: `frontend/src/api/membership.ts`
- Modify: `frontend/src/api/risk.ts`
- Test: `frontend/tests/admin-marketing.spec.ts`
- Test: `frontend/tests/admin-risk.spec.ts`

**Interfaces:**
- Consumes: coupon management/quote, seckill/group-buy monitoring, member levels/points/price-drop job, risk assessment/review resolution.
- Produces: manual points/level changes require reason, confirmation, permission, and audit trace.

- [ ] **Step 1: Write RED action tests**

```ts
await openPointsAdjustment()
await submitWithoutReason()
expect(api.earnPoints).not.toHaveBeenCalled()
expect(screen.getByText('请输入调整原因')).toBeVisible()
```

- [ ] **Step 2: Run RED**

Run: `npx playwright test tests/admin-marketing.spec.ts tests/admin-risk.spec.ts --project=chromium`

- [ ] **Step 3: Implement operator workspaces**

Use segmented tabs for coupon/price/seckill/group-buy and queue/table views for risk. Risk decisions show signal explanation and use shield mascot only for an empty queue or access block. Mutation errors remain in their drawer/form.

- [ ] **Step 4: Run tests and commit**

```powershell
git add frontend/src/views/MarketingView.vue frontend/src/views/admin/MemberOperationsView.vue frontend/src/views/RiskReviewView.vue frontend/src/api/marketing.ts frontend/src/api/membership.ts frontend/src/api/risk.ts frontend/tests/admin-marketing.spec.ts frontend/tests/admin-risk.spec.ts
git commit -m "feat(admin): rebuild growth and risk operations"
```

### Task 7: Make Tenant Context Validated And Exports Truthful

**Files:**
- Modify: `src/main/java/com/example/monkey/shared/interfaces/web/TenantContextFilter.java`
- Modify: `src/main/java/com/example/monkey/tenant/application/TenantApplicationService.java`
- Modify: `src/main/java/com/example/monkey/tenant/infrastructure/JpaTenantStore.java`
- Modify: `src/main/java/com/example/monkey/tenant/infrastructure/TenantDataExportJobEntity.java`
- Create: `src/main/java/com/example/monkey/tenant/domain/TenantExportProvider.java`
- Create: `src/main/java/com/example/monkey/tenant/infrastructure/UnavailableTenantExportProvider.java`
- Modify: `frontend/src/views/TenantAdminView.vue`
- Modify: `frontend/src/api/tenant.ts`
- Test: `src/test/java/com/example/monkey/shared/interfaces/web/TenantContextFilterTest.java`
- Test: `src/test/java/com/example/monkey/tenant/application/TenantApplicationServiceTest.java`
- Test: `frontend/tests/admin-tenants.spec.ts`

**Interfaces:**
- Produces: tenant context is accepted only for an active tenant the principal can access.
- Produces: export states `QUEUED|RUNNING|SUCCEEDED|FAILED|UNAVAILABLE`; only a provider artifact can set `SUCCEEDED`.

- [ ] **Step 1: Write RED tenant/export tests**

```java
mockMvc.perform(get("/api/v1/tenant/dashboard").header("X-Tenant-Id", "2").with(userFromTenant1()))
        .andExpect(status().isForbidden());
assertThat(service.requestExport(tenantId, request).status()).isEqualTo(UNAVAILABLE);
```

- [ ] **Step 2: Run RED**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=TenantContextFilterTest,TenantApplicationServiceTest,JpaTenantStoreTest' test`

Run: `npx playwright test tests/admin-tenants.spec.ts --project=chromium`

- [ ] **Step 3: Validate tenant and implement truthful state**

Reject suspended/expired tenants according to endpoint policy. Persist provider job ID/artifact URI only from the configured provider. The UI displays unavailable/failed inline and never fabricates a download action.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add src/main/java/com/example/monkey/shared/interfaces/web/TenantContextFilter.java src/main/java/com/example/monkey/tenant/application/TenantApplicationService.java src/main/java/com/example/monkey/tenant/infrastructure/JpaTenantStore.java src/main/java/com/example/monkey/tenant/infrastructure/TenantDataExportJobEntity.java src/main/java/com/example/monkey/tenant/domain/TenantExportProvider.java src/main/java/com/example/monkey/tenant/infrastructure/UnavailableTenantExportProvider.java frontend/src/views/TenantAdminView.vue frontend/src/api/tenant.ts src/test/java/com/example/monkey/shared/interfaces/web/TenantContextFilterTest.java src/test/java/com/example/monkey/tenant/application/TenantApplicationServiceTest.java frontend/tests/admin-tenants.spec.ts
git commit -m "fix(tenant): validate context and report real exports"
```

### Task 8: Encrypt TOTP And Make Privacy Jobs Tenant-Aware

**Files:**
- Modify: `src/main/java/com/example/monkey/user/infrastructure/User.java`
- Modify: `src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiCryptoService.java`
- Modify: `src/main/java/com/example/monkey/user/application/PiiRetentionService.java`
- Modify: `src/main/java/com/example/monkey/shared/application/observability/AuditService.java`
- Create: `src/main/java/com/example/monkey/tenant/domain/ActiveTenantReader.java`
- Create: `src/main/java/com/example/monkey/tenant/infrastructure/JpaActiveTenantReader.java`
- Create: `src/main/java/com/example/monkey/shared/application/tenant/ActiveTenantIterator.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V53__encrypt_totp_secret.sql`
- Test: `src/test/java/com/example/monkey/entity/PiiBlindIndexTargetMappingTest.java`
- Test: `src/test/java/com/example/monkey/order/infrastructure/OrderReviewEntityTest.java`
- Create: `src/test/java/com/example/monkey/privacy/MultiTenantPrivacyJobIntegrationTest.java`

**Interfaces:**
- Produces: PII encryption defaults true; startup fails when encryption is enabled without key material.
- Produces: active-tenant iterator executes privacy/audit/retention jobs for tenant 1 and tenant 2 separately.

- [ ] **Step 1: Write RED mapping and multi-tenant tests**

```java
assertThat(field(User.class, "totpSecret").getAnnotation(Convert.class).converter()).isEqualTo(PiiStringConverter.class);
assertThat(field(OrderReviewEntity.class, "content").getAnnotation(Convert.class).converter()).isEqualTo(EncryptedStringAttributeConverter.class);
assertThat(processedTenantIds).containsExactlyInAnyOrder(1L, 2L);
```

- [ ] **Step 2: Run RED**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=PiiBlindIndexTargetMappingTest,OrderReviewEntityTest,MultiTenantPrivacyJobIntegrationTest,PiiCryptoServiceTest' test`

- [ ] **Step 3: Enable safe defaults and tenant iteration**

Use `@Value("${monkeyshop.security.pii.encryption-enabled:true}")` only as compatibility fallback; prefer typed configuration. Backfill plaintext through the existing backfill service before setting migration metadata complete. Fix forget-me address iteration to query only non-deleted rows or terminate after processed IDs.

- [ ] **Step 4: Run GREEN/migration tests and commit**

```powershell
git add src/main/java/com/example/monkey/user/infrastructure/User.java src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiCryptoService.java src/main/java/com/example/monkey/user/application/PiiRetentionService.java src/main/java/com/example/monkey/shared/application/observability/AuditService.java src/main/java/com/example/monkey/tenant/domain/ActiveTenantReader.java src/main/java/com/example/monkey/tenant/infrastructure/JpaActiveTenantReader.java src/main/java/com/example/monkey/shared/application/tenant/ActiveTenantIterator.java src/main/resources/application.yml src/main/resources/db/migration/V53__encrypt_totp_secret.sql src/test/java/com/example/monkey/entity/PiiBlindIndexTargetMappingTest.java src/test/java/com/example/monkey/order/infrastructure/OrderReviewEntityTest.java src/test/java/com/example/monkey/privacy/MultiTenantPrivacyJobIntegrationTest.java
git commit -m "fix(privacy): encrypt sensitive fields across tenants"
```

Before staging, select only files changed by this task; the broad Java paths are discovery aids, not permission to stage unrelated changes.

### Task 9: Admin Accessibility And Dense Layout Gate

**Files:**
- Modify: `frontend/tests/a11y-routes.spec.ts`
- Modify: `frontend/tests/admin-primitives.spec.ts`
- Create: `frontend/tests/admin-responsive.spec.ts`
- Modify: `frontend/src/components/admin/DataTableShell.vue` if present; otherwise modify `frontend/src/components/ui/DataTableShell.vue`

**Interfaces:**
- Produces: all admin routes tested at 390/768/1024/1440 in light/dark; desktop tables use container scrolling and mobile rows preserve command access.

- [ ] **Step 1: Add RED matrix**

```ts
expect(await page.locator('h1').count()).toBe(1)
expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
await expect(page.getByRole('dialog', { name: '管理导航' })).toHaveAttribute('aria-modal', 'true')
```

- [ ] **Step 2: Run RED**

Run: `npx playwright test tests/admin-primitives.spec.ts tests/admin-responsive.spec.ts tests/a11y-routes.spec.ts --project=chromium`

- [ ] **Step 3: Fix focus, overflow, stable dimensions, and labels**

Every icon action receives tooltip + `aria-label`; all filter controls have programmatic labels; loading text and state tags cannot resize columns.

- [ ] **Step 4: Run frontend gate and commit**

Run: `npm run lint`

Run: `npm run typecheck`

Run: `npm run test:unit`

Run: `npm run build`

Run: `npx playwright test tests/admin-primitives.spec.ts tests/admin-responsive.spec.ts tests/a11y-routes.spec.ts --project=chromium`

```powershell
git add frontend/tests/a11y-routes.spec.ts frontend/tests/admin-primitives.spec.ts frontend/tests/admin-responsive.spec.ts frontend/src/components/ui/DataTableShell.vue
git commit -m "test(admin): gate dense accessible workspaces"
```

## Plan Acceptance

- All approved admin navigation groups and workspaces exist, are permission-aware, and consume real APIs.
- Payment callbacks, logistics webhooks, internal compensation, and sandbox simulation are absent from ordinary UI.
- Catalog upload cannot publish unscanned data or read another tenant's object.
- Tenant headers are validated against principal access and tenant status; export UI reflects actual provider state.
- TOTP, review content, and configured PII are encrypted by default; scheduled privacy jobs cover tenant 1 and tenant 2.
- Admin routes pass lint, typecheck, unit, build, Playwright, Axe, responsive, and dark-mode checks.
