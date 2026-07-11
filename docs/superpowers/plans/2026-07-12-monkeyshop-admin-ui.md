# MonkeyShop Admin UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the six admin routes into a quiet, dense, predictable operations workspace with route-backed query state, bounded tables, explicit mutations, and stable real-time updates.

**Architecture:** Admin views are content-only children of the route-owned admin shell. Each page uses `PageHeader`, an unframed `PageToolbar`, bounded `DataTableShell` surfaces, typed URL query state where useful, and mutation-specific pending keys. Polling and high-frequency updates pause or coalesce outside the visible page.

**Tech Stack:** Vue 3.5, Vue Router 5, Pinia 3, Element Plus 2.14, TypeScript 6, Vitest, Playwright, Axe.

## Global Constraints

- Foundation and consumer-shared primitives must pass before this plan.
- Admin desktop uses the 232px sidebar and 56px topbar defined by the foundation plan.
- Page sections are unframed; tables, dialogs, drawers, and repeated metrics may be bounded surfaces.
- Filters, search, sort, and pagination persist in the URL when they affect a result set.
- A row mutation disables only the affected row/action unless the API contract requires a page-wide lock.
- Full table resets are forbidden for progress-only or single-row updates.
- Polling pauses while `document.visibilityState !== 'visible'`.
- Tables own horizontal scrolling; the page itself never scrolls horizontally.
- Status is communicated with localized text and iconography, never color alone.
- Direct `ElMessage` and page-specific `ElMessageBox` calls are forbidden.
- Stage and commit only current-task files; preserve unrelated dirty-worktree changes.

---

## File Structure

### New files

- `frontend/src/components/admin/AdminPageToolbar.vue`: compact search/filter/action toolbar.
- `frontend/src/components/admin/MetricStrip.vue`: dense semantic metric row.
- `frontend/src/composables/usePageVisibility.ts`: visibility-aware polling helper.
- `frontend/src/composables/usePageVisibility.test.ts`: polling state tests.
- `frontend/tests/admin-flows.spec.ts`: admin desktop/mobile workflows.

### Modified files

- `frontend/src/views/AdminView.vue`
- `frontend/src/views/InventoryView.vue`
- `frontend/src/views/MarketingView.vue`
- `frontend/src/views/RiskReviewView.vue`
- `frontend/src/views/DashboardView.vue`
- `frontend/src/views/TenantAdminView.vue`
- `frontend/src/api/admin.ts`
- `frontend/src/locales/index.ts`
- `frontend/src/styles/components.css`
- `frontend/scripts/ui-smoke.mjs`

## Interfaces

```ts
export interface ToolbarFilterOption {
  label: string
  value: string
}

export interface MetricItem {
  key: string
  label: string
  value: string | number
  tone?: 'neutral' | 'info' | 'success' | 'warning' | 'danger'
}

export interface PageVisibilityController {
  isVisible: Readonly<Ref<boolean>>
  start(callback: () => void | Promise<void>, intervalMs: number): void
  stop(): void
}
```

---

### Task 1: Add Shared Admin Toolbar, Metrics, and Visibility Budget

**Files:**
- Create: `frontend/src/components/admin/AdminPageToolbar.vue`
- Create: `frontend/src/components/admin/MetricStrip.vue`
- Create: `frontend/src/composables/usePageVisibility.ts`
- Create: `frontend/src/composables/usePageVisibility.test.ts`
- Modify: `frontend/src/styles/components.css`
- Test: `frontend/tests/admin-flows.spec.ts`

**Interfaces:**
- Produces: compact toolbar and metrics contracts plus visibility-aware polling.

- [ ] **Step 1: Write failing polling tests**

Define the test helper before the cases:

```ts
function setDocumentVisibility(value: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    value,
  })
}
```

```ts
it('stops scheduled refresh while the page is hidden', () => {
  vi.useFakeTimers()
  const controller = usePageVisibility()
  const callback = vi.fn()
  controller.start(callback, 5000)
  setDocumentVisibility('hidden')
  document.dispatchEvent(new Event('visibilitychange'))
  vi.advanceTimersByTime(15000)
  expect(callback).not.toHaveBeenCalled()
})
```

Add a resume test proving exactly one interval restarts when visibility returns.

- [ ] **Step 2: Implement `usePageVisibility`**

Keep one timer ID, clear it on hidden/unmount, and schedule one callback immediately when the page returns before restarting the interval. Do not queue missed ticks.

- [ ] **Step 3: Implement `AdminPageToolbar.vue`**

Expose `search`, `filters`, and `actions` slots. The toolbar is unframed, wraps predictably, and keeps the primary action at the end. Search input is at most 320px on desktop and full width on mobile.

- [ ] **Step 4: Implement `MetricStrip.vue`**

