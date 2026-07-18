import { expect, test, type Page, type Request } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'admin-concurrency-test' }
}

interface MockRequest {
  pathname: string
  request: Request
}

async function installAdminMocks(
  page: Page,
  handler: (context: MockRequest) => unknown | Promise<unknown>,
) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')
    let data: unknown
    if (pathname === '/users/me') {
      data = { isLogin: true, identity: 'ADMIN', username: 'admin' }
    } else if (pathname === '/tracking/events') {
      data = { id: 1, eventType: 'PAGE_VIEW' }
    } else {
      data = await handler({ pathname, request })
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(data)),
    })
  })
}

const pendingReview = {
  id: 101,
  userId: 7,
  productId: 9,
  type: 'PRICE_ANOMALY',
  score: 88,
  status: 'PENDING',
  detail: 'price changed quickly',
  createdAt: '2026-07-12T08:00:00',
}

function stock(skuId: number, warehouseId: number, warehouseCode: string, availableQuantity = 12) {
  return {
    skuId,
    warehouseId,
    warehouseCode,
    province: 'East',
    availableQuantity,
    lockedQuantity: 20 - availableQuantity,
    deductedQuantity: 0,
    inTransitQuantity: 0,
    safetyStock: 3,
    totalQuantity: 20,
    belowSafetyStock: availableQuantity < 3,
  }
}

const adminProduct = {
  id: 1,
  name: 'Concurrency Monkey',
  breed: 'Capuchin',
  price: '128.00',
  imageUrl: '',
  stock: 8,
}

const adminStats = {
  totalGmv: '128.00',
  totalOrders: 1,
  totalVisits: 12,
  returnRate: '0%',
  xAxis: [],
  seriesOrder: [],
  seriesGmv: [],
  seriesVisit: [],
}

test('tenant detail mutation keeps list refresh and create actions available', async ({ page }) => {
  let dashboardLoads = 0
  let saveCalls = 0
  let releaseSave!: () => void
  const saveGate = new Promise<void>((resolve) => {
    releaseSave = resolve
  })
  const tenant = {
    id: 1,
    code: 'alpha',
    name: 'Tenant Alpha',
    status: 'ACTIVE',
    plan: 'GROWTH',
    maskedContactPhone: '138****0001',
    createdAt: '2026-01-01T00:00:00',
    expiresAt: '2027-01-01T00:00:00',
    version: 1,
  }
  const config = {
    id: 11,
    tenantId: 1,
    configType: 'PAYMENT',
    provider: 'alpha-provider',
    settings: { merchantId: 'alpha' },
    enabled: true,
    updatedAt: '2026-07-12T08:00:00',
    version: 1,
  }

  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/tenants/dashboard') {
      dashboardLoads += 1
      return {
        activeTenants: 1,
        expiredTenants: 0,
        currentMonthOrders: 18,
        currentMonthRevenue: '16800.00',
        tenants: [tenant],
      }
    }
    if (pathname === '/tenants/1/configs') {
      if (request.method() === 'PUT') {
        saveCalls += 1
        await saveGate
        return { ...config, provider: 'saved-provider', version: 2 }
      }
      return [config]
    }
    if (/^\/tenants\/1\/(bills|exports)$/.test(pathname)) return []
    return []
  })

  await page.goto('/tenants?tenant=1')
  await expect(page.getByPlaceholder('Provider')).toHaveValue('alpha-provider')
  await page.getByRole('button', { name: 'Save config', exact: true }).click()
  await expect.poll(() => saveCalls).toBe(1)

  const refresh = page.getByRole('button', { name: 'Refresh', exact: true })
  await expect(refresh).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Create tenant', exact: true })).toBeEnabled()
  await refresh.click()
  await expect.poll(() => dashboardLoads).toBe(2)

  releaseSave()
  await expect(page.getByText('saved-provider', { exact: true })).toBeVisible()
  expect(saveCalls).toBe(1)
})

