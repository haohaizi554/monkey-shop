# MonkeyShop Field Guide + Control UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild every MonkeyShop frontend surface into a coherent field-guide consumer experience, secure authentication checkpoint, and high-density operations console without changing business behavior.

**Architecture:** Keep the existing Vue route, shell, state, API, and accessibility ownership. Replace the visual contract from the center outward: semantic tokens and global primitives first, then shell surfaces, then consumer/auth/admin page groups, and finally cross-route responsive, dark-mode, accessibility, and visual verification. Area-specific appearance is selected through the existing `.app-shell[data-area]` contract; shared components retain one behavioral implementation.

**Tech Stack:** Vue 3.5, TypeScript 6, Vue Router 5, Pinia 3, Element Plus 2.14, CSS custom properties, Vitest 4, Playwright 1.61, Axe, Lighthouse, Vite 8.

**Spec:** `docs/superpowers/specs/2026-08-28-monkeyshop-field-guide-control-ui-design.md`

## Global Constraints

- Cover all 25 existing views; an updated shop page alone is not completion.
- Preserve current APIs, route paths/meta, permissions, test IDs, focus ownership, error mapping, idempotency, and async-state behavior.
- Use only semantic tokens outside `frontend/src/styles/tokens.css`; no raw color literals in Vue style blocks or other CSS files.
- Do not add gradients, glassmorphism, neon, emoji icons, fabricated data, runtime font CDNs, or large animation libraries.
- Continue using the bundled `Noto Sans SC Variable`; do not add a network font dependency.
- Touch targets are at least 44px on touch layouts; desktop icon controls are at least 36px.
- Page-level horizontal overflow is forbidden from 320px through 1920px; wide tables scroll only inside `DataTableShell` or another named local owner.
- Loading, updating, empty, error, success, disabled, hover, focus, and active states remain distinct and accessible.
- Respect `prefers-reduced-motion`; no essential meaning depends only on motion or color.
- Review visual snapshots one by one before replacing baselines; never use baseline updates to conceal unexplained diffs.

---

## File Responsibility Map

- `frontend/src/styles/tokens.css`: the only raw palette registry; shared and area-specific semantic variables.
- `frontend/src/styles/base.css`: document typography, reset, focus, selection, reduced motion, and base Element Plus-neutral behavior.
- `frontend/src/styles/shell.css`: consumer/auth/admin chrome and responsive shell geometry only.
- `frontend/src/styles/components.css`: shared UI primitives and generic consumer surfaces; remove obsolete compatibility styling as migration completes.
- `frontend/src/styles/admin-commerce.css`: admin toolbars, metrics, operation navigation, tables, and dense work surfaces.
- `frontend/src/styles/main.css`: deterministic import order only.
- `frontend/src/components/shell/*`: navigation structure and control semantics; no page content.
- `frontend/src/components/ui/*`: reusable behavioral surfaces and accessible state rendering.
- `frontend/src/components/product/ProductCard.vue`: the repeated storefront entity and its complete interaction states.
- `frontend/src/views/*.vue`: page composition and page-specific responsive layout; no private palette.
- `frontend/src/views/admin/*.vue`: operation-page composition; consume shared admin patterns rather than duplicating local chrome.
- `frontend/src/styles/token-contract.test.ts`: source-level design-token and anti-regression contract.
- `frontend/src/components/ui/ui-surfaces.test.ts`: semantic structure for shared surfaces.
- `frontend/src/components/admin/admin-primitives.test.ts`: admin primitive structure and tones.
- `frontend/tests/*.spec.ts`: route behavior, accessibility, responsive ownership, and visual evidence.

---

### Task 1: Install the Final Design Contract

**Files:**

- Modify: `frontend/src/styles/token-contract.test.ts`
- Modify: `frontend/src/styles/tokens.css`
- Modify: `frontend/src/styles/base.css`
- Modify: `frontend/src/styles/main.css`
- Test: `frontend/src/styles/token-contract.test.ts`

**Interfaces:**

