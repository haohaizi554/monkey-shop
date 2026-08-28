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

async function findVueFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = await Promise.all(
    entries.map(async (entry) => {
      const entryPath = resolve(directory, entry.name)
      if (entry.isDirectory()) {
        return findVueFiles(entryPath)
      }
      return entry.name.endsWith('.vue') ? [entryPath] : []
    }),
  )
  return files.flat()
}

async function expectSingleShell(page: Page, area: 'consumer' | 'admin' | 'auth') {
  await expect(page.locator('.app-shell')).toHaveCount(1)
  await expect(page.locator('.app-main')).toHaveCount(1)
  await expect(page.locator('.app-shell .app-shell')).toHaveCount(0)
  await expect(page.locator('.app-shell')).toHaveAttribute('data-area', area)
}

test('route views never own the application shell', async () => {
  const viewsDirectory = resolve(process.cwd(), 'src/views')
  const viewFiles = await findVueFiles(viewsDirectory)

  for (const viewFile of viewFiles) {
    const source = await readFile(viewFile, 'utf8')
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
  const consumerHeaderHeight = await page
    .locator('.consumer-header')
    .evaluate((header) => header.getBoundingClientRect().height)
  expect(consumerHeaderHeight).toBeGreaterThanOrEqual(64)
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
  const adminSidebarWidth = await page
    .locator('.admin-sidebar')
    .evaluate((sidebar) => sidebar.getBoundingClientRect().width)
  const adminTopbarHeight = await page
    .locator('.admin-topbar')
    .evaluate((topbar) => topbar.getBoundingClientRect().height)
  expect(adminSidebarWidth).toBe(248)
  expect(adminTopbarHeight).toBeGreaterThanOrEqual(64)
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

  const consumerControlHeights = await page
    .locator(
      '.consumer-header__search-shortcut, .consumer-header__cart-shortcut, .consumer-header .language-button, .consumer-bottom-nav a',
    )
    .evaluateAll((controls) => controls.map((control) => control.getBoundingClientRect().height))
  expect(consumerControlHeights.length).toBeGreaterThan(0)
  expect(Math.min(...consumerControlHeights)).toBeGreaterThanOrEqual(44)

  const bottomNavGeometry = await page.locator('.consumer-bottom-nav').evaluate((nav) => {
    const computed = getComputedStyle(nav)
    return {
      minHeight: Number.parseFloat(computed.minHeight),
      paddingBottom: Number.parseFloat(computed.paddingBottom),
      safeAreaRulePresent: Array.from(document.styleSheets).some((sheet) => {
        try {
          return Array.from(sheet.cssRules).some((rule) =>
            rule.cssText.includes('safe-area-inset-bottom'),
          )
        } catch {
          return false
        }
      }),
    }
  })
  expect(bottomNavGeometry.minHeight).toBeGreaterThanOrEqual(64)
  expect(bottomNavGeometry.paddingBottom).toBeGreaterThanOrEqual(0)
  expect(bottomNavGeometry.safeAreaRulePresent).toBe(true)
})

test('consumer chrome stays inside a 320px signed-in viewport', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await installShellMocks(page, { isLogin: true, identity: 'USER', username: 'member' })
  await page.goto('/shop')

  const geometry = await page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth
    const shell = document.querySelector<HTMLElement>('.app-shell')
    const main = document.querySelector<HTMLElement>('.app-main')
    const header = document.querySelector<HTMLElement>('.consumer-header')
    const rect = (element: HTMLElement | null) => {
      if (!element) return null
      const bounds = element.getBoundingClientRect()
      return {
        left: bounds.left,
        right: bounds.right,
        width: bounds.width,
        scrollWidth: element.scrollWidth,
        clientWidth: element.clientWidth,
      }
    }
    return {
      viewportWidth,
      documentOverflow: document.documentElement.scrollWidth - viewportWidth,
      shell: rect(shell),
      main: rect(main),
      header: rect(header),
    }
  })

  expect(geometry.documentOverflow).toBeLessThanOrEqual(1)
  for (const region of [geometry.shell, geometry.main, geometry.header]) {
    expect(region).not.toBeNull()
    expect(region?.left).toBeGreaterThanOrEqual(-1)
    expect(region?.right).toBeLessThanOrEqual(geometry.viewportWidth + 1)
    expect(region?.width).toBeLessThanOrEqual(geometry.viewportWidth + 1)
    expect(region?.scrollWidth).toBeLessThanOrEqual((region?.clientWidth ?? 0) + 1)
  }
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

