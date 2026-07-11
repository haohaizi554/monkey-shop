# MonkeyShop UI Foundation and Dual-Mode Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reliable UI test baseline, semantic visual foundation, recoverable async/feedback primitives, and route-owned consumer/admin/auth shells for every MonkeyShop page.

**Architecture:** `App.vue` owns the root error boundary and route layout. `AppShell.vue` selects consumer, admin, or auth chrome from `route.meta.area`; views become content-only. Semantic CSS tokens and focused primitives (`useAsyncState`, `useNotify`, `AsyncStateView`, `PageHeader`, `DataTableShell`) provide stable contracts for all later page plans.

**Tech Stack:** Vue 3.5, Vue Router 5, Pinia 3, Element Plus 2.14, TypeScript 6, Vite 8, Vitest, Playwright, Axe.

## Global Constraints

- Preserve Vue, Element Plus, Pinia, current API modules, and backend business contracts.
- Route areas are exactly `consumer`, `admin`, and `auth`.
- Primary color is Forest `#176B4D`; accent is Canopy Gold `#B7791F`; info is Clear Blue `#2563A6`.
- Neutral colors are Ink `#172033`, Muted `#667085`, Line `#D8DEE8`, Canvas `#F4F6F8`, and Surface `#FFFFFF`.
- Success is `#067647`, warning is `#B54708`, and danger is `#B42318`.
- Spacing uses 4/8/12/16/20/24/32/40/48/64px; controls use 6px radius; cards/dialogs use at most 8px radius.
- Fast motion is 140ms; structural motion is 220ms; all motion honors `prefers-reduced-motion`.
- Page sections are unframed; cards are reserved for repeated entities, dialogs, and genuinely framed tools.
- No page may display raw backend copy such as `Too many requests` or `Operation is not permitted`.
- Desktop icon controls are at least 36x36px; mobile touch targets are at least 44x44px.
- Do not revert unrelated dirty-worktree changes; stage and commit only files owned by each task.

---

## File Structure

### New files

- `frontend/src/router/route-meta.d.ts`: Vue Router meta typing for route area and page title keys.
- `frontend/src/components/shell/ConsumerHeader.vue`: consumer desktop/mobile top chrome.
- `frontend/src/components/shell/ConsumerBottomNav.vue`: mobile consumer navigation.
- `frontend/src/components/shell/AdminSidebar.vue`: grouped admin navigation and mobile drawer.
- `frontend/src/components/shell/AdminTopbar.vue`: admin breadcrumb/title/account actions.
- `frontend/src/components/feedback/AppFeedbackHost.vue`: accessible grouped feedback stack.
- `frontend/src/components/ui/PageHeader.vue`: shared page title and actions.
- `frontend/src/components/ui/AsyncStateView.vue`: loading/empty/error rendering contract.
- `frontend/src/components/ui/DataTableShell.vue`: table scroll, empty, and footer ownership.
- `frontend/src/styles/tokens.css`: semantic light/dark tokens and Element Plus token bridge.
- `frontend/src/styles/base.css`: reset, typography, focus, and reduced motion.
- `frontend/src/styles/shell.css`: consumer/admin/auth shell layouts.
- `frontend/src/styles/components.css`: shared controls, feedback, tables, dialogs, and states.
- `frontend/src/composables/useAsyncState.test.ts`: state-machine tests.
- `frontend/src/composables/useNotify.test.ts`: grouping and API-error mapping tests.
- `frontend/tests/shell.spec.ts`: route-area, keyboard, responsive, and accessible-name browser tests.

### Modified files

- `frontend/package.json`: unit-test scripts and font/test dependencies.
- `frontend/package-lock.json`: pinned dependency graph.
- `frontend/src/main.ts`: import bundled font and split styles.
- `frontend/src/App.vue`: error boundary, shell, feedback host, and route outlet.
- `frontend/src/router/index.ts`: route area/title metadata.
- `frontend/src/components/AppShell.vue`: route-aware shell composition only.
- `frontend/src/components/AppErrorBoundary.vue`: route recovery without exposing raw exception details.
- `frontend/src/composables/useAsyncState.ts`: latest-request-wins state machine.
- `frontend/src/composables/useNotify.ts`: app-owned grouped feedback instead of direct `ElMessage`.
- `frontend/src/styles/main.css`: import-only style entry after migration.
- `frontend/src/locales/index.ts`: shell, state, feedback, and recovery copy.
- Every `frontend/src/views/*.vue`: remove `AppShell` imports and wrappers; no page behavior changes in this plan.
- `frontend/tests/a11y.spec.ts`: unique shell locators and complete request mocks.
- `frontend/scripts/ui-smoke.mjs`: route/viewport diagnostics and deterministic readiness.