Render a semantic list with stable numeric alignment and no decorative nested cards. At wide viewports metrics share one row; at narrow viewports they use a two-column then one-column grid.

- [ ] **Step 5: Add browser geometry tests**

Assert toolbar controls do not overflow at 390px, metric values use tabular numerals, and admin sections do not have page-section shadows.

- [ ] **Step 6: Run focused checks**

Run:

```powershell
npm run test:unit -- src/composables/usePageVisibility.test.ts
npx playwright test tests/admin-flows.spec.ts --grep "toolbar|metric|visibility" --project=chromium
npm run build
```

Expected: all pass.

- [ ] **Step 7: Commit shared admin primitives**

```powershell
git add frontend/src/components/admin frontend/src/composables/usePageVisibility.ts frontend/src/composables/usePageVisibility.test.ts frontend/src/styles/components.css frontend/tests/admin-flows.spec.ts
git commit -m "feat(ui): add admin workflow primitives"
```

---

### Task 2: Rebuild Product, Order, and Audit Operations

**Files:**
- Modify: `frontend/src/views/AdminView.vue`
- Modify: `frontend/src/api/admin.ts`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/admin-flows.spec.ts`

**Interfaces:**
- Produces: product table CRUD, order action rows, and trace lookup with independent async state.

- [ ] **Step 1: Write failing product mutation tests**

Test create/edit validation, image upload, delete confirmation, row-level pending, and failure recovery:

```ts
test('deleting one product disables only that row', async ({ page }) => {
  await page.goto('/admin')
  const firstRow = page.locator('.product-table .el-table__row').first()
  const secondRow = page.locator('.product-table .el-table__row').nth(1)
  await firstRow.getByRole('button', { name: '删除' }).click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(firstRow.getByRole('button', { name: '删除' })).toBeDisabled()
  await expect(secondRow.getByRole('button', { name: '删除' })).toBeEnabled()
})
```

- [ ] **Step 2: Separate page resources**

Use independent async state for stats/products/orders and trace events. A trace search must not block product or order controls. Replace one page-wide `loading` boolean with resource and mutation keys.

- [ ] **Step 3: Recompose product management**

Use `PageHeader` and `AdminPageToolbar`. Product CRUD lives in a bounded table. Create/edit uses one dialog with schema-backed validation, image preview, upload pending state, and unsaved-change confirmation.

- [ ] **Step 4: Recompose order operations**

Keep order search in URL query state. Show localized status and only valid next actions. Each action key is `${action}:${orderId}` and only that action shows pending.

- [ ] **Step 5: Recompose audit trace**

Trace lookup is an unframed tool section with input, submit, explicit empty/error states, and a compact timeline. User IDs and trace IDs use tabular/monospace utility text without becoming decorative chips.

- [ ] **Step 6: Migrate feedback**

Use `notify.confirm` for product deletion and refunds; `notify.fromApiError` for failures; successful save/delete/order mutation uses grouped app feedback.

- [ ] **Step 7: Run admin route checks**

Run:

```powershell
npx playwright test tests/admin-flows.spec.ts --grep "product|order|audit" --project=chromium
npm run lint
npm run build
```

Expected: tests pass and no page-wide mutation lock remains.

- [ ] **Step 8: Commit product/order operations**

```powershell
git add frontend/src/views/AdminView.vue frontend/src/api/admin.ts frontend/src/locales/index.ts frontend/tests/admin-flows.spec.ts
git commit -m "feat(ui): streamline product and order operations"
```

---

### Task 3: Make Inventory Query-Driven and Row-Stable

**Files:**
- Modify: `frontend/src/views/InventoryView.vue`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/admin-flows.spec.ts`

**Interfaces:**
- Produces: URL-backed `{ skuId, region }` inventory query and stable reservation rows.

- [ ] **Step 1: Write failing query and row-update tests**

Assert reload preserves SKU/region and a reservation mutation changes only the matching stable row. Assert safety-stock state includes text and icon.

- [ ] **Step 2: Persist inventory query**

Parse positive integer `skuId` and normalized region string from route query. Debounce query writes by 250ms and trigger resource update without clearing the previous valid table.

- [ ] **Step 3: Recompose stock tables**

Use a single toolbar for SKU, region, refresh, reserve, release, and reconcile tasks. Warehouse stock and reservation reconciliation are separate bounded tables with explicit empty/error branches.

- [ ] **Step 4: Apply stable row patches**

After reserve/release, replace or insert only the row matching `reservationKey`; after reconciliation, patch discrepancy rows by `skuId:warehouseId` rather than replacing unrelated data.

- [ ] **Step 5: Run inventory checks**

Run:

```powershell
npx playwright test tests/admin-flows.spec.ts --grep "inventory" --project=chromium
npm run lint
npm run build
```

Expected: query, row update, mobile overflow, and status tests pass.

- [ ] **Step 6: Commit inventory UI**

