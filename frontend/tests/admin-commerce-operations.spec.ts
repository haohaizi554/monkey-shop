import { expect, test, type Page, type Route } from '@playwright/test'

const SNOWFLAKE_ID = '338329504114688001'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'admin-commerce-test' }
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

async function fulfillOk(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(ok(data)),
  })
}

function order(id: number, status: string, orderNo: string) {
  return {
    id,
    orderNo,
    userId: 7,
    buyerName: 'Alice',
    productId: 101,
    productName: 'Golden Monkey',
    productImage: '',
    price: '128.00',
    receiverName: 'Alice',
    receiverPhone: '138****0001',
    addressSnapshot: 'East Road',
    status,
    createTime: '2026-07-12T08:00:00',
  }
}

function payment(orderId: number) {
  return {
    id: 700 + orderId,
    paymentNo: `PAY-${orderId}`,
    orderId,
    userId: 7,
    method: 'WECHAT',
    amount: '128.00',
    paidAmount: '128.00',
    refundedAmount: '0.00',
    status: 'PAID',
    providerTradeNo: `PROVIDER-${orderId}`,
    paymentUrl: '',
    paidAt: '2026-07-12T09:00:00',
    createTime: '2026-07-12T08:30:00',
  }
}

const orders = [
  order(11, 'PAID', 'ORDER-PAID'),
  order(12, 'RETURN_REQUESTED', 'ORDER-RETURN'),
  order(13, 'RETURN_SHIPPING', 'ORDER-REFUND'),
  order(14, 'COMPLETED', 'ORDER-COMPLETE'),
]

async function installAdminMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await fulfillOk(route, { isLogin: true, identity: 'ADMIN', username: 'admin' })
      return
    }
    if (pathname === '/tracking/events') {
      await fulfillOk(route, { id: 1, eventType: 'PAGE_VIEW' })
      return
    }
    if (pathname === '/orders/all') {
      await fulfillOk(route, pageResult(orders))
      return
    }
    if (pathname === '/orders/admin/11/shipments') {
      await fulfillOk(route, [])
      return
    }
    await fulfillOk(route, [])
  })
}

test('commerce operations are four linked admin workspaces without callback simulation', async ({
  page,
}) => {
  await installAdminMocks(page)

  for (const [path, heading] of [
    ['/admin/orders', 'Order operations'],
    ['/admin/returns', 'Return operations'],
    ['/admin/payments', 'Payment operations'],
    ['/admin/logistics', 'Logistics operations'],
  ] as const) {
    await page.goto(path)
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    await expect(page.locator('body')).not.toContainText(/Push webhook|Simulate callback/)
  }
})

test('admin orders request one bounded server page at a time', async ({ page }) => {
  const queries: Array<{ page: string | null; size: string | null }> = []
  await installAdminMocks(page)
  await page.route('**/api/v1/orders/all**', async (route) => {
    const url = new URL(route.request().url())
    const pageNumber = Number(url.searchParams.get('page') ?? 0)
    queries.push({ page: url.searchParams.get('page'), size: url.searchParams.get('size') })
    await fulfillOk(route, {
      content: [{ ...orders[0], id: 100 + pageNumber, orderNo: `ADMIN-PAGE-${pageNumber + 1}` }],
      page: pageNumber,
      size: 25,
      totalElements: 51,
      totalPages: 3,
      first: pageNumber === 0,
      last: pageNumber === 2,
    })
  })

  await page.goto('/admin/orders')
  await expect(page.getByText('ADMIN-PAGE-1', { exact: true })).toBeVisible()
  expect(queries).toEqual([{ page: '0', size: '25' }])

  await page.locator('.admin-orders-pagination .btn-next').click()
  await expect.poll(() => queries.at(-1)?.page).toBe('1')
  await expect(page.getByText('ADMIN-PAGE-2', { exact: true })).toBeVisible()
})

test('returns and logistics declare bounded status filters to the server', async ({ page }) => {
  const queries: Array<{
    path: string
    page: string | null
    size: string | null
    status: string | null
  }> = []
  await installAdminMocks(page)
  await page.route('**/api/v1/orders/all**', async (route) => {
    const url = new URL(route.request().url())
    queries.push({
      path: url.pathname,
      page: url.searchParams.get('page'),
      size: url.searchParams.get('size'),
      status: url.searchParams.get('status'),
    })
    const statuses = new Set((url.searchParams.get('status') ?? '').split(','))
    await fulfillOk(route, pageResult(orders.filter((candidate) => statuses.has(candidate.status))))
  })

  await page.goto('/admin/returns')
  await expect
    .poll(() => queries.at(-1)?.status)
    .toBe('RETURN_REQUESTED,WAITING_RETURN_SHIPMENT,RETURN_SHIPPING')

  await page.goto('/admin/logistics')
  await expect
    .poll(() => queries.at(-1)?.status)
    .toBe('PAID,PARTIALLY_SHIPPED,SHIPPED,PARTIALLY_RECEIVED,COMPLETED')
  expect(queries.map(({ page: pageNumber, size }) => ({ page: pageNumber, size }))).toEqual([
    { page: '0', size: '25' },
    { page: '0', size: '25' },
  ])
})