- Produces: `--consumer-*`, `--auth-*`, and `--admin-*` area roles derived from one semantic light/dark registry.
- Produces: shared spacing, typography, radius, shadow, control, focus, motion, content-width, and z-index tokens used by every later task.

- [ ] **Step 1: Change the token test to the approved palette and new semantic roles**

```ts
expect(tokens).toContain('--color-canvas: #f3f1ea')
expect(tokens).toContain('--color-surface: #fffcf6')
expect(tokens).toContain('--color-ink: #17312a')
expect(tokens).toContain('--color-primary: #126b5b')
expect(tokens).toContain('--color-accent: #a8691f')
expect(tokens).toContain('--radius-control: 8px')
expect(tokens).toContain('--radius-surface: 12px')
expect(tokens).toContain('--content-consumer: 1320px')
expect(tokens).toContain('--content-detail: 1440px')
expect(tokens).toContain('--admin-sidebar-width: 248px')
expect(tokens).toMatch(/--font-sans:/)
```

Keep the existing raw-color scan and add assertions for `text-wrap: pretty`, `prefers-reduced-motion`, and dark theme area tokens.

- [ ] **Step 2: Run the contract test and confirm it fails for the old theme**

Run: `npm run test:unit -- src/styles/token-contract.test.ts`

Expected: FAIL on the old canvas, ink, primary, radii, and missing content-width tokens.

- [ ] **Step 3: Implement the token registry and base typography**

```css
:root {
  --color-canvas: #f3f1ea;
  --color-surface: #fffcf6;
  --color-surface-subtle: #eaede7;
  --color-surface-raised: #ffffff;
  --color-ink: #17312a;
  --color-muted: #66736e;
  --color-line: #d7d9d2;
  --color-line-strong: #aab3ac;
  --color-primary: #126b5b;
  --color-primary-strong: #0b4f43;
  --color-primary-soft: #dcede7;
  --color-accent: #a8691f;
  --color-accent-soft: #f6e7c9;
  --color-info: #315f9c;
  --color-danger: #b4493d;
  --radius-control: 8px;
  --radius-surface: 12px;
  --radius-overlay: 16px;
  --content-consumer: 1320px;
  --content-detail: 1440px;
  --admin-sidebar-width: 248px;
}
```

Map every Element Plus color/fill/border/radius/mask variable to a semantic token. Keep compatibility aliases only where current source still consumes them and document their removal path. In `base.css`, add `text-wrap: pretty` for headings/paragraphs, `tabular-nums` for commercial values, themed selection, and a reduced-motion block that disables non-essential transitions without hiding state changes.

- [ ] **Step 4: Run the focused contract and global static checks**

Run: `npm run test:unit -- src/styles/token-contract.test.ts`

Run: `npm run typecheck`

Run: `npm run lint`

Expected: all commands exit 0.

- [ ] **Step 5: Commit the design foundation**

```powershell
git add frontend/src/styles/token-contract.test.ts frontend/src/styles/tokens.css frontend/src/styles/base.css frontend/src/styles/main.css
git commit -m "feat(ui): establish field guide design contract"
```

---

### Task 2: Rebuild the Shell and Shared Surface Hierarchy

**Files:**

- Modify: `frontend/src/components/shell/ConsumerHeader.vue`
- Modify: `frontend/src/components/shell/ConsumerBottomNav.vue`
- Modify: `frontend/src/components/shell/AdminSidebar.vue`
- Modify: `frontend/src/components/shell/AdminTopbar.vue`
- Modify: `frontend/src/components/ui/PageHeader.vue`
- Modify: `frontend/src/components/ui/DataTableShell.vue`
- Modify: `frontend/src/components/ui/InlineNotice.vue`
- Modify: `frontend/src/components/ui/FormSection.vue`
- Modify: `frontend/src/components/ui/StatusTag.vue`
- Modify: `frontend/src/styles/shell.css`
- Modify: `frontend/src/styles/components.css`
- Modify: `frontend/src/styles/admin-commerce.css`
- Modify: `frontend/src/components/ui/ui-surfaces.test.ts`
- Modify: `frontend/src/components/admin/admin-primitives.test.ts`
- Modify: `frontend/tests/shell.spec.ts`

