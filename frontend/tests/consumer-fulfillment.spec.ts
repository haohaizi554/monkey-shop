import { expect, test, type Page, type Route } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

type ApiHandler = (route: Route, pathname: string) => Promise<boolean>

const SNOWFLAKE_ID = '338329504114688001'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'fulfillment-test' }
}

async function fulfillOk(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(ok(data)),
  })
}

async function installFulfillmentMocks(page: Page, handleApi: ApiHandler) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })

  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (await handleApi(route, pathname)) {
      return
    }

    if (pathname === '/users/me') {
      await fulfillOk(route, {
        isLogin: true,
        identity: 'USER',
        username: 'member',
        passwordChangeRequired: false,
      })
      return
    }
    if (pathname === '/tracking/events') {
      await fulfillOk(route, { id: 1, eventType: 'PAGE_VIEW' })
      return
    }
    await fulfillOk(route, [])
  })

  await page.route('**/images/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 3"><rect width="4" height="3" fill="#d8dee8"/></svg>',
    })
  })
}

function order(id: number, status: string, productName: string) {
  return {
    id,
    orderNo: `ORDER-${id}`,
    userId: 7,
    buyerName: 'Member',
    productId: id,
    productName,
    productImage: `/images/order-${id}.svg`,
    price: '288.00',
    receiverName: 'Member',
    receiverPhone: '13800138000',
    addressSnapshot: 'Hangzhou, Zhejiang',
    status,
    createTime: '2026-07-12T08:00:00Z',
    shippingTime: '2026-07-12T10:00:00Z',
  }
}

test('orders localize fulfillment states and confirm a single return request', async ({ page }) => {
  let returnCalls = 0
  let releaseReturn!: () => void
  const returnGate = new Promise<void>((resolve) => {
    releaseReturn = resolve
  })
  const orders = [
    order(101, 'PENDING_PAYMENT', 'Pending monkey'),
    order(102, 'IN_TRANSIT', 'Travelling monkey'),
    order(103, 'COMPLETED', 'Completed monkey'),
    order(104, 'PAID', 'Paid monkey'),
    order(105, 'RETURN_APPROVED', 'Returning monkey'),
  ]

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/orders/my') {
      await fulfillOk(route, {
        content: orders,
        page: 0,
        size: 100,
        totalElements: orders.length,
        totalPages: 1,
        first: true,
        last: true,
      })
      return true
    }
    if (pathname === '/orders/return/apply/103') {
      returnCalls += 1
      await returnGate
      await fulfillOk(route, order(103, 'RETURN_REQUESTED', 'Completed monkey'))
      return true
    }
    return false
  })

  await page.goto('/orders')

  await expect(page.locator('.page-header')).toContainText('Orders')
  await expect(page.locator('.order-status-timeline')).toHaveCount(0)
  await expect(page.getByText('Awaiting payment', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('In transit', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('Completed', { exact: true }).first()).toBeVisible()
  await expect(page.locator('body')).not.toContainText(/PENDING_PAYMENT|IN_TRANSIT|COMPLETED/)

  const pendingOrder = page.locator('.order-row').filter({ hasText: 'Pending monkey' })
  await expect(pendingOrder.getByRole('button', { name: 'Payment', exact: true })).toBeVisible()
  await expect(pendingOrder.getByRole('button', { name: 'Ship', exact: true })).toHaveCount(0)
  const paidOrder = page.locator('.order-row').filter({ hasText: 'Paid monkey' })
  await expect(paidOrder.getByRole('button', { name: 'Ship', exact: true })).toHaveCount(0)
  const returningOrder = page.locator('.order-row').filter({ hasText: 'Returning monkey' })
  await expect(
    returningOrder.getByRole('button', { name: 'Ship return', exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('button', { name: 'Approve return', exact: true })).toHaveCount(0)

  const completedOrder = page.locator('.order-row').filter({ hasText: 'Completed monkey' })
  await completedOrder.getByRole('button', { name: 'View order details ORDER-103' }).click()
  await expect(completedOrder.locator('.order-status-timeline')).toBeVisible()
  const returnButton = completedOrder.getByRole('button', { name: 'Return', exact: true })
  await returnButton.click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expect(returnCalls).toBe(0)

  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect.poll(() => returnCalls).toBe(1)
  await expect(returnButton).toBeDisabled()
  await returnButton.evaluate((button) => (button as HTMLButtonElement).click())
  expect(returnCalls).toBe(1)

  releaseReturn()
  await expect(returnButton).toBeEnabled()

  await page.getByRole('button', { name: 'Switch language' }).click()
  await expect(page.getByText('\u5f85\u652f\u4ed8', { exact: true }).first()).toBeVisible()
})

test('order details load shipment batches lazily and receive one batch at a time', async ({
  page,
}) => {
  let shipmentReads = 0
  let receivedShipmentId = 0
  const shippedOrder = order(102, 'SHIPPED', 'Travelling monkey')
  const shipment = {
    id: 501,
    orderId: 102,
    shipmentNo: 'SHIP-501',
    carrier: 'SF',
    trackingNo: 'SF-TRACK-501',
    status: 'SHIPPED',
    shippedAt: '2026-07-12T10:00:00Z',
    lines: [{ skuId: 102, productName: 'Travelling monkey', quantity: 1 }],
  }

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/orders/my') {
      await fulfillOk(route, {
        content: [shippedOrder],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      })
      return true
    }
    if (pathname === '/orders/102/shipments') {
      shipmentReads += 1
      await fulfillOk(route, [shipment])
      return true
    }
    if (pathname === '/orders/shipments/receive/501') {
      receivedShipmentId = 501
      await fulfillOk(route, {
        ...shipment,
        status: 'RECEIVED',
        receivedAt: '2026-07-12T12:00:00Z',
      })
      return true
    }
    return false
  })

  await page.goto('/orders')
  await expect.poll(() => shipmentReads).toBe(0)
  await page.getByRole('button', { name: 'View order details ORDER-102' }).click()
  await expect.poll(() => shipmentReads).toBe(1)
  const shipmentRow = page.locator('.order-shipment').filter({ hasText: 'SF-TRACK-501' })
  await expect(shipmentRow).toContainText('Travelling monkey')
  await shipmentRow.getByRole('button', { name: 'Receive shipment', exact: true }).click()
  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect.poll(() => receivedShipmentId).toBe(501)
})

