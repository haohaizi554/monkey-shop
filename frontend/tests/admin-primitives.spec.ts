import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'admin-primitives' }
}

async function installAdminMocks(page: Page, theme: 'light' | 'dark' = 'light') {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
  })
  await page.addInitScript((selectedTheme) => {
    localStorage.setItem('monkeyshop-theme', selectedTheme)
  }, theme)
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    if (pathname === '/users/me') {
      data = { isLogin: true, identity: 'ADMIN', username: 'admin' }
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
    } else if (pathname === '/orders/all') {
      data = {
        content: [
          {
            id: 11,
            orderNo: 'ORDER-11',
            buyerName: 'Admin',
            productName: 'Golden Monkey',
            price: '12.00',
            status: 'PAID',
            createTime: '2026-08-28T08:00:00',
            lines: [],
          },
        ],
        page: 0,
        size: 25,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
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

test('admin toolbar and metrics remain dense and bounded at 390px', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installAdminMocks(page)
  await page.goto('/admin')
  await expect(page.locator('.admin-page-toolbar')).toBeVisible()
  await expect(page.locator('[data-metric-key="orders"]')).toBeVisible()
  await expect(page.locator('.admin-page-toolbar')).toHaveAttribute('data-density', 'compact')
  await expect(page.locator('.metric-strip')).toHaveAttribute('data-surface', 'signal-strip')

  const geometry = await page.evaluate(() => {
    const toolbar = document.querySelector<HTMLElement>('.admin-page-toolbar')
    const search = document.querySelector<HTMLElement>('.admin-page-toolbar__search .el-input')
    const action = document.querySelector<HTMLElement>('.page-header__actions button')
    const metric = document.querySelector<HTMLElement>('.metric-strip__item strong')
    const metricStrip = document.querySelector<HTMLElement>('.metric-strip')
    const sidebar = document.querySelector<HTMLElement>('.admin-sidebar')
    const canvas = document.querySelector<HTMLElement>('.app-shell')
    return {
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      toolbarShadow: toolbar ? getComputedStyle(toolbar).boxShadow : '',
      searchWidth: search?.getBoundingClientRect().width ?? 0,
      toolbarWidth: toolbar?.getBoundingClientRect().width ?? 0,
      actionHeight: action?.getBoundingClientRect().height ?? 0,
      metricNumerals: metric ? getComputedStyle(metric).fontVariantNumeric : '',
      metricRadius: metricStrip ? Number.parseFloat(getComputedStyle(metricStrip).borderRadius) : 0,
      metricBackground: metricStrip ? getComputedStyle(metricStrip).backgroundColor : '',
      sidebarBackground: sidebar ? getComputedStyle(sidebar).backgroundColor : '',
      canvasBackground: canvas ? getComputedStyle(canvas).backgroundColor : '',
      realAdminMounted: Boolean(document.querySelector('.admin-page')),
    }
  })

  expect(geometry.realAdminMounted).toBe(true)
  expect(geometry.pageOverflow).toBeLessThanOrEqual(1)
  expect(geometry.toolbarShadow).toBe('none')
  expect(geometry.searchWidth).toBeLessThanOrEqual(geometry.toolbarWidth)
  expect(Math.round(geometry.actionHeight)).toBeGreaterThanOrEqual(44)
  expect(geometry.metricNumerals).toContain('tabular-nums')
  expect(geometry.metricRadius).toBeGreaterThan(0)
  expect(geometry.metricRadius).toBeLessThanOrEqual(8)
  expect(geometry.metricBackground).not.toBe('rgba(0, 0, 0, 0)')
  expect(geometry.sidebarBackground).not.toBe(geometry.canvasBackground)
})

test('an optional-slot toolbar does not reserve empty search or action tracks', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await installAdminMocks(page)
  await page.goto('/admin')

  const toolbar = page.locator('.admin-page-toolbar')
  await expect(toolbar).toBeVisible()
  await toolbar.evaluate((element) => {
    element.querySelector('.admin-page-toolbar__search')?.remove()
    element.querySelector('.admin-page-toolbar__actions')?.remove()

    if (!element.querySelector('.admin-page-toolbar__filters')) {
      const filters = document.createElement('div')
      filters.className = 'admin-page-toolbar__filters'
      filters.textContent = 'Filters'
      element.append(filters)
    }
  })

  const geometry = await toolbar.evaluate((element) => {
    const toolbarRect = element.getBoundingClientRect()
    const filtersRect = element
      .querySelector<HTMLElement>('.admin-page-toolbar__filters')
      ?.getBoundingClientRect()
    return {
      leadingGap: filtersRect ? filtersRect.left - toolbarRect.left : Number.POSITIVE_INFINITY,
      trailingGap: filtersRect ? toolbarRect.right - filtersRect.right : Number.POSITIVE_INFINITY,
    }
  })

  expect(geometry.leadingGap).toBeLessThanOrEqual(20)
  expect(geometry.trailingGap).toBeLessThanOrEqual(20)
})

