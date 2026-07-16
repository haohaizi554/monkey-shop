import { expect, test, type Page } from '@playwright/test'

const cart = {
  userId: 7,
  items: [
    {
      skuId: 101,
      shopId: 11,
      productName: 'Curious Capuchin',
      unitPrice: '48.00',
      quantity: 2,
      selected: true,
      lineAmount: '96.00',
      updatedAt: '2026-07-12T00:00:00+08:00',
    },
    {
      skuId: 202,
      shopId: 22,
      productName: 'Gentle Macaque',
      unitPrice: '32.00',
      quantity: 1,
      selected: true,
      lineAmount: '32.00',
      updatedAt: '2026-07-12T00:00:00+08:00',
    },
  ],
  selectedQuantity: 3,
  selectedAmount: '128.00',
}

const addressPage = {
  content: [
    {
      id: 1,
      receiverName: 'Avery Chen',
      phone: '13800000001',
      detailAddress: 'No. 18 West Lake Road, Hangzhou',
      isDefault: 1,
    },
    {
      id: 2,
      receiverName: 'Jordan Lee',
      phone: '13800000002',
      detailAddress: 'No. 66 Huaihai Road, Shanghai',
      isDefault: 0,
    },
  ],
  page: 0,
  size: 100,
  totalElements: 2,
  totalPages: 1,
  first: true,
  last: true,
}

const checkoutPreview = {
  id: 1,
  checkoutNo: 'CO-PREVIEW-001',
  userId: 7,
  addressId: 1,
  originalAmount: '128.00',
  discountAmount: '8.00',
  payableAmount: '120.00',
  status: 'RESERVED',
  province: 'CN-ZJ',
  createdAt: '2026-07-12T00:00:00+08:00',
  subOrders: [
    {
      id: 11,
      shopId: 11,
      orderNo: 'SO-PREVIEW-001',
      originalAmount: '128.00',
      storeDiscountAmount: '3.00',
      platformDiscountAmount: '5.00',
      discountAmount: '8.00',
      payableAmount: '120.00',
      status: 'RESERVED',
      lines: [
        {
          id: 111,
          skuId: 101,
          shopId: 11,
          productName: 'Curious Capuchin',
          quantity: 2,
          unitPrice: '48.00',
          originalAmount: '96.00',
          discountAmount: '8.00',
          payableAmount: '88.00',
          couponCodes: ['SAVE-8'],
          reservationKey: 'preview-only',
        },
      ],
    },
  ],
}

const checkoutOk = {
  code: 'OK',
  message: 'ok',
  data: {
    id: 1,
    checkoutNo: 'CO-UI-001',
    userId: 7,
    addressId: 1,
    originalAmount: '128.00',
    discountAmount: '0.00',
    payableAmount: '128.00',
    status: 'CHECKED_OUT',
    province: 'CN-ZJ',
    createdAt: '2026-07-12T00:00:00+08:00',
    subOrders: [
      {
        ...checkoutPreview.subOrders[0],
        status: 'CHECKED_OUT',
        formalOrderId: 501,
      },
    ],
    orderIds: [501],
  },
}

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'cart-checkout-test' }
}

