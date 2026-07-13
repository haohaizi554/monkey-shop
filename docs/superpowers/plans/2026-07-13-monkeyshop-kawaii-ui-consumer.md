# MonkeyShop Kawaii Consumer UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every auth and consumer-facing screen with a mature, high-density kawaii commerce experience that uses real backend capabilities and consistent state handling.

**Architecture:** Keep the existing Vue/Pinia/router foundation, but make `AppShell` the sole shell owner and build reusable tokens, feedback, form, status, and mascot primitives. Views are thin route workspaces backed by typed API modules and composables; machine-only endpoints never receive UI controls.

**Tech Stack:** Vue 3.5, TypeScript 6, Pinia 3, Vue Router, Element Plus and its icon set, vue-i18n, CSS custom properties, Vitest, Playwright, Axe.

## Global Constraints

- Canvas `#F6F7F9`, surface `#FFFFFF`, ink `#182230`, muted `#667085`, line `#D8E0E8`, primary `#0B6E61`, cobalt `#2F61D5`, coral `#C94355`, honey `#A86100`, danger `#B42318`.
- Use 6px control radius and 8px card/drawer/dialog radius; spacing follows 4/8/12/16/20/24/32/40/48.
- No gradients, glow blobs, bokeh, oversized marketing hero, nested cards, or page sections styled as floating cards.
- One page has one `h1` and at most one major mascot; tables, metric strips, toolbars, and ordinary cards have no decorative mascot.
- Mobile touch targets are at least 44x44px; desktop icon buttons are at least 36x36px with tooltip and `aria-label`.
- Test widths are 320, 390, 768, 1024, and 1440px in light and dark modes.
- Resource state is `idle -> loading -> success | empty | error`; refresh uses `updating`, and mutation state uses a scoped `pendingKey`.
- Raw backend phrases such as `Too many requests` and `Operation is not permitted` are never rendered.

---

## File Map

- `frontend/src/styles/tokens.css`: all semantic tokens and themes.
- `frontend/src/components/AppShell.vue`: exclusive auth/consumer/admin shell switch.
- `frontend/src/components/ui`: focused reusable surfaces and state components.
- `frontend/src/components/mascot`: pose-specific accessible mascot rendering.
- `frontend/src/views`: route workspaces only; no duplicated shell markup.
- `frontend/src/api`: typed calls generated-shaped to backend OpenAPI.
- `frontend/src/stores`: session/theme/cart data only, not page presentation state.

### Task 1: Produce Optimized Pose-Specific Mascot Assets

**Files:**
- Source: `docs/superpowers/specs/assets/2026-07-13-monkeyshop-mascot-pose-sheet-v1.png`
- Create: `frontend/src/assets/mascot/monkey-welcome-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-welcome-2x.webp`
- Create: `frontend/src/assets/mascot/monkey-shopping-bag-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-search-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-cart-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-package-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-celebrate-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-clipboard-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-warning-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-shield-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-support-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-dashboard-1x.webp`
- Create: `frontend/src/assets/mascot/monkey-hourglass-1x.webp`
- Create: `frontend/src/components/mascot/MascotState.vue`
- Test: `frontend/src/components/mascot/MascotState.test.ts`

**Interfaces:**
- Produces: `<MascotState pose="search" size="sm|md|lg" :decorative="boolean" alt="..." />`.
- Produces: lazy-loaded per-pose resources with explicit width/height and reduced-motion behavior.

- [ ] **Step 1: Write RED component tests**

```ts
const wrapper = mount(MascotState, { props: { pose: 'search', size: 'md', alt: '没有匹配商品' } })
expect(wrapper.get('img').attributes('src')).toContain('monkey-search-1x.webp')
expect(wrapper.get('img').attributes('width')).toBeTruthy()
expect(wrapper.get('img').attributes('alt')).toBe('没有匹配商品')
```

- [ ] **Step 2: Run RED**

Run: `npm run test:unit -- src/components/mascot/MascotState.test.ts`

Working directory: `frontend`. Expected: component and pose assets are missing.

- [ ] **Step 3: Generate/export assets and implement mapping**

Use the approved image-generation workflow to create transparent, pose-specific WebP files rather than shipping the full sheet. Each 1x image is at most 480px on its longest side and each 2x image is at most 960px; retain the fixed jacket, mint trim, coral scarf, cobalt bag, and white shoes.

