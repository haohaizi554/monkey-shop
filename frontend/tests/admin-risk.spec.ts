import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'risk-test' }
}

async function installRiskMocks(page: Page) {
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
    } else if (pathname === '/risk/reviews' && request.method() === 'GET') {
      data = [
        {
          id: 101,
          userId: 7,
          productId: 9,
          type: 'PRICE_ANOMALY',
          score: 88,
          status: 'PENDING',
          detail: 'price changed quickly',
          createdAt: '2026-07-12T08:00:00',
        },
        {
          id: 102,
          userId: 8,
          type: 'SELF_BUY',
          score: 30,
          status: 'APPROVED',
          detail: 'resolved',
          resolution: 'verified',
          createdAt: '2026-07-12T07:00:00',
        },
      ]
    } else if (pathname === '/risk/reviews/101/resolve') {
      await new Promise((resolve) => setTimeout(resolve, 250))
      const body = request.postDataJSON() as {
        status: string
        resolution: string
        totpCode: string
      }
      data = {
        id: 101,
        userId: 7,
        productId: 9,
        type: 'PRICE_ANOMALY',
        score: 88,
        status: body.status,
        detail: 'price changed quickly',
        resolution: body.resolution,
        createdAt: '2026-07-12T08:00:00',
        handledAt: '2026-07-12T08:05:00',
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

test('risk block decision enforces note, TOTP, confirmation, and row pending', async ({ page }) => {
  await installRiskMocks(page)
  await page.goto('/risk?status=PENDING&minScore=70')
  await expect(page.getByText('Price anomaly')).toBeVisible()
  await expect(page.getByText('Self buy')).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText('PRICE_ANOMALY')

  await page.getByRole('button', { name: 'Block case 101' }).click()
  await page.getByRole('button', { name: 'Save decision' }).click()
  await expect(page.getByText('Resolution note is required')).toBeVisible()
  await page.getByRole('textbox', { name: 'Resolution note' }).fill('confirmed abuse')
  await page.getByRole('button', { name: 'Save decision' }).click()
  await expect(page.getByText('Admin TOTP is required for blocking')).toBeVisible()
  await page.getByRole('textbox', { name: 'TOTP', exact: true }).fill('654321')
  await page.getByRole('button', { name: 'Save decision' }).click()
  await expect(page.getByRole('dialog', { name: 'Block risk case' })).toBeVisible()
  await page.getByRole('button', { name: 'Block', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save decision' })).toBeDisabled()
  await expect(page.locator('.el-drawer').getByText('Blocked', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(/status=PENDING/)
})
