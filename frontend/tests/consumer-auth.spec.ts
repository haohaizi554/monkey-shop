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
})

test('mobile auth keeps the brand mascot and form surface touch-safe without overflow', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/login')

  const brand = await page.locator('.auth-brand-region').boundingBox()
  const surface = await page.locator('.auth-surface').boundingBox()
  const submit = await page.getByRole('button', { name: 'Sign in', exact: true }).boundingBox()
  const mascot = await page.locator('.auth-brand-region img.mascot-state').boundingBox()
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )

  expect(brand).not.toBeNull()
  expect(surface).not.toBeNull()
  expect((brand?.y ?? 1) < (surface?.y ?? 0)).toBe(true)
  expect(mascot?.height).toBeLessThanOrEqual(144)
  expect(submit?.height).toBeGreaterThanOrEqual(44)
  expect(overflow).toBeLessThanOrEqual(1)
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
