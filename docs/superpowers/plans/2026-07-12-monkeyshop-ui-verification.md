# MonkeyShop UI Verification and Final Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the complete MonkeyShop UI redesign with deterministic static contracts, unit tests, all-route browser checks, accessibility, screenshots, image pixels, performance, and live local integration evidence.

**Architecture:** Fast static/unit checks reject contract drift before browser startup. Mocked Playwright checks make all routes deterministic across three viewports, while a separate live-local smoke confirms frontend/backend integration without weakening deterministic tests. Verification writes machine-readable JSON plus screenshots under ignored output directories.

**Tech Stack:** Node.js, Vue/Vite, Vitest, Playwright, Axe, Lighthouse, existing PowerShell runtime smoke scripts.

## Global Constraints

- Foundation, consumer, and admin plans must be implemented before final completion.
- Verification must inspect current files and runtime; prior passing output is not accepted as current evidence.
- A command timeout or missing route context is a verification failure, not a pass.
- Console errors, console warnings, unexpected request failures, stuck loading, broken images, and page-level horizontal overflow are failures.
- Test fixtures may use deterministic data but rendered product copy must not claim fabricated production facts.
- Browser artifacts live under `frontend/output/playwright/`; Lighthouse artifacts live under the existing configured output path.
- All generated artifact directories must remain ignored by Git.
- Stage and commit only current-task files.

---

## File Structure

### New files

- `frontend/scripts/ui-contract.mjs`: static UI architecture/token/feedback contract scanner.
- `frontend/scripts/ui-visual.mjs`: all-route screenshot and pixel verification runner.
- `frontend/tests/theme-and-locale.spec.ts`: dark/light, locale, reduced-motion, and zoom checks.
- `docs/reviews/2026-07-12-ui-completion-audit.md`: requirement-to-evidence completion matrix.

### Modified files

- `frontend/package.json`
- `frontend/.gitignore` or repository `.gitignore`
- `frontend/scripts/ui-smoke.mjs`
- `frontend/scripts/a11y.mjs`
- `frontend/scripts/lighthouse.mjs`
- `frontend/tests/a11y.spec.ts`
- `frontend/playwright.config.ts`
- `README.md`

## Interfaces

```ts
export interface UiContractResult {
  name: string
  status: 'pass' | 'fail'
  files: string[]
  details: string[]
}

export interface VisualRouteResult {
  route: string
  viewport: string
  screenshot: string
  consoleErrors: string[]
  consoleWarnings: string[]
  requestFailures: string[]
  horizontalOverflow: number
  brokenImages: string[]
  nonBlankImagePixels: number
}
```

---

### Task 1: Enforce Static UI Contracts

**Files:**
- Create: `frontend/scripts/ui-contract.mjs`
- Modify: `frontend/package.json`
- Modify: repository `.gitignore`

**Interfaces:**
- Produces: `npm run test:ui-contract` and JSON-readable failures.

- [ ] **Step 1: Write the scanner with explicit rules**

The script recursively reads `src/views`, `src/components`, and `src/styles` and fails on:

```js
const rules = [
  {
    name: 'views-do-not-own-shell',
    files: ['src/views/**/*.vue'],
    pattern: /(?:import\s+AppShell|<AppShell\b)/,
  },
  {
    name: 'views-do-not-use-global-element-feedback',
    files: ['src/views/**/*.vue'],
    pattern: /\b(?:ElMessage|ElNotification)\b/,
  },
  {
    name: 'raw-colors-live-only-in-token-registry',
    files: ['src/**/*.vue', 'src/styles/**/*.css'],
    exclude: ['src/styles/tokens.css'],
    pattern: /#[0-9a-f]{3,8}\b|rgba?\(/i,
  },
  {
    name: 'no-scroll-into-view',
    files: ['src/**/*.{ts,vue}'],
    pattern: /scrollIntoView\s*\(/,
  },
]
```

Also verify every route has `meta.area` and `meta.titleKey`, and every view exports exactly one template root.

- [ ] **Step 2: Add the package script**

```json
"test:ui-contract": "node scripts/ui-contract.mjs"
```

- [ ] **Step 3: Ignore browser artifacts**

Add:

```gitignore
frontend/output/
frontend/test-results/
frontend/playwright-report/
frontend/.playwright-cli/
```

- [ ] **Step 4: Run the scanner**

Run: `npm run test:ui-contract`

