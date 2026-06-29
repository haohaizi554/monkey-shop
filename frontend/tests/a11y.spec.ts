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
  await page.route('**/images/default_product.png', async (route) => {
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