test('commerce navigation keeps current semantics and local scroll owners at 390px', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installAdminMocks(page)
  await page.goto('/admin/orders')

  const navigation = page.locator('.admin-commerce-nav')
  const activeTab = navigation.locator('.el-tabs__item.is-active')
  const tableScroller = page.locator('.data-table-shell__scroller').first()

  await expect(navigation).toBeVisible()
  await expect(navigation).toHaveAttribute('data-surface', 'commerce-navigation')
  await expect(navigation).toHaveAttribute('aria-label', 'Commerce operations')
  await expect(navigation).toHaveAttribute('tabindex', '0')
  await expect(activeTab).toHaveAttribute('aria-selected', 'true')
  await expect(navigation.locator('[aria-current="page"]')).toHaveText('Orders')
  await expect(tableScroller).toHaveAttribute('data-surface', 'data-table-scroll')
  await expect(tableScroller).toHaveAttribute('role', 'region')
  await expect(tableScroller).toHaveAttribute('tabindex', '0')

  const geometry = await page.evaluate(() => {
    const navigation = document.querySelector<HTMLElement>('.admin-commerce-nav')
    const tableScroller = document.querySelector<HTMLElement>('.data-table-shell__scroller')
    const appMain = document.querySelector<HTMLElement>('.app-main')
    return {
      navigationOverflow: navigation ? getComputedStyle(navigation).overflowX : '',
      tableOverflow: tableScroller ? getComputedStyle(tableScroller).overflowX : '',
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      appMainOverflow: appMain ? getComputedStyle(appMain).overflowX : '',
    }
  })

  expect(geometry.navigationOverflow).toBe('auto')
  expect(geometry.tableOverflow).toBe('auto')
  expect(geometry.pageOverflow).toBeLessThanOrEqual(1)
  expect(geometry.appMainOverflow).not.toBe('auto')

  await navigation.getByRole('tab', { name: 'Payments', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/payments$/)
})

test('admin controls retain touch geometry at 390px and 320px', async ({ page }) => {
  await installAdminMocks(page)

  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 })
    await page.goto('/admin')
    await expect(page.locator('.admin-page-toolbar')).toHaveAttribute('data-density', 'compact')

    const controls = await page.evaluate(() => {
      return Array.from(
        document.querySelectorAll<HTMLElement>(
          '.app-shell[data-area="admin"] :is(button, a, input, select, textarea, [role="combobox"])',
        ),
      )
        .filter((element) => {
          const style = getComputedStyle(element)
          const rect = element.getBoundingClientRect()
          return (
            style.display !== 'none' &&
            style.visibility !== 'hidden' &&
            rect.width > 0 &&
            rect.height > 0
          )
        })
        .map((element) => ({
          name:
            element.getAttribute('aria-label') || element.textContent?.trim() || element.tagName,
          height: element.getBoundingClientRect().height,
        }))
    })

    expect(controls.length).toBeGreaterThan(0)
    expect(Math.min(...controls.map((control) => control.height))).toBeGreaterThanOrEqual(44)
  }
})

test('admin observatory surfaces stay dark-mode and reduced-motion safe', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await installAdminMocks(page, 'dark')
  await page.goto('/admin')

  await expect(page.locator('.admin-page-toolbar')).toHaveAttribute('data-density', 'compact')
  await expect(page.locator('.metric-strip')).toHaveAttribute('data-surface', 'signal-strip')

  const state = await page.evaluate(() => {
    const toolbar = document.querySelector<HTMLElement>('.admin-page-toolbar')
    const metrics = document.querySelector<HTMLElement>('.metric-strip')
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    return {
      dark: document.documentElement.classList.contains('dark'),
      reducedMotion,
      toolbarBackground: toolbar ? getComputedStyle(toolbar).backgroundColor : '',
      metricsBackground: metrics ? getComputedStyle(metrics).backgroundColor : '',
      toolbarTransition: toolbar ? getComputedStyle(toolbar).transitionDuration : '',
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    }
  })

  expect(state.dark).toBe(true)
  expect(state.reducedMotion).toBe(true)
  expect(state.toolbarBackground).not.toBe('rgba(0, 0, 0, 0)')
  expect(state.metricsBackground).not.toBe('rgba(0, 0, 0, 0)')
  expect(Number.parseFloat(state.toolbarTransition)).toBeLessThanOrEqual(1)
  expect(state.pageOverflow).toBeLessThanOrEqual(1)

  await page.goto('/admin/orders')
  await expect(page.locator('.admin-commerce-nav')).toHaveAttribute(
    'data-surface',
    'commerce-navigation',
  )
  const navigationTransition = await page
    .locator('.admin-commerce-nav')
    .evaluate((element) => getComputedStyle(element).transitionDuration)
  expect(Number.parseFloat(navigationTransition)).toBeLessThanOrEqual(1)
})