test('admin keeps refresh available and lets the product delete patch win', async ({ page }) => {
  let catalogLoads = 0
  let deleteCalls = 0
  let releaseDelete!: () => void
  const deleteGate = new Promise<void>((resolve) => {
    releaseDelete = resolve
  })

  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/stats/data') return adminStats
    if (pathname === '/orders/all') return []
    if (pathname === '/monkeys' && request.method() === 'GET') {
      catalogLoads += 1
      return {
        content: [adminProduct],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      }
    }
    if (pathname === '/monkeys/1' && request.method() === 'DELETE') {
      deleteCalls += 1
      await deleteGate
      return null
    }
    return []
  })

  await page.goto('/admin')
  await expect(page.getByText('Concurrency Monkey', { exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: 'Delete Concurrency Monkey' }).click()
  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect.poll(() => deleteCalls).toBe(1)

  const refresh = page.getByRole('button', { name: 'Refresh', exact: true })
  await expect(refresh).toBeEnabled()
  await refresh.click()
  await expect.poll(() => catalogLoads).toBe(2)

  releaseDelete()
  await expect(page.getByText('Concurrency Monkey', { exact: true })).toHaveCount(0)
})

test('inventory keeps search available and lets the reservation patch win', async ({ page }) => {
  let stockLoads = 0
  let reserveCalls = 0
  let releaseReserve!: () => void
  const reserveGate = new Promise<void>((resolve) => {
    releaseReserve = resolve
  })

  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/inventory/skus/7/stocks') {
      stockLoads += 1
      return [stock(7, 1, 'SKU-7-CURRENT')]
    }
    if (pathname === '/inventory/reservations' && request.method() === 'POST') {
      reserveCalls += 1
      const body = request.postDataJSON() as { reservationKey: string }
      await reserveGate
      return {
        reservationKey: body.reservationKey,
        skuId: 7,
        warehouseId: 1,
        quantity: 1,
        status: 'RESERVED',
        expiresAt: '2026-07-12T09:00:00',
        stock: stock(7, 1, 'SKU-7-RESERVED', 11),
      }
    }
    return []
  })

  await page.goto('/inventory?skuId=7')
  await expect(page.getByText('SKU-7-CURRENT', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: 'Reservation key' }).fill('same-sku-write')
  await page.getByRole('button', { name: 'Reserve', exact: true }).click()
  await expect.poll(() => reserveCalls).toBe(1)

  const search = page.getByRole('button', { name: 'Search', exact: true })
  await expect(search).toBeEnabled()
  await search.click()
  await expect.poll(() => stockLoads).toBe(2)

  releaseReserve()
  await expect(page.getByText('same-sku-write', { exact: true })).toBeVisible()
  await expect(page.getByText('SKU-7-RESERVED', { exact: true })).toBeVisible()
})

test('risk refresh stays available during a decision write and final patch wins', async ({
  page,
}) => {
  let reviewLoads = 0
  let decisionCalls = 0
  let releaseDecision!: () => void
  const decisionGate = new Promise<void>((resolve) => {
    releaseDecision = resolve
  })

  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/risk/reviews' && request.method() === 'GET') {
      reviewLoads += 1
      return [pendingReview]
    }
    if (pathname === '/risk/reviews/101/resolve') {
      decisionCalls += 1
      const body = request.postDataJSON() as { status: string; resolution: string }
      await decisionGate
      return {
        ...pendingReview,
        status: body.status,
        resolution: body.resolution,
        handledAt: '2026-07-12T08:05:00',
      }
    }
    return []
  })

  await page.goto('/risk')
  await page.getByRole('button', { name: 'Approve case 101' }).click()
  const drawer = page.locator('.el-drawer')
  await drawer.getByRole('textbox', { name: 'Resolution note' }).fill('verified manually')
  await drawer.getByRole('button', { name: 'Save decision' }).click()
  await expect.poll(() => decisionCalls).toBe(1)

  const refresh = page.getByRole('button', { name: 'Refresh', exact: true })
  await expect(refresh).toBeEnabled()
  await page.keyboard.press('Escape')
  await refresh.click()
  await expect.poll(() => reviewLoads).toBe(2)

  releaseDecision()
  await expect(page.locator('tbody').getByText('Approved', { exact: true })).toBeVisible()
  await expect(page.locator('tbody').getByText('Pending', { exact: true })).toHaveCount(0)
  expect(reviewLoads).toBe(2)
})

