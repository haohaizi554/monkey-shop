import { expect, test, type Page, type Route } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'admin-resilience-test' }
}

async function fulfill(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(ok(data)),
  })
}

async function fail(route: Route) {
  await route.fulfill({
    status: 500,
    contentType: 'application/problem+json',
    body: JSON.stringify({ title: 'Internal error', detail: 'private backend detail' }),
  })
}

async function useEnglishAdmin(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
}

const tenants = [
  {
    id: 1,
    code: 'alpha',
    name: 'Tenant Alpha',
    status: 'ACTIVE',
    plan: 'GROWTH',
    maskedContactPhone: '138****0001',
    createdAt: '2026-01-01T00:00:00',
    expiresAt: '2027-01-01T00:00:00',
    version: 1,
  },
  {
    id: 2,
    code: 'beta',
    name: 'Tenant Beta',
    status: 'TRIAL',
    plan: 'STARTER',
    maskedContactPhone: '138****0002',
    createdAt: '2026-02-01T00:00:00',
    expiresAt: '2027-02-01T00:00:00',
    version: 1,
  },
]

function tenantConfig(tenantId: number) {
  const prefix = tenantId === 1 ? 'alpha' : 'beta'
  return {
    id: tenantId * 10 + 1,
    tenantId,
    configType: 'PAYMENT',
    provider: `${prefix}-provider`,
    settings: { merchantId: `${prefix}-server` },
    enabled: true,
    updatedAt: '2026-07-12T08:00:00',
    version: 1,
  }
}

test('tenant config drafts stay isolated by tenant and save the active tenant snapshot', async ({
  page,
}) => {
  await useEnglishAdmin(page)
  let savedTenantId: number | undefined
  let savedPayload: Record<string, unknown> | undefined

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await fulfill(route, { isLogin: true, identity: 'ADMIN', username: 'admin' })
      return
    }
    if (pathname === '/tenants/dashboard') {
      await fulfill(route, {
        activeTenants: 1,
        expiredTenants: 0,
        currentMonthOrders: 18,
        currentMonthRevenue: '16800.00',
        tenants,
      })
      return
    }
    const configMatch = pathname.match(/^\/tenants\/(\d+)\/configs$/)
    if (configMatch) {
      const tenantId = Number(configMatch[1])
      if (request.method() === 'PUT') {
        savedTenantId = tenantId
        savedPayload = request.postDataJSON() as Record<string, unknown>
        await fulfill(route, {
          ...tenantConfig(tenantId),
          ...savedPayload,
          version: 2,
        })
      } else {
        await fulfill(route, [tenantConfig(tenantId)])
      }
      return
    }
    if (/^\/tenants\/\d+\/(bills|exports)$/.test(pathname)) {
      await fulfill(route, [])
      return
    }
    await fulfill(route, [])
  })

  await page.goto('/tenants?tenant=1', { waitUntil: 'domcontentloaded' })
  const provider = page.getByPlaceholder('Provider')
  const settings = page.getByLabel('Settings JSON')
  await expect(provider).toHaveValue('alpha-provider')
  await provider.fill('alpha-unsaved-provider')
  await settings.fill('{"merchantId":"alpha-draft"}')

  await page.getByRole('button', { name: 'Open Tenant Beta' }).click()
  await expect(provider).toHaveValue('beta-provider')
  await expect(settings).toHaveValue(/beta-server/)
  await page.getByRole('button', { name: 'Save config', exact: true }).click()

  await expect.poll(() => savedTenantId).toBe(2)
  expect(savedPayload).toMatchObject({
    configType: 'PAYMENT',
    provider: 'beta-provider',
    settings: { merchantId: 'beta-server' },
  })

  await page.getByRole('button', { name: 'Open Tenant Alpha' }).click()
  await expect(provider).toHaveValue('alpha-unsaved-provider')
  await expect(settings).toHaveValue(/alpha-draft/)
})