Expected: every named contract prints `PASS`; any violation prints file and line and exits 1.

- [ ] **Step 5: Commit static contracts**

```powershell
git add frontend/scripts/ui-contract.mjs frontend/package.json .gitignore
git commit -m "test(ui): enforce architecture contracts"
```

---

### Task 2: Expand All-Route Smoke to Three Viewports

**Files:**
- Modify: `frontend/scripts/ui-smoke.mjs`
- Modify: `frontend/package.json`

**Interfaces:**
- Produces: 57 deterministic route checks and `output/playwright/ui-smoke-results.json`.

- [ ] **Step 1: Add the tablet viewport**

```js
{
  name: 'tablet',
  options: {
    viewport: { width: 768, height: 1024 },
    deviceScaleFactor: 1,
    isMobile: false,
    hasTouch: true,
  },
}
```

- [ ] **Step 2: Record every route result incrementally**

After each route, write the full current results array to `output/playwright/ui-smoke-results.json`. Ensure the output directory exists through `mkdir({ recursive: true })`. A process interruption therefore preserves the last completed route.

- [ ] **Step 3: Add explicit checks**

For each route assert:

- one shell/main and no nested shell;
- page-level overflow at most 2px;
- no visible loading mask after readiness;
- no raw banned copy;
- no image with `naturalWidth === 0` after image settle timeout;
- no visible text clipped outside its control box;
- no control smaller than 44px on mobile or 36px on desktop when it is icon-only;
- no console error/warning and no unexpected request failure;
- API request count at or below the route budget.

- [ ] **Step 4: Keep route failures isolated**

Each route runs in its own context and returns failure details without aborting the loop. At the end, print all failures grouped by viewport and route and exit 1 if any exist.

- [ ] **Step 5: Run the 57-route gate**

Run: `npm run test:ui-smoke`

Expected: table contains 57 pass rows and final text `UI smoke passed 57 route checks.`

- [ ] **Step 6: Commit expanded smoke coverage**

```powershell
git add frontend/scripts/ui-smoke.mjs frontend/package.json
git commit -m "test(ui): cover all routes at three viewports"
```

---

### Task 3: Capture Visual Evidence and Verify Image Pixels

**Files:**
- Create: `frontend/scripts/ui-visual.mjs`
- Modify: `frontend/package.json`

**Interfaces:**
- Produces: screenshots for representative routes and `ui-visual-results.json`.

- [ ] **Step 1: Define the representative route matrix**

Use:

```js
const routes = [
  '/login',
  '/shop',
  '/shop/1',
  '/search?q=golden',
  '/cart',
  '/checkout',
  '/orders',
  '/profile',
  '/admin',
  '/inventory?skuId=101',
  '/marketing',
  '/risk',
  '/dashboard',
  '/tenants?tenant=1',
]
```

Capture each at 390x844, 768x1024, and 1440x900 in light mode. Capture `/login`, `/shop`, `/admin`, and `/dashboard` again in dark mode.

- [ ] **Step 2: Reuse deterministic API/image fixtures**

Extract the fixture installer from `ui-smoke.mjs` into an exported module or import a shared fixture module created during implementation. Do not duplicate divergent route contracts.

- [ ] **Step 3: Add pixel non-blank checks**

For the login hero and first product image, draw the loaded image to a canvas and sample a 10x10 grid. Fail when fewer than 10 sampled pixels differ from the computed background color or when canvas dimensions are zero.

```js
const pixels = await locator.evaluate((image) => {
  const canvas = document.createElement('canvas')
  canvas.width = image.naturalWidth
  canvas.height = image.naturalHeight
  const context = canvas.getContext('2d')
  if (!context || canvas.width === 0 || canvas.height === 0) return 0
  context.drawImage(image, 0, 0)
  let nonBlank = 0
  for (let y = 0; y < 10; y += 1) {
    for (let x = 0; x < 10; x += 1) {
      const pixel = context.getImageData(
        Math.floor((x + 0.5) * canvas.width / 10),
        Math.floor((y + 0.5) * canvas.height / 10),
        1,
        1,
      ).data
      if (pixel[3] > 0 && (pixel[0] + pixel[1] + pixel[2]) > 24) nonBlank += 1
    }
  }
  return nonBlank
})
```

- [ ] **Step 4: Capture full-page screenshots**

Save names as `<route-slug>-<viewport>-<theme>.png` under `output/playwright/visual/`. Disable animations through reduced-motion media before capture and wait for fonts/images.