## Interfaces

```ts
export type RouteArea = 'consumer' | 'admin' | 'auth'

declare module 'vue-router' {
  interface RouteMeta {
    area: RouteArea
    titleKey: string
    requiresAuth?: boolean
    requiresAdmin?: boolean
    publicOnly?: boolean
  }
}

export type AsyncStatus = 'idle' | 'loading' | 'updating' | 'success' | 'empty' | 'error'

export interface AsyncLoadContext {
  signal: AbortSignal
}

export interface AsyncLoadOptions<T> {
  isEmpty?: (value: T) => boolean
  preserveData?: boolean
  timeoutMs?: number
}

export interface FeedbackOptions {
  key?: string
  title?: string
  message: string
  level?: 'success' | 'info' | 'warning' | 'error'
  duration?: number
  traceId?: string
}

export interface ConfirmOptions {
  title?: string
  content: string
  confirmText?: string
  cancelText?: string
  type?: 'success' | 'info' | 'warning' | 'error'
}

export interface NotifyApi {
  success(message: string, options?: Omit<FeedbackOptions, 'message' | 'level'>): string
  info(message: string, options?: Omit<FeedbackOptions, 'message' | 'level'>): string
  warning(message: string, options?: Omit<FeedbackOptions, 'message' | 'level'>): string
  error(message: string, options?: Omit<FeedbackOptions, 'message' | 'level'>): string
  fromApiError(error: unknown, fallback: string): string
  confirm(options: ConfirmOptions): Promise<boolean>
  dismiss(id: string): void
}
```

---

### Task 1: Make the Current UI Baseline Diagnostic

**Files:**
- Modify: `frontend/scripts/ui-smoke.mjs`
- Modify: `frontend/tests/a11y.spec.ts`
- Modify: `frontend/src/views/MembershipView.vue`
- Modify: `frontend/src/views/ProfileView.vue`
- Modify: `frontend/src/views/CartView.vue`

**Interfaces:**
- Consumes: existing route list and API fixtures in `ui-smoke.mjs`.
- Produces: deterministic per-route diagnostics and a clean lint baseline.

- [ ] **Step 1: Add route context before each smoke navigation**

Add this log immediately before `checkRoute` is called:

```js
console.log(`[ui-smoke] start ${route.path} [${viewport.name}]`)
```

Wrap each route invocation so an infrastructure exception is returned as a normal failed result:

```js
try {
  results.push(await checkRoute(browser, baseURL, route, viewport))
} catch (error) {
  results.push({
    route: route.path,
    viewport: viewport.name,
    apiRequests: 0,
    status: 'fail',
    failures: [`${route.path} [${viewport.name}]: ${error instanceof Error ? error.message : String(error)}`],
  })
}
```

- [ ] **Step 2: Replace global network-idle waiting with app readiness**

After `page.goto`, wait for the shell and for visible loading masks to settle:

```js
await page.goto(`${baseURL}${route.path}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
await page.waitForSelector('.app-shell', { state: 'visible', timeout: 10000 })
await page
  .waitForFunction(() => !document.querySelector('.el-loading-mask:not([style*="display: none"])'), null, {
    timeout: 5000,
  })
  .catch(() => undefined)