```powershell
git add frontend/src/views/InventoryView.vue frontend/src/locales/index.ts frontend/tests/admin-flows.spec.ts
git commit -m "feat(ui): make inventory operations query-driven"
```

---

### Task 4: Separate Marketing Tasks into Focused Workflows

**Files:**
- Modify: `frontend/src/views/MarketingView.vue`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/admin-flows.spec.ts`

**Interfaces:**
- Produces: independent coupon, quote, seckill, and group-buy task sections.

- [ ] **Step 1: Write failing independent-pending tests**

Assert a coupon claim in progress does not disable quote, seckill, or group-buy controls. Assert each task keeps its own result/error surface.

- [ ] **Step 2: Introduce task-specific mutation keys**

Use `coupon:claim`, `coupon:redeem`, `coupon:return`, `quote`, `seckill`, and `group-buy` keys. Each task has one derived pending state and one last-result state.

- [ ] **Step 3: Recompose page layout**

Use `PageHeader`, then a two-column task grid at wide desktop and one column below 1000px. Each task is a genuinely framed tool surface; do not wrap the whole page or nest tools inside decorative cards.

- [ ] **Step 4: Add inline validation and feedback**

Validate positive IDs, amounts, quantities, and required idempotency keys before requests. Show calculation results next to the quote form and successful IDs in each task result; use app feedback only for completed mutations.

- [ ] **Step 5: Run marketing checks**

Run:

```powershell
npx playwright test tests/admin-flows.spec.ts --grep "marketing|coupon|seckill|group" --project=chromium
npm run test:a11y
npm run build
```

Expected: independent-state and accessibility tests pass.

- [ ] **Step 6: Commit marketing workflows**

```powershell
git add frontend/src/views/MarketingView.vue frontend/src/locales/index.ts frontend/tests/admin-flows.spec.ts
git commit -m "feat(ui): separate marketing workflows"
```

---

### Task 5: Strengthen Risk Assessment and Review Decisions

**Files:**
- Modify: `frontend/src/views/RiskReviewView.vue`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/admin-flows.spec.ts`

**Interfaces:**
- Produces: separate assessment tool and manual-review queue with guarded decisions.

- [ ] **Step 1: Write failing decision tests**

Assert resolution note and TOTP requirements are enforced before approve/reject/block requests. Assert raw signal/status enums are not visible.

- [ ] **Step 2: Recompose risk assessment**

The assessment form and result form one top tool band. Score, decision, signals, automatic product action, and token revocation use localized labels with text and icons.

- [ ] **Step 3: Recompose manual review queue**

Use a bounded table with filters for status and score range in URL state. Put decision actions in a row detail drawer on narrow screens and a fixed action column on wide screens.

- [ ] **Step 4: Guard high-impact actions**

Block requires confirmation; approve/reject require a resolution note; TOTP appears only when required. Pending is keyed by `review:${id}:${decision}`.

- [ ] **Step 5: Run risk checks**

Run:

```powershell
npx playwright test tests/admin-flows.spec.ts --grep "risk|review decision" --project=chromium
npm run test:a11y
npm run build
```

Expected: all decision, responsive, and status tests pass.

- [ ] **Step 6: Commit risk UI**

```powershell
git add frontend/src/views/RiskReviewView.vue frontend/src/locales/index.ts frontend/tests/admin-flows.spec.ts
git commit -m "feat(ui): clarify risk assessment decisions"
```

---

### Task 6: Bound Dashboard Polling and Preserve Valid Data

**Files:**
- Modify: `frontend/src/views/DashboardView.vue`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/admin-flows.spec.ts`

**Interfaces:**
- Consumes: `usePageVisibility`, `useAsyncState`, `MetricStrip`.
- Produces: visibility-aware 5-second dashboard refresh with preserved data.

- [ ] **Step 1: Write failing visibility and update tests**

Use `page.evaluate` to change visibility in a controlled test fixture. Assert no dashboard request while hidden, one immediate refresh when visible, and prior metrics remain visible during updating.

- [ ] **Step 2: Replace raw interval ownership**

Remove local `window.setInterval` management and use `usePageVisibility().start(loadDashboard, 5000)`. Manual pause stops the controller; resume performs one refresh and starts one timer.

- [ ] **Step 3: Recompose dashboard surfaces**

Use `PageHeader`, `MetricStrip`, one bounded funnel table, and two unframed profile sections. Product profile input is a compact tool action, not part of the section title geometry.

- [ ] **Step 4: Preserve stale-while-updating data**

Dashboard, current user profile, and product profile use separate async state. Updating shows a compact indicator and retains the last valid value. A product profile error does not clear dashboard metrics.

- [ ] **Step 5: Run dashboard checks**

Run:

```powershell
npx playwright test tests/admin-flows.spec.ts --grep "dashboard|polling" --project=chromium
npm run test:unit -- src/composables/usePageVisibility.test.ts
npm run build
```

Expected: polling, stale-data, and layout tests pass.

- [ ] **Step 6: Commit dashboard UI**

```powershell
git add frontend/src/views/DashboardView.vue frontend/src/locales/index.ts frontend/tests/admin-flows.spec.ts
git commit -m "feat(ui): bound dashboard refresh work"
```

---

### Task 7: Rebuild Tenant Operations as Master-Detail

**Files:**
- Modify: `frontend/src/views/TenantAdminView.vue`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/admin-flows.spec.ts`

