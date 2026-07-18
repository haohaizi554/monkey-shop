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
  exportRequestResult?: Record<string, unknown>
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
    } else if (
      route.request().method() === 'POST' &&
      pathname === '/tenants/1/exports' &&
      options.exportRequestResult
    ) {
      data = options.exportRequestResult
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

test('an invalid tenant query is canonicalized without leaving a ghost selection', async ({
  page,
}) => {
  await installTenantMocks(page)
  await page.goto('/tenants?tenant=1')
  await expect(page.getByRole('heading', { name: 'Tenant Alpha' })).toBeVisible()

  await page.evaluate(() => {
    window.history.pushState({}, '', '/tenants?tenant=999')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })

  await expect(page).toHaveURL(/tenant=1/)
  await expect(page.getByRole('heading', { name: 'Tenant Alpha' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Tenant Beta' })).toHaveCount(0)

  for (const malformedTenant of ['1junk', '1.5']) {
    await page.evaluate((tenant) => {
      window.history.pushState({}, '', `/tenants?tenant=${tenant}`)
      window.dispatchEvent(new PopStateEvent('popstate'))
    }, malformedTenant)

    await expect(page).toHaveURL(/\/tenants\?tenant=1$/)
    await expect(page.getByRole('heading', { name: 'Tenant Alpha' })).toBeVisible()
  }
})

test('a tenant detail failure leaves the tenant list usable and hides backend copy', async ({
  page,
}) => {
  await installTenantMocks(page, { failTenantTwoDetails: true })
  await page.goto('/tenants?tenant=1')
  await expect(page.getByRole('button', { name: 'Open Tenant Beta' })).toBeVisible()
  await page.getByRole('button', { name: 'Open Tenant Beta' }).click()
  await expect(page.getByRole('button', { name: 'Open Tenant Alpha' })).toBeEnabled()
  const safeErrors = page.getByText('The request failed. Please try again later.')
  await expect(safeErrors).toHaveCount(3)
  await expect(safeErrors.first()).toBeVisible()
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

test('a stale same-tenant detail refresh cannot overwrite a completed config mutation', async ({
  page,
}) => {
  let configGetCount = 0
  let releaseSave!: () => void
  let markSaveStarted!: () => void
  let releaseStaleGet!: () => void
  let markStaleGetStarted!: () => void
  const saveGate = new Promise<void>((resolve) => {
    releaseSave = resolve
  })
  const saveStarted = new Promise<void>((resolve) => {
    markSaveStarted = resolve
  })
  const staleGetGate = new Promise<void>((resolve) => {
    releaseStaleGet = resolve
  })
  const staleGetStarted = new Promise<void>((resolve) => {
    markStaleGetStarted = resolve
  })

  await installTenantMocks(page)
  await page.route('**/api/v1/tenants/1/configs', async (route) => {
    if (route.request().method() === 'PUT') {
      markSaveStarted()
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
      return
    }
    configGetCount += 1
    if (configGetCount === 1) {
      await route.fallback()
      return
    }
    markStaleGetStarted()
    await staleGetGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok([
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
        ]),
      ),
    })
  })

  await page.goto('/tenants?tenant=1')
  await expect(page.getByText('alpha-provider')).toBeVisible()
  await page.getByRole('button', { name: 'Save config', exact: true }).click()
  await saveStarted
  await page.getByRole('button', { name: 'Open Tenant Alpha' }).click()
  await staleGetStarted

  const saveResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname.endsWith('/api/v1/tenants/1/configs'),
  )
  releaseSave()
  await saveResponse
  await expect(page.getByText('saved-alpha-provider')).toBeVisible()

  releaseStaleGet()
  await expect(page.getByText('saved-alpha-provider')).toBeVisible()
  await expect(page.getByText('alpha-provider', { exact: true })).toHaveCount(0)
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
            status: 'SUCCEEDED',
            artifactAvailable: true,
            requestedBy: 1,
            requestedAt: '2026-07-12T08:00:00',
            version: 1,
          },
          {
            id: 92,
            tenantId: 1,
            exportType: 'ORDERS',
            status: 'UNAVAILABLE',
            artifactAvailable: false,
            requestedBy: 1,
            requestedAt: '2026-07-12T08:01:00',
            errorMessage: 'tenant export provider is not configured',
            version: 1,
          },
          {
            id: 93,
            tenantId: 1,
            exportType: 'USERS',
            status: 'FAILED',
            artifactAvailable: false,
            requestedBy: 1,
            requestedAt: '2026-07-12T08:02:00',
            errorMessage: 'tenant export provider failed',
            version: 1,
          },
          {
            id: 94,
            tenantId: 1,
            exportType: 'FULL',
            status: 'QUEUED',
            artifactAvailable: false,
            requestedBy: 1,
            requestedAt: '2026-07-12T08:03:00',
            version: 1,
          },
          {
            id: 95,
            tenantId: 1,
            exportType: 'FULL',
            status: 'RUNNING',
            artifactAvailable: false,
            requestedBy: 1,
            requestedAt: '2026-07-12T08:04:00',
            version: 1,
          },
        ]),
      ),
    })
  })

  await page.goto('/tenants?tenant=1')
  await page.getByRole('tab', { name: 'Export', exact: true }).click()
  const exportTable = page.getByRole('tabpanel', { name: 'Export' }).locator('.el-table')
  await expect(exportTable.getByText('Full', { exact: true })).toHaveCount(3)
  await expect(exportTable.getByText('FULL', { exact: true })).toHaveCount(0)
  await expect(exportTable.getByText('Succeeded', { exact: true })).toBeVisible()
  await expect(exportTable.getByText('Unavailable', { exact: true })).toBeVisible()
  await expect(exportTable.getByText('Failed', { exact: true })).toBeVisible()
  await expect(exportTable.getByText('Queued', { exact: true })).toBeVisible()
  await expect(exportTable.getByText('Running', { exact: true })).toBeVisible()
  await expect(exportTable.getByText('Encrypted archive ready', { exact: true })).toBeVisible()
  await expect(exportTable.getByText('Provider unavailable', { exact: true })).toBeVisible()
  await expect(exportTable.getByText('Export failed', { exact: true })).toBeVisible()
  await expect(exportTable.getByText('Preparing archive', { exact: true })).toHaveCount(2)
  await expect(exportTable.getByRole('link')).toHaveCount(0)
  await expect(exportTable.getByText('tenant export provider failed', { exact: true })).toHaveCount(0)
})