await page.waitForTimeout(100)
```

- [ ] **Step 3: Fix the duplicate accessible-name assertion**

In `a11y.spec.ts`, scope the marketplace assertion to primary navigation:

```ts
await expect(
  page.getByRole('navigation', { name: 'Primary' }).getByRole('link', {
    name: '商城',
    exact: true,
  }),
).toBeVisible()
```

Add mocks for `/api/v1/tracking/events`, `/api/v1/catalog/spus/1`, and `/images/monkey.png` so the test emits no proxy errors.

- [ ] **Step 4: Fix the current lint failures without changing behavior**

Remove unused `dismissed` catch variables from `MembershipView.vue` and `ProfileView.vue`. Reformat the multiline checkout `RouterLink` in `CartView.vue`.

- [ ] **Step 5: Run the baseline checks**

Run:

```powershell
npm run lint
npm run build
npm run test:a11y
npm run test:ui-smoke
```

Expected: lint has 0 errors/0 warnings; build passes; Axe tests pass; smoke prints every route/viewport even if later UI assertions still fail.

- [ ] **Step 6: Commit the baseline diagnostics**

```powershell
git add frontend/scripts/ui-smoke.mjs frontend/tests/a11y.spec.ts frontend/src/views/MembershipView.vue frontend/src/views/ProfileView.vue frontend/src/views/CartView.vue
git commit -m "test(ui): make route checks deterministic"
```

---

### Task 2: Add Unit-Test and Bundled Font Foundations

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Modify: `frontend/src/main.ts`
- Create: `frontend/src/test/setup.ts`

**Interfaces:**
- Produces: `npm run test:unit`; bundled Noto Sans SC variable font available to CSS.

- [ ] **Step 1: Install exact dependencies and pin them in the lockfile**

Run:

```powershell
npm install @fontsource-variable/noto-sans-sc
npm install --save-dev vitest jsdom
```

Expected: packages resolve and `package-lock.json` records exact versions.

- [ ] **Step 2: Add unit-test scripts**

Add to `package.json`:

```json
"test:unit": "vitest run --environment jsdom",
"test:unit:watch": "vitest --environment jsdom"
```

- [ ] **Step 3: Import the bundled font once**

At the top of `main.ts` add:

```ts
import '@fontsource-variable/noto-sans-sc'
```

- [ ] **Step 4: Add deterministic browser globals for unit tests**

Create `src/test/setup.ts`:

```ts
import { afterEach, vi } from 'vitest'

afterEach(() => {
  vi.useRealTimers()
  localStorage.clear()
})
```

- [ ] **Step 5: Verify the dependency and build contract**

Run:

```powershell
npm run test:unit -- --passWithNoTests
npm run build
```

Expected: unit runner exits 0 and the font is emitted as a local build asset.

- [ ] **Step 6: Commit the test/font foundation**

```powershell
git add frontend/package.json frontend/package-lock.json frontend/src/main.ts frontend/src/test/setup.ts
git commit -m "build(ui): add unit tests and bundled typography"
```

---

### Task 3: Implement the Semantic Token Contract

**Files:**
- Create: `frontend/src/styles/tokens.css`
- Create: `frontend/src/styles/base.css`
- Create: `frontend/src/styles/components.css`
- Create: `frontend/src/styles/shell.css`
- Modify: `frontend/src/styles/main.css`

**Interfaces:**
- Produces: stable `--color-*`, `--space-*`, `--radius-*`, `--shadow-*`, and `--motion-*` tokens.

- [ ] **Step 1: Write the token contract**

Create `tokens.css` with this light-theme core:

```css
:root {
  color-scheme: light;
  --color-canvas: #f4f6f8;
  --color-surface: #ffffff;
  --color-surface-subtle: #f8fafb;
  --color-text: #172033;
  --color-text-muted: #667085;
  --color-line: #d8dee8;
  --color-line-strong: #aeb8c7;
  --color-brand: #176b4d;
  --color-brand-strong: #10523b;
  --color-brand-soft: #e8f3ee;
  --color-accent: #b7791f;
  --color-accent-soft: #fff4dc;
  --color-info: #2563a6;
  --color-info-soft: #eaf2fb;
  --color-success: #067647;
  --color-success-soft: #e7f6ef;
  --color-warning: #b54708;
  --color-warning-soft: #fff3e6;
  --color-danger: #b42318;
  --color-danger-soft: #fdefed;
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 20px;
  --space-6: 24px;
  --space-8: 32px;
  --space-10: 40px;
  --space-12: 48px;
  --space-16: 64px;
  --radius-control: 6px;
  --radius-surface: 8px;
  --radius-pill: 999px;
  --shadow-card: 0 4px 14px rgb(23 32 51 / 0.06);
  --shadow-overlay: 0 18px 48px rgb(23 32 51 / 0.18);
  --motion-fast: 140ms cubic-bezier(0.2, 0, 0, 1);
  --motion-structure: 220ms cubic-bezier(0.2, 0, 0, 1);
}
```

Add this graphite dark theme:

```css
html.dark {
  color-scheme: dark;
  --color-canvas: #101412;
  --color-surface: #171c19;
  --color-surface-subtle: #1c231f;
  --color-text: #edf3ef;
  --color-text-muted: #a5b0a9;
  --color-line: #303a34;
  --color-line-strong: #4b5a51;
  --color-brand: #62c69e;
  --color-brand-strong: #84d7b5;
  --color-brand-soft: #18362a;
  --color-accent: #e0a94b;
  --color-accent-soft: #3c2b10;
  --color-info: #7fb1e6;
  --color-info-soft: #172d45;
  --color-success: #64c99a;
  --color-success-soft: #143427;
  --color-warning: #f4b35f;
  --color-warning-soft: #422913;
  --color-danger: #f38b82;
  --color-danger-soft: #421d1b;
  --shadow-card: 0 4px 14px rgb(0 0 0 / 0.2);
  --shadow-overlay: 0 18px 48px rgb(0 0 0 / 0.38);
}

