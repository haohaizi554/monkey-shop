import { expect, test, type Page } from '@playwright/test'
import { readFile, stat } from 'node:fs/promises'
import { resolve } from 'node:path'

const consumerViews = [
  'LoginView.vue',
  'ShopView.vue',
  'ProductDetailView.vue',
  'SearchView.vue',
  'RecommendView.vue',
  'CartView.vue',
  'CheckoutView.vue',
  'OrdersView.vue',
  'PaymentView.vue',
  'LogisticsView.vue',
  'ReviewView.vue',
  'MembershipView.vue',
  'ProfileView.vue',
]

test('the standalone SPA ships and serves its default image fallbacks locally', async ({
  request,
}) => {
  for (const filename of ['default_product.jpg', 'default_avatar.jpg']) {
    const asset = await stat(resolve(process.cwd(), 'public/images', filename))
    expect(asset.size, `${filename} must be a real bundled image`).toBeGreaterThan(100)
    expect(asset.size, `${filename} must stay lightweight`).toBeLessThan(500_000)
    const response = await request.get(`/images/${filename}`)
    expect(response.status(), `${filename} must not be proxied to the backend`).toBe(200)
    expect(response.headers()['content-type']).toContain('image/jpeg')
  }
})

test('consumer source guard rejects direct feedback APIs and hard-coded colors', async () => {
  const viewsDirectory = resolve(process.cwd(), 'src/views')

  for (const view of consumerViews) {
    const source = await readFile(resolve(viewsDirectory, view), 'utf8')
    expect(source, `${view} must not own global Element feedback`).not.toMatch(
      /ElMessage|ElNotification/,
    )
    expect(source, `${view} must not render raw exception messages`).not.toMatch(
      /(?:notify\.(?:error|warning)|showAuthNotice)\([^\n]*error\.message|\{\{[^}]*error\.message/,
    )
    expect(source, `${view} must consume semantic color tokens`).not.toMatch(
      /#[0-9a-f]{3}(?:[0-9a-f]{3})?(?:[0-9a-f]{2})?\b|rgba?\(/i,
    )
  }
})

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'consumer-completion' }
}

async function installStorefrontMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown = null
    if (pathname === '/users/me') {
      data = { isLogin: false }
    } else if (pathname === '/monkeys') {
      data = {
        content: [
          {
            id: 1,
            name: 'Golden Monkey',
            breed: 'Golden',
            price: '128.00',
            description: 'A calm companion.',
            imageUrl: '/images/default_product.jpg',
            stock: 8,
          },
        ],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      }
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

test('real mobile storefront renders bounded token-backed product surfaces', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installStorefrontMocks(page)
  await page.goto('/shop')
  await expect(page.locator('.product-card')).toBeVisible()

  const geometry = await page.evaluate(() => {
    const card = document.querySelector<HTMLElement>('.product-card')
    const action = document.querySelector<HTMLElement>('.product-card button')
    const probe = document.createElement('div')
    probe.style.background = 'var(--color-surface)'
    probe.style.borderRadius = 'var(--radius-surface)'
    document.body.append(probe)
    const expected = getComputedStyle(probe)
    const actual = card ? getComputedStyle(card) : null
    const result = {
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      actionHeight: action?.getBoundingClientRect().height ?? 0,
      backgroundMatches: actual?.backgroundColor === expected.backgroundColor,
      radiusMatches: actual?.borderRadius === expected.borderRadius,
    }
    probe.remove()
    return result
  })

  expect(geometry.pageOverflow).toBeLessThanOrEqual(1)
  expect(geometry.actionHeight).toBeGreaterThanOrEqual(44)
  expect(geometry.backgroundMatches).toBe(true)
  expect(geometry.radiusMatches).toBe(true)
})
