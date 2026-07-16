import { expect, test, type Locator, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

type Operation = 'claim' | 'redeem' | 'return' | 'quote' | 'seckill' | 'group-buy'
type MarketingMocks = {
  gates?: Partial<Record<Operation, Promise<void>>>
  failureCount?: Partial<Record<Operation, number>>
}

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'marketing-test' }
}

function operationFor(pathname: string): Operation | undefined {
  return {
    '/marketing/coupons/claim': 'claim',
    '/marketing/coupons/redeem': 'redeem',
    '/marketing/coupons/return': 'return',
    '/marketing/price/quote': 'quote',
    '/marketing/seckill-orders': 'seckill',
    '/marketing/group-buy/join': 'group-buy',
  }[pathname] as Operation | undefined
}

function resultFor(operation: Operation) {
  if (operation === 'quote')
    return {
      originalAmount: '128.00',
      discountAmount: '20.00',
      payableAmount: '108.00',
      appliedCoupons: ['PLATFORM-20'],
    }
  if (operation === 'seckill')
    return {
      id: 88,
      activityId: 2500000000001,
      skuId: 7,
      userId: 1,
      quantity: 1,
      idempotencyKey: 'flash-test',
      createdAt: '2026-07-12T08:00:00',
    }
  if (operation === 'group-buy')
    return {
      id: 99,
      activityId: 2600000000001,
      skuId: 7,
      leaderUserId: 1,
      targetSize: 3,
      joinedCount: 2,
      status: 'OPEN',
      expiresAt: '2026-07-12T09:00:00',
    }
  return {
    id: 1,
    couponId: 2400000000001,
    couponCode: 'PLATFORM-20',
    userId: 1,
    status: operation === 'return' ? 'RETURNED' : operation === 'redeem' ? 'USED' : 'CLAIMED',
    claimedAt: '2026-07-12T08:00:00',
  }
}

function deferred() {
  let release!: () => void
  const promise = new Promise<void>((resolve) => {
    release = resolve
  })
  return { promise, release }
}

async function installMarketingMocks(page: Page, mocks: MarketingMocks = {}) {
  const calls = new Map<Operation, number>()
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(ok({ isLogin: true, identity: 'ADMIN', username: 'admin' })),
      })
      return
    }
    if (pathname === '/tracking/events') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(ok({ id: 1, eventType: 'PAGE_VIEW' })),
      })
      return
    }
    const operation = operationFor(pathname)
    if (!operation) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(ok([])),
      })
      return
    }
    const requestNumber = (calls.get(operation) ?? 0) + 1
    calls.set(operation, requestNumber)
    await mocks.gates?.[operation]
    if (requestNumber <= (mocks.failureCount?.[operation] ?? 0)) {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'MARKETING_DOWN',
          message: 'raw upstream failure',
          traceId: 'bad-trace',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(resultFor(operation))),
    })
  })
  return calls
}

function task(page: Page, name: string) {
  return page.getByRole('region', { name })
}

function requests(calls: Map<Operation, number>, operation: Operation) {
  return calls.get(operation) ?? 0
}

async function setNumericExpression(input: Locator, value: string) {
  await input.evaluate((element, nextValue) => {
    const control = element as HTMLInputElement
    control.value = nextValue
    control.dispatchEvent(
      new InputEvent('input', { bubbles: true, inputType: 'insertText', data: nextValue }),
    )
    control.dispatchEvent(new Event('change', { bubbles: true }))
  }, value)
}

async function fillCouponOrder(coupon: Locator) {
  await coupon.getByLabel('Coupon code').fill('PLATFORM-20')
  await coupon.getByRole('spinbutton', { name: 'Order ID' }).fill('42')
}

const couponPendingCases: ReadonlyArray<{
  operation: Extract<Operation, 'claim' | 'redeem' | 'return'>
  button: string
  result: string
  prepare?: (coupon: Locator) => Promise<void>
  remainingButtons: readonly string[]
}> = [
  {
    operation: 'claim',
    button: 'Claim',
    result: 'Claimed',
    remainingButtons: ['Redeem', 'Return'],
  },
  {
    operation: 'redeem',
    button: 'Redeem',
    result: 'Redeemed',
    prepare: fillCouponOrder,
    remainingButtons: ['Claim', 'Return'],
  },
  {
    operation: 'return',
    button: 'Return',
    result: 'Returned',
    prepare: fillCouponOrder,
    remainingButtons: ['Claim', 'Redeem'],
  },
]