:root,
html.dark {
  --el-color-primary: var(--color-brand);
  --el-color-success: var(--color-success);
  --el-color-warning: var(--color-warning);
  --el-color-danger: var(--color-danger);
  --el-color-info: var(--color-info);
  --el-text-color-primary: var(--color-text);
  --el-text-color-regular: var(--color-text);
  --el-text-color-secondary: var(--color-text-muted);
  --el-border-color: var(--color-line);
  --el-border-color-light: var(--color-line);
  --el-fill-color-blank: var(--color-surface);
  --el-bg-color: var(--color-surface);
  --el-bg-color-page: var(--color-canvas);
}
```

- [ ] **Step 2: Move resets and typography into `base.css`**

Set the bundled font and stable layout primitives:

```css
html {
  font-family: 'Noto Sans SC Variable', 'Noto Sans SC', sans-serif;
  letter-spacing: 0;
  background: var(--color-canvas);
  color: var(--color-text);
}

body {
  min-width: 320px;
  min-height: 100vh;
  margin: 0;
  background: var(--color-canvas);
}

:focus-visible {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
}
```

- [ ] **Step 3: Move shared Element Plus surfaces into `components.css`**

Define controls, dialogs, poppers, tables, status tags, buttons, skeletons, and feedback using tokens only. Keep cards at 8px radius or less and remove page-section shadows.

- [ ] **Step 4: Make `main.css` import-only**

```css
@import './tokens.css';
@import './base.css';
@import './shell.css';
@import './components.css';
```

- [ ] **Step 5: Verify token migration**

Run:

```powershell
npm run lint
npm run build
rg -n "#[0-9A-Fa-f]{3,8}|rgba?\(" frontend/src/styles --glob "!tokens.css"
```

Expected: build passes and the raw-color scan has no output outside `tokens.css`.

- [ ] **Step 6: Commit the token contract**

```powershell
git add frontend/src/styles
git commit -m "feat(ui): establish semantic design tokens"
```

---

### Task 4: Make Async Resources Latest-Request-Wins

**Files:**
- Modify: `frontend/src/composables/useAsyncState.ts`
- Create: `frontend/src/composables/useAsyncState.test.ts`
- Modify: `frontend/src/views/ShopView.vue`

**Interfaces:**
- Produces: `useAsyncState<T>(defaults?)` with `load(loader, options)`, `cancel()`, `reset()`, and `setError()`.

- [ ] **Step 1: Write failing transition tests**

Cover initial loading, empty, timeout, updating with preserved data, cancellation, and stale request suppression:

```ts
it('ignores an older request that resolves after the latest request', async () => {
  const state = useAsyncState<string>()
  let resolveOld!: (value: string) => void
  const old = state.load(() => new Promise((resolve) => (resolveOld = resolve)))
  const latest = state.load(async () => 'latest')
  await latest
  resolveOld('old')
  await old
  expect(state.data.value).toBe('latest')
  expect(state.status.value).toBe('success')
})
```

```ts
it('keeps valid data visible while updating', async () => {
  const state = useAsyncState<string>()
  await state.load(async () => 'first')
  let resolveUpdate!: (value: string) => void
  const update = state.load(() => new Promise((resolve) => (resolveUpdate = resolve)))
  expect(state.status.value).toBe('updating')
  expect(state.data.value).toBe('first')
  resolveUpdate('second')
  await update
  expect(state.data.value).toBe('second')
})
```

- [ ] **Step 2: Run tests and verify red state**

Run: `npm run test:unit -- src/composables/useAsyncState.test.ts`

Expected: FAIL because `updating`, cancellation, and stale-result guards do not exist.

- [ ] **Step 3: Implement the state machine**

Use a monotonically increasing request ID and AbortController. Only the active request may write `data`, `status`, or `error`. Timeout rejects with a localized error key and aborts the active controller. When data exists and `preserveData !== false`, enter `updating` instead of `loading`.

Core guard:

```ts
const requestId = ++activeRequestId
controller?.abort()
controller = new AbortController()

