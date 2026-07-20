import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'admin-operations-test' }
}

function pageResult<T>(content: T[]) {
  return {
    content,
    page: 0,
    size: 100,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
  }
}

const products = [
  {
    id: 1,
    name: 'Golden Monkey',
    breed: 'Golden',
    price: '128.00',
    imageUrl: '/images/default_product.jpg',
    stock: 8,
  },
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
      data = pageResult(products)
    } else if (pathname === '/orders/all') {
      data = pageResult(orders)
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
  const productImageBox = await page.getByRole('img', { name: 'Golden Monkey' }).boundingBox()
  expect(productImageBox?.width).toBeLessThanOrEqual(64)
  expect(productImageBox?.height).toBeLessThanOrEqual(56)
  expect(productImageBox?.height).toBeGreaterThan(32)

  await page.getByRole('button', { name: 'Delete Golden Monkey' }).click()
  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Delete Golden Monkey' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Delete Capuchin' })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()

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
  await page.route('**/api/v1/orders/all**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(pageResult([returnOrder]))),
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
  await page.route('**/api/v1/monkeys**', async (route) => {
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
      body: JSON.stringify(ok(pageResult(products))),
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

test('admin product dialog validates, uploads an image, confirms unsaved edits, and recovers after a failed delete', async ({
  page,
}) => {
  let createCalls = 0
  await installAdminMocks(page)
  await page.route('**/api/v1/uploads', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok({ path: '/uploads/river-monkey.webp', cropped: false })),
    })
  })
  await page.route('**/api/v1/monkeys/add', async (route) => {
    createCalls += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 3,
          name: 'River Monkey',
          breed: 'Tamarin',
          price: '88.00',
          imageUrl: '/uploads/river-monkey.webp',
          stock: 4,
        }),
      ),
    })
  })
  await page.route('**/api/v1/monkeys/2', async (route) => {
    await route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({ title: 'catalog unavailable', status: 503 }),
    })
  })

  await page.goto('/admin')
  await page.getByRole('button', { name: 'Create product' }).click()
  const dialog = page.getByRole('dialog', { name: 'Create product' })
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).toContainText('Product name is required')
  expect(createCalls).toBe(0)

  await dialog.getByLabel('Name').fill('River Monkey')
  await dialog.getByLabel('Breed').fill('Tamarin')
  await dialog.getByLabel('Price').fill('88')
  await dialog.getByLabel('Stock').fill('4')
  await dialog.locator('#product-image-input').setInputFiles({
    name: 'river-monkey.webp',
    mimeType: 'image/webp',
    buffer: Buffer.from('mock-image'),
  })
  await expect(dialog.getByRole('img', { name: 'River Monkey' })).toHaveAttribute(
    'src',
    '/uploads/river-monkey.webp',
  )
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Discard product changes?' })).toBeVisible()
  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect(dialog).toBeHidden()

  await page.getByRole('button', { name: 'Create product' }).click()
  await dialog.getByLabel('Name').fill('River Monkey')
  await dialog.getByLabel('Breed').fill('Tamarin')
  await dialog.getByLabel('Price').fill('88')
  await dialog.getByLabel('Stock').fill('4')
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByText('River Monkey', { exact: true })).toBeVisible()
  expect(createCalls).toBe(1)

  const deleteCapuchin = page.getByRole('button', { name: 'Delete Capuchin' })
  await deleteCapuchin.click()
  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect(
    page.locator('.app-feedback-item').filter({ hasText: 'Unable to delete product' }),
  ).toContainText('Unable to delete product')
  await expect(deleteCapuchin).toBeEnabled()
  await expect(page.getByText('Capuchin', { exact: true }).first()).toBeVisible()
})

test('admin edits an existing product once and updates only that product row', async ({ page }) => {
  let updateCalls = 0
  let releaseUpdate!: () => void
  const updateGate = new Promise<void>((resolve) => {
    releaseUpdate = resolve
  })
  const updatePayloads: unknown[] = []

  await installAdminMocks(page)
  await page.route('**/api/v1/monkeys/update', async (route) => {
    updateCalls += 1
    updatePayloads.push(route.request().postDataJSON())
    await updateGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          ...products[0],
          name: 'Golden Monkey Revised',
          stock: 11,
        }),
      ),
    })
  })

  await page.goto('/admin')
  await page.getByRole('button', { name: 'Edit Golden Monkey' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Edit product' })
  await dialog.getByLabel('Name').fill('Golden Monkey Revised')
  await dialog.getByLabel('Stock').fill('11')
  const save = dialog.getByRole('button', { name: 'Save', exact: true })
  await save.click()

  await expect(save).toBeDisabled()
  await expect.poll(() => updateCalls).toBe(1)
  expect(updatePayloads).toEqual([
    expect.objectContaining({
      id: 1,
      name: 'Golden Monkey Revised',
      stock: 11,
    }),
  ])
  await expect(page.getByText('Capuchin', { exact: true }).first()).toBeVisible()

  releaseUpdate()
  await expect(dialog).toBeHidden()
  await expect(page.getByText('Golden Monkey Revised', { exact: true }).first()).toBeVisible()
  await expect(
    page.locator('.product-table').getByText('Golden Monkey', { exact: true }),
  ).toHaveCount(0)
  await expect(page.getByText('Capuchin', { exact: true }).first()).toBeVisible()
})