test('risk decisions use one case lock even when another decision is selected', async ({
  page,
}) => {
  let releaseDecision!: () => void
  const decisionGate = new Promise<void>((resolve) => {
    releaseDecision = resolve
  })
  let decisionRequests = 0

  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/risk/reviews' && request.method() === 'GET') return [pendingReview]
    if (pathname === '/risk/reviews/101/resolve') {
      decisionRequests += 1
      await decisionGate
      const body = request.postDataJSON() as { status: string; resolution: string }
      return {
        ...pendingReview,
        status: body.status,
        resolution: body.resolution,
        handledAt: '2026-07-12T08:05:00',
      }
    }
    return []
  })

  await page.goto('/risk')
  await page.getByRole('button', { name: 'Approve case 101' }).click()
  const drawer = page.locator('.el-drawer')
  await drawer.getByRole('textbox', { name: 'Resolution note' }).fill('verified manually')
  const requestStarted = page.waitForRequest((request) =>
    request.url().includes('/api/v1/risk/reviews/101/resolve'),
  )

  try {
    await drawer.getByRole('button', { name: 'Save decision' }).click()
    await requestStarted
    await expect(drawer.getByRole('radio', { name: 'Reject' })).toBeDisabled()
    await expect(drawer.getByRole('button', { name: 'Save decision' })).toBeDisabled()
    expect(decisionRequests).toBe(1)
  } finally {
    releaseDecision()
  }
})

test('a stale reserve response cannot add its SKU stock to the current query', async ({ page }) => {
  let releaseReserve!: () => void
  const reserveGate = new Promise<void>((resolve) => {
    releaseReserve = resolve
  })

  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/inventory/skus/7/stocks') return [stock(7, 1, 'SKU-7-CURRENT')]
    if (pathname === '/inventory/skus/8/stocks') return [stock(8, 2, 'SKU-8-CURRENT', 18)]
    if (pathname === '/inventory/reservations' && request.method() === 'POST') {
      await reserveGate
      const body = request.postDataJSON() as { reservationKey: string }
      return {
        reservationKey: body.reservationKey,
        skuId: 7,
        warehouseId: 77,
        quantity: 1,
        status: 'RESERVED',
        expiresAt: '2026-07-12T09:00:00',
        stock: stock(7, 77, 'STALE-RESERVE-SKU-7', 11),
      }
    }
    return []
  })

  await page.goto('/inventory?skuId=7')
  await expect(page.getByText('SKU-7-CURRENT', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: 'Reservation key' }).fill('stale-reserve')
  const reserveStarted = page.waitForRequest((request) =>
    request.url().endsWith('/api/v1/inventory/reservations'),
  )
  await page.getByRole('button', { name: 'Reserve', exact: true }).click()
  await reserveStarted

  await page.getByRole('spinbutton', { name: 'SKU id' }).fill('8')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByText('SKU-8-CURRENT', { exact: true })).toBeVisible()
  releaseReserve()

  await expect(page.getByText('stale-reserve', { exact: true })).toBeVisible()
  await expect(page.getByText('STALE-RESERVE-SKU-7', { exact: true })).toHaveCount(0)
  await expect(page.getByText('SKU-8-CURRENT', { exact: true })).toBeVisible()
})