test('payment refund locks lookup controls and keeps the failure local', async ({ page }) => {
  let refundCalls = 0
  let releaseRefund!: () => void
  const refundGate = new Promise<void>((resolve) => {
    releaseRefund = resolve
  })
  const payment = {
    id: 11,
    paymentNo: 'PAY-101',
    orderId: 101,
    userId: 7,
    method: 'WECHAT',
    amount: '288.00',
    paidAmount: '288.00',
    refundedAmount: '0.00',
    status: 'PENDING',
    createTime: '2026-07-12T08:05:00Z',
  }

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/payments/orders/101') {
      await fulfillOk(route, payment)
      return true
    }
    if (pathname === '/payments/refund') {
      refundCalls += 1
      await refundGate
      await route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'ledger exploded', status: 500 }),
      })
      return true
    }
    return false
  })

  await page.goto('/payment/101')

  await expect(page.locator('.page-header')).toContainText('Payment')
  await expect(page.getByText('Pending', { exact: true })).toBeVisible()
  await expect(page.locator('body')).not.toContainText(/\bPENDING\b/)

  const refundForm = page.locator('.refund-task')
  await refundForm.getByRole('spinbutton').fill('50')
  const refundButton = refundForm.getByRole('button', { name: 'Refund', exact: true })
  await refundButton.click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expect(refundCalls).toBe(0)
  await page.getByRole('button', { name: 'OK', exact: true }).click()

  await expect.poll(() => refundCalls).toBe(1)
  await expect(refundButton).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Submit payment', exact: true })).toBeHidden()
  await expect(page.getByLabel('TOTP code')).toBeHidden()
  await expect(page.getByLabel('Order ID')).toBeDisabled()
  await expect(refundForm.getByRole('spinbutton')).toBeDisabled()
  await expect(refundForm.getByLabel('Refund reason')).toBeDisabled()
  await refundButton.evaluate((button) => (button as HTMLButtonElement).click())
  expect(refundCalls).toBe(1)

  releaseRefund()
  await expect(refundForm.locator('.task-error')).toContainText('Unable to submit refund')
  await expect(page.getByRole('button', { name: 'Submit payment', exact: true })).toBeHidden()
  await expect(page.locator('body')).not.toContainText('ledger exploded')
})

