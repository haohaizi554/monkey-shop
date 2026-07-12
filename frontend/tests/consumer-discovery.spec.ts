import { expect, test, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const catalogProduct = {
  id: 1,
  name: 'Golden Monkey',
  breed: 'Sichuan golden snub-nosed monkey',
  price: '128.00',
  description: 'A calm companion with a golden coat.',
  imageUrl: '/images/broken.jpg',
  stock: 6,
}

const catalogSpu = {
  id: 91,
  categoryId: 7,
  name: catalogProduct.name,
  title: catalogProduct.breed,
  status: 'LISTED',
  originalPrice: '158.00',
  memberPrice: catalogProduct.price,
  strikePrice: '168.00',
  regionPrices: {},
  attributes: { description: catalogProduct.description },
  imageUrl: '/images/product-detail.jpg',
  skus: [
    {
      id: 901,
      spuId: 91,
      skuCode: 'GM-GOLD',
      specification: { coat: 'golden' },
      originalPrice: '158.00',
      memberPrice: catalogProduct.price,
      strikePrice: '168.00',
      regionPrices: {},
      active: true,
    },
  ],
}

const searchProduct = {
  productId: 1,
  categoryId: 7,
  name: catalogProduct.name,
  title: catalogProduct.description,
  imageUrl: '/images/search-result.jpg',
  originalPrice: '158.00',
  memberPrice: catalogProduct.price,
  attributes: { coat: 'golden' },
  score: 0.98,
}

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'consumer-discovery-test' }
}

async function fulfillJson(route: Parameters<Parameters<Page['route']>[1]>[0], data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(ok(data)),
  })
}

async function installDiscoveryMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })

  await page.route('**/images/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 3"><rect width="4" height="3" fill="#d8dee8"/></svg>',
    })
  })

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')

    if (pathname === '/users/me') {
      await fulfillJson(route, {
        isLogin: true,
        identity: 'USER',
        username: 'member',
        passwordChangeRequired: false,
      })
      return
    }
    if (pathname === '/monkeys') {
      await fulfillJson(route, [catalogProduct])
      return
    }
    if (pathname === '/catalog/spus/1') {
      await fulfillJson(route, catalogSpu)
      return
    }
    if (pathname === '/search/products') {
      await fulfillJson(route, {
        content: [searchProduct],
        page: Number(new URL(request.url()).searchParams.get('page') ?? 0),
        size: 12,
        totalElements: 30,
        totalPages: 3,
        first: false,
        last: false,
      })
      return
    }
    if (pathname === '/search/hot') {
      await fulfillJson(route, [{ keyword: 'golden', score: 12 }])
      return
    }
    if (pathname === '/search/suggestions') {
      await fulfillJson(route, [{ keyword: 'golden monkey', source: 'catalog', score: 0.9 }])
      return
    }
    if (pathname === '/search/recommendations') {
      await fulfillJson(route, [
        {
          productId: 1,
          name: catalogProduct.name,
          title: catalogProduct.description,
          imageUrl: '/images/recommendation.jpg',
          reason: 'Matches your saved interests',
          score: 0.91,
        },
      ])
      return
    }
    if (pathname === '/search/profile') {
      await fulfillJson(route, {
        userId: 1,
        maskedInterestProfile: 'family sh***',
        tags: ['family'],
        updatedAt: '2026-07-12T00:00:00+08:00',
        version: 1,
      })
      return
    }
    if (pathname === '/addresses' && request.method() === 'POST') {
      await fulfillJson(route, {
        id: 10,
        receiverName: 'Lin',
        phone: '13800138000',
        detailAddress: 'No. 1 Monkey Street',
        isDefault: 1,
      })
      return
    }
    if (pathname === '/addresses') {
      await fulfillJson(route, [])
      return
    }
    if (pathname === '/auth/captcha/config') {
      await fulfillJson(route, { provider: 'local', siteKey: '' })
      return
    }

    await fulfillJson(route, null)
  })
}

test.beforeEach(async ({ page }) => {
  await installDiscoveryMocks(page)
})

test('consumer discovery views use shared state and feedback contracts', async () => {
  const views = ['ShopView.vue', 'SearchView.vue', 'RecommendView.vue', 'ProductDetailView.vue']

  for (const view of views) {
    const source = await readFile(resolve(process.cwd(), 'src/views', view), 'utf8')
    expect(source, view).toContain('useAsyncState')
    expect(source, view).toContain('PageHeader')
    expect(source, view).toContain('AsyncStateView')
    expect(source, view).not.toContain('ElMessage')
    expect(source, view).not.toContain('error.message')
  }
})

test('catalog card keeps geometry when its image fails', async ({ page }) => {
  await page.route('**/images/broken.jpg', (route) => route.abort())
  await page.goto('/shop')

  const card = page.locator('.product-card').first()
  await expect(card).toBeVisible()
  const image = card.locator('img')
  await expect.poll(() => image.getAttribute('src')).toMatch(/default_product|fallback/)

  const box = await card.locator('.product-card__media').boundingBox()
  expect(box?.width).toBeGreaterThan(200)
  expect(box?.height).toBeGreaterThan(150)
})