test('a release response updates the reservation but not another SKU stock table', async ({
  page,
}) => {
  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/inventory/skus/7/stocks') return [stock(7, 1, 'SKU-7-CURRENT')]
    if (pathname === '/inventory/skus/8/stocks') return [stock(8, 2, 'SKU-8-CURRENT', 18)]
    if (pathname === '/inventory/reservations' && request.method() === 'POST') {
      const body = request.postDataJSON() as { reservationKey: string }
      return {
        reservationKey: body.reservationKey,
        skuId: 7,
        warehouseId: 1,
        quantity: 1,
        status: 'RESERVED',
        expiresAt: '2026-07-12T09:00:00',
        stock: stock(7, 1, 'SKU-7-CURRENT', 11),
      }
    }
    if (pathname === '/inventory/reservations/release-me/release') {
      return {
        reservationKey: 'release-me',
        skuId: 7,
        warehouseId: 88,
        quantity: 1,
        status: 'RELEASED',
        expiresAt: '2026-07-12T09:00:00',
        stock: stock(7, 88, 'STALE-RELEASE-SKU-7', 12),
      }
    }
    return []
  })

  await page.goto('/inventory?skuId=7')
  await page.getByRole('textbox', { name: 'Reservation key' }).fill('release-me')
  await page.getByRole('button', { name: 'Reserve', exact: true }).click()
  await expect(page.getByText('release-me', { exact: true })).toBeVisible()

  await page.getByRole('spinbutton', { name: 'SKU id' }).fill('8')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByText('SKU-8-CURRENT', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Release release-me' }).click()

  await expect(page.getByText('Released', { exact: true })).toBeVisible()
  await expect(page.getByText('STALE-RELEASE-SKU-7', { exact: true })).toHaveCount(0)
  await expect(page.getByText('SKU-8-CURRENT', { exact: true })).toBeVisible()
})

test('risk assessment inputs expose stable accessible names', async ({ page }) => {
  await installAdminMocks(page, ({ pathname }) =>
    pathname === '/risk/reviews' ? [pendingReview] : [],
  )
  await page.goto('/risk')

  await expect(page.getByRole('textbox', { name: 'Phone', exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Device fingerprint', exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Client IP', exact: true })).toBeVisible()
  await expect(page.getByRole('spinbutton', { name: 'Product', exact: true })).toBeVisible()
  await expect(page.getByRole('spinbutton', { name: 'Order', exact: true })).toBeVisible()
  await expect(
    page.getByRole('spinbutton', { name: 'Seckill activity', exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('spinbutton', { name: 'Seller', exact: true })).toBeVisible()
  await expect(
    page.getByRole('spinbutton', { name: 'Price before change', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('spinbutton', { name: 'Price after change', exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Admin TOTP', exact: true })).toBeVisible()
})

test('unknown risk enum values use localized fallback copy instead of internal tokens', async ({
  page,
}) => {
  await installAdminMocks(page, ({ pathname, request }) => {
    if (pathname === '/risk/reviews') {
      return [
        { ...pendingReview, type: 'FUTURE_REVIEW_SIGNAL' },
        {
          ...pendingReview,
          id: 102,
          type: 'PRICE_ANOMALY',
          status: 'FUTURE_REVIEW_STATUS',
        },
      ]
    }
    if (pathname === '/risk/assess' && request.method() === 'POST') {
      return {
        userId: 7,
        score: 42,
        decision: 'FUTURE_RISK_DECISION',
        signals: [{ type: 'FUTURE_ASSESSMENT_SIGNAL', weight: 10, detail: 'future signal' }],
        productAutoUnlisted: false,
        userTokensRevoked: false,
        assessedAt: '2026-07-12T08:00:00',
      }
    }
    return []
  })

  await page.goto('/risk')
  await page.getByRole('button', { name: 'Assess', exact: true }).click()
  await expect(page.getByText('Unknown', { exact: true }).first()).toBeVisible()
  await expect(page.locator('body')).not.toContainText('FUTURE_REVIEW_SIGNAL')
  await expect(page.locator('body')).not.toContainText('FUTURE_REVIEW_STATUS')
  await expect(page.locator('body')).not.toContainText('FUTURE_RISK_DECISION')
  await expect(page.locator('body')).not.toContainText('FUTURE_ASSESSMENT_SIGNAL')
})

test('a late risk refresh cannot restore a review resolved while it was pending', async ({
  page,
}) => {
  let reviewLoads = 0
  let releaseRefresh!: () => void
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve
  })

  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/risk/reviews' && request.method() === 'GET') {
      reviewLoads += 1
      if (reviewLoads > 1) await refreshGate
      return [pendingReview]
    }
    if (pathname === '/risk/reviews/101/resolve') {
      const body = request.postDataJSON() as { status: string; resolution: string }
      return {
        ...pendingReview,
        status: body.status,
        resolution: body.resolution,
        handledAt: '2026-07-12T08:05:00',
      }
    }
    return []
  })

  await page.goto('/risk')
  await expect(page.getByRole('button', { name: 'Approve case 101' })).toBeVisible()
  const refreshResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' && response.url().endsWith('/api/v1/risk/reviews'),
  )
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => reviewLoads).toBe(2)

  await page.getByRole('button', { name: 'Approve case 101' }).click()
  const drawer = page.locator('.el-drawer')
  await drawer.getByRole('textbox', { name: 'Resolution note' }).fill('verified manually')
  await drawer.getByRole('button', { name: 'Save decision' }).click()
  const tableStatus = page.locator('tbody').getByText('Approved', { exact: true })
  await expect(tableStatus).toBeVisible()

  releaseRefresh()
  await refreshResponse
  await expect(tableStatus).toBeVisible()
  await expect(page.getByRole('button', { name: 'Approve case 101' })).toHaveCount(0)
})