- [ ] **Step 5: Add the package script and run**

```json
"test:visual": "node scripts/ui-visual.mjs"
```

Run: `npm run test:visual`

Expected: all screenshots exist, all image pixel counts pass, and JSON reports zero visual runtime failures.

- [ ] **Step 6: Commit the visual runner**

```powershell
git add frontend/scripts/ui-visual.mjs frontend/package.json
git commit -m "test(ui): add visual and pixel verification"
```

---

### Task 4: Verify Accessibility, Theme, Locale, Motion, and Zoom

**Files:**
- Modify: `frontend/tests/a11y.spec.ts`
- Create: `frontend/tests/theme-and-locale.spec.ts`
- Modify: `frontend/scripts/a11y.mjs`
- Modify: `frontend/playwright.config.ts`

**Interfaces:**
- Produces: WCAG A/AA coverage for representative consumer/admin routes and interaction preferences.

- [ ] **Step 1: Expand Axe route coverage**

Run Axe against `/login`, `/shop`, `/shop/1`, `/cart`, `/checkout`, `/profile`, `/admin`, `/inventory`, `/risk`, and `/tenants` with deterministic API mocks. Fail on any `serious` or `critical` violation and print selectors/help URLs.

- [ ] **Step 2: Test light/dark and locale persistence**

```ts
test('theme and locale survive navigation and reload', async ({ page }) => {
  await page.goto('/shop')
  await page.getByRole('button', { name: '切换到深色主题' }).click()
  await page.getByRole('button', { name: '切换语言' }).click()
  await page.goto('/cart')
  await page.reload()
  await expect(page.locator('html')).toHaveClass(/dark/)
  await expect(page.getByRole('navigation', { name: 'Primary' })).toContainText('Shop')
})
```

- [ ] **Step 3: Test reduced motion and 200% zoom**

Emulate reduced motion and assert route transition duration is effectively disabled. Set browser zoom to 200% through CSS/device emulation and assert no page-level horizontal overflow at the target viewport.

- [ ] **Step 4: Test keyboard-only workflows**

Tab through consumer header, auth form, admin drawer, table actions, tabs, dialog, and confirmation. Verify focus is visible, Escape closes overlays, and focus returns to the opener.

- [ ] **Step 5: Run accessibility and preference suites**

Run:

```powershell
npm run test:a11y
npx playwright test tests/theme-and-locale.spec.ts --project=chromium
```

Expected: all pass with no proxy errors.

- [ ] **Step 6: Commit accessibility coverage**

```powershell
git add frontend/tests/a11y.spec.ts frontend/tests/theme-and-locale.spec.ts frontend/scripts/a11y.mjs frontend/playwright.config.ts
git commit -m "test(ui): verify accessibility and preferences"
```

---

### Task 5: Run Performance and Bundle Gates

**Files:**
- Modify: `frontend/scripts/lighthouse.mjs`
- Modify: `frontend/package.json`

**Interfaces:**
- Produces: explicit Lighthouse thresholds and build-size evidence.

- [ ] **Step 1: Set explicit thresholds**

Require representative `/shop` and `/login` runs to meet:

```js
const thresholds = {
  performance: 0.85,
  accessibility: 0.95,
  bestPractices: 0.95,
  seo: 0.9,
}
```

Also fail on CLS greater than 0.1, LCP greater than 2.5 seconds, or total blocking time greater than 300ms in the controlled local run.

- [ ] **Step 2: Report route chunk sizes**

After `vite build`, parse the manifest/stats output and print consumer entry, admin lazy chunks, CSS total, and font assets. Fail if admin route modules are included in the initial consumer chunk or if any single uncompressed lazy route chunk exceeds 250KB without an allowlist explanation.

- [ ] **Step 3: Run build and Lighthouse**

Run:

```powershell
npm run build
npm run test:lighthouse
```

Expected: build and all thresholds pass.

- [ ] **Step 4: Commit performance gates**

```powershell
git add frontend/scripts/lighthouse.mjs frontend/package.json
git commit -m "test(ui): enforce performance budgets"
```

---

### Task 6: Verify Live Local Integration

**Files:**
- Modify: `README.md`
- Reuse: repository local runtime scripts and Docker/local-service configuration.

**Interfaces:**
- Produces: reproducible local launch commands and live UI evidence against the actual backend.

- [ ] **Step 1: Confirm required local services**