async function installConsumerMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')
    let data: unknown = []

    if (pathname === '/users/me') {
      data = {
        isLogin: true,
        identity: 'USER',
        username: 'cart-tester',
        passwordChangeRequired: false,
      }
    } else if (pathname === '/cart' && request.method() === 'GET') {
      data = cart
    } else if (pathname === '/addresses' && request.method() === 'GET') {
      data = addressPage
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

test.beforeEach(async ({ page }) => {
  await installConsumerMocks(page)
})

test('cart restores a failed quantity and limits pending state to that line action', async ({
  page,
}) => {
  let releaseUpdate!: () => void
  const updateGate = new Promise<void>((resolve) => {
    releaseUpdate = resolve
  })

  await page.route('**/api/v1/cart/items/101', async (route) => {
    if (route.request().method() !== 'PATCH') {
      await route.fallback()
      return
    }
    await updateGate
    await route.fulfill({
      status: 409,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        title: 'RAW_BACKEND_STOCK_FAILURE',
        detail: 'quantity rejected by inventory internals',
        status: 409,
      }),
    })
  })

  await page.goto('/cart')
  const firstRow = page
    .locator('.cart-table .el-table__row')
    .filter({ hasText: 'Curious Capuchin' })
  const secondRow = page.locator('.cart-table .el-table__row').filter({ hasText: 'Gentle Macaque' })
  const firstQuantity = firstRow.getByRole('spinbutton')
  const secondQuantity = secondRow.getByRole('spinbutton')
  const requestStarted = page.waitForRequest(
    (request) =>
      request.method() === 'PATCH' && new URL(request.url()).pathname.endsWith('/cart/items/101'),
  )

  await firstQuantity.fill('4')
  await firstQuantity.press('Enter')
  await requestStarted

  await expect(firstQuantity).toHaveValue('4')
  await expect(firstQuantity).toBeDisabled()
  await expect(firstRow.getByRole('switch')).toBeDisabled()
  await expect(firstRow.getByRole('button', { name: 'Delete Curious Capuchin' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeDisabled()
  await expect(secondQuantity).toBeEnabled()

  releaseUpdate()

  await expect(firstQuantity).toHaveValue('2')
  await expect(firstRow.getByRole('alert')).toContainText('Unable to update quantity')
  await expect(page.locator('body')).not.toContainText('RAW_BACKEND_STOCK_FAILURE')
  await expect(page.locator('body')).not.toContainText('quantity rejected by inventory internals')
  await expect(page.locator('.el-message')).toHaveCount(0)
})

test('cart serializes full-snapshot writes from different rows', async ({ page }) => {
  let releaseFirst!: () => void
  const firstGate = new Promise<void>((resolve) => {
    releaseFirst = resolve
  })
  let firstCalls = 0
  let secondCalls = 0

  await page.route('**/api/v1/cart/items/101', async (route) => {
    if (route.request().method() !== 'PATCH') {
      await route.fallback()
      return
    }
    firstCalls += 1
    await firstGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          ...cart,
          items: cart.items.map((item) => (item.skuId === 101 ? { ...item, quantity: 4 } : item)),
        }),
      ),
    })
  })
  await page.route('**/api/v1/cart/items/202', async (route) => {
    if (route.request().method() !== 'PATCH') {
      await route.fallback()
      return
    }
    secondCalls += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          ...cart,
          items: cart.items.map((item) =>
            item.skuId === 101
              ? { ...item, quantity: 4 }
              : item.skuId === 202
                ? { ...item, quantity: 3 }
                : item,
          ),
        }),
      ),
    })
  })

  await page.goto('/cart')
  const firstQuantity = page
    .locator('.cart-table .el-table__row')
    .filter({ hasText: 'Curious Capuchin' })
    .getByRole('spinbutton')
  const secondQuantity = page
    .locator('.cart-table .el-table__row')
    .filter({ hasText: 'Gentle Macaque' })
    .getByRole('spinbutton')

  await firstQuantity.fill('4')
  await firstQuantity.press('Enter')
  await expect.poll(() => firstCalls).toBe(1)
  await secondQuantity.fill('3')
  await secondQuantity.press('Enter')
  await page.waitForTimeout(100)
  expect(secondCalls).toBe(0)

  releaseFirst()
  await expect.poll(() => secondCalls).toBe(1)
  await expect(firstQuantity).toHaveValue('4')
  await expect(secondQuantity).toHaveValue('3')
})

test('cart blocks writes while a refresh snapshot is in flight', async ({ page }) => {
  let getCalls = 0
  let releaseRefresh!: () => void
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve
  })

  await page.route('**/api/v1/cart', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }
    getCalls += 1
    if (getCalls > 1) {
      await refreshGate
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(cart)),
    })
  })

  await page.goto('/cart')
  await page.getByPlaceholder('SKU').fill('303')
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => getCalls).toBe(2)

  const firstRow = page
    .locator('.cart-table .el-table__row')
    .filter({ hasText: 'Curious Capuchin' })
  await expect(firstRow.getByRole('spinbutton')).toBeDisabled()
  await expect(firstRow.getByRole('switch')).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Add to cart', exact: true })).toBeDisabled()
  releaseRefresh()
})

test('checkout sends one request while submit is pending', async ({ page }) => {
  let calls = 0
  let idempotencyKey = ''
  let releaseCheckout!: () => void
  const checkoutGate = new Promise<void>((resolve) => {
    releaseCheckout = resolve
  })

  await page.route('**/api/v1/cart/checkout', async (route) => {
    calls += 1
    idempotencyKey = (await route.request().allHeaders())['idempotency-key'] ?? ''
    await checkoutGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(checkoutOk),
    })
  })

  await page.goto('/checkout')
  await expect(page.getByRole('radio', { name: /Avery Chen/ })).toBeChecked()
  const submit = page.getByRole('button', { name: 'Submit', exact: true })
  const widthBefore = (await submit.boundingBox())?.width ?? 0

  await submit.evaluate((element) => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })

  await expect.poll(() => calls).toBe(1)
  await expect(submit).toBeDisabled()
  await expect.poll(async () => (await submit.boundingBox())?.width ?? 0).toBe(widthBefore)
  expect(idempotencyKey).not.toBe('')

  releaseCheckout()
  await expect(page).toHaveURL(/\/payment\/501/)
})

