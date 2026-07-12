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
      body: JSON.stringify({ title: 'Too many requests', status: 429 }),
    })
  })
  await page.goto('/login')
  const loginPanel = page.getByRole('tabpanel', { name: 'Sign in' })
  await loginPanel.getByLabel('Username').fill('admin')
  await loginPanel.getByLabel('Password').fill('bad-password')
  await loginPanel.getByRole('button', { name: 'Sign in', exact: true }).click()

  await expect(page.locator('.auth-feedback')).toContainText(
    'Too many operations. Please wait a moment and try again.',
  )
  await expect(page.locator('body')).not.toContainText('Too many requests')
  await expect(page.locator('.app-feedback-host')).toHaveCount(0)
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
  await expect(page.locator('.auth-feedback')).toContainText(
    'If the account matches, a reset challenge was sent',
  )
})

test('mobile auth is form-first, touch-safe, and uses the bundled image', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/login')

  const panel = await page.locator('.auth-panel').boundingBox()
  const visual = await page.locator('.auth-visual').boundingBox()
  const submit = await page.getByRole('button', { name: 'Sign in', exact: true }).boundingBox()
  const imageSource = await page.locator('.auth-visual img').getAttribute('src')
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )

  expect(panel).not.toBeNull()
  expect(visual).not.toBeNull()
  expect((panel?.y ?? 1) < (visual?.y ?? 0)).toBe(true)
  expect(visual?.height).toBeLessThanOrEqual(180)
  expect(submit?.height).toBeGreaterThanOrEqual(44)
  expect(imageSource).not.toContain('/images/monkey.png')
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
