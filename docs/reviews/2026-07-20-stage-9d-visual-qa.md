# Stage 9D Visual QA

Date: 2026-07-20

## Scope

Stage 9D closes the visual quality gate for the rebuilt MonkeyShop UI. The
baseline covers the authentication workspace, every consumer route, every
admin workspace, core mobile commerce flows, and representative dark-theme
surfaces.

The visual suite is opt-in:

- `npm run test:visual` compares against committed baselines.
- `npm run test:visual:update` intentionally regenerates baselines.
- `npm run test:a11y` keeps visual cases skipped so platform-specific snapshots
  do not pollute the regular CI accessibility job.

## Coverage

- 31 committed Chromium/Windows PNG baselines, 3,701,976 bytes total.
- 14 consumer routes checked at a 390 x 844 viewport.
- 11 admin routes checked at a 390 x 844 viewport.
- Desktop baselines use 1440 x 900.
- Authentication, shop, cart, and checkout have explicit mobile baselines.
- Shop and admin store operations have explicit dark-theme baselines.
- Every captured viewport rejects horizontal document overflow.
- Visible viewport images must finish loading and have a non-zero natural
  width before a screenshot is accepted.
- The browser clock is fixed in the harness so date-based idempotency keys and
  operational timestamps cannot drift between runs.

## Defects Found And Fixed

1. The member operations medal icon rendered at an unbounded SVG size and
   consumed most of the page. It now has a token-backed 20 x 20 contract and a
   Playwright geometry assertion capped at 24 x 24.
2. Product images retained their HTML `height="480"` attribute after CSS
   constrained only their width. The shared image contract now sets
   `height: auto`; the admin table regression test requires a compact 32-56 px
   rendered height.
3. The login brand heading was 40 px and violated the project's 36 px heading
   ceiling at desktop and tablet widths. A `--text-4xl` token now sets the
   desktop heading to 36 px and the existing `--text-3xl` token sets mobile to
   30 px.
4. Marketing, payment, dashboard, and tenant screenshots contained
   `Date.now()`-derived values. Fixing the test clock removed false pixel
   drift without changing runtime behavior.
5. The route harness replaced all product images with a gray SVG. It now serves
   the real local product fixture, allowing image aspect ratio, crop, and table
   density defects to be detected.
6. Login-view cold compilation could exceed Playwright's default five-second
   locator wait. Trace evidence showed the session mock and route were correct;
   the harness now gives the asynchronous authentication view an explicit
   30-second readiness budget.

## Verification Evidence

| Gate                            | Result                                          |
| ------------------------------- | ----------------------------------------------- |
| `npm run test:visual`           | 7 passed; 31 baselines matched                  |
| `npm run test:a11y`             | 33 passed; 7 visual cases intentionally skipped |
| Admin geometry Playwright suite | 12 passed                                       |
| `npm run test:ui-smoke`         | 57 route/viewport checks passed                 |
| `npm run test:unit`             | 23 files, 119 tests passed                      |
| `npm run test:api-contract`     | 17 modules, 107 UI-consumed clients passed      |
| `npm run lint`                  | Passed                                          |
| `npm run typecheck`             | Passed                                          |
| `npm run build`                 | Passed; 1,391 modules transformed               |

## Residual Notes

- Baselines are named for `chromium-win32`. A Linux visual CI job should
  generate and commit Linux-specific baselines instead of reusing Windows
  pixels.
- Vite reports occasional Element Plus `ResizeObserver loop completed` messages
  during the long synthetic route sweep. They do not produce console
  assertions, layout overflow, screenshot drift, or test failures, but remain a
  harness-noise item to monitor.
- The production build reports upstream `@vueuse/core` pure-annotation warnings;
  the build still completes successfully.