test('tenant dashboard keeps stale data visible with freshness and a working retry', async ({
  page,
}) => {
  await useEnglishAdmin(page)
  let dashboardRequests = 0

  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await fulfill(route, { isLogin: true, identity: 'ADMIN', username: 'admin' })
      return
    }
    if (pathname === '/tenants/dashboard') {
      dashboardRequests += 1
      if (dashboardRequests === 2) {
        await fail(route)
        return
      }
      await fulfill(route, {
        activeTenants: 1,
        expiredTenants: 0,
        currentMonthOrders: dashboardRequests === 1 ? 18 : 21,
        currentMonthRevenue: '16800.00',
        tenants: [tenants[0]],
      })
      return
    }
    if (/^\/tenants\/1\/(configs|bills|exports)$/.test(pathname)) {
      await fulfill(route, pathname.endsWith('/configs') ? [tenantConfig(1)] : [])
      return
    }
    await fulfill(route, [])
  })

  await page.goto('/tenants?tenant=1', { waitUntil: 'domcontentloaded' })
  await expect(page.getByText('18', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()

  await expect(page.getByRole('alert')).toContainText('The request failed')
  await expect(page.getByText('18', { exact: true })).toBeVisible()
  await expect(page.getByTestId('tenant-dashboard-last-success')).toContainText('Updated')
  await page.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(page.getByText('21', { exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('user and product profiles preserve stale content and expose independent retries', async ({
  page,
}) => {
  await useEnglishAdmin(page)
  let userRequests = 0
  let productRequests = 0
  let dashboardRequests = 0

  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await fulfill(route, { isLogin: true, identity: 'ADMIN', username: 'admin' })
      return
    }
    if (pathname === '/tracking/dashboard') {
      dashboardRequests += 1
      await fulfill(route, {
        pageViews: 128,
        uniqueVisitors: 42,
        orderCount: 9,
        paymentAmount: '8200.00',
        funnel: [],
        generatedAt: '2026-07-12T08:00:00',
        refreshIntervalSeconds: 5,
      })
      return
    }
    if (pathname === '/tracking/profile/me') {
      userRequests += 1
      if (userRequests === 2) {
        await fail(route)
        return
      }
      await fulfill(route, {
        userId: 1,
        profileSummary: userRequests === 1 ? 'last=PAGE_VIEW,page=/dashboard' : 'last=SEARCH',
        behaviorTags: [],
        interestTags: [],
        lastEventAt: '2026-07-12T08:00:00',
        version: userRequests,
      })
      return
    }
    if (pathname === '/tracking/products/1') {
      productRequests += 1
      if (productRequests === 2) {
        await fail(route)
        return
      }
      await fulfill(route, {
        productId: 1,
        tagVector: ['popular'],
        salesCount: productRequests === 1 ? 12 : 24,
        reviewScore: '4.8',
        lastEventAt: '2026-07-12T08:00:00',
        version: productRequests,
      })
      return
    }
    await fulfill(route, [])
  })

  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  const userSection = page.getByRole('region', { name: 'User profile' })
  const productSection = page.getByRole('region', { name: 'Product profile' })
  await expect(userSection).toContainText('Page view')
  await expect(productSection).toContainText('12')
  await expect(page.getByText('128', { exact: true })).toBeVisible()
  expect(dashboardRequests).toBe(1)
  await page.getByRole('button', { name: 'Pause polling' }).click()

  await userSection.getByRole('button', { name: 'Refresh now' }).click()
  await productSection.getByRole('button', { name: 'Refresh now' }).click()

  await expect(userSection.getByRole('alert')).toContainText('The request failed')
  await expect(productSection.getByRole('alert')).toContainText('The request failed')
  await expect(userSection).toContainText('Page view')
  await expect(productSection).toContainText('12')
  await expect(page.getByText('128', { exact: true })).toBeVisible()
  expect(dashboardRequests).toBe(1)
  await expect(userSection.getByTestId('user-profile-last-success')).toContainText('Updated')
  await expect(productSection.getByTestId('product-profile-last-success')).toContainText('Updated')

  await userSection.getByRole('button', { name: 'Retry', exact: true }).click()
  await productSection.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(userSection).toContainText('Search')
  await expect(productSection).toContainText('24')
  await expect(userSection.getByRole('alert')).toHaveCount(0)
  await expect(productSection.getByRole('alert')).toHaveCount(0)
})

test('dashboard keeps valid metrics through an error and exposes a localized retry recovery', async ({
  page,
}) => {
  await useEnglishAdmin(page)
  let dashboardRequests = 0

  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await fulfill(route, { isLogin: true, identity: 'ADMIN', username: 'admin' })
      return
    }
    if (pathname === '/tracking/dashboard') {
      dashboardRequests += 1
      if (dashboardRequests === 2) {
        await fail(route)
        return
      }
      await fulfill(route, {
        pageViews: dashboardRequests === 1 ? 128 : 144,
        uniqueVisitors: 42,
        orderCount: 9,
        paymentAmount: '8200.00',
        funnel: [{ eventType: 'SEARCH', count: 30, conversionRate: '1' }],
        generatedAt: '2026-07-12T08:00:00',
        refreshIntervalSeconds: 5,
      })
      return
    }
    if (pathname === '/tracking/profile/me') {
      await fulfill(route, {
        userId: 1,
        profileSummary: 'last=PAGE_VIEW',
        behaviorTags: [],
        interestTags: [],
        lastEventAt: '2026-07-12T08:00:00',
        version: 1,
      })
      return
    }
    if (pathname === '/tracking/products/1') {
      await fulfill(route, {
        productId: 1,
        tagVector: [],
        salesCount: 12,
        reviewScore: '4.8',
        lastEventAt: '2026-07-12T08:00:00',
        version: 1,
      })
      return
    }
    await fulfill(route, [])
  })

  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await expect(page.getByText('128', { exact: true })).toBeVisible()
  await page.locator('.page-header').getByRole('button', { name: 'Refresh now' }).click()
  await expect(page.getByRole('alert')).toContainText('The request failed')
  await expect(page.getByText('128', { exact: true })).toBeVisible()
  await expect(page.getByTestId('dashboard-last-success')).toContainText('Updated')
  await page.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(page.getByText('144', { exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('profile labels use known localizations and safe fallbacks for unknown tracking tokens', async ({
  page,
}) => {
  await useEnglishAdmin(page)

  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await fulfill(route, { isLogin: true, identity: 'ADMIN', username: 'admin' })
      return
    }
    if (pathname === '/tracking/dashboard') {
      await fulfill(route, {
        pageViews: 128,
        uniqueVisitors: 42,
        orderCount: 9,
        paymentAmount: '8200.00',
        funnel: [
          { eventType: 'SEARCH', count: 30, conversionRate: '1' },
          { eventType: 'INTERNAL_EVENT_TOKEN', count: 1, conversionRate: '0.1' },
        ],
        generatedAt: '2026-07-12T08:00:00',
        refreshIntervalSeconds: 5,
      })
      return
    }
    if (pathname === '/tracking/profile/me') {
      await fulfill(route, {
        userId: 1,
        profileSummary: 'last=INTERNAL_EVENT_TOKEN,previous=SEARCH,page=/dashboard,source=web',
        behaviorTags: ['event:INTERNAL_EVENT_TOKEN', 'page:/dashboard'],
        interestTags: [
          'source:internal_system',
          'INTERNAL_PROFILE_TOKEN',
          'page:/shop/42',
          'page:INTERNAL_PAGE_TOKEN',
          'page:/internal/operations',
        ],
        lastEventAt: '2026-07-12T08:00:00',
        version: 1,
      })
      return
    }
    if (pathname === '/tracking/products/1') {
      await fulfill(route, {
        productId: 1,
        tagVector: ['popular', 'INTERNAL_PRODUCT_TOKEN'],
        salesCount: 12,
        reviewScore: '4.8',
        lastEventAt: '2026-07-12T08:00:00',
        version: 1,
      })
      return
    }
    await fulfill(route, [])
  })

  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  const userSection = page.getByRole('region', { name: 'User profile' })
  const productSection = page.getByRole('region', { name: 'Product profile' })
  await expect(page.locator('.el-table').getByText('Search', { exact: true })).toBeVisible()
  await expect(userSection).toContainText('Latest event: Unknown')
  await expect(userSection).toContainText('Previous event: Search')
  await expect(userSection).toContainText('Page: Dashboard')
  await expect(userSection).toContainText('Source: Web')
  await expect(userSection.locator('.tag-row')).toContainText('Page: Product')
  await expect(userSection.locator('.tag-row')).toContainText('Unknown')
  await expect(productSection.locator('.tag-row')).toContainText('Unknown')
  await expect(productSection).toContainText('Popular')
  for (const rawToken of [
    'INTERNAL_EVENT_TOKEN',
    'INTERNAL_PROFILE_TOKEN',
    'INTERNAL_PRODUCT_TOKEN',
    'INTERNAL_PAGE_TOKEN',
    '/internal/operations',
    'internal_system',
  ]) {
    await expect(page.locator('body')).not.toContainText(rawToken)
  }
})
test('product ID validation blocks invalid requests and clears after a valid selection', async ({
  page,
}) => {
  await useEnglishAdmin(page)
  let productRequests = 0

  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await fulfill(route, { isLogin: true, identity: 'ADMIN', username: 'admin' })
      return
    }
    if (pathname === '/tracking/dashboard') {
      await fulfill(route, {
        pageViews: 128,
        uniqueVisitors: 42,
        orderCount: 9,
        paymentAmount: '8200.00',
        funnel: [],
        generatedAt: '2026-07-12T08:00:00',
        refreshIntervalSeconds: 5,
      })
      return
    }
    if (pathname === '/tracking/profile/me') {
      await fulfill(route, {
        userId: 1,
        profileSummary: 'last=PAGE_VIEW',
        behaviorTags: [],
        interestTags: [],
        lastEventAt: '2026-07-12T08:00:00',
        version: 1,
      })
      return
    }
    if (pathname.match(/^\/tracking\/products\/\d+$/)) {
      productRequests += 1
      const productId = Number(pathname.split('/').at(-1))
      await fulfill(route, {
        productId,
        tagVector: [],
        salesCount: productId === 2 ? 20 : 12,
        reviewScore: '4.8',
        lastEventAt: '2026-07-12T08:00:00',
        version: productRequests,
      })
      return
    }
    await fulfill(route, [])
  })

  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  const productSection = page.getByRole('region', { name: 'Product profile' })
  const productId = productSection.getByLabel('Product ID')
  await expect(productSection).toContainText('12')
  expect(productRequests).toBe(1)

  for (const invalidId of ['0', '-1', '']) {
    await productId.fill(invalidId)
    await productId.press('Tab')
    await expect(productSection.getByRole('alert')).toContainText('Enter a positive product ID')
    await productSection.getByRole('button', { name: 'Refresh now' }).click()
    expect(productRequests).toBe(1)
  }

  await productId.fill('2')
  await productId.press('Tab')
  await expect(productSection.getByRole('alert')).toHaveCount(0)
  await productSection.getByRole('button', { name: 'Refresh now' }).click()
  await expect.poll(() => productRequests).toBe(2)
  await expect(productSection).toContainText('20')
})