**Interfaces:**

- Consumes: route `area` and `hideConsumerBottomNav` metadata without changing their types.
- Produces: area-scoped shell geometry, stable navigation regions, a field-guide page header, compact admin work surfaces, and local table scroll ownership.

- [ ] **Step 1: Add structural and computed-style assertions**

```ts
expect(host.querySelector('.page-header')?.getAttribute('data-surface')).toBe('page-heading')
expect(host.querySelector('.data-table-shell__scroller')?.getAttribute('tabindex')).toBe('0')
expect(host.querySelector('.inline-notice')?.getAttribute('role')).toBe('alert')
```

In `shell.spec.ts`, assert desktop consumer chrome is at least 64px high, admin sidebar width resolves to 248px, current navigation has `aria-current="page"`, the mobile bottom bar respects `env(safe-area-inset-bottom)`, and existing one-shell/focus/overflow tests remain unchanged.

- [ ] **Step 2: Run focused tests and confirm the new appearance contract fails**

Run: `npm run test:unit -- src/components/ui/ui-surfaces.test.ts src/components/admin/admin-primitives.test.ts`

Run: `npx playwright test tests/shell.spec.ts --project=chromium`

Expected: new `data-surface` and computed shell geometry assertions fail; existing behavior assertions continue to pass.

- [ ] **Step 3: Implement consumer and auth chrome**

Keep the current routes and labels. Arrange desktop chrome as brand / primary routes / utilities, reduce secondary-link weight, and give the active route a short underline plus soft fill. At widths below 760px, render only brand/search/cart/language in the header and preserve the five-destination bottom navigation. Use existing Element Plus icons and real brand assets; do not insert emoji or generated icons.

```vue
<header class="consumer-header" :data-compact="compact" data-surface="consumer-chrome">
  <!-- existing accessible brand, navigation, and action controls -->
</header>
```

- [ ] **Step 4: Implement admin chrome and shared surface styles**

Use the 248px sidebar token, 64px topbar, grouped navigation, amber active signal, quiet workspace search, 12px shared surfaces, and 16px overlays. Keep the current mobile drawer focus trap and Escape/focus-return behavior. Give `PageHeader`, `DataTableShell`, `InlineNotice`, `FormSection`, and `StatusTag` explicit `data-surface`/`data-tone` hooks without changing their public props.

- [ ] **Step 5: Run shell, unit, dark, and 320px checks**

Run: `npm run test:unit -- src/components/ui/ui-surfaces.test.ts src/components/admin/admin-primitives.test.ts src/components/ui/commerce-primitives.test.ts`

Run: `npx playwright test tests/shell.spec.ts --project=chromium`

Expected: all tests pass at existing 320, 390, and 1440 viewport cases.

- [ ] **Step 6: Commit shell and primitives**

```powershell
git add frontend/src/components/shell frontend/src/components/ui frontend/src/styles/shell.css frontend/src/styles/components.css frontend/src/styles/admin-commerce.css frontend/src/components/admin/admin-primitives.test.ts frontend/tests/shell.spec.ts
git commit -m "feat(ui): rebuild application chrome and surfaces"
```

---

### Task 3: Recompose Discovery and Product Pages

**Files:**

- Modify: `frontend/src/views/ShopView.vue`
- Modify: `frontend/src/views/SearchView.vue`
- Modify: `frontend/src/views/RecommendView.vue`
- Modify: `frontend/src/views/ProductDetailView.vue`
- Modify: `frontend/src/components/product/ProductCard.vue`
- Modify: `frontend/src/components/ProductImage.vue`
- Modify: `frontend/tests/consumer-discovery.spec.ts`
- Modify: `frontend/tests/a11y.spec.ts`

**Interfaces:**

- Preserves: existing filters, URL state, category links, quick checkout, collection, inventory, tracking, async state, and API calls.
- Produces: photo-first product grid, specimen metadata line, coherent card states, and responsive image/purchase composition.

- [ ] **Step 1: Extend browser assertions for the new composition**