test('inline notice actions retain the minimum mobile hit target', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installShellMocks(page, { isLogin: false })
  await page.goto('/shop')
  await page.locator('.app-main').evaluate((main) => {
    main.insertAdjacentHTML(
      'afterbegin',
      `<aside class="inline-notice" data-surface="inline-notice" data-tone="warning" role="alert">
        <div class="inline-notice__content">
          <div class="inline-notice__retry"><button type="button">Retry</button></div>
        </div>
        <button class="inline-notice__dismiss" type="button" aria-label="Dismiss">Dismiss</button>
      </aside>`,
    )
  })

  const noticeActionHeights = await page
    .locator(
      '[data-surface="inline-notice"] :is(.inline-notice__retry button, .inline-notice__dismiss)',
    )
    .evaluateAll((controls) => controls.map((control) => control.getBoundingClientRect().height))
  expect(noticeActionHeights).toHaveLength(2)
  expect(Math.min(...noticeActionHeights)).toBeGreaterThanOrEqual(44)
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

test('admin mobile drawer and backdrop clear the topbar at 900px and 760px', async ({ page }) => {
  await installShellMocks(page, { isLogin: true, identity: 'ADMIN', username: 'admin' })

  for (const width of [900, 760]) {
    await page.setViewportSize({ width, height: 844 })
    await page.goto('/admin')

    const sidebar = page.locator('.admin-sidebar')
    const navigationTrigger = page.getByRole('button', { name: 'Open navigation', exact: true })
    await navigationTrigger.click()
    await expect(sidebar).toBeVisible()
    await expect(sidebar.getByRole('link').first()).toBeFocused()

    const layers = await page.evaluate(() => {
      const zIndex = (selector: string) => {
        const element = document.querySelector<HTMLElement>(selector)
        return element ? Number.parseInt(getComputedStyle(element).zIndex, 10) : 0
      }
      return {
        sidebar: zIndex('.admin-sidebar'),
        backdrop: zIndex('.admin-sidebar-backdrop'),
        topbar: zIndex('.admin-topbar'),
      }
    })

    expect(layers.sidebar).toBeGreaterThan(layers.topbar)
    expect(layers.backdrop).toBeGreaterThan(layers.topbar)

    await page.keyboard.press('Shift+Tab')
    await expect(sidebar.getByRole('link').last()).toBeFocused()

    const controlGeometry = await page.evaluate(() => {
      const selectors = [
        '.admin-topbar__menu',
        '.admin-command-trigger',
        '.admin-account',
        '.admin-nav-group a',
      ]
      return selectors.flatMap((selector) =>
        Array.from(document.querySelectorAll<HTMLElement>(selector)).map(
          (element) => element.getBoundingClientRect().height,
        ),
      )
    })
    expect(controlGeometry.length).toBeGreaterThan(0)
    expect(Math.min(...controlGeometry)).toBeGreaterThanOrEqual(44)

    await page.locator('.admin-sidebar-backdrop').click({ position: { x: width - 8, y: 8 } })
    await expect(sidebar).toBeHidden()
    await expect(navigationTrigger).toHaveAttribute('aria-expanded', 'false')
    await expect(navigationTrigger).toBeFocused()
  }
})

test('consumer header aligns with the content track at 1440px and 1920px', async ({ page }) => {
  await installShellMocks(page, { isLogin: false })

  for (const width of [1440, 1920]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/shop')

    const edges = await page.evaluate(() => {
      const header = document.querySelector<HTMLElement>('.consumer-header')
      const brand = document.querySelector<HTMLElement>('.consumer-header .brand')
      const main = document.querySelector<HTMLElement>('.app-main')
      if (!header || !brand || !main) return null
      const mainBounds = main.getBoundingClientRect()
      const headerBounds = header.getBoundingClientRect()
      const brandBounds = brand.getBoundingClientRect()
      const mainStyle = getComputedStyle(main)
      return {
        viewportWidth: document.documentElement.clientWidth,
        headerLeft: headerBounds.left,
        headerRight: headerBounds.right,
        brandLeft: brandBounds.left,
        mainInnerLeft: mainBounds.left + Number.parseFloat(mainStyle.paddingLeft),
      }
    })

    expect(edges).not.toBeNull()
    expect(Math.abs((edges?.brandLeft ?? 0) - (edges?.mainInnerLeft ?? 0))).toBeLessThanOrEqual(1)
    expect(Math.abs((edges?.headerRight ?? 0) - edges!.viewportWidth)).toBeLessThanOrEqual(1)
    expect(Math.abs(edges?.headerLeft ?? 0)).toBeLessThanOrEqual(1)
  }
})

test('desktop consumer shell exposes the complete commerce navigation', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await installShellMocks(page, { isLogin: true, identity: 'USER', username: 'member' })
  await page.goto('/shop')

  const navigation = page.getByRole('navigation', { name: 'Primary' })
  await expect(navigation.getByRole('link', { name: 'Discover', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  )
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