test('a late inventory refresh cannot overwrite a completed reservation', async ({ page }) => {
  let stockLoads = 0
  let releaseRefresh!: () => void
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve
  })

  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/inventory/skus/7/stocks') {
      stockLoads += 1
      if (stockLoads > 1) {
        await refreshGate
        return [stock(7, 1, 'STALE-REFRESH', 4)]
      }
      return [stock(7, 1, 'CURRENT-STOCK')]
    }
    if (pathname === '/inventory/reservations' && request.method() === 'POST') {
      const body = request.postDataJSON() as { reservationKey: string }
      return {
        reservationKey: body.reservationKey,
        skuId: 7,
        warehouseId: 1,
        quantity: 1,
        status: 'RESERVED',
        expiresAt: '2026-07-12T09:00:00',
        stock: stock(7, 1, 'RESERVED-STOCK', 11),
      }
    }
    return []
  })

  await page.goto('/inventory?skuId=7')
  await expect(page.getByText('CURRENT-STOCK', { exact: true })).toBeVisible()
  const refreshResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().endsWith('/api/v1/inventory/skus/7/stocks'),
  )
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect.poll(() => stockLoads).toBe(2)

  await page.getByRole('textbox', { name: 'Reservation key' }).fill('reserve-during-refresh')
  await page.getByRole('button', { name: 'Reserve', exact: true }).click()
  await expect(page.getByText('RESERVED-STOCK', { exact: true })).toBeVisible()

  releaseRefresh()
  await refreshResponse
  await expect(page.getByText('RESERVED-STOCK', { exact: true })).toBeVisible()
  await expect(page.getByText('STALE-REFRESH', { exact: true })).toHaveCount(0)
})

test('risk assessment freezes its request fields until the response settles', async ({ page }) => {
  let releaseAssessment!: () => void
  const assessmentGate = new Promise<void>((resolve) => {
    releaseAssessment = resolve
  })

  await installAdminMocks(page, async ({ pathname, request }) => {
    if (pathname === '/risk/reviews') return []
    if (pathname === '/risk/assess' && request.method() === 'POST') {
      await assessmentGate
      return {
        userId: 7,
        score: 12,
        decision: 'ALLOW',
        signals: [],
        productAutoUnlisted: false,
        userTokensRevoked: false,
        assessedAt: '2026-07-12T08:00:00',
      }
    }
    return []
  })

  await page.goto('/risk')
  await page.getByRole('textbox', { name: 'Phone', exact: true }).fill('13800138000')
  const assessmentStarted = page.waitForRequest((request) =>
    request.url().endsWith('/api/v1/risk/assess'),
  )
  await page.getByRole('button', { name: 'Assess', exact: true }).click()
  await assessmentStarted

  await expect(page.getByRole('textbox', { name: 'Phone', exact: true })).toBeDisabled()
  await expect(
    page.getByRole('textbox', { name: 'Device fingerprint', exact: true }),
  ).toBeDisabled()
  await expect(page.getByRole('spinbutton', { name: 'Product', exact: true })).toBeDisabled()
  await expect(page.getByRole('textbox', { name: 'Admin TOTP', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Assess', exact: true })).toBeDisabled()

  releaseAssessment()
  await expect(page.getByRole('textbox', { name: 'Phone', exact: true })).toBeEnabled()
})
