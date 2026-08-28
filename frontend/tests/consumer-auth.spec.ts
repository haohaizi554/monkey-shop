import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'auth-test' }
}

async function installAuthMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown = null
    if (pathname === '/users/me') {
      data = { isLogin: false }
    } else if (pathname === '/auth/captcha/config') {
      data = { provider: 'local', siteKey: '' }
    } else if (pathname === '/tracking/events') {
      data = { id: 1, eventType: 'PAGE_VIEW' }
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(data)),
    })
  })
}

test.beforeEach(async ({ page }) => {
  await installAuthMocks(page)
})

test('login keeps rate limits inside the form and hides backend copy', async ({ page }) => {
  await page.route('**/api/v1/auth/login', async (route) => {
    await route.fulfill({
      status: 429,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        title: 'Too many requests',
        status: 429,
        code: 'RATE_LIMIT',
        retryAfterSeconds: 10,
      }),
    })
  })
  await page.goto('/login')
  const loginPanel = page.getByRole('tabpanel', { name: 'Sign in' })
  await loginPanel.getByLabel('Username').fill('admin')
  await loginPanel.getByLabel('Password').fill('bad-password')
  await loginPanel.getByRole('button', { name: 'Sign in', exact: true }).click()

  await expect(page.getByRole('alert')).toContainText(
    'Too many operations. Please wait a moment and try again.',
  )
  await expect(page.locator('.auth-workspace[data-surface="secure-checkpoint"]')).toBeVisible()
  await expect(page.locator('.auth-workspace h1')).toHaveCount(1)
  await expect(page.locator('.auth-workspace .mascot-state[data-pose="hourglass"]')).toBeVisible()
  await expect(page.locator('body')).not.toContainText('Too many requests')
  await expect(page.locator('.app-feedback-host')).toHaveCount(0)
  await expect(page.getByTestId('retry-countdown')).toContainText('10')
  await expect(loginPanel.getByRole('button', { name: 'Sign in', exact: true })).toBeDisabled()
  await expect(loginPanel.getByLabel('Username')).toBeEditable()
})

test('registration uses account, contact, and complete steps with inline 422 errors', async ({
  page,
}) => {
  await page.route('**/api/v1/auth/password-policy', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          minLength: 10,
          requireUppercase: true,
          requireLowercase: true,
          requireDigit: true,
          requireSpecial: true,
          forbidWhitespace: true,
        }),
      ),
    })
  })
  await page.route('**/api/v1/auth/register', async (route) => {
    await route.fulfill({
      status: 422,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        title: 'Validation failed',
        status: 422,
        code: 'VALIDATION_FAILED',
        fieldErrors: [{ field: 'username', code: 'Unique', message: 'Username is already in use' }],
      }),
    })
  })
  await page.goto('/login')
  await page.getByTestId('register-tab').click()

  await expect(page.getByTestId('register-account-step')).toBeVisible()
  await expect(page.getByTestId('register-contact-step')).toHaveCount(0)
  await expect(page.locator('.auth-workspace .mascot-state[data-pose="welcome"]')).toBeVisible()
  await page.getByTestId('register-username').fill('member')
  await page.getByTestId('register-password').fill('ValidPass!1')
  await page.getByTestId('register-next').click()
  await expect(page.getByTestId('register-contact-step')).toBeVisible()

  await page.getByTestId('register-phone').fill('13800138000')
  await page.getByTestId('register-captcha').fill('1234')
  await page.getByTestId('register-submit').click()

  await expect(page.locator('[data-field-error="username"]')).toContainText(
    'Username is already in use',
  )
  await expect(page.getByTestId('register-account-step')).toBeVisible()
  await expect(page.locator('.auth-workspace .mascot-state[data-pose="shield"]')).toBeVisible()
  await expect(page.locator('[data-testid="register-account-step"]:visible')).toHaveCount(1)
  await expect(page.locator('[data-testid="register-contact-step"]:visible')).toHaveCount(0)
})

test('password reset reveals challenge fields only after identity succeeds', async ({ page }) => {
  await page.goto('/login')
  await page.getByRole('tab', { name: 'Reset password' }).click()
  const resetPanel = page.getByRole('tabpanel', { name: 'Reset password' })

  await expect(resetPanel.getByLabel('One-time code')).toHaveCount(0)
  await expect(resetPanel.getByLabel('New password')).toHaveCount(0)
  await resetPanel.getByLabel('Username').fill('member')
  await resetPanel.getByLabel('Phone').fill('13800138000')
  await resetPanel.getByRole('button', { name: 'Request reset code' }).click()

  await expect(resetPanel.getByLabel('One-time code')).toBeVisible()
  await expect(resetPanel.getByLabel('New password')).toBeVisible()
  await expect(page.getByRole('status')).toContainText(
    'If the account matches, a reset challenge was sent',
  )
  await expect(page.locator('.auth-workspace .mascot-state[data-pose="shield"]')).toBeVisible()
  await expect(resetPanel.locator('.reset-stage:visible')).toHaveCount(1)
})

