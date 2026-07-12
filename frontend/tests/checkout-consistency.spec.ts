import { expect, test, type Page } from '@playwright/test'

const cart = {
  userId: 7,
  items: [
    {
      skuId: 101,
      shopId: 11,
      productName: 'Curious Capuchin',
      unitPrice: '128.00',
      quantity: 1,
      selected: true,
      lineAmount: '128.00',
      updatedAt: '2026-07-12T00:00:00+08:00',
    },
  ],
  selectedQuantity: 1,
  selectedAmount: '128.00',
}

function checkout(addressId: number, payableAmount: string) {
  return {
    id: addressId,
    checkoutNo: `CO-${addressId}`,
    userId: 7,
    addressId,
    originalAmount: '128.00',
    discountAmount: (128 - Number(payableAmount)).toFixed(2),
    payableAmount,
    status: 'RESERVED',
    province: addressId === 1 ? 'CN-ZJ' : 'CN-SH',
    createdAt: '2026-07-12T00:00:00+08:00',
    subOrders: [],
  }
}

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'checkout-consistency-test' }
}

async function installMocks(page: Page) {
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
        username: 'checkout-tester',
        passwordChangeRequired: false,
      }
    } else if (pathname === '/cart' && request.method() === 'GET') {
      data = cart
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

function payableMetric(page: Page) {
  return page.locator('.checkout-summary__metric').filter({ hasText: 'Payable' }).locator('strong')
}

test.beforeEach(async ({ page }) => {
  await installMocks(page)
})

test('binds a preview to the normalized input snapshot and invalidates it on change', async ({
  page,
}) => {
  let previewBody: unknown
  await page.route('**/api/v1/cart/checkout/preview', async (route) => {
    previewBody = route.request().postDataJSON()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(checkout(1, '96.00'))),
    })
  })

  await page.goto('/checkout')
  await page.getByPlaceholder('Address ID').fill('1')
  await page.getByPlaceholder('Province').fill('CN-ZJ')
  await page.getByPlaceholder('Coupons: PLATFORM-20,SHOP-10').fill(' SAVE-8, SHOP-2 ')
  await page.getByRole('button', { name: 'Preview', exact: true }).click()

  await expect(payableMetric(page)).toHaveText('96.00')
  expect(previewBody).toEqual({
    addressId: 1,
    province: 'CN-ZJ',
    couponCodes: ['SAVE-8', 'SHOP-2'],
  })

  await page.getByPlaceholder('Province').fill('CN-SH')

  await expect(payableMetric(page)).toHaveText('128.00')
  await expect(page.getByRole('button', { name: 'Preview', exact: true })).toBeEnabled()
})

test('prevents checkout submission while the current preview is pending', async ({ page }) => {
  let releasePreview!: () => void
  const previewGate = new Promise<void>((resolve) => {
    releasePreview = resolve
  })
  let checkoutCalls = 0

  await page.route('**/api/v1/cart/checkout/preview', async (route) => {
    await previewGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(checkout(1, '96.00'))),
    })
  })
  await page.route('**/api/v1/cart/checkout', async (route) => {
    checkoutCalls += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(checkout(1, '96.00'))),
    })
  })

  await page.goto('/checkout')
  await page.getByPlaceholder('Address ID').fill('1')
  const previewRequest = page.waitForRequest('**/api/v1/cart/checkout/preview')
  await page.getByRole('button', { name: 'Preview', exact: true }).click()
  await previewRequest

  const submit = page.getByRole('button', { name: 'Submit', exact: true })
  try {
    await expect(submit).toBeDisabled()
    await submit.evaluate((element) => {
      element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })
    expect(checkoutCalls).toBe(0)
  } finally {
    releasePreview()
  }

  await expect(payableMetric(page)).toHaveText('96.00')
})

