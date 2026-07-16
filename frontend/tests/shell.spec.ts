import { expect, test, type Page } from '@playwright/test'
import { readdir, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

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

test('route views never own the application shell', async () => {
  const viewsDirectory = resolve(process.cwd(), 'src/views')
  const viewFiles = (await readdir(viewsDirectory)).filter((file) => file.endsWith('.vue'))

  for (const viewFile of viewFiles) {
    const source = await readFile(resolve(viewsDirectory, viewFile), 'utf8')
    expect(source, viewFile).not.toContain('AppShell')
  }
})

test('the production error boundary never renders exception internals', async () => {
  const source = await readFile(
    resolve(process.cwd(), 'src/components/AppErrorBoundary.vue'),
    'utf8',
  )

  expect(source).not.toContain('error.stack')
  expect(source).not.toContain('error.message')
  expect(source).toContain('common.errorReference')
})

test('consumer routes own one consumer shell with unique home and discover names', async ({
  page,
}) => {
  await installShellMocks(page, { isLogin: false })
  await page.goto('/shop')

  await expectSingleShell(page, 'consumer')
  await expect(page.locator('.consumer-header')).toBeVisible()
  await expect(page.locator('.admin-sidebar')).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'MonkeyShop home', exact: true })).toBeVisible()
  await expect(
    page
      .getByRole('navigation', { name: 'Primary' })
      .getByRole('link', { name: 'Discover', exact: true }),
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

test('admin shell owns one content heading and searchable workspace navigation', async ({
  page,
}) => {
  await installShellMocks(page, { isLogin: true, identity: 'ADMIN', username: 'admin' })
  await page.goto('/admin')

  await expect(page.locator('h1')).toHaveCount(1)
  await expect(page.locator('.admin-topbar h1')).toHaveCount(0)

  await page.getByRole('button', { name: 'Search workspace', exact: true }).click()
  const commandDialog = page.getByRole('dialog', { name: 'Go to workspace' })
  await expect(commandDialog).toBeVisible()
  await commandDialog.getByRole('searchbox', { name: 'Search workspace' }).fill('risk')
  await expect(commandDialog.getByRole('link', { name: 'Risk review', exact: true })).toBeVisible()
  await commandDialog.getByRole('link', { name: 'Risk review', exact: true }).click()

  await expect(page).toHaveURL(/\/risk$/)
  await expect(commandDialog).toBeHidden()
})

test('consumer mobile routes expose bottom navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installShellMocks(page, { isLogin: true, identity: 'USER', username: 'member' })
  await page.goto('/shop')

  await expectSingleShell(page, 'consumer')
  await expect(page.locator('.consumer-bottom-nav')).toBeVisible()
  await expect(page.locator('.consumer-header .primary-nav')).toBeHidden()
})

test('consumer chrome stays inside a 320px signed-in viewport', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await installShellMocks(page, { isLogin: true, identity: 'USER', username: 'member' })
  await page.goto('/shop')

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
})

test('failed logout stays recoverable without replacing the shell', async ({ page }) => {
  await installShellMocks(page, { isLogin: true, identity: 'USER', username: 'member' })
  await page.route('**/api/v1/users/logout', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/problem+json',
      body: JSON.stringify({ title: 'Raw provider failure', status: 500 }),
    })
  })
  await page.goto('/shop')
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()

  await expect(page.locator('.app-feedback-item')).toContainText(
    'Could not sign out. Please try again.',
  )
  await expect(page.locator('.app-feedback-item')).not.toContainText('Raw provider failure')
  await expectSingleShell(page, 'consumer')
})