```ts
const poseSources: Record<MascotPose, string> = {
  welcome: welcome1x,
  shoppingBag: bag1x,
  search: search1x,
  cart: cart1x,
  package: package1x,
  celebrate: celebrate1x,
  clipboard: clipboard1x,
  warning: warning1x,
  shield: shield1x,
  support: support1x,
  dashboard: dashboard1x,
  hourglass: hourglass1x,
}
```

- [ ] **Step 4: Run tests, inspect alpha on both themes, and commit**

Run: `npm run test:unit -- src/components/mascot/MascotState.test.ts`

Expected: tests pass; browser screenshots show no colored fringe on white or graphite backgrounds.

```powershell
git add frontend/src/assets/mascot frontend/src/components/mascot/MascotState.vue frontend/src/components/mascot/MascotState.test.ts
git commit -m "feat(ui): add accessible mascot pose assets"
```

### Task 2: Replace The Design Token Contract

**Files:**
- Modify: `frontend/src/styles/tokens.css`
- Modify: `frontend/src/styles/base.css`
- Modify: `frontend/src/styles/components.css`
- Modify: `frontend/src/styles/main.css`
- Test: `frontend/src/components/ui/ui-surfaces.test.ts`
- Create: `frontend/src/styles/token-contract.test.ts`

**Interfaces:**
- Produces: semantic color, spacing, typography, radius, shadow, focus, motion, and z-index variables for light/dark themes.

- [ ] **Step 1: Write RED token assertions**

```ts
const css = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8')
expect(css).toContain('--color-primary: #0B6E61')
expect(css).toContain('--radius-control: 6px')
expect(css).not.toMatch(/linear-gradient|radial-gradient|letter-spacing:\s*-|border-radius:\s*(1[2-9]|[2-9]\d)px/)
```

- [ ] **Step 2: Run RED**

Run: `npm run test:unit -- src/styles/token-contract.test.ts src/components/ui/ui-surfaces.test.ts`

Expected: old palette/radii violate the approved contract.

- [ ] **Step 3: Implement exact light/dark tokens**

```css
:root {
  --color-canvas: #F6F7F9;
  --color-surface: #FFFFFF;
  --color-ink: #182230;
  --color-muted: #667085;
  --color-line: #D8E0E8;
  --color-primary: #0B6E61;
  --color-primary-soft: #DDF6F0;
  --color-cobalt: #2F61D5;
  --color-coral: #C94355;
  --color-honey: #A86100;
  --color-danger: #B42318;
  --radius-control: 6px;
  --radius-surface: 8px;
}
```

Dark mode uses graphite neutrals and preserves the four accent semantics. Set tabular numerals on price, stock, IDs, and metrics; letter spacing remains 0.

- [ ] **Step 4: Run unit, lint, and commit**

Run: `npm run test:unit -- src/styles/token-contract.test.ts src/components/ui/ui-surfaces.test.ts`

Run: `npm run lint`

```powershell
git add frontend/src/styles/tokens.css frontend/src/styles/base.css frontend/src/styles/components.css frontend/src/styles/main.css frontend/src/components/ui/ui-surfaces.test.ts frontend/src/styles/token-contract.test.ts
git commit -m "feat(ui): install kawaii commerce token contract"
```

### Task 3: Complete Shared Feedback, Form, Status, And Action Primitives

**Files:**
- Modify: `frontend/src/components/ui/AsyncStateView.vue`
- Create: `frontend/src/components/ui/FormSection.vue`
- Create: `frontend/src/components/ui/InlineNotice.vue`
- Create: `frontend/src/components/ui/StatusTag.vue`
- Create: `frontend/src/components/ui/ConfirmAction.vue`
- Modify: `frontend/src/components/feedback/AppFeedbackHost.vue`
- Modify: `frontend/src/composables/useAsyncState.ts`
- Modify: `frontend/src/composables/useNotify.ts`
- Create: `frontend/src/components/ui/commerce-primitives.test.ts`

**Interfaces:**
- Produces: `AsyncStateView` modes `table|grid|detail|form`, preserving data during `updating`.
- Produces: `InlineNotice` severities `info|success|warning|danger` and optional retry countdown.
- Produces: stable `StatusTag` maps for order, payment, inventory, logistics, risk, and tenant states.
- Produces: promise-returning `ConfirmAction` with focus return and scoped pending state.

- [ ] **Step 1: Write RED behavior tests**

```ts
expect(renderState({ status: 'updating', data: [row] }).text()).toContain(row.name)
expect(statusTag('PENDING_PAYMENT')).toEqual({ tone: 'warning', labelKey: 'order.pendingPayment' })
expect(notify.normalize('Too many requests')).toBe('操作过于频繁，请稍后再试')
```