```ts
await expect(page.locator('.product-grid')).toHaveAttribute('data-layout', 'field-guide')
await expect(page.locator('.product-card').first()).toHaveAttribute('data-surface', 'specimen')
await expect(page.locator('.product-card__media').first()).toHaveCSS('aspect-ratio', /4 \/ 3/)
await expect(page.locator('.product-card__primary').first()).toBeVisible()
```

Add 390px assertions for one readable column, 768px for at least two grid tracks, and product-detail assertions for one-column mobile / two-column desktop without document overflow.

- [ ] **Step 2: Run discovery tests and verify the new layout assertions fail**

Run: `npx playwright test tests/consumer-discovery.spec.ts tests/a11y.spec.ts --project=chromium`

Expected: new data attributes/aspect/layout assertions fail on the old card composition.

- [ ] **Step 3: Implement the field-guide storefront**

Use a compact editorial page intro, scrollable taxonomy rail, one consolidated filter surface, and a grid defined by:

```css
.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr));
  gap: clamp(var(--space-5), 2.4vw, var(--space-8));
}
```

Make product photography 4:3 with stable sizing and intentional `object-position`. Put name/breed and price on the first text row, description below, then stock and actions. Add hover/focus-within elevation, active press feedback, sold-out desaturation, loading stability, and dark variants solely through tokens.

- [ ] **Step 4: Recompose search, recommendations, and product detail**

Reuse the same card and filter language. Product detail uses a photo gallery region plus a sticky purchase/specification panel on desktop and a single reading sequence on mobile. Keep existing SKU, price, inventory, collection, quantity, add-to-cart, buy-now, and offer-estimate behavior intact.

- [ ] **Step 5: Run discovery, Axe, type, and unit gates**

Run: `npm run test:unit -- src/views/ProductDetailView.test.ts`

Run: `npx playwright test tests/consumer-discovery.spec.ts tests/search-navigation-consistency.spec.ts tests/a11y.spec.ts --project=chromium`

Run: `npm run typecheck`

Expected: all commands exit 0; document overflow is at most 1px.

- [ ] **Step 6: Commit discovery pages**

```powershell
git add frontend/src/views/ShopView.vue frontend/src/views/SearchView.vue frontend/src/views/RecommendView.vue frontend/src/views/ProductDetailView.vue frontend/src/components/product/ProductCard.vue frontend/src/components/ProductImage.vue frontend/tests/consumer-discovery.spec.ts frontend/tests/a11y.spec.ts
git commit -m "feat(shop): create field guide discovery experience"
```

---

### Task 4: Rebuild Transaction, Fulfillment, Membership, and Account Surfaces

**Files:**

- Modify: `frontend/src/views/CartView.vue`
- Modify: `frontend/src/views/CheckoutView.vue`
- Modify: `frontend/src/views/PaymentView.vue`
- Modify: `frontend/src/views/OrdersView.vue`
- Modify: `frontend/src/views/ReviewView.vue`
- Modify: `frontend/src/views/LogisticsView.vue`
- Modify: `frontend/src/views/MembershipView.vue`
- Modify: `frontend/src/views/ProfileView.vue`
- Modify: `frontend/src/components/order/OrderStatusTimeline.vue`
- Modify: `frontend/tests/consumer-cart-checkout.spec.ts`
- Modify: `frontend/tests/consumer-fulfillment.spec.ts`
- Modify: `frontend/tests/consumer-account.spec.ts`
- Modify: `frontend/tests/consumer-completion.spec.ts`

**Interfaces:**

- Preserves: cart selection/quantity, checkout totals and idempotency, payment uncertainty, order actions, returns, reviews, tracking, membership ledgers, profile security, and dirty-form safeguards.
- Produces: packing-slip transaction hierarchy, readable timelines, mobile sticky summaries that do not obscure navigation, and consistent long-form sections.

- [ ] **Step 1: Add visual-structure assertions without weakening behavior checks**

