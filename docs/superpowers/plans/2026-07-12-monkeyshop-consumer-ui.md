# MonkeyShop Consumer UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver polished, recoverable, responsive consumer journeys from authentication and discovery through checkout, fulfillment, membership, and profile management.

**Architecture:** Consumer views are content-only children of the route-owned consumer/auth shell. Every resource uses the shared async state machine, every mutation uses app feedback or inline form feedback, and URL-relevant search state is route-backed. Product and order imagery always has stable geometry and a local fallback.

**Tech Stack:** Vue 3.5, Vue Router 5, Pinia 3, Element Plus 2.14, TypeScript 6, Vitest, Playwright, Axe.

## Global Constraints

- Foundation plan `2026-07-12-monkeyshop-ui-foundation-shell.md` must pass first.
- Do not reintroduce `AppShell` imports into views.
- Use `PageHeader`, `AsyncStateView`, `DataTableShell`, `useAsyncState`, and `useNotify` contracts from the foundation plan.
- Product, avatar, captcha, and hero images must have stable dimensions, accurate alt text, and local fallback behavior.
- Consumer mobile touch targets are at least 44x44px.
- Forms retain valid input after server errors and show field or form-level recovery copy.
- Toasts are limited to successful mutations or cross-page completion; loading and validation errors are inline.
- Query, filter, sort, and pagination state persists in the URL when it affects discovery results.
- Never display raw backend status names or raw backend error text.
- Do not add marketing-only sections, fake reviews, fake metrics, or invented testimonials.
- Stage and commit only the files owned by the current task.

---

## File Structure

### New files

- `frontend/src/assets/monkey-login.png`: frontend-owned authentication image copied from the repository's approved static asset.
- `frontend/src/components/product/ProductCard.vue`: stable product card and actions.
- `frontend/src/components/order/OrderStatusTimeline.vue`: localized order/logistics progression.
- `frontend/src/composables/useRouteQueryState.ts`: typed query read/write helper.
- `frontend/src/composables/useRouteQueryState.test.ts`: query-state tests.
- `frontend/tests/consumer-flows.spec.ts`: consumer desktop/mobile flows and screenshots.

### Modified files

- `frontend/src/components/ProductImage.vue`
- `frontend/src/components/HumanVerification.vue`
- `frontend/src/composables/useCheckout.ts`
- `frontend/src/locales/index.ts`
- `frontend/src/styles/components.css`
- `frontend/src/views/LoginView.vue`
- `frontend/src/views/ShopView.vue`
- `frontend/src/views/ProductDetailView.vue`
- `frontend/src/views/SearchView.vue`
- `frontend/src/views/RecommendView.vue`
- `frontend/src/views/CartView.vue`
- `frontend/src/views/CheckoutView.vue`
- `frontend/src/views/OrdersView.vue`
- `frontend/src/views/PaymentView.vue`
- `frontend/src/views/LogisticsView.vue`
- `frontend/src/views/ReviewView.vue`
- `frontend/src/views/MembershipView.vue`
- `frontend/src/views/ProfileView.vue`
- `frontend/scripts/ui-smoke.mjs`

## Interfaces

```ts
export interface ProductCardProps {
  product: Monkey
  pending?: boolean
  primaryActionLabel: string
  disabled?: boolean
}

export interface RouteQuerySchema<T extends Record<string, unknown>> {
  parse(query: LocationQuery): T
  serialize(value: T): LocationQueryRaw
}

export interface SearchRouteState {
  keyword: string
  category: string
  attribute: string
  value: string
  sort: 'RELEVANCE' | 'PRICE_ASC' | 'PRICE_DESC' | 'NEWEST' | 'HOT'
  page: number
  size: 12 | 24 | 48
}

export function parseSearchQuery(query: LocationQuery): SearchRouteState
export function serializeSearchQuery(value: SearchRouteState): LocationQueryRaw

export type PasswordResetStage = 'identity' | 'challenge'
```

