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
  await page.locator('.app-main').evaluate((main) => {
    main.innerHTML = `
      <div class="route-view">
        <section class="admin-page-toolbar" aria-label="Product controls">
          <div class="admin-page-toolbar__search"><input type="search" /></div>
          <div class="admin-page-toolbar__filters"><select><option>All</option></select></div>
          <div class="admin-page-toolbar__actions"><button class="primary-button">Create</button></div>
        </section>
        <ul class="metric-strip">
          <li class="metric-strip__item"><span>Orders</span><strong>12,345</strong></li>
          <li class="metric-strip__item"><span>Risk cases</span><strong>7</strong></li>
        </ul>
      </div>`
  })

  const geometry = await page.evaluate(() => {
    const toolbar = document.querySelector<HTMLElement>('.admin-page-toolbar')
    const search = document.querySelector<HTMLElement>('.admin-page-toolbar__search input')
    const action = document.querySelector<HTMLElement>('.admin-page-toolbar__actions button')
    const metric = document.querySelector<HTMLElement>('.metric-strip__item strong')
    return {
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      toolbarShadow: toolbar ? getComputedStyle(toolbar).boxShadow : '',
      searchWidth: search?.getBoundingClientRect().width ?? 0,
      toolbarWidth: toolbar?.getBoundingClientRect().width ?? 0,
      actionHeight: action?.getBoundingClientRect().height ?? 0,
      metricNumerals: metric ? getComputedStyle(metric).fontVariantNumeric : '',
    }
  })

  expect(geometry.pageOverflow).toBeLessThanOrEqual(1)
  expect(geometry.toolbarShadow).toBe('none')
  expect(geometry.searchWidth).toBeLessThanOrEqual(geometry.toolbarWidth)
  expect(geometry.actionHeight).toBeGreaterThanOrEqual(44)
  expect(geometry.metricNumerals).toContain('tabular-nums')
})