- [ ] **Step 2: Run RED**

Run: `npm run test:unit -- src/components/ui/commerce-primitives.test.ts src/composables/useAsyncState.test.ts src/composables/useNotify.test.ts`

- [ ] **Step 3: Implement primitives without global raw-error toasts**

```ts
export type ResourceState<T> =
  | { status: 'idle'; data?: undefined }
  | { status: 'loading'; data?: undefined }
  | { status: 'success' | 'updating'; data: T }
  | { status: 'empty'; data?: undefined }
  | { status: 'error'; data?: T; problem: ApiProblem }
```

Field, rate-limit, permission, and section failures render inline. Toast remains for short success and cross-route completion only.

- [ ] **Step 4: Run unit/typecheck and commit**

```powershell
git add frontend/src/components/ui frontend/src/components/feedback/AppFeedbackHost.vue frontend/src/composables/useAsyncState.ts frontend/src/composables/useNotify.ts frontend/src/composables/useAsyncState.test.ts frontend/src/composables/useNotify.test.ts
git commit -m "feat(ui): unify state and feedback primitives"
```

### Task 4: Rebuild The Root And Consumer Shells

**Files:**
- Modify: `frontend/src/components/AppShell.vue`
- Modify: `frontend/src/components/shell/ConsumerHeader.vue`
- Modify: `frontend/src/components/shell/ConsumerBottomNav.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/router/route-meta.d.ts`
- Modify: `frontend/src/styles/shell.css`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/shell.spec.ts`
- Create: `frontend/src/router/route-meta.test.ts`

**Interfaces:**
- Produces: route areas `auth|consumer|admin`; only root `AppShell` owns shell UI.
- Produces: consumer desktop navigation for discover, category, search, recommendations, orders, cart, membership, account, theme, language.
- Produces: mobile bottom navigation discover/search/cart/orders/me; hidden on checkout/payment.

- [ ] **Step 1: Write RED shell tests**

```ts
expect(routeByPath('/checkout').meta.hideConsumerBottomNav).toBe(true)
expect(routeByPath('/payment/:orderId?').meta.hideConsumerBottomNav).toBe(true)
expect(document.querySelectorAll('main').length).toBe(1)
expect(document.querySelectorAll('h1').length).toBe(1)
```

- [ ] **Step 2: Run RED tests**

Run: `npm run test:unit -- src/router/route-meta.test.ts`

Run: `npx playwright test tests/shell.spec.ts --project=chromium`

- [ ] **Step 3: Implement shell ownership and focus management**

```ts
router.afterEach(async (to) => {
  await nextTick()
  document.documentElement.lang = locale.value
  document.title = `${t(to.meta.titleKey)} | MonkeyShop`
  document.querySelector<HTMLElement>('#page-title, main')?.focus({ preventScroll: true })
})
```

Add a visible-on-focus skip link. Use CSS grid with stable header/content/bottom-nav tracks; no page-level horizontal overflow.

- [ ] **Step 4: Run tests at 320 and 1440, then commit**

```powershell
git add frontend/src/components/AppShell.vue frontend/src/components/shell/ConsumerHeader.vue frontend/src/components/shell/ConsumerBottomNav.vue frontend/src/router/index.ts frontend/src/router/route-meta.d.ts frontend/src/styles/shell.css frontend/src/App.vue frontend/src/locales/index.ts frontend/tests/shell.spec.ts frontend/src/router/route-meta.test.ts
git commit -m "feat(ui): rebuild consumer application shell"
```

### Task 5: Rebuild Login, Three-Step Registration, And Reset

**Files:**
- Modify: `frontend/src/views/LoginView.vue`
- Modify: `frontend/src/api/auth.ts`
- Modify: `frontend/src/types.ts`
- Use: `frontend/src/composables/usePasswordPolicy.ts`
- Use: `frontend/src/composables/useRetryCountdown.ts`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/consumer-auth.spec.ts`
- Create: `frontend/src/views/LoginView.test.ts`

**Interfaces:**
- Consumes: password metadata, captcha config, register, login/MFA, reset request/reset, 422 field errors, and 429 retry metadata.
- Produces: auth modes `login|register|reset`; register steps `account|contact|complete`.

- [ ] **Step 1: Write RED registration state tests**