test('ignores an older preview response that arrives after a newer snapshot', async ({ page }) => {
  let releaseFirstPreview!: () => void
  const firstPreviewGate = new Promise<void>((resolve) => {
    releaseFirstPreview = resolve
  })
  let previewCalls = 0

  await page.route('**/api/v1/cart/checkout/preview', async (route) => {
    previewCalls += 1
    const body = route.request().postDataJSON() as { addressId: number }
    if (body.addressId === 1) {
      await firstPreviewGate
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(checkout(body.addressId, body.addressId === 1 ? '96.00' : '88.00'))),
    })
  })

  await page.goto('/checkout')
  const address = page.getByPlaceholder('Address ID')
  const preview = page.getByRole('button', { name: 'Preview', exact: true })
  await address.fill('1')
  await preview.click()
  await expect.poll(() => previewCalls).toBe(1)

  try {
    await address.fill('2')
    await expect(preview).toBeEnabled()
    await preview.click()
    await expect.poll(() => previewCalls).toBe(2)
    await expect(payableMetric(page)).toHaveText('88.00')
  } finally {
    releaseFirstPreview()
  }

  await expect(payableMetric(page)).toHaveText('88.00')
})

test('does not let a stale preview overwrite a checkout using newer inputs', async ({ page }) => {
  let releasePreview!: () => void
  const previewGate = new Promise<void>((resolve) => {
    releasePreview = resolve
  })
  let releaseCheckout!: () => void
  const checkoutGate = new Promise<void>((resolve) => {
    releaseCheckout = resolve
  })
  let checkoutBody: unknown

  await page.route('**/api/v1/cart/checkout/preview', async (route) => {
    await previewGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(checkout(1, '77.00'))),
    })
  })
  await page.route('**/api/v1/cart/checkout', async (route) => {
    checkoutBody = route.request().postDataJSON()
    await checkoutGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(checkout(2, '88.00'))),
    })
  })

  await page.goto('/checkout')
  const address = page.getByPlaceholder('Address ID')
  await address.fill('1')
  const previewRequest = page.waitForRequest('**/api/v1/cart/checkout/preview')
  await page.getByRole('button', { name: 'Preview', exact: true }).click()
  await previewRequest

  await address.fill('2')
  const checkoutRequest = page.waitForRequest('**/api/v1/cart/checkout')
  await page.getByRole('button', { name: 'Submit', exact: true }).click()
  await checkoutRequest

  try {
    expect(checkoutBody).toEqual({ addressId: 2, couponCodes: [] })
    releasePreview()
    await expect(payableMetric(page)).toHaveText('128.00')
  } finally {
    releasePreview()
    releaseCheckout()
  }

  await expect(page).toHaveURL(/\/orders$/)
})

test('reuses the cart checkout intent after failure and completes it after success', async ({
  page,
}) => {
  const idempotencyKeys: string[] = []
  await page.route('**/api/v1/cart/checkout', async (route) => {
    idempotencyKeys.push((await route.request().allHeaders())['idempotency-key'] ?? '')
    if (idempotencyKeys.length === 1) {
      await route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Checkout failed', detail: 'temporary checkout failure' }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(checkout(1, '128.00'))),
    })
  })

  await page.goto('/checkout')
  await page.getByPlaceholder('Address ID').fill('1')
  const submit = page.getByRole('button', { name: 'Submit', exact: true })
  await submit.click()
  await expect.poll(() => idempotencyKeys).toHaveLength(1)
  await expect(page.locator('.app-feedback-item')).toBeVisible()

  await submit.click()
  await expect(page).toHaveURL(/\/orders$/)
  expect(idempotencyKeys[0]).not.toBe('')
  expect(idempotencyKeys[1]).toBe(idempotencyKeys[0])

  await page.goto('/checkout')
  await page.getByPlaceholder('Address ID').fill('1')
  await page.getByRole('button', { name: 'Submit', exact: true }).click()
  await expect(page).toHaveURL(/\/orders$/)
  expect(idempotencyKeys[2]).not.toBe(idempotencyKeys[1])
})