async function assertAuthViewport(
  page: Page,
  width: number,
  height: number,
  mode: 'login' | 'register' | 'reset' = 'login',
) {
  await page.setViewportSize({ width, height })
  await page.goto('/login')
  await expect(page.locator('.auth-workspace[data-surface="secure-checkpoint"]')).toBeVisible()
  if (mode !== 'login') {
    await page.getByTestId(`${mode}-tab`).click()
    await expect(page.locator(`[data-mode="${mode}"]`)).toBeVisible()
  }
  await page.evaluate(() => window.scrollTo(0, 0))

  const geometry = await page.evaluate(() => {
    const workspace = document.querySelector<HTMLElement>('.auth-workspace')
    const brand = document.querySelector<HTMLElement>('.auth-brand-region')
    const surface = document.querySelector<HTMLElement>('.auth-surface')
    const mascot = document.querySelector<HTMLElement>('.auth-brand-region img.mascot-state')
    const submit = document.querySelector<HTMLElement>('.auth-primary-action')
    const overflowOwners = [
      workspace,
      brand,
      surface,
      ...Array.from(workspace?.querySelectorAll('*') ?? []),
    ]
      .filter((element): element is HTMLElement => element instanceof HTMLElement)
      .filter((element) => element.scrollWidth > element.clientWidth + 1)
      .map((element) => `${element.tagName.toLowerCase()}.${element.className}`)

    return {
      documentOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      brandTop: brand?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
      surfaceTop: surface?.getBoundingClientRect().top ?? Number.NEGATIVE_INFINITY,
      mascotHeight: mascot?.getBoundingClientRect().height ?? Number.POSITIVE_INFINITY,
      submitBottom: submit?.getBoundingClientRect().bottom ?? Number.POSITIVE_INFINITY,
      submitHeight: submit?.getBoundingClientRect().height ?? 0,
      overflowOwners,
    }
  })

  expect(geometry.brandTop).toBeLessThan(geometry.surfaceTop)
  expect(geometry.mascotHeight).toBeLessThanOrEqual(height * 0.35)
  expect(geometry.submitBottom).toBeLessThanOrEqual(height)
  expect(geometry.submitHeight).toBeGreaterThanOrEqual(44)
  expect(geometry.documentOverflow).toBeLessThanOrEqual(1)
  expect(geometry.overflowOwners).toEqual([])

  const controls = page.locator(
    '.auth-workspace button:visible, .auth-workspace input:visible, .auth-workspace a:visible',
  )
  for (let index = 0; index < (await controls.count()); index += 1) {
    const control = controls.nth(index)
    await control.focus()
    const focusedGeometry = await control.evaluate((element) => {
      const rect = element.getBoundingClientRect()
      const centerX = Math.min(window.innerWidth - 1, Math.max(0, rect.left + rect.width / 2))
      const centerY = Math.min(window.innerHeight - 1, Math.max(0, rect.top + rect.height / 2))
      const hit = document.elementFromPoint(centerX, centerY)
      return {
        inViewport:
          rect.top >= -1 &&
          rect.left >= -1 &&
          rect.bottom <= window.innerHeight + 1 &&
          rect.right <= window.innerWidth + 1,
        uncovered: Boolean(
          hit && (hit === element || element.contains(hit) || hit.contains(element)),
        ),
      }
    })
    expect(focusedGeometry.inViewport).toBe(true)
    expect(focusedGeometry.uncovered).toBe(true)
  }
}

test('mobile auth keeps the secure checkpoint touch-safe at 390px and 320px', async ({ page }) => {
  for (const viewport of [
    { width: 390, height: 844 },
    { width: 320, height: 800 },
  ]) {
    for (const mode of ['login', 'register', 'reset'] as const) {
      await assertAuthViewport(page, viewport.width, viewport.height, mode)
    }
  }

  await page.goto('/login')
  await page.getByTestId('register-tab').click()
  await page.getByTestId('register-username').fill('member')
  await page.getByTestId('register-password').fill('ValidPass!1')
  await page.getByTestId('register-next').click()
  await expect(page.getByTestId('register-contact-step')).toBeVisible()
  await expect(page.locator('.auth-back-button')).toHaveCSS('min-height', '44px')
  await expect(page.locator('.auth-back-button')).toHaveJSProperty('offsetHeight', 44)
})

test('auth mode tabs support roving ArrowLeft/ArrowRight/Home/End keyboard navigation', async ({
  page,
}) => {
  await page.goto('/login')
  const tabs = page.getByRole('tab')
  await expect(tabs).toHaveCount(3)
  await tabs.nth(0).focus()

  await page.keyboard.press('ArrowRight')
  await expect(tabs.nth(1)).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('[role="tabpanel"]:visible')).toHaveCount(1)

  await page.keyboard.press('End')
  await expect(tabs.nth(2)).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('[role="tabpanel"]:visible')).toHaveCount(1)

  await page.keyboard.press('Home')
  await expect(tabs.nth(0)).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('ArrowLeft')
  await expect(tabs.nth(2)).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('[role="tabpanel"]:visible')).toHaveCount(1)
})

test('verification retries with a fresh provider script after the first load fails', async ({
  page,
}) => {
  let scriptRequests = 0

  await page.route('**/api/v1/auth/captcha/config', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok({ provider: 'turnstile', siteKey: 'test-site-key' })),
    })
  })
  await page.route('https://challenges.cloudflare.com/turnstile/v0/api.js**', async (route) => {
    scriptRequests += 1
    if (scriptRequests === 1) {
      await route.abort('failed')
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/javascript',
      body: `window.turnstile = {
        render(element, options) {
          element.textContent = 'Verification ready'
          options.callback('verified-token')
          return 'test-widget'
        },
        remove() {}
      }`,
    })
  })

  await page.goto('/login')
  const loginPanel = page.getByRole('tabpanel', { name: 'Sign in' })
  await expect(loginPanel.locator('.turnstile-error')).toBeVisible()
  await loginPanel.getByRole('button', { name: /Retry/i }).click()

  await expect(loginPanel.locator('.turnstile-widget')).toContainText('Verification ready')
  expect(scriptRequests).toBe(2)
})