test('multi-shop checkout carries every persisted order into the payment queue', async ({
  page,
}) => {
  await page.route('**/api/v1/cart/checkout', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...checkoutOk,
        data: {
          ...checkoutOk.data,
          orderIds: [501, 502],
          subOrders: [
            ...checkoutOk.data.subOrders,
            {
              ...checkoutOk.data.subOrders[0],
              id: 22,
              shopId: 22,
              orderNo: 'SO-UI-002',
              formalOrderId: 502,
            },
          ],
        },
      }),
    })
  })

  await page.goto('/checkout')
  await expect(page.getByRole('radio', { name: /Avery Chen/ })).toBeChecked()
  await page.getByRole('button', { name: 'Submit', exact: true }).click()

  await expect(page).toHaveURL(/\/payment\/501\?orderIds=501(?:%2C|,)502/)
  await expect(page.getByRole('button', { name: 'Order ID 501' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await page.getByRole('button', { name: 'Order ID 502' }).click()
  await expect(page).toHaveURL(/\/payment\/502\?orderIds=501(?:%2C|,)502/)
})

test('checkout renders real address choices and separates discount allocations', async ({
  page,
}) => {
  await page.route('**/api/v1/cart/checkout/preview', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(checkoutPreview)),
    })
  })

  await page.goto('/checkout')
  const defaultAddress = page.getByRole('radio', { name: /Avery Chen/ })
  await expect(defaultAddress).toBeChecked()
  await expect(page.locator('.address-option').filter({ hasText: 'Avery Chen' })).toContainText(
    'No. 18 West Lake Road',
  )
  await page.getByPlaceholder('Province').fill('CN-ZJ')
  await page.getByRole('button', { name: 'Preview', exact: true }).click()

  const suborder = page.locator('.suborder-section').filter({ hasText: 'Curious Capuchin' })
  await expect(suborder.getByText('Store discount')).toBeVisible()
  await expect(suborder.getByText('3.00', { exact: true })).toBeVisible()
  await expect(suborder.getByText('Platform discount')).toBeVisible()
  await expect(suborder.getByText('5.00', { exact: true })).toBeVisible()
  await expect(page.locator('.checkout-summary')).toContainText('Freight')
})

test('empty cart uses the cart mascot instead of a generic text placeholder', async ({ page }) => {
  await page.route('**/api/v1/cart', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({ userId: 7, items: [], selectedQuantity: 0, selectedAmount: '0.00' }),
      ),
    })
  })

  await page.goto('/cart')
  await expect(page.locator('img.mascot-state[data-pose="cart"]')).toBeVisible()
})

test('pending payment stays pending with an explicit status query action', async ({ page }) => {
  await page.route('**/api/v1/payments/orders/501', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 81,
          paymentNo: 'PAY-501',
          orderId: 501,
          userId: 7,
          method: 'WECHAT',
          amount: '120.00',
          paidAmount: '0.00',
          refundedAmount: '0.00',
          status: 'PENDING',
          createTime: '2026-07-12T00:00:00+08:00',
        }),
      ),
    })
  })

  await page.goto('/payment/501')
  await expect(page.locator('img.mascot-state[data-pose="hourglass"]')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Check status' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Submit payment', exact: true })).toBeHidden()
  await expect(page.locator('img.mascot-state[data-pose="celebrate"]')).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText('Payment successful')
})

test('payment creation returning pending never emits a success state', async ({ page }) => {
  await page.route('**/api/v1/payments/orders/601', async (route) => {
    await route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      body: JSON.stringify({ title: 'Payment not found', status: 404 }),
    })
  })
  await page.route('**/api/v1/payments/pay', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 91,
          paymentNo: 'PAY-601',
          orderId: 601,
          userId: 7,
          method: 'WECHAT',
          amount: '88.00',
          paidAmount: '0.00',
          refundedAmount: '0.00',
          status: 'PENDING',
          createTime: '2026-07-12T00:00:00+08:00',
        }),
      ),
    })
  })

  await page.goto('/payment/601')
  await page.getByRole('button', { name: 'Submit payment', exact: true }).click()

  await expect(page.locator('img.mascot-state[data-pose="hourglass"]')).toBeVisible()
  await expect(page.locator('.app-feedback-item')).toContainText('Payment request submitted')
  await expect(page.locator('img.mascot-state[data-pose="celebrate"]')).toHaveCount(0)
  await expect(page.locator('.app-feedback-item--success')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Submit payment', exact: true })).toBeHidden()
})