test('shared page and table surfaces contain mobile overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installShellMocks(page, { isLogin: false })
  await page.goto('/shop')
  await page.locator('.app-main').evaluate((main) => {
    main.innerHTML = `
      <div class="route-view">
        <header class="page-header">
          <div class="page-header__main">
            <h1>A deliberately long operational page heading</h1>
          </div>
        </header>
        <section class="data-table-shell">
          <div class="data-table-shell__scroller">
            <table style="width: 1200px"><tbody><tr><td>Wide table content</td></tr></tbody></table>
          </div>
        </section>
      </div>`
  })

  const geometry = await page.evaluate(() => {
    const title = document.querySelector<HTMLElement>('.page-header h1')
    const scroller = document.querySelector<HTMLElement>('.data-table-shell__scroller')
    return {
      titleSize: title ? Number.parseFloat(getComputedStyle(title).fontSize) : 0,
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      tableOverflow: scroller ? scroller.scrollWidth - scroller.clientWidth : 0,
    }
  })

  expect(geometry.titleSize).toBeLessThanOrEqual(32)
  expect(geometry.pageOverflow).toBeLessThanOrEqual(1)
  expect(geometry.tableOverflow).toBeGreaterThan(0)
})

test('admin mobile navigation opens, receives focus, and closes with Escape', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installShellMocks(page, { isLogin: true, identity: 'ADMIN', username: 'admin' })
  await page.goto('/admin')

  const sidebar = page.locator('.admin-sidebar')
  await expect(sidebar).toBeHidden()
  const navigationTrigger = page.getByRole('button', { name: 'Open navigation', exact: true })
  await navigationTrigger.click()
  await expect(navigationTrigger).toHaveAttribute('aria-expanded', 'true')
  await expect(sidebar).toBeVisible()
  await expect(sidebar.getByRole('link').first()).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(sidebar.getByRole('link').last()).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(sidebar).toBeHidden()
  await expect(navigationTrigger).toHaveAttribute('aria-expanded', 'false')
  await expect(navigationTrigger).toBeFocused()
})

test('desktop consumer shell exposes the complete commerce navigation', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await installShellMocks(page, { isLogin: true, identity: 'USER', username: 'member' })
  await page.goto('/shop')

  const navigation = page.getByRole('navigation', { name: 'Primary' })
  const labels = ['Discover', 'Categories', 'Search', 'Recommend', 'Orders', 'Cart', 'Membership']
  for (const label of labels) {
    await expect(navigation.getByRole('link', { name: label, exact: true })).toBeVisible()
  }
  await expect(page.locator('.consumer-bottom-nav')).toBeHidden()

  await navigation.getByRole('link', { name: 'Categories', exact: true }).click()
  await expect(page).toHaveURL(/\/search#category-filter$/)
  await expect(page.locator('#category-filter')).toBeVisible()
})

test('mobile consumer shell keeps five stable destinations and hides them in focused flows', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installShellMocks(page, { isLogin: true, identity: 'USER', username: 'member' })
  await page.goto('/shop')

  const navigation = page.getByRole('navigation', { name: 'Mobile primary' })
  await expect(navigation.getByRole('link')).toHaveCount(5)
  for (const label of ['Discover', 'Search', 'Cart', 'Orders', 'Me']) {
    await expect(navigation.getByRole('link', { name: label, exact: true })).toBeVisible()
  }

  await page.goto('/checkout')
  await expect(page.locator('.consumer-bottom-nav')).toHaveCount(0)
  await page.goto('/payment/42')
  await expect(page.locator('.consumer-bottom-nav')).toHaveCount(0)
})

test('skip navigation and route changes focus the single main landmark', async ({ page }) => {
  await installShellMocks(page, { isLogin: false })
  await page.goto('/shop')

  await expect(page.locator('main')).toHaveCount(1)
  await expect(page.locator('h1')).toHaveCount(1)
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await expect(page).toHaveTitle('Shop | MonkeyShop')

  const skipLink = page.getByRole('link', { name: 'Skip to content', exact: true })
  await skipLink.focus()
  await expect(skipLink).toBeVisible()
  await skipLink.click()
  await expect(page.locator('#main-content')).toBeFocused()

  await page.getByRole('link', { name: 'Search', exact: true }).first().click()
  await expect(page).toHaveURL(/\/search$/)
  await expect(page).toHaveTitle('Search | MonkeyShop')
  await expect(page.locator('#main-content')).toBeFocused()
})