---

### Task 1: Make Authentication Resilient and Focused

**Files:**
- Create: `frontend/src/assets/monkey-login.png`
- Modify: `frontend/src/views/LoginView.vue`
- Modify: `frontend/src/components/HumanVerification.vue`
- Modify: `frontend/src/styles/components.css`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/consumer-flows.spec.ts`

**Interfaces:**
- Produces: an auth form with login/register/reset modes and progressive reset stages.

- [ ] **Step 1: Add failing auth layout and feedback tests**

Test desktop and mobile:

```ts
test('login keeps failures inside the form and never exposes backend copy', async ({ page }) => {
  await page.route('**/api/v1/auth/login', (route) =>
    route.fulfill({
      status: 429,
      contentType: 'application/problem+json',
      body: JSON.stringify({ title: 'Too many requests', status: 429 }),
    }),
  )
  await page.goto('/login')
  await page.getByLabel('用户名').fill('admin')
  await page.getByLabel('密码').fill('bad-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.auth-feedback')).toContainText('操作太频繁')
  await expect(page.locator('body')).not.toContainText('Too many requests')
})
```

Add a mobile assertion that the submit button is at least 44px high and no horizontal overflow exists.

- [ ] **Step 2: Copy the approved local image into frontend assets**

Run:

```powershell
Copy-Item -LiteralPath ..\src\main\resources\static\images\monkey.png -Destination src\assets\monkey-login.png
```

Import it from `LoginView.vue`:

```ts
import monkeyLoginImage from '@/assets/monkey-login.png'
```

- [ ] **Step 3: Refactor auth feedback and reset progression**

Keep `authFeedback` local to the form. Replace the three-field reset stack with `resetStage`:

```ts
const resetStage = ref<PasswordResetStage>('identity')

async function requestResetCode() {
  const valid = await resetFormRef.value?.validateField(['username', 'phone']).then(() => true).catch(() => false)
  if (!valid) return
  await authApi.requestPasswordReset({
    username: resetForm.username,
    phone: resetForm.phone,
    email: resetForm.email,
    captcha: resetRequestCaptcha.value,
  })
  resetStage.value = 'challenge'
}
```

The challenge stage renders OTP, email token, new password, and final verification. Switching tabs clears feedback but preserves fields within the selected flow.

- [ ] **Step 4: Recompose the auth layout**

Desktop uses `minmax(0, 1.15fr) minmax(360px, 460px)` with the image showing its subject. Mobile uses form-first layout and a 160px image strip. “继续浏览” becomes a secondary link inside the form footer, not a third grid child.

- [ ] **Step 5: Make human verification recoverable**

Render unavailable/expired states inline with retry; refresh buttons have unique aria-labels; captcha images use localized alt text; Turnstile errors never become global toasts.

- [ ] **Step 6: Run focused checks**

Run:

```powershell
npx playwright test tests/consumer-flows.spec.ts --grep "login|captcha" --project=chromium
npm run lint
npm run build
```

Expected: auth tests pass, image is bundled, and the browser console is clean.

- [ ] **Step 7: Commit authentication UI**

```powershell
git add frontend/src/assets/monkey-login.png frontend/src/views/LoginView.vue frontend/src/components/HumanVerification.vue frontend/src/styles/components.css frontend/src/locales/index.ts frontend/tests/consumer-flows.spec.ts
git commit -m "feat(ui): refine authentication journeys"
```

---

### Task 2: Build Stable Product Cards and Image Fallbacks

**Files:**
- Create: `frontend/src/components/product/ProductCard.vue`
- Modify: `frontend/src/components/ProductImage.vue`
- Modify: `frontend/src/views/ShopView.vue`
- Modify: `frontend/src/styles/components.css`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/consumer-flows.spec.ts`

**Interfaces:**
- Produces: `ProductCard` with `primary` and `secondary` emits.

- [ ] **Step 1: Write failing image/card tests**

