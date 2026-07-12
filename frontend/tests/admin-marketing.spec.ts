import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'marketing-test' }
}

async function installMarketingMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    if (pathname === '/users/me') {
      data = { isLogin: true, identity: 'ADMIN', username: 'admin' }
    } else if (pathname === '/marketing/coupons/claim') {
      await new Promise((resolve) => setTimeout(resolve, 450))
      data = {
        id: 1,
        couponId: 2400000000001,
        couponCode: 'PLATFORM-20',
        userId: 1,
        status: 'CLAIMED',
        claimedAt: '2026-07-12T08:00:00',
      }
    } else if (pathname === '/marketing/price/quote') {
      data = {
        originalAmount: '128.00',
        discountAmount: '20.00',
        payableAmount: '108.00',
        appliedCoupons: ['PLATFORM-20'],
      }
    } else if (pathname === '/marketing/seckill-orders') {
      data = {
        id: 88,
        activityId: 2500000000001,
        skuId: 7,
        userId: 1,
        quantity: 1,
        idempotencyKey: 'flash-test',
        createdAt: '2026-07-12T08:00:00',
      }
    } else if (pathname === '/marketing/group-buy/join') {
      data = {
        id: 99,
        activityId: 2600000000001,
        skuId: 7,
        leaderUserId: 1,
        targetSize: 3,
        joinedCount: 2,
        status: 'OPEN',
        expiresAt: '2026-07-12T09:00:00',
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

test('marketing tasks keep independent pending and result surfaces', async ({ page }) => {
  await installMarketingMocks(page)
  await page.goto('/marketing')

  await page.getByRole('button', { name: 'Claim', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Claim', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Quote', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Submit seckill' })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Join group buy' })).toBeEnabled()

  await page.getByRole('button', { name: 'Quote', exact: true }).click()
  await expect(page.getByText(/Payable.*108/)).toBeVisible()
  await expect(page.getByText('PLATFORM-20', { exact: true })).toBeVisible()
  await expect(page.getByText(/Claimed/)).toBeVisible()
  await expect(page.locator('body')).not.toContainText('锛')
})