for (const pendingCase of couponPendingCases) {
  test(`coupon ${pendingCase.operation} pending leaves the other coupon operations usable`, async ({
    page,
  }) => {
    const gate = deferred()
    await installMarketingMocks(page, { gates: { [pendingCase.operation]: gate.promise } })
    await page.goto('/marketing')
    const coupon = task(page, 'Coupon')
    await pendingCase.prepare?.(coupon)

    await coupon.getByRole('button', { name: pendingCase.button, exact: true }).click()
    await expect(
      coupon.getByRole('button', { name: pendingCase.button, exact: true }),
    ).toBeDisabled()
    for (const button of pendingCase.remainingButtons) {
      await expect(coupon.getByRole('button', { name: button, exact: true })).toBeEnabled()
    }
    gate.release()
    await expect(coupon.getByTestId('coupon-result')).toContainText(pendingCase.result)
  })
}

test('group retry clears its task error and preserves a completed quote', async ({ page }) => {
  const calls = await installMarketingMocks(page, { failureCount: { 'group-buy': 1 } })
  await page.goto('/marketing')
  const quote = task(page, 'Price quote')
  const group = task(page, 'Group buy')
  await quote.getByRole('button', { name: 'Quote', exact: true }).click()
  await expect(quote.getByTestId('quote-result')).toContainText('Payable')

  await group.getByRole('button', { name: 'Join group buy' }).click()
  await expect(group.getByTestId('group-error')).toHaveText('Unable to join group buy')
  await expect(quote.getByTestId('quote-result')).toContainText('Payable')
  expect(requests(calls, 'group-buy')).toBe(1)

  await group.getByRole('button', { name: 'Join group buy' }).click()
  await expect(group.getByTestId('group-error')).toHaveCount(0)
  await expect(group.getByTestId('group-result')).toContainText('Open')
  await expect(quote.getByTestId('quote-result')).toContainText('Payable')
  expect(requests(calls, 'group-buy')).toBe(2)
})

test('coupon claim blocks invalid ID expressions and blank idempotency keys before a request', async ({
  page,
}) => {
  const calls = await installMarketingMocks(page)
  await page.goto('/marketing')
  const coupon = task(page, 'Coupon')
  const claim = coupon.getByRole('button', { name: 'Claim', exact: true })
  await coupon.getByLabel('Idempotency key').fill('   ')
  await claim.click()
  await expect(coupon.getByTestId('coupon-error')).toHaveText('Enter an idempotency key')
  expect(requests(calls, 'claim')).toBe(0)
  await coupon.getByLabel('Idempotency key').fill('coupon-test')

  for (const expression of ['0', '-1', '', 'not-a-number']) {
    await setNumericExpression(coupon.getByRole('spinbutton', { name: 'Coupon ID' }), expression)
    await claim.click()
    await expect(coupon.getByTestId('coupon-error')).toHaveText('Enter a positive value')
    expect(requests(calls, 'claim')).toBe(0)
  }
})

test('coupon redeem and return block blank codes and invalid order IDs before requests', async ({
  page,
}) => {
  const calls = await installMarketingMocks(page)
  await page.goto('/marketing')
  const coupon = task(page, 'Coupon')
  const code = coupon.getByLabel('Coupon code')
  const orderId = coupon.getByRole('spinbutton', { name: 'Order ID' })
  for (const [button, operation] of [
    ['Redeem', 'redeem'],
    ['Return', 'return'],
  ] as const) {
    await code.fill('   ')
    await orderId.fill('42')
    await coupon.getByRole('button', { name: button, exact: true }).click()
    await expect(coupon.getByTestId('coupon-error')).toHaveText(
      'Enter both coupon code and order ID',
    )
    expect(requests(calls, operation)).toBe(0)

    await code.fill('PLATFORM-20')
    for (const expression of ['0', '-1', '', 'not-a-number']) {
      await setNumericExpression(orderId, expression)
      await coupon.getByRole('button', { name: button, exact: true }).click()
      await expect(coupon.getByTestId('coupon-error')).toHaveText(
        'Enter both coupon code and order ID',
      )
      expect(requests(calls, operation)).toBe(0)
    }
  }
})

test('quote blocks invalid amount expressions before a request', async ({ page }) => {
  const calls = await installMarketingMocks(page)
  await page.goto('/marketing')
  const quote = task(page, 'Price quote')
  for (const expression of ['0', '-1', '', 'not-a-number']) {
    await setNumericExpression(quote.getByRole('spinbutton', { name: 'Order amount' }), expression)
    await quote.getByRole('button', { name: 'Quote', exact: true }).click()
    await expect(quote.getByTestId('quote-error')).toHaveText('Enter a positive value')
    expect(requests(calls, 'quote')).toBe(0)
  }
})