```ts
await expect(page.locator('.cart-summary')).toHaveAttribute('data-surface', 'transaction-summary')
await expect(page.locator('.checkout-summary')).toHaveAttribute('data-surface', 'transaction-summary')
await expect(page.locator('.order-status-timeline')).toHaveAttribute('data-surface', 'status-spine')
```

At 390px, assert the primary payable/submit action is visible, the page has no horizontal overflow, and any sticky region does not overlap the consumer bottom navigation or focused input.

- [ ] **Step 2: Run the consumer flow suites and confirm only new appearance assertions fail**

Run: `npx playwright test tests/consumer-cart-checkout.spec.ts tests/checkout-consistency.spec.ts tests/consumer-fulfillment.spec.ts tests/consumer-account.spec.ts tests/consumer-completion.spec.ts --project=chromium`

- [ ] **Step 3: Implement cart, checkout, and payment hierarchy**

Use a line-item ledger, explicit cost breakdown, bounded summary surface, and one dominant primary action. On mobile convert wide cart rows into readable item blocks while preserving accessible names and input controls. Keep pending/uncertain/error states in place and visually distinct; never style provider uncertainty as success.

- [ ] **Step 4: Implement orders, reviews, and logistics hierarchy**

Use status spine/timeline semantics, clear next actions, metadata clusters, and local table/list responsive behavior. Reserve package/clipboard/warning mascots for empty or exceptional states; do not repeat them per row.

- [ ] **Step 5: Implement membership and profile hierarchy**

Use compact value metrics, ledger/list sections, stable tabs, readable form sections, masked sensitive information, and isolated destructive actions. Remove nested decorative cards while retaining all field/error/test hooks.

- [ ] **Step 6: Run flow, Axe, unit, and type checks**

Run: `npm run test:unit -- src/composables/useCheckout.test.ts src/utils/orderActions.test.ts src/utils/orderLineContract.test.ts`

Run: `npx playwright test tests/consumer-cart-checkout.spec.ts tests/checkout-consistency.spec.ts tests/consumer-fulfillment.spec.ts tests/consumer-account.spec.ts tests/consumer-completion.spec.ts --project=chromium`

Run: `npm run typecheck`

Expected: all commands exit 0 and existing business assertions remain unchanged.

- [ ] **Step 7: Commit consumer lifecycle pages**

```powershell
git add frontend/src/views/CartView.vue frontend/src/views/CheckoutView.vue frontend/src/views/PaymentView.vue frontend/src/views/OrdersView.vue frontend/src/views/ReviewView.vue frontend/src/views/LogisticsView.vue frontend/src/views/MembershipView.vue frontend/src/views/ProfileView.vue frontend/src/components/order/OrderStatusTimeline.vue frontend/tests/consumer-cart-checkout.spec.ts frontend/tests/consumer-fulfillment.spec.ts frontend/tests/consumer-account.spec.ts frontend/tests/consumer-completion.spec.ts
git commit -m "feat(shop): refine transaction and account journeys"
```

---

### Task 5: Rebuild Authentication and Recovery States

**Files:**

- Modify: `frontend/src/views/LoginView.vue`
- Modify: `frontend/src/views/NotFoundView.vue`
- Modify: `frontend/src/components/AppErrorBoundary.vue`
- Modify: `frontend/src/components/mascot/MascotState.vue`
- Modify: `frontend/src/views/LoginView.test.ts`
- Modify: `frontend/tests/consumer-auth.spec.ts`
- Modify: `frontend/tests/a11y-routes.spec.ts`

**Interfaces:**

- Preserves: login, registration steps, reset stages, captcha, MFA, password policy, field errors, retry countdown, forced password change, route recovery, and safe production error copy.
- Produces: secure checkpoint layout with calm state hierarchy and purposeful mascot use.

- [ ] **Step 1: Add auth composition and state tests**

```ts
expect(host.querySelector('.auth-workspace')?.getAttribute('data-surface')).toBe('secure-checkpoint')
expect(host.querySelector('.auth-mode-switch')?.getAttribute('role')).toBe('tablist')
expect(host.querySelectorAll('h1')).toHaveLength(1)
```