```ts
test('catalog card keeps geometry when its image fails', async ({ page }) => {
  await page.route('**/images/broken.jpg', (route) => route.abort())
  await page.goto('/shop')
  const image = page.locator('.product-card img').first()
  await expect(image).toHaveAttribute('src', /default_product|fallback/)
  const box = await page.locator('.product-card__media').first().boundingBox()
  expect(box?.width).toBeGreaterThan(200)
  expect(box?.height).toBeGreaterThan(150)
})
```

- [ ] **Step 2: Harden `ProductImage.vue`**

Use a finite fallback chain and prevent error loops:

```ts
const failed = ref(false)
const resolvedSrc = computed(() => (failed.value || !props.src ? fallbackUrl : props.src))

watch(() => props.src, () => {
  failed.value = false
})
```

The rendered image always has explicit width/height behavior through a fixed aspect-ratio parent.

- [ ] **Step 3: Implement `ProductCard.vue`**

The card renders media, name, breed, price, description, stock, and one stable primary action. The button width and height do not change while pending; pending state changes icon/spinner, not layout.

- [ ] **Step 4: Replace inline catalog cards**

`ShopView.vue` retains its explicit loading/error/empty/success branches and delegates each item to `ProductCard`. Filters remain an unframed toolbar and collapse to two rows at tablet width and one column at mobile width.

- [ ] **Step 5: Run catalog checks**

Run:

```powershell
npx playwright test tests/consumer-flows.spec.ts --grep "catalog|image" --project=chromium
npm run test:a11y
npm run build
```

Expected: card, fallback, and Axe checks pass.

- [ ] **Step 6: Commit product card foundation**

```powershell
git add frontend/src/components/product/ProductCard.vue frontend/src/components/ProductImage.vue frontend/src/views/ShopView.vue frontend/src/styles/components.css frontend/src/locales/index.ts frontend/tests/consumer-flows.spec.ts
git commit -m "feat(ui): stabilize catalog product cards"
```

---

### Task 3: Persist Discovery State and Refine Product Detail

**Files:**
- Create: `frontend/src/composables/useRouteQueryState.ts`
- Create: `frontend/src/composables/useRouteQueryState.test.ts`
- Modify: `frontend/src/views/SearchView.vue`
- Modify: `frontend/src/views/RecommendView.vue`
- Modify: `frontend/src/views/ProductDetailView.vue`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/consumer-flows.spec.ts`

**Interfaces:**
- Produces: typed URL-backed search state `{ keyword, category, attribute, value, sort, page, size }`.

- [ ] **Step 1: Write failing query round-trip tests**

```ts
it('serializes only non-default discovery state', () => {
  const value = { keyword: 'golden', category: '', attribute: '', value: '', sort: 'PRICE_ASC', page: 2, size: 12 }
  expect(serializeSearchQuery(value)).toEqual({ q: 'golden', sort: 'PRICE_ASC', page: '2' })
})
```

Write a browser test that navigates away and back, then sees the same filters and page.

- [ ] **Step 2: Implement typed query parsing and serialization**

Clamp page to 0 or greater and size to the supported set `[12, 24, 48]`. Unknown sort values fall back to `RELEVANCE`. Replace route query only after a 250ms debounced change.

```ts
import type { LocationQuery, LocationQueryRaw, LocationQueryValue } from 'vue-router'

const supportedSorts = new Set<SearchRouteState['sort']>([
  'RELEVANCE',
  'PRICE_ASC',
  'PRICE_DESC',
  'NEWEST',
  'HOT',
])
const supportedSizes = new Set<SearchRouteState['size']>([12, 24, 48])

function first(value: LocationQueryValue | LocationQueryValue[]): string {
  return Array.isArray(value) ? value[0] ?? '' : value ?? ''
}