test('payment creation freezes refund and lookup controls until it settles', async ({ page }) => {
  let createCalls = 0
  let releaseCreate!: () => void
  const createGate = new Promise<void>((resolve) => {
    releaseCreate = resolve
  })
  const payment = {
    id: 11,
    paymentNo: 'PAY-101',
    orderId: 101,
    userId: 7,
    method: 'WECHAT',
    amount: '288.00',
    paidAmount: '288.00',
    refundedAmount: '0.00',
    status: 'PAID',
    createTime: '2026-07-12T08:05:00Z',
  }
  let paymentCreated = false

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/payments/orders/101') {
      await fulfillOk(
        route,
        paymentCreated ? payment : { ...payment, status: 'FAILED', paidAmount: '0.00' },
      )
      return true
    }
    if (pathname === '/payments/pay') {
      createCalls += 1
      await createGate
      paymentCreated = true
      await fulfillOk(route, { ...payment, paymentNo: 'PAY-NEW' })
      return true
    }
    return false
  })

  await page.goto('/payment/101')
  const refundForm = page.locator('.refund-task')
  await expect(refundForm).toHaveCount(0)
  await page.getByRole('button', { name: 'Submit payment', exact: true }).click()
  await expect.poll(() => createCalls).toBe(1)

  await expect(page.getByLabel('Order ID')).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Search', exact: true })).toBeDisabled()
  await expect(page.getByLabel('TOTP code')).toBeDisabled()
  await expect(refundForm).toHaveCount(0)

  releaseCreate()
  await expect(page.getByRole('button', { name: 'Submit payment', exact: true })).toBeHidden()
  await expect(refundForm).toBeVisible()
  await expect(refundForm.getByRole('spinbutton')).toBeEnabled()
})

test('refund confirmation freezes the selected payment and lookup controls', async ({ page }) => {
  let refundedPaymentNo = ''
  const payment = {
    id: 11,
    paymentNo: 'PAY-101',
    orderId: 101,
    userId: 7,
    method: 'WECHAT',
    amount: '288.00',
    paidAmount: '288.00',
    refundedAmount: '0.00',
    status: 'PAID',
    createTime: '2026-07-12T08:05:00Z',
  }

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/payments/orders/101') {
      await fulfillOk(route, payment)
      return true
    }
    if (pathname === '/payments/refund') {
      refundedPaymentNo = (route.request().postDataJSON() as { paymentNo: string }).paymentNo
      await fulfillOk(route, {
        ledgerId: 1,
        paymentNo: refundedPaymentNo,
        amount: '20.00',
        refundedAmount: '20.00',
        paymentStatus: 'PARTIALLY_REFUNDED',
        ledgerStatus: 'SUCCESS',
        createTime: '2026-07-12T09:00:00Z',
      })
      return true
    }
    return false
  })

  await page.goto('/payment/101')
  const refundForm = page.locator('.refund-task')
  await refundForm.getByRole('spinbutton').fill('20')
  await refundForm.getByRole('button', { name: 'Refund', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()

  const lookup = page.locator('.lookup-task')
  await expect(lookup.getByRole('spinbutton', { name: 'Order ID' })).toBeDisabled()
  await expect(lookup.getByRole('button', { name: 'Search', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: 'OK', exact: true }).click()
  await expect.poll(() => refundedPaymentNo).toBe('PAY-101')
})

test('a failed refund retry reuses the same business idempotency key', async ({ page }) => {
  const keys: string[] = []
  let calls = 0
  const payment = {
    id: 11,
    paymentNo: 'PAY-101',
    orderId: 101,
    userId: 7,
    method: 'WECHAT',
    amount: '288.00',
    paidAmount: '288.00',
    refundedAmount: '0.00',
    status: 'PAID',
    createTime: '2026-07-12T08:05:00Z',
  }

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/payments/orders/101') {
      await fulfillOk(route, payment)
      return true
    }
    if (pathname === '/payments/refund') {
      calls += 1
      keys.push((await route.request().allHeaders())['idempotency-key'] ?? '')
      if (calls === 1) {
        await route.fulfill({
          status: 503,
          contentType: 'application/problem+json',
          body: JSON.stringify({ title: 'response lost', status: 503 }),
        })
      } else {
        await fulfillOk(route, {
          ledgerId: 1,
          paymentNo: 'PAY-101',
          amount: '20.00',
          refundedAmount: '20.00',
          paymentStatus: 'PARTIALLY_REFUNDED',
          ledgerStatus: 'SUCCESS',
          createTime: '2026-07-12T09:00:00Z',
        })
      }
      return true
    }
    return false
  })

  await page.goto('/payment/101')
  const refundForm = page.locator('.refund-task')
  await refundForm.getByRole('spinbutton').fill('20')
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await refundForm.getByRole('button', { name: 'Refund', exact: true }).click()
    const confirmation = page.locator('.el-message-box:visible').last()
    await confirmation.getByRole('button', { name: 'OK', exact: true }).click()
    await expect(confirmation).toBeHidden()
    await expect.poll(() => calls).toBe(attempt + 1)
    if (attempt === 0) {
      await expect(refundForm.getByRole('button', { name: 'Refund', exact: true })).toBeEnabled()
    }
  }

  expect(keys[0]).not.toBe('')
  expect(keys[1]).toBe(keys[0])
})