In Playwright, assert the form action remains visible at 390x844, the welcome visual does not exceed 35% of the first viewport, focused controls are not covered, and rate-limit/MFA/password errors stay inline.

- [ ] **Step 2: Run auth unit/browser tests and verify new composition assertions fail**

Run: `npm run test:unit -- src/views/LoginView.test.ts`

Run: `npx playwright test tests/consumer-auth.spec.ts --project=chromium`

- [ ] **Step 3: Implement secure checkpoint and recovery pages**

Keep one brand illustration near the heading, one bounded form surface, a clear three-mode selector, and restrained register/reset steppers. Use shield/hourglass/warning/celebrate poses only for the corresponding state. Give not-found and error-boundary pages one concise recovery action group with no fake diagnostic detail.

- [ ] **Step 4: Run auth, Axe, and source-security checks**

Run: `npm run test:unit -- src/views/LoginView.test.ts src/components/mascot/MascotState.test.ts`

Run: `npx playwright test tests/consumer-auth.spec.ts tests/a11y-routes.spec.ts --project=chromium --grep "auth|not-found|error"`

Run: `npm run lint`

Expected: all commands exit 0; no raw backend or exception detail is rendered.

- [ ] **Step 5: Commit authentication and recovery surfaces**

```powershell
git add frontend/src/views/LoginView.vue frontend/src/views/NotFoundView.vue frontend/src/components/AppErrorBoundary.vue frontend/src/components/mascot/MascotState.vue frontend/src/views/LoginView.test.ts frontend/tests/consumer-auth.spec.ts frontend/tests/a11y-routes.spec.ts
git commit -m "feat(auth-ui): create secure checkpoint experience"
```

---

### Task 6: Rebuild Every Admin Workspace

**Files:**

- Modify: `frontend/src/views/AdminView.vue`
- Modify: `frontend/src/views/InventoryView.vue`
- Modify: `frontend/src/views/MarketingView.vue`
- Modify: `frontend/src/views/DashboardView.vue`
- Modify: `frontend/src/views/RiskReviewView.vue`
- Modify: `frontend/src/views/TenantAdminView.vue`
- Modify: `frontend/src/views/admin/OrderOperationsView.vue`
- Modify: `frontend/src/views/admin/ReturnOperationsView.vue`
- Modify: `frontend/src/views/admin/PaymentOperationsView.vue`
- Modify: `frontend/src/views/admin/LogisticsOperationsView.vue`
- Modify: `frontend/src/views/admin/MemberOperationsView.vue`
- Modify: `frontend/src/components/admin/AdminCommerceNav.vue`
- Modify: `frontend/src/components/admin/AdminPageToolbar.vue`
- Modify: `frontend/src/components/admin/MetricStrip.vue`
- Modify: `frontend/src/styles/admin-commerce.css`
- Modify: `frontend/tests/admin-primitives.spec.ts`
- Modify: `frontend/tests/admin-dashboard.spec.ts`
- Modify: `frontend/tests/admin-inventory.spec.ts`
- Modify: `frontend/tests/admin-marketing.spec.ts`
- Modify: `frontend/tests/admin-risk.spec.ts`
- Modify: `frontend/tests/admin-tenants.spec.ts`
- Modify: `frontend/tests/admin-operations.spec.ts`
- Modify: `frontend/tests/admin-commerce-operations.spec.ts`
- Modify: `frontend/tests/admin-member-operations.spec.ts`

**Interfaces:**

- Preserves: every existing admin query, mutation, permission, polling, export, dialog/drawer, table, and status action.
- Produces: one operations-observatory hierarchy across catalog, inventory, marketing, dashboard, risk, tenants, orders, returns, payments, logistics, and members.

- [ ] **Step 1: Add admin surface and density assertions**

```ts
await expect(page.locator('.admin-page-toolbar')).toHaveAttribute('data-density', 'compact')
await expect(page.locator('.metric-strip')).toHaveAttribute('data-surface', 'signal-strip')
await expect(page.locator('.data-table-shell__scroller')).toHaveCSS('overflow-x', 'auto')
```