test('payment celebrates only after a status query confirms paid', async ({ page }) => {
  let queryCount = 0
  await page.route('**/api/v1/payments/orders/501', async (route) => {
    queryCount += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 81,
          paymentNo: 'PAY-501',
          orderId: 501,
          userId: 7,
          method: 'WECHAT',
          amount: '120.00',
          paidAmount: queryCount > 1 ? '120.00' : '0.00',
          refundedAmount: '0.00',
          status: queryCount > 1 ? 'PAID' : 'PENDING',
          paidAt: queryCount > 1 ? '2026-07-12T00:02:00+08:00' : undefined,
          createTime: '2026-07-12T00:00:00+08:00',
        }),
      ),
    })
  })

  await page.goto('/payment/501')
  await expect(page.locator('img.mascot-state[data-pose="hourglass"]')).toBeVisible()
  await page.getByRole('button', { name: 'Check status' }).click()
  await expect(page.locator('img.mascot-state[data-pose="celebrate"]')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Payment successful' })).toBeVisible()
})

test('mobile cart uses labeled line items and keeps its summary above navigation', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/cart')

  const mobileItem = page.locator('.cart-mobile-item').filter({ hasText: 'Curious Capuchin' })
  await expect(mobileItem).toBeVisible()
  await expect(mobileItem).toContainText('SKU')
  await expect(mobileItem).toContainText('Quantity')
  await expect(page.locator('.cart-table')).toBeHidden()

  const summary = page.locator('.cart-summary')
  const navigation = page.locator('.consumer-bottom-nav')
  await expect(summary).toHaveCSS('position', 'fixed')
  await expect
    .poll(async () => {
      const summaryBox = await summary.boundingBox()
      const navigationBox = await navigation.boundingBox()
      return (summaryBox?.y ?? 0) + (summaryBox?.height ?? 0) - (navigationBox?.y ?? 0)
    })
    .toBeLessThanOrEqual(1)
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
})

test('mobile checkout preserves its form and preview after a sanitized submit failure', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/v1/cart/checkout/preview', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(checkoutPreview)),
    })
  })
  await page.route('**/api/v1/cart/checkout', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        title: 'RAW_CHECKOUT_ENGINE_FAILURE',
        detail: 'reservation coordinator exploded',
        status: 500,
      }),
    })
  })

  await page.goto('/checkout')
  const address = page.getByRole('radio', { name: /Avery Chen/ })
  const province = page.getByPlaceholder('Province')
  const coupons = page.getByPlaceholder('Coupons: PLATFORM-20,SHOP-10')
  await expect(address).toBeChecked()
  await province.fill('CN-ZJ')
  await coupons.fill('SAVE-8')
  await page.getByRole('button', { name: 'Preview', exact: true }).click()

  const mobileLine = page.locator('.checkout-mobile-line').filter({ hasText: 'Curious Capuchin' })
  await expect(mobileLine).toBeVisible()
  await expect(mobileLine).toContainText('Stock reservation')
  await page.getByRole('button', { name: 'Submit', exact: true }).click()

  await expect(page.locator('.app-feedback-item')).toContainText('Unable to submit checkout')
  await expect(address).toBeChecked()
  await expect(province).toHaveValue('CN-ZJ')
  await expect(coupons).toHaveValue('SAVE-8')
  await expect(mobileLine).toBeVisible()
  const summary = page.locator('.checkout-summary')
  const navigation = page.locator('.consumer-bottom-nav')
  await expect(summary).toHaveCSS('position', 'fixed')
  await expect(navigation).toBeHidden()
  await expect
    .poll(async () => {
      const summaryBox = await summary.boundingBox()
      const viewportHeight = page.viewportSize()?.height ?? 0
      return Math.abs((summaryBox?.y ?? 0) + (summaryBox?.height ?? 0) - viewportHeight)
    })
    .toBeLessThanOrEqual(1)
  await expect(page.locator('body')).not.toContainText('RAW_CHECKOUT_ENGINE_FAILURE')
  await expect(page.locator('body')).not.toContainText('reservation coordinator exploded')
  await expect(page.locator('.el-message')).toHaveCount(0)

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
})