test('admin ship action keeps pending scoped to its order and refreshes legal actions', async ({
  page,
}) => {
  let releaseShip!: () => void
  const shipGate = new Promise<void>((resolve) => {
    releaseShip = resolve
  })
  let shipCalls = 0
  const operationalOrders = [
    { ...orders[0], status: 'PAID' },
    { ...orders[1], status: 'PAID' },
    { ...orders[1], id: 13, orderNo: 'ORDER-COMPLETE', status: 'COMPLETED' },
  ]

  await installAdminMocks(page)
  await page.route('**/api/v1/orders/all**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(pageResult(operationalOrders))),
    })
  })
  await page.route('**/api/v1/orders/ship/11', async (route) => {
    shipCalls += 1
    await shipGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok({ ...operationalOrders[0], status: 'SHIPPED' })),
    })
  })

  await page.goto('/admin')
  const shipActions = page
    .locator('.order-table')
    .getByRole('button', { name: 'Ship', exact: true })
  await expect(shipActions).toHaveCount(2)
  await shipActions.first().click()

  await expect(shipActions.first()).toBeDisabled()
  await expect(shipActions.nth(1)).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
  await page.getByRole('textbox', { name: 'Trace ID' }).fill('ship-trace')
  await page.getByRole('button', { name: 'Search trace' }).click()
  await expect(page.getByRole('heading', { name: 'Order created' })).toBeVisible()
  expect(shipCalls).toBe(1)

  releaseShip()
  await expect(page.getByText('Shipped', { exact: true })).toBeVisible()
  await expect(shipActions).toHaveCount(1)
  await expect(page.getByText('ORDER-COMPLETE', { exact: true })).toBeVisible()
})
test('admin order actions stay legal and audit lookup has empty, error, and timeline states', async ({
  page,
}) => {
  await installAdminMocks(page)
  await page.route('**/api/v1/orders/all**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok(
          pageResult([
            { ...orders[0], status: 'PAID' },
            { ...orders[1], status: 'COMPLETED' },
          ]),
        ),
      ),
    })
  })
  await page.route('**/api/v1/stats/audit-trace**', async (route) => {
    const traceId = new URL(route.request().url()).searchParams.get('traceId')
    if (traceId === 'missing') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(ok([])),
      })
      return
    }
    if (traceId === 'broken') {
      await route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'audit unavailable', status: 503 }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok([
          {
            id: 'evt-1',
            eventType: 'ORDER_CREATED',
            userId: '7',
            description: 'Order created',
            createdAt: '2026-07-12T08:00:00',
            traceId,
          },
        ]),
      ),
    })
  })

  await page.goto('/admin')
  const orderTable = page.locator('.order-table')
  await expect(orderTable.getByRole('button', { name: 'Ship', exact: true })).toHaveCount(1)
  await expect(orderTable.getByText('ORDER-BETA', { exact: true })).toBeVisible()

  const traceInput = page.getByRole('textbox', { name: 'Trace ID' })
  const searchTrace = page.getByRole('button', { name: 'Search trace' })
  await traceInput.fill('missing')
  await searchTrace.click()
  await expect(page.locator('.async-state-view__empty')).toContainText('No audit events found')

  await traceInput.fill('broken')
  await searchTrace.click()
  await expect(page.locator('.async-state-view__error')).toContainText(
    'The request failed. Please try again later.',
  )

  await traceInput.fill('trace-9')
  await searchTrace.click()
  await expect(page.getByRole('heading', { name: 'Order created' })).toBeVisible()
  await expect(page.locator('.trace-timeline')).toContainText('trace trace-9')
  await expect(page.locator('.trace-timeline code').filter({ hasText: 'user 7' })).toContainText(
    'user 7',
  )
})