For representative tables, assert header contrast, row focus/hover hooks, visible status tags, and local overflow at 390px. Keep all current action and API assertions.

- [ ] **Step 2: Run the complete admin browser group and confirm new style hooks fail**

Run: `npx playwright test tests/admin-*.spec.ts --project=chromium`

Expected: new data-surface/density assertions fail; existing behavior remains green.

- [ ] **Step 3: Implement shared admin patterns first**

Give `AdminPageToolbar`, `MetricStrip`, `AdminCommerceNav`, `DataTableShell`, drawers, dialogs, and forms stable compact density and observatory surfaces. Use status lines and restrained color encoding, not decorative left-border cards. Keep table scroll regions focusable and labelled.

- [ ] **Step 4: Migrate all 11 admin views**

For each view, establish one page header, one primary toolbar, task-grouped content, consistent metrics, table/list ownership, and clear empty/error/updating states. Dashboard/risk/tenant pages may retain specialized layouts, but must consume shared tokens and density. Remove page-local raw spacing/radius systems and redundant cards.

- [ ] **Step 5: Run all admin, async-state, and accessibility checks**

Run: `npm run test:unit -- src/components/admin/admin-primitives.test.ts src/components/ui/ui-surfaces.test.ts src/composables/useAsyncState.test.ts`

Run: `npx playwright test tests/admin-*.spec.ts tests/a11y-routes.spec.ts --project=chromium --grep "admin|dashboard|risk|tenant"`

Run: `npm run typecheck`

Expected: all commands exit 0, polling/update states keep existing data, and page overflow is at most 1px.

- [ ] **Step 6: Commit all admin workspaces**

```powershell
git add frontend/src/views/AdminView.vue frontend/src/views/InventoryView.vue frontend/src/views/MarketingView.vue frontend/src/views/DashboardView.vue frontend/src/views/RiskReviewView.vue frontend/src/views/TenantAdminView.vue frontend/src/views/admin frontend/src/components/admin frontend/src/styles/admin-commerce.css frontend/tests/admin-*.spec.ts
git commit -m "feat(admin-ui): rebuild operations observatory"
```

---

### Task 7: Consolidate Responsive, Dark, Interaction, and State Details

**Files:**

- Modify: `frontend/src/styles/tokens.css`
- Modify: `frontend/src/styles/base.css`
- Modify: `frontend/src/styles/shell.css`
- Modify: `frontend/src/styles/components.css`
- Modify: `frontend/src/styles/admin-commerce.css`
- Modify: all view/component scoped style blocks that still contain obsolete spacing or breakpoints
- Modify: `frontend/src/styles/token-contract.test.ts`
- Modify: `frontend/tests/a11y-routes.spec.ts`
- Modify: `frontend/scripts/ui-smoke.mjs`

**Interfaces:**

- Produces: shared 320/375/390/768/1024/1440/1920 responsive behavior, complete light/dark states, reduced-motion behavior, and a source scan free of rogue palette systems.

- [ ] **Step 1: Extend the contract and route matrix before cleanup**

```ts
expect(tokens).toContain("html.dark")
expect(styles).toContain('@container')
expect(styles).toContain('@media (prefers-reduced-motion: reduce)')
expect(offenders).toEqual([])
```

Extend UI smoke viewport definitions to include 320x720 and 1920x1080 while retaining 390, 768, and 1440. Add checks for clipped fixed/sticky actions and visible focus outlines.

- [ ] **Step 2: Run contract and a representative smoke subset to expose gaps**

Run: `npm run test:unit -- src/styles/token-contract.test.ts`

Run: `node scripts/ui-smoke.mjs --routes /shop,/shop/1,/checkout,/profile,/login,/admin,/risk,/tenants`

Expected: new container/dark/viewport requirements identify remaining old-style or layout gaps.

- [ ] **Step 3: Consolidate responsive rules and interaction states**

Replace one-off breakpoints with component/container rules where safe. Verify hover, focus-visible, active, selected, disabled, loading, empty, error, updating, dialog, drawer, dropdown, table, pagination, tabs, and sticky actions. Preserve intentional local scroll owners for tables and admin tab rows.

