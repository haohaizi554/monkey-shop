import { expect, test, type Page } from '@playwright/test'

interface MockUser {
  isLogin: boolean
  identity?: 'USER' | 'ADMIN'
  username?: string
  passwordChangeRequired?: boolean
}

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'shell-test' }
}

async function installShellMocks(page: Page, user: MockUser) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    if (pathname === '/users/me') {
      data = user
    } else if (pathname === '/auth/captcha/config') {
      data = { provider: 'local', siteKey: '' }
    } else if (pathname === '/stats/data') {
      data = {
        totalGmv: '0.00',
        totalOrders: 0,
        totalVisits: 0,
        returnRate: '0%',
        xAxis: [],
        seriesOrder: [],
        seriesGmv: [],
        seriesVisit: [],
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
  await page.route('**/images/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 3"><rect width="4" height="3" fill="#d8dee8"/></svg>',
    })
  })
}

async function expectSingleShell(page: Page, area: 'consumer' | 'admin' | 'auth') {
  await expect(page.locator('.app-shell')).toHaveCount(1)
  await expect(page.locator('.app-main')).toHaveCount(1)
  await expect(page.locator('.app-shell .app-shell')).toHaveCount(0)
  await expect(page.locator('.app-shell')).toHaveAttribute('data-area', area)
}

test('consumer routes own one consumer shell with unique home and shop names', async ({ page }) => {
  await installShellMocks(page, { isLogin: false })
  await page.goto('/shop')

  await expectSingleShell(page, 'consumer')
  await expect(page.locator('.consumer-header')).toBeVisible()
  await expect(page.locator('.admin-sidebar')).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'MonkeyShop home', exact: true })).toBeVisible()
  await expect(
    page
      .getByRole('navigation', { name: 'Primary' })
      .getByRole('link', { name: 'Shop', exact: true }),
  ).toBeVisible()
})

test('auth routes use compact auth chrome without consumer bottom navigation', async ({ page }) => {
  await installShellMocks(page, { isLogin: false })
  await page.goto('/login')

  await expectSingleShell(page, 'auth')
  await expect(page.locator('.consumer-header')).toHaveAttribute('data-compact', 'true')
  await expect(page.getByRole('navigation', { name: 'Primary' })).toHaveCount(0)
  await expect(page.locator('.consumer-bottom-nav')).toHaveCount(0)
})

test('admin routes replace consumer chrome with sidebar and topbar', async ({ page }) => {
  await installShellMocks(page, { isLogin: true, identity: 'ADMIN', username: 'admin' })
  await page.goto('/admin')

  await expectSingleShell(page, 'admin')
  await expect(page.locator('.admin-sidebar')).toBeVisible()
  await expect(page.locator('.admin-topbar')).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Primary' })).toHaveCount(0)
})

test('consumer mobile routes expose bottom navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installShellMocks(page, { isLogin: true, identity: 'USER', username: 'member' })
  await page.goto('/shop')

  await expectSingleShell(page, 'consumer')
  await expect(page.locator('.consumer-bottom-nav')).toBeVisible()
  await expect(page.locator('.consumer-header .primary-nav')).toBeHidden()
})

test('admin mobile navigation opens, receives focus, and closes with Escape', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installShellMocks(page, { isLogin: true, identity: 'ADMIN', username: 'admin' })
  await page.goto('/admin')

  const sidebar = page.locator('.admin-sidebar')
  await expect(sidebar).toBeHidden()
  await page.getByRole('button', { name: 'Open navigation', exact: true }).click()
  await expect(sidebar).toBeVisible()
  await expect(sidebar.getByRole('link').first()).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(sidebar).toBeHidden()
})