export function parseSearchQuery(query: LocationQuery): SearchRouteState {
  const sortCandidate = first(query.sort).toUpperCase() as SearchRouteState['sort']
  const pageCandidate = Number.parseInt(first(query.page), 10)
  const sizeCandidate = Number.parseInt(first(query.size), 10) as SearchRouteState['size']
  return {
    keyword: first(query.q),
    category: first(query.category),
    attribute: first(query.attribute),
    value: first(query.value),
    sort: supportedSorts.has(sortCandidate) ? sortCandidate : 'RELEVANCE',
    page: Number.isFinite(pageCandidate) ? Math.max(0, pageCandidate) : 0,
    size: supportedSizes.has(sizeCandidate) ? sizeCandidate : 12,
  }
}

export function serializeSearchQuery(value: SearchRouteState): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (value.keyword.trim()) query.q = value.keyword.trim()
  if (value.category) query.category = value.category
  if (value.attribute) query.attribute = value.attribute
  if (value.value) query.value = value.value
  if (value.sort !== 'RELEVANCE') query.sort = value.sort
  if (value.page > 0) query.page = String(value.page)
  if (value.size !== 12) query.size = String(value.size)
  return query
}
```

- [ ] **Step 3: Migrate `SearchView.vue`**

Use `useAsyncState` and keep previous results during updating. Show result count and active filters in the toolbar; empty state offers clear filters. Pagination writes the URL and returns focus to the result heading without using `scrollIntoView`.

- [ ] **Step 4: Refine recommendations**

Use the same `ProductCard` surface with a compact reason label. Profile updates show inline pending state and success feedback through `useNotify`.

- [ ] **Step 5: Recompose product detail**

Use a two-column image/purchase layout at desktop and one column at mobile. Price, stock, SKU and primary purchase action remain visible as one cohesive purchase surface. The checkout dialog uses form validation and mobile-safe width.

- [ ] **Step 6: Run discovery checks**

Run:

```powershell
npm run test:unit -- src/composables/useRouteQueryState.test.ts
npx playwright test tests/consumer-flows.spec.ts --grep "search|recommendation|product detail" --project=chromium
npm run lint
npm run build
```

Expected: query and browser tests pass.

- [ ] **Step 7: Commit discovery UI**

```powershell
git add frontend/src/composables/useRouteQueryState.ts frontend/src/composables/useRouteQueryState.test.ts frontend/src/views/SearchView.vue frontend/src/views/RecommendView.vue frontend/src/views/ProductDetailView.vue frontend/src/locales/index.ts frontend/tests/consumer-flows.spec.ts
git commit -m "feat(ui): persist product discovery state"
```

---

### Task 4: Make Cart and Checkout Mutation-Safe

**Files:**
- Modify: `frontend/src/composables/useCheckout.ts`
- Modify: `frontend/src/views/CartView.vue`
- Modify: `frontend/src/views/CheckoutView.vue`
- Modify: `frontend/src/styles/components.css`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/consumer-flows.spec.ts`

**Interfaces:**
- Produces: per-line cart pending state and a single-flight checkout submission.

- [ ] **Step 1: Write failing duplicate-submit and rollback tests**

Define the response fixture used by the duplicate-submit test:

```ts
const checkoutOk = {
  code: 'OK',
  message: 'ok',
  data: {
    id: 1,
    checkoutNo: 'CO-UI-001',
    userId: 1,
    addressId: 1,
    originalAmount: '128.00',
    discountAmount: '0.00',
    payableAmount: '128.00',
    status: 'RESERVED',
    province: 'CN-ZJ',
    createdAt: '2026-07-12T00:00:00+08:00',
    subOrders: [],
  },
}
```

```ts
test('checkout sends one request while submit is pending', async ({ page }) => {
  let calls = 0
  await page.route('**/api/v1/cart/checkout', async (route) => {
    calls += 1
    await new Promise((resolve) => setTimeout(resolve, 300))
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(checkoutOk) })
  })
  await page.goto('/checkout')
  await page.getByRole('button', { name: '提交订单' }).dblclick()
  await expect.poll(() => calls).toBe(1)
})
```