test('product profile keeps the newest product response and does not clear dashboard or user data', async ({
  page,
}) => {
  await useEnglishAdmin(page)
  let dashboardRequests = 0
  let profileRequests = 0
  let resolveProductTwo!: () => void
  const productTwo = new Promise<void>((resolve) => {
    resolveProductTwo = resolve
  })

  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await fulfill(route, { isLogin: true, identity: 'ADMIN', username: 'admin' })
      return
    }
    if (pathname === '/tracking/dashboard') {
      dashboardRequests += 1
      await fulfill(route, {
        pageViews: 128,
        uniqueVisitors: 42,
        orderCount: 9,
        paymentAmount: '8200.00',
        funnel: [],
        generatedAt: '2026-07-12T08:00:00',
        refreshIntervalSeconds: 5,
      })
      return
    }
    if (pathname === '/tracking/profile/me') {
      profileRequests += 1
      await fulfill(route, {
        userId: 1,
        profileSummary: profileRequests === 1 ? 'last=PAGE_VIEW' : 'last=SEARCH',
        behaviorTags: [],
        interestTags: [],
        lastEventAt: '2026-07-12T08:00:00',
        version: profileRequests,
      })
      return
    }
    if (pathname.match(/^\/tracking\/products\/\d+$/)) {
      const productId = Number(pathname.split('/').at(-1))
      if (productId === 2) {
        await productTwo
      }
      await fulfill(route, {
        productId,
        tagVector: [],
        salesCount: productId === 3 ? 30 : productId === 2 ? 20 : 12,
        reviewScore: '4.8',
        lastEventAt: '2026-07-12T08:00:00',
        version: productId,
      })
      return
    }
    await fulfill(route, [])
  })

  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  const userSection = page.getByRole('region', { name: 'User profile' })
  const productSection = page.getByRole('region', { name: 'Product profile' })
  const productId = productSection.getByLabel('Product ID')
  await expect(productSection).toContainText('12')
  expect(dashboardRequests).toBe(1)
  await page.getByRole('button', { name: 'Pause polling' }).click()

  await userSection.getByRole('button', { name: 'Refresh now' }).click()
  await expect(userSection).toContainText('Search')
  expect(profileRequests).toBe(2)
  expect(dashboardRequests).toBe(1)

  await productId.fill('2')
  await productId.press('Tab')
  await productSection.getByRole('button', { name: 'Refresh now' }).click()
  await expect(productSection).not.toContainText('12')

  await productId.fill('3')
  await productId.press('Tab')
  await productSection.getByRole('button', { name: 'Refresh now' }).click()
  await expect(productSection).toContainText('30')
  resolveProductTwo()
  await expect(productSection).toContainText('30')
  expect(dashboardRequests).toBe(1)
  await expect(userSection).toContainText('Search')
})