if (requestId !== activeRequestId) {
  return null
}
```

Update the existing `ShopView.vue` call from `isEmptyCheck` to the new `isEmpty` option so the foundation build remains green:

```ts
await load(() => listMonkeys(), {
  isEmpty: (list) => list.length === 0,
})
```

- [ ] **Step 4: Run focused and full unit tests**

Run:

```powershell
npm run test:unit -- src/composables/useAsyncState.test.ts
npm run test:unit
```

Expected: all async-state tests pass with no unhandled rejection.

- [ ] **Step 5: Commit the state machine**

```powershell
git add frontend/src/composables/useAsyncState.ts frontend/src/composables/useAsyncState.test.ts frontend/src/views/ShopView.vue
git commit -m "feat(ui): enforce async resource transitions"
```

---

### Task 5: Replace Global Element Messages with App Feedback

**Files:**
- Modify: `frontend/src/composables/useNotify.ts`
- Create: `frontend/src/composables/useNotify.test.ts`
- Create: `frontend/src/components/feedback/AppFeedbackHost.vue`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/locales/index.ts`

**Interfaces:**
- Produces: `useNotify()` with `success`, `info`, `warning`, `error`, `fromApiError`, `confirm`, and `dismiss`.
- Produces: singleton readonly `feedbackItems` consumed only by `AppFeedbackHost`.

- [ ] **Step 1: Write failing grouping and mapping tests**

```ts
it('groups repeated feedback by key', () => {
  const notify = useNotify()
  notify.error('请稍后重试', { key: 'rate-limit' })
  notify.error('请稍后重试', { key: 'rate-limit' })
  expect(feedbackItems).toHaveLength(1)
  expect(feedbackItems[0].count).toBe(2)
})
```

```ts
it('maps a 429 ApiError to recoverable Chinese copy', () => {
  const notify = useNotify()
  notify.fromApiError(new ApiError('Too many requests', 429), '请求失败')
  expect(feedbackItems[0].message).toContain('操作太频繁')
  expect(feedbackItems[0].message).not.toContain('Too many requests')
})
```

- [ ] **Step 2: Run tests and verify red state**

Run: `npm run test:unit -- src/composables/useNotify.test.ts`

Expected: FAIL because app-owned feedback items and grouping do not exist.

- [ ] **Step 3: Implement the singleton feedback queue**

Use a module-level reactive array capped at three visible items. Repeated keys increment `count` and refresh duration. `fromApiError` handles 401, 403, 429, trace IDs, and fallback copy. Confirmations may continue to use `ElMessageBox`, but no informational feedback may call `ElMessage`.

- [ ] **Step 4: Render accessible feedback surfaces**

`AppFeedbackHost.vue` renders a top-right stack with `role="status"` for success/info and `role="alert"` for warning/error. Each item has an icon-only close button with an aria-label, optional trace details, stable dimensions, and no colored left border.

- [ ] **Step 5: Mount the feedback host and add translations**

Mount `<AppFeedbackHost />` once in `App.vue`. Add `common.dismiss`, `common.errorDetails`, `common.traceId`, and status titles in both locales.

- [ ] **Step 6: Run unit, lint, and build checks**

Run:

```powershell
npm run test:unit -- src/composables/useNotify.test.ts
npm run lint
npm run build
```