**Interfaces:**
- Produces: URL-selected tenant, list/detail master-detail workspace, and tabbed config/billing/export tasks.

- [ ] **Step 1: Write failing tenant-selection tests**

Assert selected tenant ID persists in `?tenant=1`, reload restores selection, list stays usable while details update, and a failed detail request does not clear the list.

- [ ] **Step 2: Separate tenant list and detail state**

Use independent async resources for dashboard/list and selected-tenant configs/bills/exports. A monotonically increasing selection request ID prevents an earlier tenant response from overwriting a later selection.

- [ ] **Step 3: Recompose master-detail layout**

At desktop use `minmax(360px, 0.9fr) minmax(0, 1.35fr)`. The list panel owns its table and create action. The detail panel owns selected tenant actions and tabs. At mobile, list and detail stack with a clear back-to-list control.

- [ ] **Step 4: Move forms into dedicated surfaces**

Create tenant uses a dialog with schema validation. Config uses a validated JSON editor field with inline parse errors. Bill and export forms live in their corresponding tabs, not three simultaneous columns.

- [ ] **Step 5: Guard tenant mutations**

Downgrade requires confirmation; renew, save config, generate bill, and export use independent pending keys. Successful mutations patch or refresh only their relevant resource.

- [ ] **Step 6: Run tenant checks**

Run:

```powershell
npx playwright test tests/admin-flows.spec.ts --grep "tenant" --project=chromium
npm run test:a11y
npm run lint
npm run build
```

Expected: selection race, responsive, validation, and mutation tests pass.

- [ ] **Step 7: Commit tenant operations**

```powershell
git add frontend/src/views/TenantAdminView.vue frontend/src/locales/index.ts frontend/tests/admin-flows.spec.ts
git commit -m "feat(ui): rebuild tenant master detail operations"
```

---

### Task 8: Complete Admin Feedback and Route Coverage

**Files:**
- Modify: all admin views in this plan.
- Modify: `frontend/scripts/ui-smoke.mjs`
- Modify: `frontend/tests/admin-flows.spec.ts`

**Interfaces:**
- Produces: no direct Element message APIs and full admin route coverage.

- [ ] **Step 1: Enforce static feedback and raw-color rules**

These scans must have no output:

```powershell
rg -n "ElMessage|ElNotification" frontend/src/views/AdminView.vue frontend/src/views/InventoryView.vue frontend/src/views/MarketingView.vue frontend/src/views/RiskReviewView.vue frontend/src/views/DashboardView.vue frontend/src/views/TenantAdminView.vue
rg -n "#[0-9A-Fa-f]{3,8}|rgba?\(" frontend/src/views/AdminView.vue frontend/src/views/InventoryView.vue frontend/src/views/MarketingView.vue frontend/src/views/RiskReviewView.vue frontend/src/views/DashboardView.vue frontend/src/views/TenantAdminView.vue
```

- [ ] **Step 2: Exercise all admin routes at three viewports**

Run `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, and `/tenants` at 390x844, 768x1024, and 1440x900. Assert no page overflow, no stuck loading, no raw enums/errors, and no console warning/error.

- [ ] **Step 3: Run the admin completion gate**

Run:

```powershell
npm run test:unit
npm run lint
npm run build
npm run test:a11y
npx playwright test tests/admin-flows.spec.ts --project=chromium
npm run test:ui-smoke
```

Expected: all commands pass.

- [ ] **Step 4: Commit admin completion**

```powershell
git add frontend/src/views/AdminView.vue frontend/src/views/InventoryView.vue frontend/src/views/MarketingView.vue frontend/src/views/RiskReviewView.vue frontend/src/views/DashboardView.vue frontend/src/views/TenantAdminView.vue frontend/scripts/ui-smoke.mjs frontend/tests/admin-flows.spec.ts
git commit -m "test(ui): complete admin route coverage"
```

---

## Admin Plan Completion Gate

The plan is complete only when product/order/audit, inventory, marketing, risk, dashboard, and tenant workflows pass desktop/tablet/mobile tests; route-backed state survives reload; polling sleeps while hidden; row mutations do not freeze unrelated controls; and no admin view directly owns global messages, raw colors, or application chrome.
