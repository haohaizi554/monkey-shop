import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'admin-operations-test' }
}

const products = [
  { id: 1, name: 'Golden Monkey', breed: 'Golden', price: '128.00', imageUrl: '', stock: 8 },
  { id: 2, name: 'Capuchin', breed: 'Capuchin', price: '98.00', imageUrl: '', stock: 12 },
]

const orders = [
  {
    id: 11,
    orderNo: 'ORDER-ALPHA',
    userId: 7,
    buyerName: 'Alice',
    productId: 1,
    productName: 'Golden Monkey',
    productImage: '',
    price: '128.00',
    receiverName: 'Alice',
    receiverPhone: '138****0001',
    addressSnapshot: 'East Road',
    status: 'PAID',
    createTime: '2026-07-12T08:00:00',
  },
  {
    id: 12,
    orderNo: 'ORDER-BETA',
    userId: 8,
    buyerName: 'Bob',
    productId: 2,
    productName: 'Capuchin',
    productImage: '',
    price: '98.00',
    receiverName: 'Bob',
    receiverPhone: '138****0002',
    addressSnapshot: 'West Road',
    status: 'PAID',
    createTime: '2026-07-12T08:00:00',
  },
]

async function installAdminMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const pathname = url.pathname.replace('/api/v1', '')
    let data: unknown = []
    if (pathname === '/users/me') {
      data = { isLogin: true, identity: 'ADMIN', username: 'admin' }
    } else if (pathname === '/stats/data') {
      data = {
        totalGmv: '226.00',
        totalOrders: 2,
        totalVisits: 24,
        returnRate: '0%',
        xAxis: [],
        seriesOrder: [],
        seriesGmv: [],
        seriesVisit: [],
      }
    } else if (pathname === '/monkeys' && request.method() === 'GET') {
      data = products
    } else if (pathname === '/orders/all') {
      data = orders
    } else if (pathname === '/monkeys/1' && request.method() === 'DELETE') {
      await new Promise((resolve) => setTimeout(resolve, 450))
      data = null
    } else if (pathname === '/stats/audit-trace') {
      data = [
        {
          id: 'evt-1',
          eventType: 'ORDER_CREATED',
          userId: '7',
          description: 'Order created',
          createdAt: '2026-07-12T08:00:00',
          traceId: url.searchParams.get('traceId'),
        },
      ]
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

test('admin product mutation is row-scoped while trace and URL order search stay usable', async ({
  page,
}) => {
  await installAdminMocks(page)
  await page.goto('/admin')
  await expect(page.getByText('Golden Monkey', { exact: true }).first()).toBeVisible()

  await page.getByRole('button', { name: 'Delete Golden Monkey' }).click()
  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Delete Golden Monkey' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Delete Capuchin' })).toBeEnabled()

  await page.getByRole('textbox', { name: 'Trace ID' }).fill('trace-123')
  await page.getByRole('button', { name: 'Search trace' }).click()
  await expect(page.getByRole('heading', { name: 'Order created' })).toBeVisible()

  await page.getByRole('textbox', { name: 'Search orders' }).fill('BETA')
  await expect(page).toHaveURL(/order=BETA/)
  await expect(page.getByText('ORDER-BETA', { exact: true })).toBeVisible()
  await expect(page.getByText('ORDER-ALPHA', { exact: true })).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Search orders' })).toHaveValue('BETA')
})

test('retrying return confirmation after a completed refund does not refund twice', async ({
  page,
}) => {
  let refundCalls = 0
  let confirmCalls = 0
  let refunded = false
  const returnOrder = { ...orders[0], status: 'RETURN_SHIPPING' }

  await installAdminMocks(page)
  await page.route('**/api/v1/orders/all', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok([returnOrder])),
    })
  })
  await page.route('**/api/v1/payments/admin/orders/11', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 91,
          paymentNo: 'PAY-11',
          orderId: 11,
          userId: 7,
          method: 'WECHAT',
          amount: '128.00',
          paidAmount: '128.00',
          refundedAmount: refunded ? '128.00' : '0.00',
          status: refunded ? 'REFUNDED' : 'PAID',
          createTime: '2026-07-12T08:00:00',
        }),
      ),
    })
  })
  await page.route('**/api/v1/payments/admin/refund', async (route) => {
    refundCalls += 1
    refunded = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          ledgerId: 1,
          paymentNo: 'PAY-11',
          amount: '128.00',
          refundedAmount: '128.00',
          paymentStatus: 'REFUNDED',
          ledgerStatus: 'SUCCESS',
          createTime: '2026-07-12T09:00:00',
        }),
      ),
    })
  })
  await page.route('**/api/v1/orders/return/confirm/11', async (route) => {
    confirmCalls += 1
    if (confirmCalls === 1) {
      await route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'order transition unavailable', status: 503 }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok({ ...returnOrder, status: 'REFUNDED' })),
    })
  })

  await page.goto('/admin')
  const refund = page.locator('#app').getByRole('button', { name: 'Refund', exact: true })
  await refund.click()
  await page.getByRole('button', { name: 'Refund', exact: true }).last().click()
  await expect(page.locator('.app-feedback-item')).toContainText(
    'Refund completed. Return confirmation is still pending.',
  )

  await refund.click()
  await page.getByRole('button', { name: 'Refund', exact: true }).last().click()
  await expect.poll(() => confirmCalls).toBe(2)
  expect(refundCalls).toBe(1)
})

test('failed admin refresh labels preserved metrics as stale instead of current', async ({
  page,
}) => {
  let statsCalls = 0
  await installAdminMocks(page)
  await page.route('**/api/v1/stats/data', async (route) => {
    statsCalls += 1
    if (statsCalls > 1) {
      await route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'metrics unavailable', status: 503 }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          totalGmv: '226.00',
          totalOrders: 2,
          totalVisits: 24,
          returnRate: '0%',
          xAxis: [],
          seriesOrder: [],
          seriesGmv: [],
          seriesVisit: [],
        }),
      ),
    })
  })

  await page.goto('/admin')
  const ordersMetric = page.locator('[data-metric-key="orders"] strong')
  await expect(ordersMetric).toHaveText('2')
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()

  await expect(page.locator('.async-state-view__stale-error')).toContainText(
    'The request failed. Please try again later.',
  )
  await expect(ordersMetric).toHaveText('2')
  await expect(page.locator('body')).not.toContainText('metrics unavailable')
})

test('a late catalog refresh cannot restore a product deleted while it was pending', async ({
  page,
}) => {
  let catalogCalls = 0
  let releaseRefresh!: () => void
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve
  })

  await installAdminMocks(page)
  await page.route('**/api/v1/monkeys', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }
    catalogCalls += 1
    if (catalogCalls > 1) {
      await refreshGate
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(products)),
    })
  })

  await page.goto('/admin')
  const catalog = page.getByRole('region', { name: 'Catalog' })
  await expect(catalog.getByText('Golden Monkey', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => catalogCalls).toBe(2)

  await page.getByRole('button', { name: 'Delete Golden Monkey' }).click()
  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect(catalog.getByText('Golden Monkey', { exact: true })).toHaveCount(0)
  releaseRefresh()
  await page.waitForTimeout(100)
  await expect(catalog.getByText('Golden Monkey', { exact: true })).toHaveCount(0)
})