Expected: all pass.

- [ ] **Step 7: Commit feedback infrastructure**

```powershell
git add frontend/src/composables/useNotify.ts frontend/src/composables/useNotify.test.ts frontend/src/components/feedback/AppFeedbackHost.vue frontend/src/App.vue frontend/src/locales/index.ts
git commit -m "feat(ui): add grouped application feedback"
```

---

### Task 6: Move Shell Ownership to the Route Layout

**Files:**
- Create: `frontend/src/router/route-meta.d.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/components/AppShell.vue`
- Modify: `frontend/src/components/AppErrorBoundary.vue`
- Create: `frontend/src/components/shell/ConsumerHeader.vue`
- Create: `frontend/src/components/shell/ConsumerBottomNav.vue`
- Create: `frontend/src/components/shell/AdminSidebar.vue`
- Create: `frontend/src/components/shell/AdminTopbar.vue`
- Modify: `frontend/src/styles/shell.css`
- Test: `frontend/tests/shell.spec.ts`

**Interfaces:**
- Consumes: route metadata, auth store, theme store, i18n.
- Produces: one `.app-shell`, one `.app-main`, and area-specific chrome per route.

- [ ] **Step 1: Write failing shell ownership tests**

For `/shop`, `/login`, and `/admin`, assert exactly one shell and the expected chrome:

```ts
await expect(page.locator('.app-shell')).toHaveCount(1)
await expect(page.locator('.app-main')).toHaveCount(1)
await expect(page.locator('.app-shell .app-shell')).toHaveCount(0)
```

Admin test asserts the sidebar is visible at 1440px and the consumer primary nav is absent. Mobile test asserts sidebar drawer and consumer bottom nav behavior.

- [ ] **Step 2: Run the shell test and verify red state**

Run: `npx playwright test tests/shell.spec.ts --project=chromium`

Expected: FAIL because views still own `AppShell` and admin uses consumer navigation.

- [ ] **Step 3: Type route metadata and annotate every route**

Create `route-meta.d.ts` with the interface in this plan. Add `area` and `titleKey` to all route records. `/login` is `auth`; admin, inventory, marketing, risk, dashboard, and tenants are `admin`; all remaining routes are `consumer`.

- [ ] **Step 4: Compose the route-aware shell**

`AppShell.vue` reads `useRoute()` and renders:

```vue
<div class="app-shell" :data-area="area">
  <ConsumerHeader v-if="area !== 'admin'" />
  <AdminSidebar v-else />
  <AdminTopbar v-if="area === 'admin'" />
  <main class="app-main" tabindex="-1"><slot /></main>
  <ConsumerBottomNav v-if="area === 'consumer'" />
</div>
```

Give the brand link aria-label `MonkeyShop 首页`, while the marketplace nav link keeps `商城`, eliminating duplicate names.

- [ ] **Step 5: Move shell and error boundary into `App.vue`**

```vue
<AppErrorBoundary>
  <AppShell>
    <RouterView v-slot="{ Component }">
      <Transition name="route" mode="out-in">
        <component :is="Component" />
      </Transition>
    </RouterView>
  </AppShell>
</AppErrorBoundary>
<AppFeedbackHost />
```

The error boundary shows localized recovery copy and a retry action; raw `error.message` is available only in an expandable diagnostic detail.

- [ ] **Step 6: Implement stable responsive shell geometry**

Consumer desktop uses a single 64px row. Admin desktop uses a 232px sidebar, 56px topbar, and a minmax content track. At 900px the admin sidebar becomes a focus-managed drawer. At 640px the consumer bottom nav appears and the top nav reduces to brand/search/account actions.

- [ ] **Step 7: Run shell tests**

Run:

```powershell
npx playwright test tests/shell.spec.ts --project=chromium
npm run lint
npm run build
```

Expected: shell tests pass at desktop and mobile; build passes.

- [ ] **Step 8: Commit route-owned shell infrastructure**

```powershell
git add frontend/src/router frontend/src/App.vue frontend/src/components/AppShell.vue frontend/src/components/AppErrorBoundary.vue frontend/src/components/shell frontend/src/styles/shell.css frontend/tests/shell.spec.ts
git commit -m "feat(ui): add route-owned consumer and admin shells"
```

---