- [ ] **Step 4: Complete dark and reduced-motion coverage**

Inspect every shared surface and high-risk page in dark mode. Correct Element Plus overlays, inputs, masks, tables, poppers, dialogs, scrollbars, status tones, image fallbacks, focus rings, and disabled contrast. Under reduced motion, remove translation/scale while keeping opacity-free state visibility.

- [ ] **Step 5: Run source, unit, smoke, and accessibility gates**

Run: `npm run test:unit`

Run: `npm run test:ui-smoke`

Run: `npm run test:a11y`

Run: `npm run lint`

Expected: all commands exit 0, all viewport checks pass, and no serious/critical Axe issue remains.

- [ ] **Step 6: Commit cross-route polish**

```powershell
git add frontend/src frontend/tests/a11y-routes.spec.ts frontend/scripts/ui-smoke.mjs
git commit -m "feat(ui): complete responsive and dark state polish"
```

---

### Task 8: Verify, Review, and Record the New Visual Baseline

**Files:**

- Modify: `frontend/tests/a11y-routes.spec.ts-snapshots/*.png` only after manual review
- Modify: `frontend/lighthouse-report.json` only if the existing script intentionally refreshes the tracked report
- Modify: `docs/superpowers/plans/2026-08-28-monkeyshop-field-guide-control-ui.md` checkbox state

**Interfaces:**

- Produces: current build, static, browser, accessibility, visual, performance, and worktree evidence for the entire redesign.

- [ ] **Step 1: Run the complete static and unit gate**

Run: `npm run format`

Run: `npm run lint`

Run: `npm run typecheck`

Run: `npm run test:unit`

Run: `npm run test:api-contract`

Run: `npm run build`

Expected: 0 errors/warnings from owned source; 29 or more test files and 151 or more unit tests pass; API contract reports 19 modules and 113 or more UI-consumed clients.

- [ ] **Step 2: Generate the intended visual diffs without replacing baselines**

Run: `npm run test:visual`

Expected: snapshot mismatches are expected on the first run and generate actual/diff artifacts. Inspect every consumer, auth, and admin screenshot for composition, clipping, states, images, dark mode, and typography.

- [ ] **Step 3: Fix unexplained visual defects, then update baselines once**

Run after fixes: `npm run test:visual:update`

Run immediately again: `npm run test:visual`

Expected: the second command exits 0; no baseline is accepted without viewing its corresponding actual image.

- [ ] **Step 4: Run complete browser and performance gates**

Run: `npm run test:ui-smoke`

Run: `npm run test:a11y`

Run: `npm run test:lighthouse`

Run: `npm run test:runtime` only if the required local runtime stack is already available; otherwise record it separately as unverified rather than restarting user services.

Expected: UI smoke, Axe, and Lighthouse exit 0; Lighthouse categories remain at least 0.95 and LCP remains at most 2500ms.

- [ ] **Step 5: Perform the completion audit**

Inspect all 25 routes against the spec, `git diff --check`, `git status --short`, raw-color scan, console results, and representative 320/390/768/1440/1920 screenshots. Confirm there is no old-theme island, page-level overflow, clipped action, duplicated heading/chrome, stale baseline, or unverified state claim.

- [ ] **Step 6: Commit verified baselines and plan completion state**

```powershell
git add frontend/tests/a11y-routes.spec.ts-snapshots docs/superpowers/plans/2026-08-28-monkeyshop-field-guide-control-ui.md
git commit -m "test(ui): record verified field guide baselines"
```

## Plan Self-Review

- Spec coverage: all shared foundations, shells, 14 consumer/auth views, 11 admin views, dark mode, responsive behavior, states, accessibility, performance, and visual baselines map to Tasks 1–8.
- Placeholder scan: the plan contains no deferred implementation markers or unspecified test steps.
- Interface consistency: all tasks preserve the existing route/API/state contracts; new hooks are CSS/data attributes and semantic tokens consumed by later tasks.
- Execution choice: the user delegated all decisions, so use subagent-driven execution with sequential file ownership and primary-agent review between tasks.

