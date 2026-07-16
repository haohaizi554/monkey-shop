import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'admin-primitives' }
}

async function installAdminMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
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
