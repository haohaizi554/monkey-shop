import { AxeBuilder } from '@axe-core/playwright'
import { test, expect, type Route } from '@playwright/test'

function expectTraceHeader(route: Route) {
  expect(route.request().headers()['x-trace-id']).toMatch(/^[A-Za-z0-9._:-]{1,128}$/)
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/users/me', async (route) => {
    expectTraceHeader(route)
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'OK',
        message: 'ok',
        data: { isLogin: false },
      }),
    })
  })
  await page.route('**/api/v1/monkeys', async (route) => {
    expectTraceHeader(route)
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'OK',
        message: 'ok',
        data: [
          {
            id: 1,
            name: 'Golden Snub-nosed',
            breed: 'Rhinopithecus roxellana',
            price: '128.00',
            description: 'Healthy and ready for browsing.',
            imageUrl: '/images/default_product.png',
            stock: 3,
          },
        ],
      }),
    })
  })
  await page.route('**/api/v1/tracking/events', async (route) => {
    expectTraceHeader(route)
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'OK',
        message: 'ok',
        data: { id: 1, eventType: 'PAGE_VIEW' },
      }),
    })
  })
  await page.route('**/api/v1/catalog/spus/1', async (route) => {
    expectTraceHeader(route)
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'OK',
        message: 'ok',
        data: {
          id: 1,
          categoryId: 10,
          name: 'Golden Snub-nosed',
          title: 'Rhinopithecus roxellana',
          status: 'LISTED',
          originalPrice: '128.00',
          memberPrice: '118.00',
          strikePrice: '168.00',
          imageUrl: '/images/monkey.png',
          attributes: { description: 'Healthy and ready for browsing.' },
          skus: [],
        },
      }),
    })
  })
  await page.route('**/images/default_product.png', async (route) => {
    await route.fulfill({
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 3"><rect width="4" height="3" fill="#d9e2ec"/></svg>',
    })
  })
  await page.route('**/images/monkey.png', async (route) => {
    await route.fulfill({
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 3"><rect width="4" height="3" fill="#d9e2ec"/></svg>',
    })
  })
})

test('shop route renders without serious accessibility violations', async ({ page }) => {
  await page.goto('/shop')
  await expect(page.getByRole('heading', { name: 'MonkeyShop' })).toBeVisible()
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
  expect(results.violations).toEqual([])
})

test('app shell toggles language and dark theme', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' })
  await page.addInitScript(() => localStorage.setItem('monkeyshop-locale', 'zh'))
  await page.goto('/shop')
  await expect(
    page
      .getByRole('navigation', { name: 'Primary' })
      .getByRole('link', { name: '商城', exact: true }),
  ).toBeVisible()

  await page.getByRole('button', { name: '切换语言', exact: true }).click()
  await expect(
    page
      .getByRole('navigation', { name: 'Primary' })
      .getByRole('link', { name: 'Shop', exact: true }),
  ).toBeVisible()
  await expect
    .poll(async () => page.evaluate(() => localStorage.getItem('monkeyshop-locale')))
    .toBe('en')

  await page.getByRole('button', { name: 'Switch to dark theme', exact: true }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  await expect(
    page.getByRole('button', { name: 'Switch to light theme', exact: true }),
  ).toBeVisible()
})

test('product detail route renders product JSON-LD without serious accessibility violations', async ({
  page,
}) => {
  await page.goto('/shop/1')
  await expect(page.getByRole('heading', { name: 'Golden Snub-nosed' })).toBeVisible()
  const jsonLd = await page
    .locator('#monkeyshop-product-jsonld')
    .evaluate((node) => node.textContent ?? '')
  expect(jsonLd).toContain('"@type":"Product"')
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
  expect(results.violations).toEqual([])
})