test('logistics localizes tracking and isolates quote errors from shipment data', async ({
  page,
}) => {
  let quoteCalls = 0
  const tracking = {
    id: 21,
    trackingNo: 'SF-101',
    orderId: 101,
    userId: 7,
    carrier: 'SF',
    status: 'IN_TRANSIT',
    province: 'Zhejiang',
    city: 'Hangzhou',
    district: 'Xihu',
    detailSummary: 'Wenyi Road',
    freightAmount: '12.00',
    etaHours: 12,
    pickedUpAt: '2026-07-12T09:00:00Z',
    inTransitAt: '2026-07-12T10:00:00Z',
    createTime: '2026-07-12T08:30:00Z',
    updateTime: '2026-07-12T10:00:00Z',
    events: [
      {
        id: 31,
        eventType: 'TRANSIT',
        fromStatus: 'PICKED_UP',
        toStatus: 'IN_TRANSIT',
        eventId: 'event-31',
        eventTime: '2026-07-12T10:00:00Z',
        location: 'Hangzhou hub',
      },
    ],
  }

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/logistics/orders/101') {
      await fulfillOk(route, tracking)
      return true
    }
    if (pathname === '/logistics/freight/quote') {
      quoteCalls += 1
      await route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'carrier stack trace', status: 500 }),
      })
      return true
    }
    return false
  })

  await page.goto('/logistics/101')

  await expect(page.locator('.page-header')).toContainText('Logistics')
  await expect(page.getByText('In transit', { exact: true }).first()).toBeVisible()
  await expect(page.locator('body')).not.toContainText(/IN_TRANSIT|PICKED_UP|TRANSIT/)
  const quoteButton = page.getByRole('button', { name: 'Quote', exact: true })
  await quoteButton.click()

  await expect.poll(() => quoteCalls).toBe(1)
  await expect(page.locator('.quote-task .task-error')).toContainText('Unable to quote freight')
  await expect(page.getByText('SF-101', { exact: true })).toBeVisible()
  await expect(page.locator('body')).not.toContainText('carrier stack trace')
  await page.getByLabel('Full address').fill('100 Wenyi Road')
  await expect(page.getByRole('button', { name: 'Parse address', exact: true })).toBeEnabled()
})

test('logistics keeps a Snowflake order ID exact from route and text input to request URL', async ({
  page,
}) => {
  const lookups: string[] = []
  const tracking = {
    id: SNOWFLAKE_ID,
    trackingNo: 'SF-SNOWFLAKE',
    orderId: SNOWFLAKE_ID,
    userId: '7',
    carrier: 'SF',
    status: 'IN_TRANSIT',
    province: 'Zhejiang',
    city: 'Hangzhou',
    district: 'Xihu',
    freightAmount: '12.00',
    etaHours: 12,
    createTime: '2026-07-12T08:30:00Z',
    updateTime: '2026-07-12T10:00:00Z',
    events: [],
  }

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname.startsWith('/logistics/orders/')) {
      lookups.push(pathname)
      await fulfillOk(route, tracking)
      return true
    }
    return false
  })

  await page.goto(`/logistics/${SNOWFLAKE_ID}`)

  await expect.poll(() => lookups).toEqual([`/logistics/orders/${SNOWFLAKE_ID}`])
  const orderIdInput = page.getByRole('textbox', { name: 'Order ID' })
  await expect(orderIdInput).toHaveValue(SNOWFLAKE_ID)
  await expect(page.getByRole('spinbutton', { name: 'Order ID' })).toHaveCount(0)

  await orderIdInput.fill(SNOWFLAKE_ID)
  await page
    .locator('.lookup-form')
    .first()
    .getByRole('button', { name: 'Search', exact: true })
    .click()
  await expect
    .poll(() => lookups)
    .toEqual([`/logistics/orders/${SNOWFLAKE_ID}`, `/logistics/orders/${SNOWFLAKE_ID}`])
})