test('seckill blocks invalid IDs, quantities, and blank idempotency keys before requests', async ({
  page,
}) => {
  const calls = await installMarketingMocks(page)
  await page.goto('/marketing')
  const seckill = task(page, 'Seckill')
  const submit = seckill.getByRole('button', { name: 'Submit seckill', exact: true })
  const activityId = seckill.getByRole('spinbutton', { name: 'Activity ID' })
  const quantity = seckill.getByRole('spinbutton', { name: 'Quantity' })
  await seckill.getByLabel('Idempotency key').fill('   ')
  await submit.click()
  await expect(seckill.getByTestId('seckill-error')).toHaveText('Enter an idempotency key')
  expect(requests(calls, 'seckill')).toBe(0)
  await seckill.getByLabel('Idempotency key').fill('flash-test')

  for (const input of [activityId, quantity]) {
    for (const expression of ['0', '-1', '', 'not-a-number']) {
      await setNumericExpression(input, expression)
      await submit.click()
      await expect(seckill.getByTestId('seckill-error')).toHaveText('Enter a positive value')
      expect(requests(calls, 'seckill')).toBe(0)
      await activityId.fill('2500000000001')
      await quantity.fill('1')
    }
  }
})

test('group buy enforces supplied IDs and keys while allowing an empty optional team ID', async ({
  page,
}) => {
  const calls = await installMarketingMocks(page)
  await page.goto('/marketing')
  const group = task(page, 'Group buy')
  const join = group.getByRole('button', { name: 'Join group buy', exact: true })
  const activityId = group.getByRole('spinbutton', { name: 'Activity ID' })
  const teamId = group.getByRole('spinbutton', { name: 'Team ID (optional)' })
  await group.getByLabel('Idempotency key').fill('   ')
  await join.click()
  await expect(group.getByTestId('group-error')).toHaveText('Enter an idempotency key')
  expect(requests(calls, 'group-buy')).toBe(0)
  await group.getByLabel('Idempotency key').fill('group-test')

  for (const expression of ['0', '-1', '', 'not-a-number']) {
    await setNumericExpression(activityId, expression)
    await join.click()
    await expect(group.getByTestId('group-error')).toHaveText('Enter a positive value')
    expect(requests(calls, 'group-buy')).toBe(0)
  }
  await activityId.fill('2600000000001')

  for (const expression of ['0', '-1']) {
    await setNumericExpression(teamId, expression)
    await join.click()
    await expect(group.getByTestId('group-error')).toHaveText('Enter a positive value')
    expect(requests(calls, 'group-buy')).toBe(0)
  }
  await setNumericExpression(teamId, '')
  await join.click()
  await expect(group.getByTestId('group-error')).toHaveCount(0)
  await expect(group.getByTestId('group-result')).toContainText('Open')
  expect(requests(calls, 'group-buy')).toBe(1)
})

test('price quote presents localized amounts, coupons, and app feedback', async ({ page }) => {
  await installMarketingMocks(page)
  await page.goto('/marketing')
  const quote = task(page, 'Price quote')
  await quote.getByRole('button', { name: 'Quote', exact: true }).click()
  const result = quote.getByTestId('quote-result')
  await expect(result).toContainText('Original')
  await expect(result).toContainText('Discount')
  await expect(result).toContainText('Payable')
  await expect(result).toContainText('Applied coupons')
  await expect(result.getByText('PLATFORM-20', { exact: true })).toBeVisible()
  await expect(page.getByText('Price quote ready', { exact: true })).toBeVisible()
})

test('marketing workspace adapts from two columns to one without horizontal overflow', async ({
  page,
}) => {
  await installMarketingMocks(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/marketing')
  await page.screenshot({ path: 'output/task4-marketing-desktop.png', fullPage: true })
  const desktop = await Promise.all(
    ['Coupon', 'Price quote'].map((name) => task(page, name).boundingBox()),
  )
  expect(desktop[0]?.y).toBe(desktop[1]?.y)
  expect(desktop[0]?.x).not.toBe(desktop[1]?.x)
  expect(await page.locator('body').evaluate((body) => body.scrollWidth <= body.clientWidth)).toBe(
    true,
  )
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'output/task4-marketing-mobile.png', fullPage: true })
  const mobile = await Promise.all(
    ['Coupon', 'Price quote'].map((name) => task(page, name).boundingBox()),
  )
  expect(mobile[0]?.x).toBe(mobile[1]?.x)
  expect(mobile[0]?.y).toBeLessThan(mobile[1]?.y ?? 0)
  expect(await page.locator('body').evaluate((body) => body.scrollWidth <= body.clientWidth)).toBe(
    true,
  )
})

test('marketing view uses useNotify and not Element Plus message APIs', () => {
  const source = readFileSync(resolve(process.cwd(), 'src/views/MarketingView.vue'), 'utf8')
  expect(source).toContain('useNotify')
  expect(source).not.toContain('ElMessage')
  expect(source).not.toContain('ElNotification')
})