test('an unavailable export response never announces a successful submission', async ({ page }) => {
  await installTenantMocks(page, {
    exportRequestResult: {
      id: 96,
      tenantId: 1,
      exportType: 'FULL',
      status: 'UNAVAILABLE',
      artifactAvailable: false,
      requestedBy: 1,
      requestedAt: '2026-07-12T08:05:00',
      version: 1,
    },
  })
  await page.goto('/tenants?tenant=1')
  await page.getByRole('tab', { name: 'Export' }).click()
  await page.getByRole('button', { name: 'Submit export' }).click()

  await expect(page.locator('.app-feedback-item--warning')).toContainText('Provider unavailable')
  await expect(page.locator('.app-feedback-item--success')).toHaveCount(0)
})

test('billing rejects an impossible month inline without making a request', async ({ page }) => {
  let billCalls = 0
  await installTenantMocks(page)
  await page.route('**/api/v1/tenants/1/bills', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.fallback()
      return
    }
    billCalls += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok({})),
    })
  })

  await page.goto('/tenants?tenant=1')
  await page.getByRole('tab', { name: 'Billing', exact: true }).click()
  await page.getByPlaceholder('yyyy-MM').fill('2026-13')
  await page.getByRole('button', { name: 'Generate bill', exact: true }).click()

  await expect(page.getByRole('alert')).toContainText('valid month')
  expect(billCalls).toBe(0)
})

test('billing validation errors do not leak into another tenant', async ({ page }) => {
  await installTenantMocks(page)
  await page.goto('/tenants?tenant=1')
  await page.getByRole('tab', { name: 'Billing', exact: true }).click()
  await page.getByPlaceholder('yyyy-MM').fill('2026-13')
  await page.getByRole('button', { name: 'Generate bill', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('valid month')

  await page.getByRole('button', { name: 'Open Tenant Beta' }).click()

  await expect(page.getByRole('heading', { name: 'Tenant Beta' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('downgrade owns its pending lock before confirmation and releases it on cancel', async ({
  page,
}) => {
  let downgradeCalls = 0
  await installTenantMocks(page)
  await page.route('**/api/v1/tenants/1/downgrade', async (route) => {
    downgradeCalls += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok({ ...tenants[0], status: 'DOWNGRADED', plan: 'STARTER' })),
    })
  })

  await page.goto('/tenants?tenant=1')
  const tenantDetail = page.getByRole('region', { name: 'Tenant Alpha' })
  const downgrade = tenantDetail.getByRole('button', { name: 'Downgrade', exact: true })
  const renew = tenantDetail.getByRole('button', { name: 'Renew', exact: true })
  await downgrade.click()

  await expect(page.getByRole('dialog', { name: 'Downgrade tenant' })).toHaveCount(1)
  await expect(downgrade).toBeDisabled()
  await expect(renew).toBeDisabled()
  await downgrade.evaluate((element) => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
  await expect(page.getByRole('dialog', { name: 'Downgrade tenant' })).toHaveCount(1)
  await page.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect.poll(() => downgradeCalls).toBe(0)
  await expect(downgrade).toBeEnabled()
  await expect(renew).toBeEnabled()

  await downgrade.click()
  await page.getByRole('button', { name: 'Downgrade', exact: true }).last().click()
  await expect.poll(() => downgradeCalls).toBe(1)
})

test('tenant creation rejects whitespace-only identity fields before the API call', async ({
  page,
}) => {
  let createCalls = 0
  await installTenantMocks(page)
  await page.route('**/api/v1/tenants', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.fallback()
      return
    }
    createCalls += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(tenants[0])),
    })
  })

  await page.goto('/tenants?tenant=1')
  await page.getByRole('button', { name: 'Create tenant', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Create a tenant' })
  await dialog.getByRole('textbox', { name: 'Code' }).fill('   ')
  await dialog.getByPlaceholder('Merchant name').fill('Valid tenant')
  await dialog.getByRole('button', { name: 'Create', exact: true }).click()

  await expect(dialog.getByText('Tenant code is required')).toBeVisible()
  expect(createCalls).toBe(0)
})

test('tenant master-detail stays usable in desktop and mobile evidence views', async ({ page }) => {
  await installTenantMocks(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/tenants?tenant=1')
  await expect(page.getByRole('button', { name: 'Open navigation' })).toBeHidden()
  await expect
    .poll(() =>
      page
        .locator('.admin-topbar')
        .evaluate((element) => element.scrollHeight <= element.clientHeight),
    )
    .toBe(true)
  await expect(page.getByRole('heading', { name: 'Tenant list' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Tenant Alpha' })).toBeVisible()
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true)
  await page.screenshot({ path: 'output/task7-tenant-desktop.png', fullPage: false })

  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: 'Open Tenant Beta' }).click()
  await expect(page.getByRole('button', { name: 'Back to tenant list' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Tenant Beta' })).toBeVisible()
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true)
  await page.screenshot({ path: 'output/task7-tenant-mobile.png', fullPage: false })
})