test('refund stays disabled while a different order payment is loading', async ({ page }) => {
  let lookupCalls = 0
  let releaseLookup!: () => void
  const lookupGate = new Promise<void>((resolve) => {
    releaseLookup = resolve
  })

  await installAdminMocks(page)
  await page.route('**/api/v1/payments/admin/orders/11', (route) => fulfillOk(route, payment(11)))
  await page.route('**/api/v1/payments/admin/orders/12', async (route) => {
    lookupCalls += 1
    await lookupGate
    await fulfillOk(route, payment(12))
  })

  await page.goto('/admin/payments?orderId=11')
  const refundButton = page.getByRole('button', { name: 'Issue refund', exact: true })
  await expect(refundButton).toBeEnabled()

  await page.getByRole('textbox', { name: 'Order ID' }).fill('12')
  await page.getByRole('button', { name: 'Load payment', exact: true }).click()
  await expect.poll(() => lookupCalls).toBe(1)
  await expect(refundButton).toBeDisabled()

  releaseLookup()
  await expect(page.getByText('PAY-12', { exact: true })).toBeVisible()
})

test('payment lookup keeps a Snowflake order ID exact in query, input, URL, and comparison', async ({
  page,
}) => {
  const lookups: string[] = []

  await installAdminMocks(page)
  await page.route('**/api/v1/payments/admin/orders/*', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    const requestedId = pathname.split('/').at(-1) ?? ''
    lookups.push(pathname)
    await fulfillOk(route, {
      id: `PAYMENT-${requestedId}`,
      paymentNo: `PAY-${requestedId}`,
      orderId: requestedId,
      userId: '7',
      method: 'WECHAT',
      amount: '128.00',
      paidAmount: '128.00',
      refundedAmount: '0.00',
      status: 'PAID',
      providerTradeNo: `PROVIDER-${requestedId}`,
      paidAt: '2026-07-12T09:00:00',
      createTime: '2026-07-12T08:30:00',
    })
  })

  await page.goto(`/admin/payments?orderId=${SNOWFLAKE_ID}`)

  await expect.poll(() => lookups).toEqual([`/payments/admin/orders/${SNOWFLAKE_ID}`])
  const orderIdInput = page.getByRole('textbox', { name: 'Order ID' })
  await expect(orderIdInput).toHaveValue(SNOWFLAKE_ID)
  await expect(page.getByRole('spinbutton', { name: 'Order ID' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Issue refund', exact: true })).toBeEnabled()

  await orderIdInput.fill('12')
  await page.getByRole('button', { name: 'Load payment', exact: true }).click()
  await expect(page).toHaveURL(/orderId=12/)
  await expect
    .poll(() => lookups)
    .toEqual([`/payments/admin/orders/${SNOWFLAKE_ID}`, '/payments/admin/orders/12'])
})

test('logistics creation is scoped to one paid order and blocks duplicate submission', async ({
  page,
}) => {
  let createCalls = 0
  let releaseCreate!: () => void
  const createGate = new Promise<void>((resolve) => {
    releaseCreate = resolve
  })
  const createdShipment = {
    id: 501,
    orderId: 11,
    shipmentNo: 'SHIP-501',
    carrier: 'SF',
    trackingNo: 'SF-NEW-11',
    status: 'SHIPPED',
    shippedAt: '2026-07-12T10:00:00',
    lines: [{ skuId: 101, productName: 'Golden Monkey', quantity: 1 }],
  }

  await installAdminMocks(page)
  await page.route('**/api/v1/orders/shipments/11', async (route) => {
    createCalls += 1
    expect(route.request().postDataJSON()).toEqual({
      carrier: 'SF',
      trackingNo: 'SF-NEW-11',
      lines: [{ skuId: 101, productName: 'Golden Monkey', quantity: 1, orderedQuantity: 1 }],
    })
    await createGate
    await fulfillOk(route, createdShipment)
  })

  await page.goto('/admin/logistics?orderId=11')
  await page.getByLabel('Tracking number').fill('SF-NEW-11')
  const createButton = page.getByRole('button', { name: 'Create shipment', exact: true })
  await createButton.click()
  await expect.poll(() => createCalls).toBe(1)
  await expect(createButton).toBeDisabled()
  await createButton.evaluate((button) => (button as HTMLButtonElement).click())
  expect(createCalls).toBe(1)
  releaseCreate()
  await expect(page.getByText('SF-NEW-11', { exact: true })).toBeVisible()
})

test('return approval keeps pending state scoped to the selected row', async ({ page }) => {
  let approveCalls = 0
  let releaseApprove!: () => void
  const approveGate = new Promise<void>((resolve) => {
    releaseApprove = resolve
  })
  await installAdminMocks(page)
  await page.route('**/api/v1/orders/return/approve/12', async (route) => {
    approveCalls += 1
    await approveGate
    await fulfillOk(route, { ...orders[1], status: 'WAITING_RETURN_SHIPMENT' })
  })

  await page.goto('/admin/returns')
  const requested = page.locator('[data-order-id="12"]')
  const refunding = page.locator('[data-order-id="13"]')
  await requested.getByRole('button', { name: 'Approve return', exact: true }).click()
  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect.poll(() => approveCalls).toBe(1)
  await expect(
    requested.getByRole('button', { name: 'Approve return', exact: true }),
  ).toBeDisabled()
  await expect(refunding.getByRole('button', { name: 'Refund', exact: true })).toBeEnabled()
  releaseApprove()
  await expect(requested).toContainText('Awaiting return shipment')
})
