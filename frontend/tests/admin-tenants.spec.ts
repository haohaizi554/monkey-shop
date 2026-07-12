import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'tenant-test' }
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

interface TenantMockOptions {
  failTenantTwoDetails?: boolean
  tenantOneDetailsGate?: Promise<void>
  onTenantOneDetailStarted?: () => void
  onTenantOneDetailCompleted?: () => void
}

function requestBatchGate(expectedRequests: number) {
  let release!: () => void
  let markStartedReady!: () => void
  let markCompletedReady!: () => void
  let startedCount = 0
  let completedCount = 0
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  const started = new Promise<void>((resolve) => {
    markStartedReady = resolve
  })
  const completed = new Promise<void>((resolve) => {
    markCompletedReady = resolve
  })

  return {
    gate,
    started,
    completed,
    release,
    markStarted() {
      startedCount += 1
      if (startedCount === expectedRequests) markStartedReady()
    },
    markCompleted() {
      completedCount += 1
      if (completedCount === expectedRequests) markCompletedReady()
    },
  }
}

async function installTenantMocks(page: Page, options: TenantMockOptions = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    let tenantOneDetailRequest = false
    if (pathname === '/users/me') {
      data = { isLogin: true, identity: 'ADMIN', username: 'admin' }
    } else if (pathname === '/tenants/dashboard') {
      data = {
        activeTenants: 1,
        expiredTenants: 0,
        currentMonthOrders: 18,
        currentMonthRevenue: '16800.00',
        tenants,
      }
    } else if (/\/tenants\/1\/(configs|bills|exports)$/.test(pathname)) {
      tenantOneDetailRequest = true
      options.onTenantOneDetailStarted?.()
      await options.tenantOneDetailsGate
      data = pathname.endsWith('/configs')
        ? [
            {
              id: 11,
              tenantId: 1,
              configType: 'PAYMENT',
              provider: 'alpha-provider',
              settings: {},
              enabled: true,
              updatedAt: '2026-07-12T08:00:00',
              version: 1,
            },
          ]
        : []
    } else if (/\/tenants\/2\/(configs|bills|exports)$/.test(pathname)) {
      if (options.failTenantTwoDetails) {
        await route.fulfill({
          status: 500,
          contentType: 'application/problem+json',
          body: JSON.stringify({ title: 'Internal error', detail: 'database exploded' }),
        })
        return
      }
      data = pathname.endsWith('/configs')
        ? [
            {
              id: 21,
              tenantId: 2,
              configType: 'PAYMENT',
              provider: 'beta-provider',
              settings: {},
              enabled: true,
              updatedAt: '2026-07-12T08:00:00',
              version: 1,
            },
          ]
        : []
    } else if (pathname === '/tracking/events') {
      data = { id: 1, eventType: 'PAGE_VIEW' }
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(data)),
    })
    if (tenantOneDetailRequest) options.onTenantOneDetailCompleted?.()
  })
}

test('tenant selection is URL-backed and stale detail responses cannot overwrite it', async ({
  page,
}) => {
  const tenantOneDetails = requestBatchGate(3)
  await installTenantMocks(page, {
    tenantOneDetailsGate: tenantOneDetails.gate,
    onTenantOneDetailStarted: tenantOneDetails.markStarted,
    onTenantOneDetailCompleted: tenantOneDetails.markCompleted,
  })
  await page.goto('/tenants?tenant=1')
  await expect(page.getByRole('button', { name: 'Open Tenant Beta' })).toBeVisible()
  await tenantOneDetails.started
  await page.getByRole('button', { name: 'Open Tenant Beta' }).click()
  await expect(page).toHaveURL(/tenant=2/)
  await expect(page.getByRole('heading', { name: 'Tenant Beta' })).toBeVisible()
  await expect(page.getByText('beta-provider')).toBeVisible()
  tenantOneDetails.release()
  await tenantOneDetails.completed
  await page.waitForLoadState('networkidle')
  await expect(page.getByText('alpha-provider')).toHaveCount(0)

  await page.reload()
  await expect(page).toHaveURL(/tenant=2/)
  await expect(page.getByRole('heading', { name: 'Tenant Beta' })).toBeVisible()
})

test('a tenant detail failure leaves the tenant list usable and hides backend copy', async ({
  page,
}) => {
  await installTenantMocks(page, { failTenantTwoDetails: true })
  await page.goto('/tenants?tenant=1')
  await expect(page.getByRole('button', { name: 'Open Tenant Beta' })).toBeVisible()
  await page.getByRole('button', { name: 'Open Tenant Beta' }).click()
  await expect(page.getByRole('button', { name: 'Open Tenant Alpha' })).toBeEnabled()
  await expect(page.getByText('The request failed. Please try again later.')).toBeVisible()
  await expect(page.getByText('database exploded')).toHaveCount(0)
})

test('a completed mutation cannot write into a tenant selected while it was pending', async ({
  page,
}) => {
  let releaseSave!: () => void
  const saveGate = new Promise<void>((resolve) => {
    releaseSave = resolve
  })
  await installTenantMocks(page)
  await page.route('**/api/v1/tenants/1/configs', async (route) => {
    if (route.request().method() !== 'PUT') {
      await route.fallback()
      return
    }
    await saveGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 12,
          tenantId: 1,
          configType: 'PAYMENT',
          provider: 'saved-alpha-provider',
          settings: {},
          enabled: true,
          updatedAt: '2026-07-12T09:00:00',
          version: 2,
        }),
      ),
    })
  })

  await page.goto('/tenants?tenant=1')
  await expect(page.getByText('alpha-provider')).toBeVisible()
  await page.getByRole('button', { name: 'Save config', exact: true }).click()
  await page.getByRole('button', { name: 'Open Tenant Beta' }).click()
  await expect(page.getByText('beta-provider')).toBeVisible()

  const saveResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname.endsWith('/api/v1/tenants/1/configs'),
  )
  releaseSave()
  await saveResponse
  await page.waitForLoadState('networkidle')
  await expect(page.getByText('saved-alpha-provider')).toHaveCount(0)
  await expect(page.getByText('beta-provider')).toBeVisible()
})

test('tenant exports render localized types instead of internal enum tokens', async ({ page }) => {
  await installTenantMocks(page)
  await page.route('**/api/v1/tenants/1/exports', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok([
          {
            id: 91,
            tenantId: 1,
            exportType: 'FULL',
            status: 'COMPLETED',
            encryptedArchivePath: 'archive.enc',
            requestedBy: 1,
            requestedAt: '2026-07-12T08:00:00',
            version: 1,
          },
        ]),
      ),
    })
  })

  await page.goto('/tenants?tenant=1')
  await page.getByRole('tab', { name: 'Export', exact: true }).click()
  const exportTable = page.getByRole('tabpanel', { name: 'Export' }).locator('.el-table')
  await expect(exportTable.getByText('Full', { exact: true })).toBeVisible()
  await expect(exportTable.getByText('FULL', { exact: true })).toHaveCount(0)
})