```ts
await wrapper.get('[data-testid="register-tab"]').trigger('click')
expect(wrapper.find('[data-testid="register-account-step"]').exists()).toBe(true)
expect(wrapper.find('[data-testid="register-contact-step"]').exists()).toBe(false)
await typeInvalidPassword(wrapper)
expect(registerMock).not.toHaveBeenCalled()
```

- [ ] **Step 2: Write RED 422/429 tests**

```ts
registerMock.mockRejectedValue(problem(422, [{ field: 'username', code: 'Unique', message: '用户名已存在' }]))
expect(wrapper.get('[data-field-error="username"]').text()).toContain('用户名已存在')

loginMock.mockRejectedValue(problem(429, [], 10))
expect(wrapper.get('[data-testid="retry-countdown"]').text()).toContain('10')
expect(wrapper.get('button[type="submit"]').attributes()).toHaveProperty('disabled')
```

- [ ] **Step 3: Run RED**

Run: `npm run test:unit -- src/views/LoginView.test.ts`

Run: `npx playwright test tests/consumer-auth.spec.ts --project=chromium`

- [ ] **Step 4: Implement the mature kawaii auth workspace**

Use one unframed brand/mascot region and one 8px form surface. Do not use a split marketing hero; the brand `MonkeyShop` is a first-viewport signal. Registration success uses `pose="celebrate"`; 429 uses `pose="hourglass"`; permission/security uses `pose="shield"`. Inputs remain editable while countdown disables only submission.

- [ ] **Step 5: Run unit/Playwright/Axe and commit**

Run: `npm run test:unit -- src/views/LoginView.test.ts`

Run: `npx playwright test tests/consumer-auth.spec.ts tests/a11y-routes.spec.ts --project=chromium`

```powershell
git add frontend/src/views/LoginView.vue frontend/src/views/LoginView.test.ts frontend/src/api/auth.ts frontend/src/types.ts frontend/src/locales/index.ts frontend/tests/consumer-auth.spec.ts
git commit -m "feat(auth-ui): rebuild login and registration journey"
```

### Task 6: Rebuild Discovery, Search, Recommendations, And Product Detail

**Files:**
- Modify: `frontend/src/views/ShopView.vue`
- Modify: `frontend/src/views/SearchView.vue`
- Modify: `frontend/src/views/RecommendView.vue`
- Modify: `frontend/src/views/ProductDetailView.vue`
- Modify: `frontend/src/components/product/ProductCard.vue`
- Modify: `frontend/src/components/ProductImage.vue`
- Modify: `frontend/src/api/catalog.ts`
- Modify: `frontend/src/api/search.ts`
- Modify: `frontend/src/api/membership.ts`
- Modify: `frontend/src/TrackingSdk.ts`
- Test: `frontend/tests/consumer-discovery.spec.ts`
- Test: `frontend/tests/search-navigation-consistency.spec.ts`

**Interfaces:**
- Consumes: categories, SPU, prices, search/suggestions/hot words/recommendations, collections, browse history, inventory availability, marketing quote.
- Produces: URL-owned filter/query/sort state and cancellable latest-request-wins search.

- [ ] **Step 1: Add RED navigation and race tests**

```ts
expect(router.currentRoute.value.query).toEqual({ q: 'mint', sort: 'price_asc', category: '3' })
resolveSecondSearch(secondResult)
resolveFirstSearch(firstResult)
expect(renderedNames()).toEqual(secondResult.items.map(item => item.name))
```

- [ ] **Step 2: Run RED**

Run: `npx playwright test tests/consumer-discovery.spec.ts tests/search-navigation-consistency.spec.ts --project=chromium`

- [ ] **Step 3: Implement dense commerce layouts**

Discovery uses unframed category rails + product grid; product cards are genuine repeated entities. Product detail exposes SKU, price, stock, coupon quote, collection, and add-to-cart without decorative cards around sections. Search empty state uses `pose="search"`; inventory warning uses `pose="warning"` once in the detail view.

- [ ] **Step 4: Run Playwright, typecheck, and commit**

```powershell
git add frontend/src/views/ShopView.vue frontend/src/views/SearchView.vue frontend/src/views/RecommendView.vue frontend/src/views/ProductDetailView.vue frontend/src/components/product/ProductCard.vue frontend/src/components/ProductImage.vue frontend/src/api/catalog.ts frontend/src/api/search.ts frontend/src/api/membership.ts frontend/src/TrackingSdk.ts frontend/tests/consumer-discovery.spec.ts frontend/tests/search-navigation-consistency.spec.ts
git commit -m "feat(shop): rebuild discovery and product journeys"
```

### Task 7: Rebuild Cart, Checkout, And Payment As One Flow