Use the repository's current local deployment configuration to verify backend, database, Redis, and any configured dependent services are healthy. Do not mutate production or staging infrastructure.

- [ ] **Step 2: Start backend and frontend locally**

Use the documented backend command and:

```powershell
npm run dev -- --host 127.0.0.1 --port 5173
```

Use hidden background processes and record process IDs so they can be stopped after verification.

- [ ] **Step 3: Run live critical-path smoke**

With a valid local account, verify login, shop load, search, product detail, cart, checkout preview, orders, profile, and one admin read-only page. Do not submit irreversible payment/refund/tenant actions during live smoke.

- [ ] **Step 4: Inspect live requests and console**

Require no unexpected 4xx/5xx, no raw backend errors, no broken image request, and no console error/warning. Capture desktop and mobile screenshots for login, shop, and admin.

- [ ] **Step 5: Stop only processes started by this task**

Verify process IDs and stop those exact backend/frontend processes. Leave unrelated local services unchanged unless the user explicitly requests shutdown.

- [ ] **Step 6: Document the verified launch path**

Update `README.md` with local frontend URL, service prerequisites, exact commands, test account provisioning reference, and troubleshooting for occupied ports.

- [ ] **Step 7: Commit local verification docs**

```powershell
git add README.md
git commit -m "docs(ui): document live local verification"
```

---

### Task 7: Produce the Requirement-by-Requirement Completion Audit

**Files:**
- Create: `docs/reviews/2026-07-12-ui-completion-audit.md`

**Interfaces:**
- Produces: authoritative final evidence matrix tied to the approved design spec.

- [ ] **Step 1: Create the evidence matrix**

Use columns:

```markdown
| Requirement | Authoritative evidence | Result | Remaining risk |
| --- | --- | --- | --- |
```

Include every section from the design spec: shell, tokens, components, state, feedback, consumer routes, admin routes, responsive/a11y, performance, and completion definition.

- [ ] **Step 2: Run the full verification suite from a clean command state**

Run:

```powershell
npm run format
npm run test:ui-contract
npm run test:unit
npm run lint
npm run build
npm run test:a11y
npm run test:api-contract
npm run test:ui-smoke
npm run test:visual
npm run test:lighthouse
```

Expected: every command exits 0. `npm run format` is a check and must not rewrite files.

- [ ] **Step 3: Inspect current Git diff and artifacts**

Confirm no generated screenshot/report directory is staged, no unrelated user change was reverted, no debug logging remains, and no expected source file is untracked.

- [ ] **Step 4: Fill the audit only from current evidence**

Mark a requirement pass only when a file, command output, screenshot, or runtime flow directly proves it. Mark uncertain or missing evidence as incomplete and continue implementation instead of declaring completion.

- [ ] **Step 5: Commit the completion audit**

```powershell
git add docs/reviews/2026-07-12-ui-completion-audit.md
git commit -m "docs(ui): record completion evidence"
```

---

## Final Completion Gate

The goal may be marked complete only after all static, unit, build, Axe, 57-route smoke, visual/pixel, theme/locale, Lighthouse, and live-local checks pass in the current worktree; the completion audit has no incomplete requirement; all required UI routes are usable in target viewports; and no required work remains.

## Specification Coverage Matrix

| Design specification section | Implementation plan coverage |
| --- | --- |
| Goals and non-goals | Global constraints in all four plans |
| Users and scenarios | Consumer Tasks 1-7; Admin Tasks 1-8 |
| Route ownership and dual-mode shell | Foundation Tasks 6-7 |
| Visual system and tokens | Foundation Tasks 2-3 |
| Shared components and surfaces | Foundation Tasks 5 and 8 |
| Async state and performance | Foundation Task 4; Admin Tasks 1 and 6 |
| Notifications, errors, confirmation | Foundation Task 5; Consumer Task 7; Admin Task 8 |
| Consumer pages | Consumer Tasks 1-7 |
| Admin pages | Admin Tasks 1-8 |
| Responsive and accessibility | Foundation Task 6; Verification Tasks 2-4 |
| Performance and stability | Admin Tasks 1 and 6; Verification Tasks 2, 3, and 5 |
| Static/browser/live acceptance | Verification Tasks 1-7 |
| Completion definition | Verification Task 7 and Final Completion Gate |

Self-review found no uncovered design-spec section. If implementation changes an interface named in an earlier plan, update the dependent plan before executing that dependent task.