### Task 7: Make Every View Content-Only

**Files:**
- Modify: all files in `frontend/src/views/*.vue`

**Interfaces:**
- Consumes: root-mounted `AppShell`.
- Produces: page-root content without global chrome.

- [ ] **Step 1: Remove every `AppShell` import**

For each view, delete:

```ts
import AppShell from '@/components/AppShell.vue'
```

- [ ] **Step 2: Remove wrapper tags without changing page behavior**

Transform:

```vue
<template>
  <AppShell>
    <section class="page-root">
      <h1>{{ $t('nav.cart') }}</h1>
    </section>
  </AppShell>
</template>
```

into:

```vue
<template>
  <section class="page-root">
    <h1>{{ $t('nav.cart') }}</h1>
  </section>
</template>
```

Do this for Login, Shop, ProductDetail, Search, Recommend, Orders, Review, Payment, Logistics, Membership, Cart, Checkout, Profile, Admin, Inventory, Marketing, RiskReview, Dashboard, TenantAdmin, and NotFound.

- [ ] **Step 3: Add a static ownership assertion**

In `shell.spec.ts`, add a Node-side scan that fails when a view imports `AppShell` or contains `<AppShell>`.

- [ ] **Step 4: Run all route ownership checks**

Run:

```powershell
rg -n "AppShell" frontend/src/views
npx playwright test tests/shell.spec.ts --project=chromium
npm run test:ui-smoke
```

Expected: `rg` has no output; shell and all route checks pass or report only page-specific failures assigned to later plans.

- [ ] **Step 5: Commit the content-only page roots**

```powershell
git add frontend/src/views frontend/tests/shell.spec.ts
git commit -m "refactor(ui): make route views content-only"
```

---

### Task 8: Add Shared Page, State, and Table Surfaces

**Files:**
- Create: `frontend/src/components/ui/PageHeader.vue`
- Create: `frontend/src/components/ui/AsyncStateView.vue`
- Create: `frontend/src/components/ui/DataTableShell.vue`
- Modify: `frontend/src/styles/components.css`
- Test: `frontend/tests/shell.spec.ts`

**Interfaces:**
- Produces: shared page title/action slots, explicit async state rendering, and bounded table scrolling.

- [ ] **Step 1: Write failing component behavior tests**

Mount representative routes and assert:

- page titles do not exceed 32px;
- async error has a visible retry button;
- empty and error states cannot be visible simultaneously;
- table horizontal overflow stays inside `.data-table-shell__scroller`.

- [ ] **Step 2: Implement `PageHeader.vue`**

Props are `title`, optional `description`, optional `eyebrow`, and slots `breadcrumbs` and `actions`. Use an unframed header with stable wrapping and no card surface.

- [ ] **Step 3: Implement `AsyncStateView.vue`**

Props:

```ts
defineProps<{
  status: AsyncStatus
  error?: string | null
  loadingLines?: number
  emptyTitle?: string
  emptyDescription?: string
}>()

defineEmits<{ retry: [] }>()
```

Render exactly one branch. `updating` renders the content slot plus a compact non-blocking progress indicator.

- [ ] **Step 4: Implement `DataTableShell.vue`**

Provide `toolbar`, default, `empty`, and `footer` slots. Own the horizontal scroller and set `min-width: 0` on every grid boundary.

- [ ] **Step 5: Run browser, lint, and build checks**

Run:

```powershell
npx playwright test tests/shell.spec.ts --project=chromium
npm run lint
npm run build
```

Expected: all pass.

- [ ] **Step 6: Commit shared UI surfaces**

```powershell
git add frontend/src/components/ui frontend/src/styles/components.css frontend/tests/shell.spec.ts
git commit -m "feat(ui): add shared page and state surfaces"
```

---

## Foundation Plan Completion Gate

Run:

```powershell
npm run test:unit
npm run lint
npm run build
npm run test:a11y
npm run test:ui-smoke
```

Expected evidence:

- zero lint errors and warnings;
- all unit and Axe tests pass;
- every route reports its viewport and never loses failure context;
- each route contains one shell/main and no nested shell;
- admin and consumer chrome are distinct;
- no view imports `AppShell`;
- raw colors occur only in `tokens.css`;
- no direct `ElMessage` use exists in foundation files.