**Files:**
- Modify: `frontend/src/views/CartView.vue`
- Modify: `frontend/src/views/CheckoutView.vue`
- Modify: `frontend/src/views/PaymentView.vue`
- Modify: `frontend/src/composables/useCheckout.ts`
- Modify: `frontend/src/api/cart.ts`
- Modify: `frontend/src/api/payments.ts`
- Modify: `frontend/src/api/user.ts`
- Modify: `frontend/src/types.ts`
- Test: `frontend/tests/consumer-cart-checkout.spec.ts`
- Test: `frontend/tests/checkout-consistency.spec.ts`
- Create: `frontend/src/composables/useCheckout.test.ts`

**Interfaces:**
- Consumes: cart CRUD/select, checkout preview, formal checkout, addresses, payment create/query, exact idempotency requirements.
- Produces: stable checkout intent key per normalized cart/address/coupon intent; payment intent key per order/method.

- [ ] **Step 1: Write RED intent and totals tests**

```ts
expect(checkoutKey(intent)).toBe(checkoutKey({ ...intent }))
expect(checkoutKey({ ...intent, addressId: 9 })).not.toBe(checkoutKey(intent))
expect(sum(subOrders.map(order => order.platformDiscount))).toBe(checkout.totalPlatformDiscount)
```

- [ ] **Step 2: Run RED unit and browser flows**

Run: `npm run test:unit -- src/composables/useCheckout.test.ts src/utils/idempotencyIntent.test.ts`

Run: `npx playwright test tests/consumer-cart-checkout.spec.ts tests/checkout-consistency.spec.ts --project=chromium`

- [ ] **Step 3: Implement explicit transaction states**

Cart has line-scoped pending mutations; checkout shows address, per-shop lines, store/platform discount allocation, freight, and payable totals. Formal checkout success routes to payment using persisted order IDs. Empty cart uses `pose="cart"`; transaction success uses `pose="celebrate"`; uncertain provider status remains pending with a query action and never shows success.

- [ ] **Step 4: Run tests and commit**

```powershell
git add frontend/src/views/CartView.vue frontend/src/views/CheckoutView.vue frontend/src/views/PaymentView.vue frontend/src/composables/useCheckout.ts frontend/src/composables/useCheckout.test.ts frontend/src/api/cart.ts frontend/src/api/payments.ts frontend/src/api/user.ts frontend/src/types.ts frontend/tests/consumer-cart-checkout.spec.ts frontend/tests/checkout-consistency.spec.ts
git commit -m "feat(checkout-ui): connect cart through payment"
```

### Task 8: Rebuild Orders, Returns, Reviews, And Logistics

**Files:**
- Modify: `frontend/src/views/OrdersView.vue`
- Modify: `frontend/src/views/ReviewView.vue`
- Modify: `frontend/src/views/LogisticsView.vue`
- Modify: `frontend/src/components/order/OrderStatusTimeline.vue`
- Modify: `frontend/src/api/orders.ts`
- Modify: `frontend/src/api/logistics.ts`
- Test: `frontend/tests/consumer-fulfillment.spec.ts`

**Interfaces:**
- Consumes: paged orders, shipment batches, receive, legal return actions, refund state, reviews, freight, tracking.
- Produces: status-derived action menus; consumer UI never renders admin ship/approve/confirm controls or webhook push.

- [ ] **Step 1: Add RED action-visibility tests**

```ts
expect(actionsFor(order('PENDING_PAYMENT'))).toEqual(['pay', 'cancel'])
expect(actionsFor(order('PAID'))).not.toContain('ship')
expect(actionsFor(order('RETURN_APPROVED'))).toContain('shipReturn')
```

- [ ] **Step 2: Run RED**

Run: `npx playwright test tests/consumer-fulfillment.spec.ts --project=chromium`

- [ ] **Step 3: Implement workflows and states**

Use an order table/list with expandable detail on desktop and stable stacked rows on mobile. Logistics uses `pose="package"` only for empty/first-load state. Return and review failures stay in their form section; destructive hide/cancel uses `ConfirmAction`.

- [ ] **Step 4: Run Playwright/Axe and commit**

```powershell
git add frontend/src/views/OrdersView.vue frontend/src/views/ReviewView.vue frontend/src/views/LogisticsView.vue frontend/src/components/order/OrderStatusTimeline.vue frontend/src/api/orders.ts frontend/src/api/logistics.ts frontend/tests/consumer-fulfillment.spec.ts
git commit -m "feat(order-ui): rebuild fulfillment and after-sales"
```