test('address parsing freezes its input snapshot until the response is applied', async ({
  page,
}) => {
  let releaseParse!: () => void
  const parseGate = new Promise<void>((resolve) => {
    releaseParse = resolve
  })

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/logistics/orders/101') {
      await fulfillOk(route, null)
      return true
    }
    if (pathname === '/logistics/address/parse') {
      await parseGate
      await fulfillOk(route, {
        province: 'Zhejiang',
        city: 'Hangzhou',
        district: 'Xihu',
        detail: '100 Wenyi Road',
      })
      return true
    }
    return false
  })

  await page.goto('/logistics/101')
  await page.getByLabel('Full address').fill('100 Wenyi Road')
  await page.getByRole('button', { name: 'Parse address', exact: true }).click()

  await expect(page.getByLabel('Full address')).toBeDisabled()
  await expect(page.getByPlaceholder('Province')).toBeDisabled()
  releaseParse()
  await expect(page.getByPlaceholder('Province')).toHaveValue('Zhejiang')
})

test('consumer logistics never exposes shipment creation or webhook simulation', async ({
  page,
}) => {
  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/logistics/orders/101') {
      await fulfillOk(route, null)
      return true
    }
    return false
  })

  await page.goto('/logistics/101')
  await expect(page.getByRole('button', { name: 'Create shipment', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Push webhook', exact: true })).toHaveCount(0)
  await expect(page.locator('img.mascot-state[data-pose="package"]')).toBeVisible()
})

test('review upload leaves content editable and review submission is single-flight', async ({
  page,
}) => {
  let uploadCalls = 0
  let reviewCalls = 0
  let releaseUpload!: () => void
  let releaseReview!: () => void
  const uploadGate = new Promise<void>((resolve) => {
    releaseUpload = resolve
  })
  const reviewGate = new Promise<void>((resolve) => {
    releaseReview = resolve
  })

  await installFulfillmentMocks(page, async (route, pathname) => {
    if (pathname === '/orders/review/101' && route.request().method() === 'GET') {
      await fulfillOk(route, [])
      return true
    }
    if (pathname === '/uploads') {
      uploadCalls += 1
      await uploadGate
      await fulfillOk(route, {
        path: '/images/review.png',
        cropped: false,
        variants: {},
      })
      return true
    }
    if (pathname === '/orders/review/101' && route.request().method() === 'POST') {
      reviewCalls += 1
      await reviewGate
      await fulfillOk(route, {
        id: 41,
        orderId: 101,
        userId: 7,
        skuId: 101,
        rating: 5,
        content: 'Careful delivery',
        imageUrls: ['/images/review.png'],
        anonymous: false,
        createTime: '2026-07-12T11:00:00Z',
      })
      return true
    }
    if (pathname === '/orders/my') {
      await fulfillOk(route, [])
      return true
    }
    return false
  })

  await page.goto('/orders/101/review')

  await expect(page.locator('.page-header')).toContainText('Review')
  await expect(page.locator('img.mascot-state[data-pose="clipboard"]')).toBeVisible()
  const content = page.getByLabel('Share the fulfillment and product experience')
  await page.locator('#review-image-upload').setInputFiles({
    name: 'review.png',
    mimeType: 'image/png',
    buffer: Buffer.from('review-image'),
  })
  await expect.poll(() => uploadCalls).toBe(1)
  await expect(page.locator('.upload-progress')).toBeVisible()
  await expect(content).toBeEnabled()
  await content.fill('Careful delivery')

  releaseUpload()
  await expect(page.locator('.upload-progress')).toBeHidden()

  const submit = page.getByRole('button', { name: 'Submit review', exact: true })
  await submit.evaluate((button) => {
    ;(button as HTMLButtonElement).click()
    ;(button as HTMLButtonElement).click()
  })
  await expect.poll(() => reviewCalls).toBe(1)
  await expect(submit).toBeDisabled()

  releaseReview()
  await expect(page).toHaveURL(/\/orders$/)
})

test('review keeps Snowflake route and sku query IDs exact through selection and request body', async ({
  page,
}) => {
  const orderReads: string[] = []
  const reviewReads: string[] = []
  const reviewWrites: Array<{ pathname: string; body: unknown }> = []
  const reviewOrder = {
    ...order(101, 'COMPLETED', 'Fallback product'),
    id: SNOWFLAKE_ID,
    orderNo: `ORDER-${SNOWFLAKE_ID}`,
    lines: [
      {
        checkoutLineId: 1,
        skuId: 101,
        productName: 'Small SKU',
        productImage: '/images/small.svg',
        quantity: 1,
        unitPrice: '88.00',
        originalAmount: '88.00',
        discountAmount: '0.00',
        payableAmount: '88.00',
        couponCodes: [],
      },
      {
        checkoutLineId: 2,
        skuId: SNOWFLAKE_ID,
        productName: 'Snowflake SKU',
        productImage: '/images/snowflake.svg',
        quantity: 1,
        unitPrice: '188.00',
        originalAmount: '188.00',
        discountAmount: '0.00',
        payableAmount: '188.00',
        couponCodes: [],
      },
    ],
  }

  await installFulfillmentMocks(page, async (route, pathname) => {
    const method = route.request().method()
    if (pathname.startsWith('/orders/review/') && method === 'GET') {
      reviewReads.push(pathname)
      await fulfillOk(route, [])
      return true
    }
    if (pathname.startsWith('/orders/review/') && method === 'POST') {
      reviewWrites.push({ pathname, body: route.request().postDataJSON() })
      await fulfillOk(route, {
        id: SNOWFLAKE_ID,
        orderId: SNOWFLAKE_ID,
        userId: '7',
        skuId: SNOWFLAKE_ID,
        rating: 5,
        imageUrls: [],
        anonymous: false,
        createTime: '2026-07-12T11:00:00Z',
      })
      return true
    }
    if (/^\/orders\/[^/]+$/.test(pathname) && method === 'GET') {
      orderReads.push(pathname)
      await fulfillOk(route, reviewOrder)
      return true
    }
    return false
  })

  await page.goto(`/orders/${SNOWFLAKE_ID}/review?skuId=${SNOWFLAKE_ID}`)
  const submit = page.getByRole('button', { name: 'Submit review', exact: true })
  await expect(submit).toBeEnabled()
  await submit.click()
  await expect.poll(() => reviewWrites.length).toBe(1)

  expect(orderReads).toEqual([`/orders/${SNOWFLAKE_ID}`])
  expect(reviewReads.every((pathname) => pathname === `/orders/review/${SNOWFLAKE_ID}`)).toBe(true)
  expect(reviewWrites).toEqual([
    {
      pathname: `/orders/review/${SNOWFLAKE_ID}`,
      body: expect.objectContaining({ skuId: SNOWFLAKE_ID }),
    },
  ])
})

test('fulfillment views use shared surfaces and app feedback only', async () => {
  const viewFiles = ['OrdersView.vue', 'PaymentView.vue', 'LogisticsView.vue', 'ReviewView.vue']

  for (const file of viewFiles) {
    const source = await readFile(resolve(process.cwd(), 'src/views', file), 'utf8')
    expect(source, file).toContain('PageHeader')
    expect(source, file).toContain('AsyncStateView')
    expect(source, file).toContain('DataTableShell')
    expect(source, file).toContain('useNotify')
    expect(source, file).not.toContain('ElMessage')
    expect(source, file).not.toContain('error.message')
  }

  const timeline = await readFile(
    resolve(process.cwd(), 'src/components/order/OrderStatusTimeline.vue'),
    'utf8',
  )
  expect(timeline).toContain('logisticsEvents')
  expect(timeline).toContain('useI18n')
})