test('search restores filters and page after navigating away and back', async ({ page }) => {
  await page.goto('/search?q=golden&category=7&sort=PRICE_ASC&page=1')

  await expect(page.getByLabel('Keyword, product, breed')).toHaveValue('golden')
  await expect(page.getByLabel('Category')).toHaveValue('7')
  await expect(page.locator('.el-pager .is-active')).toHaveText('2')
  await expect(page.locator('.product-card')).toHaveCount(1)

  await page.getByRole('main').getByRole('link', { name: 'Recommend', exact: true }).click()
  await expect(page).toHaveURL(/\/recommendations$/)
  await page.goBack()

  await expect(page).toHaveURL(/q=golden/)
  await expect(page).toHaveURL(/category=7/)
  await expect(page).toHaveURL(/page=1/)
  await expect(page.getByLabel('Keyword, product, breed')).toHaveValue('golden')
  await expect(page.locator('.el-pager .is-active')).toHaveText('2')
})

test('search failure stays inline and hides backend copy', async ({ page }) => {
  await page.route('**/api/v1/search/products**', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/problem+json',
      body: JSON.stringify({ title: 'Elasticsearch shard exploded', status: 500 }),
    })
  })

  await page.goto('/search?q=golden')

  await expect(page.locator('.async-state-view__error')).toBeVisible()
  await expect(page.locator('.async-state-view__error')).toContainText('Search failed')
  await expect(page.locator('body')).not.toContainText('Elasticsearch shard exploded')
})

test('recommendations reuse product cards and acknowledge profile updates', async ({ page }) => {
  await page.goto('/recommendations')

  const card = page.locator('.product-card').first()
  await expect(card).toContainText('Golden Monkey')
  await expect(card).toContainText('Matches your saved interests')

  await page.getByLabel('Interest profile').fill('family shopping')
  await page.getByLabel('Tags, comma separated').fill('family')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.locator('.app-feedback-item')).toContainText('Profile updated')
})

test('product detail is mobile-safe and validates a new address inline', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/shop/1')

  await expect(page.getByRole('heading', { name: 'Golden Monkey', level: 1 })).toBeVisible()
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)

  await page.getByRole('button', { name: 'Buy', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Checkout' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog.locator('.el-form-item__error')).toHaveCount(3)
})

test('product detail submits a new address only once while save is pending', async ({ page }) => {
  let calls = 0
  let releaseSave!: () => void
  const saveGate = new Promise<void>((resolve) => {
    releaseSave = resolve
  })

  await page.route('**/api/v1/addresses', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.fallback()
      return
    }
    calls += 1
    await saveGate
    await fulfillJson(route, {
      id: 10,
      receiverName: 'Lin',
      phone: '13800138000',
      detailAddress: 'No. 1 Monkey Street',
      isDefault: 1,
    })
  })

  await page.goto('/shop/1')
  await page.getByRole('button', { name: 'Buy', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Checkout' })
  await dialog.getByLabel('Receiver').fill('Lin')
  await dialog.getByLabel('Phone').fill('13800138000')
  await dialog.getByLabel('Address').fill('No. 1 Monkey Street')
  const save = dialog.getByRole('button', { name: 'Save', exact: true })

  await save.evaluate((element) => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })

  await expect.poll(() => calls).toBe(1)
  await expect(save).toBeDisabled()
  releaseSave()
  await expect(save).toBeEnabled()
})

test('store checkout address controls expose explicit accessible names', async ({ page }) => {
  await page.goto('/shop')
  await page.getByRole('button', { name: 'Buy', exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Checkout' })

  await expect(dialog.getByRole('textbox', { name: 'Receiver', exact: true })).toBeVisible()
  await expect(dialog.getByRole('textbox', { name: 'Phone', exact: true })).toBeVisible()
  await expect(dialog.getByRole('textbox', { name: 'Address', exact: true })).toBeVisible()
})

test('quick checkout freezes the selected address while risk assessment is pending', async ({
  page,
}) => {
  let releaseRisk!: () => void
  const riskGate = new Promise<void>((resolve) => {
    releaseRisk = resolve
  })
  let orderAddressId: number | null = null

  await page.route('**/api/v1/addresses', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }
    await fulfillJson(route, [
      {
        id: 1,
        receiverName: 'Lin',
        phone: '13800138000',
        detailAddress: 'First address',
        isDefault: 1,
      },
      {
        id: 2,
        receiverName: 'Wu',
        phone: '13900139000',
        detailAddress: 'Second address',
        isDefault: 0,
      },
    ])
  })
  await page.route('**/api/v1/risk/assess', async (route) => {
    await riskGate
    await fulfillJson(route, { decision: 'ALLOW', score: 0, signals: [] })
  })
  await page.route('**/api/v1/orders/create', async (route) => {
    orderAddressId = (route.request().postDataJSON() as { addressId: number }).addressId
    await fulfillJson(route, { id: 91, status: 'PAID' })
  })

  await page.goto('/shop/1')
  await page.getByRole('button', { name: 'Buy', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Checkout' })
  await dialog.getByRole('button', { name: 'Place order', exact: true }).click()

  await expect(dialog.getByRole('radio', { name: /First address/ })).toBeDisabled()
  await expect(dialog.getByRole('radio', { name: /Second address/ })).toBeDisabled()
  await expect(dialog.getByRole('textbox', { name: /Receiver/ })).toBeDisabled()
  await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeDisabled()
  releaseRisk()
  await expect.poll(() => orderAddressId).toBe(1)
})