### Task 9: Rebuild Membership And Account Workspaces

**Files:**
- Modify: `frontend/src/views/MembershipView.vue`
- Modify: `frontend/src/views/ProfileView.vue`
- Modify: `frontend/src/api/membership.ts`
- Modify: `frontend/src/api/user.ts`
- Modify: `frontend/src/locales/index.ts`
- Test: `frontend/tests/consumer-account.spec.ts`
- Test: `frontend/tests/consumer-completion.spec.ts`

**Interfaces:**
- Consumes: profile/avatar/address/password/privacy, membership profile/check-in/points/collections/history/price drops.
- Produces: consumer-safe operations only; manual earn-points, level changes, and scan-price-drops remain admin operations.

- [ ] **Step 1: Add RED permission and dirty-form tests**

```ts
expect(screen.queryByRole('button', { name: '手工增加积分' })).toBeNull()
await editAddress()
await attemptRouteLeave()
expect(screen.getByRole('dialog', { name: '放弃未保存更改' })).toBeVisible()
```

- [ ] **Step 2: Run RED**

Run: `npx playwright test tests/consumer-account.spec.ts tests/consumer-completion.spec.ts --project=chromium`

- [ ] **Step 3: Implement account sections and member value**

Profile uses unframed tab sections for identity, addresses, security, and privacy. Membership uses compact metrics and real ledgers; shopping-bag mascot is reserved for empty collections. “忘记我” requires typed confirmation and explains irreversible effects without exposing PII.

- [ ] **Step 4: Run tests and commit**

```powershell
git add frontend/src/views/MembershipView.vue frontend/src/views/ProfileView.vue frontend/src/api/membership.ts frontend/src/api/user.ts frontend/src/locales/index.ts frontend/tests/consumer-account.spec.ts frontend/tests/consumer-completion.spec.ts
git commit -m "feat(account-ui): rebuild membership and profile"
```

### Task 10: Consumer Responsive, Accessibility, And Performance Gate

**Files:**
- Modify: `frontend/tests/a11y.spec.ts`
- Modify: `frontend/tests/a11y-routes.spec.ts`
- Create: `frontend/tests/consumer-responsive.spec.ts`
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/src/components/AppErrorBoundary.vue`

**Interfaces:**
- Produces: consumer route matrix across 320/390/768/1024/1440, light/dark, keyboard, reduced motion, and chunk recovery.

- [ ] **Step 1: Add RED matrix assertions**

```ts
for (const width of [320, 390, 768, 1024, 1440]) {
  await page.setViewportSize({ width, height: 900 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(await page.locator('h1').count()).toBe(1)
}
```

- [ ] **Step 2: Run RED matrix**

Run: `npx playwright test tests/a11y.spec.ts tests/a11y-routes.spec.ts tests/consumer-responsive.spec.ts --project=chromium`

- [ ] **Step 3: Fix route-level violations and split chunks by area**

Consumer initial chunks must not import admin views. Chunk-load failure displays a recoverable inline page error with home/back/logout paths and performs at most one controlled reload per build version.

- [ ] **Step 4: Run frontend gate and commit**

Run: `npm run lint`

Run: `npm run typecheck`

Run: `npm run test:unit`

Run: `npm run build`

Run: `npx playwright test tests/a11y.spec.ts tests/a11y-routes.spec.ts tests/consumer-responsive.spec.ts --project=chromium`

Expected: every command exits 0, no Axe serious/critical findings, no page-level horizontal overflow.

```powershell
git add frontend/tests/a11y.spec.ts frontend/tests/a11y-routes.spec.ts frontend/tests/consumer-responsive.spec.ts frontend/vite.config.ts frontend/src/components/AppErrorBoundary.vue
git commit -m "test(ui): gate consumer accessibility and responsiveness"
```

## Plan Acceptance

- Auth and all consumer routes are on the new tokens/shell/components with one `h1` and no duplicated navigation.
- All 12 mascot states are separate optimized assets; a route loads only its active pose.
- Registration uses backend policy metadata, three steps, field errors, and retry countdown; two ordinary failures never lock it.
- Discovery, search, recommendations, product, cart, checkout, payment, orders, returns, reviews, logistics, membership, and account use real typed API modules.
- No consumer control exposes payment callbacks, logistics webhooks, provider simulation, manual points, or admin fulfillment.
- Frontend lint, typecheck, unit, build, consumer Playwright, and Axe pass at all target widths and both themes.