Add a cart quantity failure test that restores the prior visible quantity and announces the error inline.

- [ ] **Step 2: Track pending mutation keys**

Use `Set<string>` keys such as `quantity:101`, `select:101`, and `remove:101`. Disable only the affected row action; never freeze the entire cart for one line mutation.

- [ ] **Step 3: Add optimistic quantity rollback**

Capture the previous quantity before the request. On failure restore the value and show row-level error text; on success replace the cart with the server response.

- [ ] **Step 4: Recompose responsive cart and checkout**

Desktop uses bounded tables. Mobile uses labeled line items and a fixed bottom summary that leaves safe-area spacing. Checkout groups address, discounts, stock reservation, and totals in an explicit reading order.

- [ ] **Step 5: Enforce checkout single-flight behavior**

The submit action returns immediately when `submitting` is true, remains dimensionally stable, and preserves selected address and preview after failure.

- [ ] **Step 6: Run transaction checks**

Run:

```powershell
npx playwright test tests/consumer-flows.spec.ts --grep "cart|checkout" --project=chromium
npm run lint
npm run build
```

Expected: rollback and duplicate-submit tests pass at desktop and mobile.

- [ ] **Step 7: Commit cart and checkout UI**

```powershell
git add frontend/src/composables/useCheckout.ts frontend/src/views/CartView.vue frontend/src/views/CheckoutView.vue frontend/src/styles/components.css frontend/src/locales/index.ts frontend/tests/consumer-flows.spec.ts
git commit -m "feat(ui): make cart and checkout recoverable"
```

---

### Task 5: Clarify Orders, Payment, Logistics, and Review

**Files:**
- Create: `frontend/src/components/order/OrderStatusTimeline.vue`
- Modify: `frontend/src/views/OrdersView.vue`
- Modify: `frontend/src/views/PaymentView.vue`
- Modify: `frontend/src/views/LogisticsView.vue`
- Modify: `frontend/src/views/ReviewView.vue`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/consumer-flows.spec.ts`

**Interfaces:**
- Produces: localized status timeline and per-order next actions.

- [ ] **Step 1: Write failing status and destructive-action tests**

Assert that internal values such as `PAYMENT_PENDING`, `IN_TRANSIT`, and `COMPLETED` are not visible. Assert return/refund actions require confirmation and remain disabled while pending.

- [ ] **Step 2: Implement `OrderStatusTimeline.vue`**

Map domain statuses to ordered steps and localized labels. The component accepts current status, timestamps, and optional logistics events. It uses text plus icons, never color alone.

- [ ] **Step 3: Recompose orders**

Each order row shows identity, time, amount/status, and only currently valid next actions. Loading, empty, error, and updating branches use `AsyncStateView`.

- [ ] **Step 4: Recompose payment and logistics**

Payment separates lookup, payment creation, current payment, and refund. Logistics separates shipment summary, address parsing, quote, and event timeline. Errors stay with the failed task.

- [ ] **Step 5: Recompose review submission**

Rating, content, image upload, anonymous setting, and submit state form one accessible form. Upload progress does not disable text entry; submit remains single-flight.

- [ ] **Step 6: Run fulfillment checks**

Run:

```powershell
npx playwright test tests/consumer-flows.spec.ts --grep "order|payment|logistics|review" --project=chromium
npm run test:a11y
npm run build
```

Expected: all pass with no internal status copy.

- [ ] **Step 7: Commit fulfillment UI**

```powershell
git add frontend/src/components/order/OrderStatusTimeline.vue frontend/src/views/OrdersView.vue frontend/src/views/PaymentView.vue frontend/src/views/LogisticsView.vue frontend/src/views/ReviewView.vue frontend/src/locales/index.ts frontend/tests/consumer-flows.spec.ts
git commit -m "feat(ui): clarify order fulfillment journeys"
```

---

### Task 6: Organize Membership and Profile Tasks

**Files:**
- Modify: `frontend/src/views/MembershipView.vue`
- Modify: `frontend/src/views/ProfileView.vue`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/consumer-flows.spec.ts`

