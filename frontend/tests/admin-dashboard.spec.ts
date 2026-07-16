import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'dashboard-test' }
}

interface DashboardMockOptions {
  onDashboardRequest: () => void
  resumeResponseGate: Promise<void>
}

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

async function installDashboardMocks(page: Page, options: DashboardMockOptions) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })

  let dashboardRequest = 0
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    if (pathname === '/users/me') {
      data = { isLogin: true, identity: 'ADMIN', username: 'admin' }
    } else if (pathname === '/tracking/dashboard') {
      dashboardRequest += 1
      const requestNumber = dashboardRequest
      options.onDashboardRequest()
      if (dashboardRequest > 1) {
        await options.resumeResponseGate
      }
      data = {
        pageViews: requestNumber === 1 ? 128 : 144,
        uniqueVisitors: 42,
        orderCount: 9,
        paymentAmount: '8200.00',
        funnel: [
          { eventType: 'SEARCH', count: 30, conversionRate: '1' },
          { eventType: 'PAYMENT_SUCCESS', count: 9, conversionRate: '0.3' },
        ],
        generatedAt: '2026-07-12T08:00:00',
        refreshIntervalSeconds: 5,
      }
    } else if (pathname === '/tracking/profile/me') {
      data = {
        userId: 1,
        profileSummary: 'last=PAGE_VIEW,page=/dashboard,source=web',
        behaviorTags: ['event:page_view'],
        interestTags: [],
        lastEventAt: '2026-07-12T08:00:00',
        version: 1,
      }
    } else if (pathname === '/tracking/products/1') {
      data = {
        productId: 1,
        tagVector: ['popular'],
        salesCount: 12,
        reviewScore: '4.8',
        lastEventAt: '2026-07-12T08:00:00',
        version: 1,
      }
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

test('dashboard sleeps while hidden and preserves metrics during one resume refresh', async ({
  page,
}) => {
  let requests = 0
  const resumeResponse = deferred()
  await page.clock.install()
  await installDashboardMocks(page, {
    onDashboardRequest: () => {
      requests += 1
    },
    resumeResponseGate: resumeResponse.promise,
  })
  await page.goto('/dashboard')
  await expect(page.getByText('128', { exact: true })).toBeVisible()
  expect(requests).toBe(1)

  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await page.clock.fastForward(5200)
  expect(requests).toBe(1)

  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect.poll(() => requests).toBe(2)
  await expect(page.getByText('Updating')).toBeVisible()
  await expect(page.getByText('128', { exact: true })).toBeVisible()
  resumeResponse.resolve()
  await expect(page.getByText('144', { exact: true })).toBeVisible()
  expect(requests).toBe(2)

  await page.setViewportSize({ width: 1440, height: 900 })
  await page.screenshot({ path: 'output/task6-dashboard-desktop.png' })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'output/task6-dashboard-mobile.png' })
  await expect(page.locator('html')).not.toHaveJSProperty('scrollWidth', 0)
  expect(
    await page.locator('body').evaluate((body) => body.scrollWidth <= body.clientWidth + 1),
  ).toBe(true)
})

test('dashboard resume stays dormant while hidden, manual refresh remains available, and one timer resumes', async ({
  page,
}) => {
  let requests = 0
  await page.clock.install()
  await installDashboardMocks(page, {
    onDashboardRequest: () => {
      requests += 1
    },
    resumeResponseGate: Promise.resolve(),
  })
  await page.goto('/dashboard')
  await expect(page.getByText('128', { exact: true })).toBeVisible()
  expect(requests).toBe(1)

  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await page.getByRole('button', { name: 'Pause polling' }).click()
  await page.getByRole('button', { name: 'Resume polling' }).click()
  await page.clock.fastForward(15000)
  expect(requests).toBe(1)

  await page.locator('.page-header').getByRole('button', { name: 'Refresh now' }).click()
  await expect.poll(() => requests).toBe(2)

  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect.poll(() => requests).toBe(3)
  await page.clock.fastForward(5000)
  await expect.poll(() => requests).toBe(4)
  await page.clock.fastForward(5000)
  await expect.poll(() => requests).toBe(5)
})
