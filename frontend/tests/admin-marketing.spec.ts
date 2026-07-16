import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

type Operation = 'claim' | 'redeem' | 'return' | 'quote' | 'seckill' | 'group-buy'
type Mocks = {
  pending?: Partial<Record<Operation, Promise<void>>>
  failures?: ReadonlySet<Operation>
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
  if (operation === 'quote') {
    return {
      originalAmount: '128.00',
      discountAmount: '20.00',
      payableAmount: '108.00',
      appliedCoupons: ['PLATFORM-20'],
    }
  }
  if (operation === 'seckill') {
    return {
      id: 88,
      activityId: 2500000000001,
      skuId: 7,
      userId: 1,
      quantity: 1,
      idempotencyKey: 'flash-test',
    }
  }
  if (operation === 'group-buy') {
    return {
      id: 99,
      activityId: 2600000000001,
      skuId: 7,
      leaderUserId: 1,
      targetSize: 3,
      joinedCount: 2,
      status: 'OPEN',
    }
  }
  return {
    id: 1,
    couponId: 2400000000001,
    couponCode: 'PLATFORM-20',
    userId: 1,
    status: operation === 'return' ? 'RETURNED' : operation === 'redeem' ? 'USED' : 'CLAIMED',
  }
}

async function installMarketingMocks(page: Page, mocks: Mocks = {}) {
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
        body: JSON.stringify(ok({ id: 1 })),
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
    calls.set(operation, (calls.get(operation) ?? 0) + 1)
    await mocks.pending?.[operation]
    if (mocks.failures?.has(operation)) {
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

test('marketing operations keep their pending controls independent', async ({ page }) => {
  let releaseClaim!: () => void
  const claimGate = new Promise<void>((resolve) => {
    releaseClaim = resolve
  })
  await installMarketingMocks(page, { pending: { claim: claimGate } })
  await page.goto('/marketing')

  await task(page, 'Coupon').getByRole('button', { name: 'Claim', exact: true }).click()
  await expect(
    task(page, 'Coupon').getByRole('button', { name: 'Claim', exact: true }),
  ).toBeDisabled()
  await expect(
    task(page, 'Price quote').getByRole('button', { name: 'Quote', exact: true }),
  ).toBeEnabled()
  await expect(task(page, 'Seckill').getByRole('button', { name: 'Submit seckill' })).toBeEnabled()
  await expect(
    task(page, 'Group buy').getByRole('button', { name: 'Join group buy' }),
  ).toBeEnabled()
  releaseClaim()
  await expect(task(page, 'Coupon').getByTestId('coupon-result')).toContainText('Claimed')
})

test('marketing task errors remain local and preserve another task result', async ({ page }) => {
  await installMarketingMocks(page, { failures: new Set<Operation>(['group-buy']) })
  await page.goto('/marketing')
  const quoteTask = task(page, 'Price quote')
  await quoteTask.getByRole('button', { name: 'Quote', exact: true }).click()
  await expect(quoteTask.getByTestId('quote-result')).toContainText('Payable')

  const groupTask = task(page, 'Group buy')
  await groupTask.getByRole('button', { name: 'Join group buy' }).click()
  await expect(groupTask.getByTestId('group-error')).toHaveText('Unable to join group buy')
  await expect(quoteTask.getByTestId('quote-result')).toContainText('Payable')
  await expect(page.locator('body')).not.toContainText('raw upstream failure')
})

test('marketing workflows validate inputs before requests and recover after correction', async ({
  page,
}) => {
  const calls = await installMarketingMocks(page)
  await page.goto('/marketing')

  const coupon = task(page, 'Coupon')
  await coupon.getByLabel('Idempotency key').fill('')
  await coupon.getByRole('button', { name: 'Claim', exact: true }).click()
  await expect(coupon.getByTestId('coupon-error')).toHaveText('Enter an idempotency key')
  expect(calls.get('claim') ?? 0).toBe(0)
  await coupon.getByLabel('Idempotency key').fill('coupon-test')
  await coupon.getByRole('spinbutton', { name: 'Coupon ID' }).fill('')
  await coupon.getByRole('button', { name: 'Claim', exact: true }).click()
  await expect(coupon.getByTestId('coupon-error')).toHaveText('Enter a positive value')
  expect(calls.get('claim') ?? 0).toBe(0)
  await coupon.getByRole('spinbutton', { name: 'Coupon ID' }).fill('2400000000001')
  await coupon.getByRole('button', { name: 'Claim', exact: true }).click()
  await expect(coupon.getByTestId('coupon-result')).toContainText('PLATFORM-20')
  expect(calls.get('claim')).toBe(1)
  await coupon.getByRole('button', { name: 'Redeem', exact: true }).click()
  await expect(coupon.getByTestId('coupon-error')).toHaveText('Enter both coupon code and order ID')
  expect(calls.get('redeem') ?? 0).toBe(0)
  await coupon.getByRole('spinbutton', { name: 'Order ID' }).fill('42')
  await coupon.getByRole('button', { name: 'Redeem', exact: true }).click()
  await expect(coupon.getByTestId('coupon-result')).toContainText('Redeemed')
  expect(calls.get('redeem')).toBe(1)
  await coupon.getByRole('button', { name: 'Return', exact: true }).click()
  await expect(coupon.getByTestId('coupon-result')).toContainText('Returned')
  expect(calls.get('return')).toBe(1)

  const quote = task(page, 'Price quote')
  await quote.getByRole('spinbutton', { name: 'Order amount' }).fill('')
  await quote.getByRole('button', { name: 'Quote', exact: true }).click()
  await expect(quote.getByTestId('quote-error')).toHaveText('Enter a positive value')
  expect(calls.get('quote') ?? 0).toBe(0)
  await quote.getByRole('spinbutton', { name: 'Order amount' }).fill('128')
  await quote.getByRole('button', { name: 'Quote', exact: true }).click()
  await expect(quote.getByTestId('quote-result')).toContainText('Payable')

  const seckill = task(page, 'Seckill')
  await seckill.getByRole('spinbutton', { name: 'Quantity' }).fill('')
  await seckill.getByRole('button', { name: 'Submit seckill' }).click()
  await expect(seckill.getByTestId('seckill-error')).toHaveText('Enter a positive value')
  expect(calls.get('seckill') ?? 0).toBe(0)
  await seckill.getByRole('spinbutton', { name: 'Quantity' }).fill('1')
  await seckill.getByLabel('Idempotency key').fill('')
  await seckill.getByRole('button', { name: 'Submit seckill' }).click()
  await expect(seckill.getByTestId('seckill-error')).toHaveText('Enter an idempotency key')
  expect(calls.get('seckill') ?? 0).toBe(0)
  await seckill.getByLabel('Idempotency key').fill('flash-test')
  await seckill.getByRole('button', { name: 'Submit seckill' }).click()
  await expect(seckill.getByTestId('seckill-result')).toContainText('88')

  const group = task(page, 'Group buy')
  await group.getByRole('spinbutton', { name: 'Activity ID' }).fill('')
  await group.getByRole('button', { name: 'Join group buy' }).click()
  await expect(group.getByTestId('group-error')).toHaveText('Enter a positive value')
  expect(calls.get('group-buy') ?? 0).toBe(0)
  await group.getByRole('spinbutton', { name: 'Activity ID' }).fill('2600000000001')
  await group.getByLabel('Idempotency key').fill('')
  await group.getByRole('button', { name: 'Join group buy' }).click()
  await expect(group.getByTestId('group-error')).toHaveText('Enter an idempotency key')
  expect(calls.get('group-buy') ?? 0).toBe(0)
  await group.getByLabel('Idempotency key').fill('group-test')
  await group.getByRole('button', { name: 'Join group buy' }).click()
  await expect(group.getByTestId('group-result')).toContainText('Open')
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
