import { expect, test, type Page, type Route } from '@playwright/test'

interface SearchCall {
  categoryId: string | null
  keyword: string
  page: number
}

interface MockOptions {
  hangConversions?: boolean
  onConversion?: () => void
  onSearch?: (route: Route, url: URL) => Promise<void>
}

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'search-consistency-test' }
}

function searchPage(name: string, page = 0, totalElements = 30) {
  return {
    content: [
      {
        productId: page + 1,
        categoryId: 7,
        name,
        title: `${name} description`,
        imageUrl: '/images/search-result.jpg',
        originalPrice: '158.00',
        memberPrice: '128.00',
        attributes: { coat: 'golden' },
        score: 0.98,
      },
    ],
    page,
    size: 12,
    totalElements,
    totalPages: Math.max(1, Math.ceil(totalElements / 12)),
    first: page === 0,
    last: page >= Math.ceil(totalElements / 12) - 1,
  }
}

async function fulfillJson(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(ok(data)),
  })
}

async function installMocks(page: Page, options: MockOptions = {}) {
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
    const url = new URL(request.url())
    const pathname = url.pathname.replace('/api/v1', '')

    if (pathname === '/users/me') {
      await fulfillJson(route, {
        isLogin: true,
        identity: 'USER',
        username: 'member',
        passwordChangeRequired: false,
      })
      return
    }
    if (pathname === '/search/products') {
      if (options.onSearch) {
        await options.onSearch(route, url)
        return
      }
      const pageNumber = Number(url.searchParams.get('page') ?? 0)
      const keyword = url.searchParams.get('keyword') || 'Catalog result'
      await fulfillJson(route, searchPage(keyword, pageNumber))
      return
    }
    if (pathname === '/search/hot') {
      await fulfillJson(route, [{ keyword: 'popular', score: 99 }])
      return
    }
    if (pathname === '/search/suggestions') {
      await fulfillJson(route, [])
      return
    }
    if (pathname === '/search/recommendations') {
      await fulfillJson(route, [
        {
          productId: 1,
          name: 'Recommended product',
          title: 'A recommendation',
          imageUrl: '/images/recommendation.jpg',
          reason: 'Matches your profile',
          score: 0.91,
        },
      ])
      return
    }
    if (pathname === '/search/conversions') {
      options.onConversion?.()
      if (options.hangConversions) {
        return
      }
      await fulfillJson(route, null)
      return
    }
    if (pathname === '/auth/captcha/config') {
      await fulfillJson(route, { provider: 'local', siteKey: '' })
      return
    }

    await fulfillJson(route, null)
  })
}

function recordSearchCall(calls: SearchCall[], url: URL) {
  calls.push({
    categoryId: url.searchParams.get('categoryId'),
    keyword: url.searchParams.get('keyword') ?? '',
    page: Number(url.searchParams.get('page') ?? 0),
  })
}

test('hot keyword starts one explicit search without a watcher duplicate', async ({ page }) => {
  const calls: SearchCall[] = []
  await installMocks(page, {
    onSearch: async (route, url) => {
      recordSearchCall(calls, url)
      await fulfillJson(route, searchPage(url.searchParams.get('keyword') || 'Catalog result'))
    },
  })

  await page.goto('/search')
  await expect(page.locator('.product-card')).toHaveCount(1)
  await page.getByRole('button', { name: /popular/ }).click()

  await expect.poll(() => calls.filter((call) => call.keyword === 'popular').length).toBe(1)
  await page.waitForTimeout(450)
  expect(calls.filter((call) => call.keyword === 'popular')).toHaveLength(1)
})

test('pagination starts one explicit search without a watcher duplicate', async ({ page }) => {
  const calls: SearchCall[] = []
  await installMocks(page, {
    onSearch: async (route, url) => {
      recordSearchCall(calls, url)
      const pageNumber = Number(url.searchParams.get('page') ?? 0)
      await fulfillJson(route, searchPage(`Page ${pageNumber + 1}`, pageNumber))
    },
  })

  await page.goto('/search?q=golden')
  await expect(page.locator('.product-card')).toHaveCount(1)
  await page.locator('.el-pager li.number').filter({ hasText: '2' }).click()

  await expect.poll(() => calls.filter((call) => call.page === 1).length).toBe(1)
  await page.waitForTimeout(450)
  expect(calls.filter((call) => call.page === 1)).toHaveLength(1)
})

test('clearing filters starts one explicit search without a watcher duplicate', async ({
  page,
}) => {
  const calls: SearchCall[] = []
  await installMocks(page, {
    onSearch: async (route, url) => {
      recordSearchCall(calls, url)
      await fulfillJson(route, searchPage(url.searchParams.get('keyword') || 'All products'))
    },
  })

  await page.goto('/search?q=golden&category=7')
  await expect(page.locator('.product-card')).toHaveCount(1)
  await page.getByRole('button', { name: 'Clear filters', exact: true }).click()

  const clearedCalls = () => calls.filter((call) => call.keyword === '' && call.categoryId === null)
  await expect.poll(() => clearedCalls().length).toBe(1)
  await page.waitForTimeout(450)
  expect(clearedCalls()).toHaveLength(1)
})

test('a late older search response cannot replace the latest query results', async ({ page }) => {
  const pending = new Map<string, Route>()
  await installMocks(page, {
    onSearch: async (route, url) => {
      const keyword = url.searchParams.get('keyword') ?? ''
      if (!keyword) {
        await fulfillJson(route, searchPage('Initial result'))
        return
      }
      pending.set(keyword, route)
    },
  })

  await page.goto('/search')
  await expect(page.getByRole('heading', { name: 'Initial result', level: 2 })).toBeVisible()
  const keyword = page.getByLabel('Keyword, product, breed')

  await keyword.fill('older')
  await expect.poll(() => pending.has('older')).toBe(true)
  await keyword.fill('latest')
  await expect.poll(() => pending.has('latest')).toBe(true)

  await fulfillJson(pending.get('latest')!, searchPage('Latest result'))
  await expect(page.getByRole('heading', { name: 'Latest result', level: 2 })).toBeVisible()
  await fulfillJson(pending.get('older')!, searchPage('Stale result'))
  await page.waitForTimeout(100)

  await expect(page.getByRole('heading', { name: 'Latest result', level: 2 })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Stale result', level: 2 })).toHaveCount(0)
})

test('search navigation does not wait for a hanging conversion request', async ({ page }) => {
  let conversionCalls = 0
  await installMocks(page, {
    hangConversions: true,
    onConversion: () => {
      conversionCalls += 1
    },
  })

  await page.goto('/search?q=golden')
  await page.getByRole('button', { name: 'Open', exact: true }).click()

  await expect(page).toHaveURL(/\/shop\/1$/, { timeout: 1500 })
  expect(conversionCalls).toBe(1)
})

test('recommendation navigation does not wait for a hanging conversion request', async ({
  page,
}) => {
  let conversionCalls = 0
  await installMocks(page, {
    hangConversions: true,
    onConversion: () => {
      conversionCalls += 1
    },
  })

  await page.goto('/recommendations')
  await page.getByRole('button', { name: 'Open', exact: true }).click()

  await expect(page).toHaveURL(/\/shop\/1$/, { timeout: 1500 })
  expect(conversionCalls).toBe(1)
})
