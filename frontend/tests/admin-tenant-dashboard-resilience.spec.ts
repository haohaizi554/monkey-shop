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

  await userSection.getByRole('button', { name: 'Refresh now' }).click()
  await productSection.getByRole('button', { name: 'Refresh now' }).click()

  await expect(userSection.getByRole('alert')).toContainText('The request failed')
  await expect(productSection.getByRole('alert')).toContainText('The request failed')
  await expect(userSection).toContainText('Page view')
  await expect(productSection).toContainText('12')
  await expect(userSection.getByTestId('user-profile-last-success')).toContainText('Updated')
  await expect(productSection.getByTestId('product-profile-last-success')).toContainText('Updated')

  await userSection.getByRole('button', { name: 'Retry', exact: true }).click()
  await productSection.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(userSection).toContainText('Search')
  await expect(productSection).toContainText('24')
  await expect(userSection.getByRole('alert')).toHaveCount(0)
  await expect(productSection.getByRole('alert')).toHaveCount(0)
})