**Interfaces:**
- Produces: task-grouped account pages with masked sensitive data and mutation-specific pending state.

- [ ] **Step 1: Write failing privacy and task-state tests**

Assert full phone, identity card, and real-name values are never rendered. Verify one pending membership action does not disable unrelated actions.

- [ ] **Step 2: Recompose membership**

Use one compact account summary followed by unframed task sections: check-in/identity, points, price watch, coupons, and history. Use bounded tables only where comparison matters.

- [ ] **Step 3: Recompose profile**

Order sections as overview, required password update, avatar/account, password, addresses. Required password update is a blocking Alert with a clear action, not a global toast. Address editing uses a dialog with inline validation and focus return.

- [ ] **Step 4: Migrate every mutation to app feedback**

Replace direct `ElMessage`/`ElMessageBox` calls with `useNotify` and `notify.confirm`. Keep field errors inline.

- [ ] **Step 5: Run account checks**

Run:

```powershell
npx playwright test tests/consumer-flows.spec.ts --grep "membership|profile|password|address" --project=chromium
npm run test:a11y
npm run lint
npm run build
```

Expected: privacy, focus, and mutation tests pass.

- [ ] **Step 6: Commit account UI**

```powershell
git add frontend/src/views/MembershipView.vue frontend/src/views/ProfileView.vue frontend/src/locales/index.ts frontend/tests/consumer-flows.spec.ts
git commit -m "feat(ui): organize membership and profile tasks"
```

---

### Task 7: Complete Consumer Feedback and Route Coverage

**Files:**
- Modify: all consumer view files listed in this plan.
- Modify: `frontend/scripts/ui-smoke.mjs`
- Modify: `frontend/tests/consumer-flows.spec.ts`

**Interfaces:**
- Produces: no direct `ElMessage` use in consumer views and full consumer route coverage.

- [ ] **Step 1: Add a static direct-message assertion**

Fail when this command produces output:

```powershell
rg -n "ElMessage|ElNotification" frontend/src/views/LoginView.vue frontend/src/views/ShopView.vue frontend/src/views/ProductDetailView.vue frontend/src/views/SearchView.vue frontend/src/views/RecommendView.vue frontend/src/views/CartView.vue frontend/src/views/CheckoutView.vue frontend/src/views/OrdersView.vue frontend/src/views/PaymentView.vue frontend/src/views/LogisticsView.vue frontend/src/views/ReviewView.vue frontend/src/views/MembershipView.vue frontend/src/views/ProfileView.vue
```

- [ ] **Step 2: Migrate any remaining direct calls**

Use `notify.fromApiError(error, translatedFallback)` for API failures, `notify.success` for completed mutations, and `notify.confirm` for high-impact confirmation.

- [ ] **Step 3: Exercise all consumer routes at three viewports**

Run the consumer suite at 390x844, 768x1024, and 1440x900. Assert no page-level horizontal overflow, no stuck loading mask, no raw backend copy, and no console error/warning.

- [ ] **Step 4: Run the consumer completion gate**

Run:

```powershell
npm run test:unit
npm run lint
npm run build
npm run test:a11y
npx playwright test tests/consumer-flows.spec.ts --project=chromium
npm run test:ui-smoke
```

Expected: all commands pass.

- [ ] **Step 5: Commit consumer completion**

```powershell
git add frontend/src/views frontend/scripts/ui-smoke.mjs frontend/tests/consumer-flows.spec.ts
git commit -m "test(ui): complete consumer route coverage"
```

---

## Consumer Plan Completion Gate

The plan is complete only when authentication, shop, search, recommendations, product detail, cart, checkout, orders, payment, logistics, review, membership, and profile pass desktop/tablet/mobile browser checks; images never collapse or break; all errors are recoverable; and no consumer view directly uses Element Plus message APIs.
