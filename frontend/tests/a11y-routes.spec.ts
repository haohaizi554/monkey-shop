import { AxeBuilder } from '@axe-core/playwright'
import { expect, test, type Locator, type Page, type Route } from '@playwright/test'

type SessionRole = 'anonymous' | 'user' | 'admin'

interface RouteCase {
  name: string
  path: string
  ready: (page: Page) => Locator
  expectedUrl?: RegExp
}

const monkey = {
  id: 1,
  name: 'Golden Monkey',
  breed: 'Sichuan golden snub-nosed monkey',
  price: '128.00',
  description: 'A calm companion with a golden coat.',
  imageUrl: '/images/default_product.jpg',
  stock: 6,
}

const catalogSpu = {
  id: 1,
  categoryId: 7,
  name: monkey.name,
  title: monkey.breed,
  status: 'LISTED',
  originalPrice: '158.00',
  memberPrice: monkey.price,
  strikePrice: '168.00',
  regionPrices: {},
  attributes: { description: monkey.description },
  imageUrl: monkey.imageUrl,
  skus: [
    {
      id: 101,
      spuId: 1,
      skuCode: 'GM-GOLD',
      specification: { coat: 'golden' },
      originalPrice: '158.00',
      memberPrice: monkey.price,
      strikePrice: '168.00',
      regionPrices: {},
      active: true,
    },
  ],
}

const address = {
  id: 1,
  receiverName: 'Alex Chen',
  phone: '13800138000',
  detailAddress: 'No. 1 Monkey Street, Hangzhou',
  isDefault: 1,
}

const order = {
  id: 101,
  orderNo: 'ORDER-101',
  userId: 7,
  buyerName: 'Alex Chen',
  productId: 1,
  productName: monkey.name,
  productImage: monkey.imageUrl,
  price: monkey.price,
  receiverName: address.receiverName,
  receiverPhone: '138****8000',
  addressSnapshot: address.detailAddress,
  status: 'COMPLETED',
  createTime: '2026-07-12T08:00:00Z',
  shippingTime: '2026-07-12T09:00:00Z',
}

const cart = {
  userId: 7,
  items: [
    {
      skuId: 101,
      shopId: 11,
      productName: monkey.name,
      productImage: monkey.imageUrl,
      unitPrice: monkey.price,
      quantity: 1,
      selected: true,
      lineAmount: monkey.price,
      updatedAt: '2026-07-12T08:00:00Z',
    },
  ],
  selectedQuantity: 1,
  selectedAmount: monkey.price,
}

const payment = {
  id: 11,
  paymentNo: 'PAY-101',
  orderId: 101,
  userId: 7,
  method: 'WECHAT',
  amount: monkey.price,
  paidAmount: monkey.price,
  refundedAmount: '0.00',
  status: 'PAID',
  createTime: '2026-07-12T08:05:00Z',
}

const logisticsTracking = {
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

const membershipDashboard = {
  profile: {
    userId: 7,
    level: 'SILVER',
    growthValue: 1250,
    verified: true,
    maskedRealName: 'A***',
    maskedIdCardNo: '310***********1234',
    version: 1,
    benefits: [],
  },
  wallet: {
    userId: 7,
    balance: 860,
    totalEarned: 1200,
    totalSpent: 340,
    moneyEquivalent: '8.60',
    version: 1,
  },
  coupons: [
    {
      id: 1,
      couponId: 2,
      couponCode: 'SAVE-20',
      status: 'CLAIMED',
      claimedAt: '2026-07-10T08:00:00Z',
    },
  ],
  collections: [],
  browseHistory: [],
}

const tenant = {
  id: 1,
  code: 'alpha',
  name: 'Tenant Alpha',
  status: 'ACTIVE',
  plan: 'GROWTH',
  maskedContactPhone: '138****0001',
  createdAt: '2026-01-01T00:00:00Z',
  expiresAt: '2027-01-01T00:00:00Z',
  version: 1,
}

const apiFixtures: Record<string, unknown> = {
  'GET /auth/captcha/config': { provider: 'local', siteKey: '' },
  'GET /auth/password-policy': {
    minLength: 10,
    requireUppercase: true,
    requireLowercase: true,
    requireDigit: true,
    requireSpecial: true,
    forbidWhitespace: true,
  },
  'GET /monkeys': {
    content: [monkey],
    page: 0,
    size: 100,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  },
  'GET /catalog/categories/tree': [
    { id: 7, name: 'Companions', children: [] },
    { id: 8, name: 'Golden coats', children: [] },
    { id: 9, name: 'Gentle temperaments', children: [] },
  ],
  'GET /catalog/spus/1': catalogSpu,
  'GET /catalog/spus/1/price': {
    spuId: 1,
    salePrice: monkey.price,
    strikePrice: '168.00',
    strategy: 'MEMBER',
  },
  'GET /inventory/skus/101/stocks': [
    {
      skuId: 101,
      warehouseId: 11,
      warehouseCode: 'CN-HZ-1',
      province: 'Zhejiang',
      availableQuantity: 6,
      lockedQuantity: 1,
      deductedQuantity: 2,
      inTransitQuantity: 3,
      safetyStock: 2,
      totalQuantity: 9,
      belowSafetyStock: false,
    },
  ],
  'POST /marketing/price/quote': {
    originalAmount: monkey.price,
    discountAmount: '8.00',
    payableAmount: '120.00',
    appliedCoupons: ['WELCOME'],
  },
  'POST /membership/browse': null,
  'GET /search/products': {
    content: [
      {
        productId: 1,
        categoryId: 7,
        name: monkey.name,
        title: monkey.description,
        imageUrl: monkey.imageUrl,
        originalPrice: '158.00',
        memberPrice: monkey.price,
        attributes: { coat: 'golden' },
        score: 0.98,
      },
    ],
    page: 0,
    size: 12,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  },
  'GET /search/hot': [{ keyword: 'golden', score: 12 }],
  'GET /search/suggestions': [{ keyword: 'golden monkey', source: 'catalog', score: 0.9 }],
  'GET /search/recommendations': [
    {
      productId: 1,
      name: monkey.name,
      title: monkey.description,
      imageUrl: monkey.imageUrl,
      reason: 'Matches your saved interests',
      score: 0.91,
    },
  ],
  'GET /orders/my': {
    content: [order],
    page: 0,
    size: 100,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  },
  'GET /orders/101': order,
  'GET /orders/review/101': [],
  'GET /payments/orders/101': payment,
  'GET /logistics/orders/101': logisticsTracking,
  'GET /membership/dashboard': membershipDashboard,
  'GET /membership/admin/7/dashboard': membershipDashboard,
  'GET /cart': cart,
  'GET /users/profile': {
    isLogin: true,
    identity: 'USER',
    username: 'member',
    maskedPhone: '138****8000',
    avatar: '/images/default_avatar.jpg',
    passwordChangeRequired: false,
  },
  'GET /addresses': {
    content: [address],
    page: 0,
    size: 100,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  },
  'POST /tracking/events': { id: 1, eventType: 'PAGE_VIEW' },
  'GET /stats/data': {
    totalGmv: '128.00',
    totalOrders: 1,
    totalVisits: 24,
    returnRate: '0%',
    xAxis: [],
    seriesOrder: [],
    seriesGmv: [],
    seriesVisit: [],
  },
  'GET /orders/all': {
    content: [order],
    page: 0,
    size: 100,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  },
  'GET /orders/admin/101/shipments': [],
  'GET /risk/reviews': [
    {
      id: 101,
      userId: 7,
      productId: 1,
      type: 'PRICE_ANOMALY',
      score: 88,
      status: 'PENDING',
      detail: 'Price changed quickly',
      createdAt: '2026-07-12T08:00:00Z',
    },
  ],
  'GET /tracking/dashboard': {
    pageViews: 128,
    uniqueVisitors: 42,
    orderCount: 9,
    paymentAmount: '8200.00',
    funnel: [
      { eventType: 'SEARCH', count: 30, conversionRate: '1' },
      { eventType: 'PAYMENT_SUCCESS', count: 9, conversionRate: '0.3' },
    ],
    generatedAt: '2026-07-12T08:00:00Z',
    refreshIntervalSeconds: 60,
  },
  'GET /tracking/profile/me': {
    userId: 7,
    profileSummary: 'last=PAGE_VIEW,page=/dashboard,source=web',
    behaviorTags: ['event:page_view'],
    interestTags: ['golden'],
    lastEventAt: '2026-07-12T08:00:00Z',
    version: 1,
  },
  'GET /tracking/products/1': {
    productId: 1,
    tagVector: ['popular'],
    salesCount: 12,
    reviewScore: '4.8',
    lastEventAt: '2026-07-12T08:00:00Z',
    version: 1,
  },
  'GET /tenants/dashboard': {
    activeTenants: 1,
    expiredTenants: 0,
    currentMonthOrders: 18,
    currentMonthRevenue: '16800.00',
    tenants: [tenant],
  },
  'GET /tenants': [tenant],
  'GET /tenants/1/configs': [
    {
      id: 11,
      tenantId: 1,
      configType: 'PAYMENT',
      provider: 'sandbox-provider',
      settings: {},
      enabled: true,
      updatedAt: '2026-07-12T08:00:00Z',
      version: 1,
    },
  ],
  'GET /tenants/1/bills': [
    {
      id: 21,
      tenantId: 1,
      billingMonth: '2026-07',
      orderCount: 18,
      totalAmount: '16800.00',
      status: 'GENERATED',
      version: 1,
    },
  ],
  'GET /tenants/1/exports': [
    {
      id: 31,
      tenantId: 1,
      exportType: 'FULL',
      status: 'SUCCEEDED',
      artifactAvailable: true,
      requestedBy: 1,
      requestedAt: '2026-07-12T08:00:00Z',
      version: 1,
    },
    {
      id: 32,
      tenantId: 1,
      exportType: 'ORDERS',
      status: 'QUEUED',
      artifactAvailable: false,
      requestedBy: 1,
      requestedAt: '2026-07-12T08:01:00Z',
      version: 1,
    },
    {
      id: 33,
      tenantId: 1,
      exportType: 'USERS',
      status: 'RUNNING',
      artifactAvailable: false,
      requestedBy: 1,
      requestedAt: '2026-07-12T08:02:00Z',
      version: 1,
    },
    {
      id: 34,
      tenantId: 1,
      exportType: 'FULL',
      status: 'FAILED',
      artifactAvailable: false,
      requestedBy: 1,
      requestedAt: '2026-07-12T08:03:00Z',
      version: 1,
    },
    {
      id: 35,
      tenantId: 1,
      exportType: 'FULL',
      status: 'UNAVAILABLE',
      artifactAvailable: false,
      requestedBy: 1,
      requestedAt: '2026-07-12T08:04:00Z',
      version: 1,
    },
  ],
}

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'a11y-routes-test' }
}

async function fulfillJson(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(ok(data)),
  })
}

async function fulfillImage(route: Route) {
  await route.fulfill({
    status: 200,
    contentType: 'image/svg+xml',
    body: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 3"><rect width="4" height="3" fill="#d8dee8"/></svg>',
  })
}

function session(role: SessionRole) {
  if (role === 'anonymous') return { isLogin: false }
  return {
    isLogin: true,
    identity: role === 'admin' ? 'ADMIN' : 'USER',
    username: role === 'admin' ? 'admin' : 'member',
    maskedPhone: '138****8000',
    passwordChangeRequired: false,
  }
}

function dynamicFixture(method: string, pathname: string): unknown {
  if (method === 'GET' && /^\/inventory\/skus\/\d+\/stocks$/.test(pathname)) {
    const skuId = Number(pathname.split('/')[3])
    return [
      {
        skuId,
        warehouseId: 1,
        warehouseCode: 'EAST-1',
        province: 'East',
        availableQuantity: 12,
        lockedQuantity: 8,
        deductedQuantity: 0,
        inTransitQuantity: 0,
        safetyStock: 3,
        totalQuantity: 20,
        belowSafetyStock: false,
      },
    ]
  }
  return undefined
}

async function installApiMocks(page: Page, role: SessionRole, theme: 'light' | 'dark' = 'light') {
  await page.clock.setFixedTime(new Date('2026-07-19T00:00:00+08:00'))
  const unhandledRequests: string[] = []
  await page.addInitScript((currentTheme) => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', currentTheme)
  }, theme)
  await page.emulateMedia({ reducedMotion: 'reduce', colorScheme: theme })

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')
    const method = request.method()

    if (pathname === '/auth/captcha' || pathname === '/users/captcha') {
      await fulfillImage(route)
      return
    }

    expect(
      request.headers()['x-trace-id'],
      `${method} ${pathname} must carry a legal trace header`,
    ).toMatch(/^[A-Za-z0-9._:-]{1,128}$/)

    if (method === 'GET' && pathname === '/users/me') {
      await fulfillJson(route, session(role))
      return
    }

    const key = `${method} ${pathname}`
    const fixture = Object.prototype.hasOwnProperty.call(apiFixtures, key)
      ? apiFixtures[key]
      : dynamicFixture(method, pathname)
    if (fixture !== undefined) {
      await fulfillJson(route, fixture)
      return
    }

    unhandledRequests.push(key)
    await route.fulfill({
      status: 501,
      contentType: 'application/problem+json',
      body: JSON.stringify({ title: `Unhandled a11y mock: ${key}`, status: 501 }),
    })
  })

  return unhandledRequests
}

async function settleRoute(page: Page, routeCase: RouteCase) {
  await page.goto(routeCase.path)
  if (routeCase.expectedUrl) await expect(page).toHaveURL(routeCase.expectedUrl)
  await expect(routeCase.ready(page)).toBeVisible({ timeout: 15_000 })
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.async-state-view[data-status="loading"]:visible')).toHaveCount(0)
}

async function expectNoAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
  const summary = results.violations
    .map(
      (violation) =>
        `${violation.id}: ${violation.help} (${violation.nodes.length} affected node(s))`,
    )
    .join('\n')
  expect(results.violations, summary).toEqual([])
}

function pageHeader(page: Page) {
  return page.locator('.page-header h1')
}

const consumerRoutes: RouteCase[] = [
  {
    name: 'root redirect',
    path: '/',
    expectedUrl: /\/shop$/,
    ready: (page) => page.getByRole('heading', { name: 'MonkeyShop', level: 1 }),
  },
  {
    name: 'shop',
    path: '/shop',
    ready: (page) => page.getByRole('heading', { name: 'MonkeyShop', level: 1 }),
  },
  {
    name: 'product detail',
    path: '/shop/1',
    ready: (page) => page.getByRole('heading', { name: monkey.name, level: 1 }),
  },
  { name: 'search', path: '/search?q=golden', ready: pageHeader },
  { name: 'recommendations', path: '/recommendations', ready: pageHeader },
  { name: 'orders', path: '/orders', ready: pageHeader },
  { name: 'order review', path: '/orders/101/review', ready: pageHeader },
  { name: 'payment', path: '/payment/101', ready: pageHeader },
  { name: 'logistics', path: '/logistics/101', ready: pageHeader },
  { name: 'membership', path: '/membership', ready: pageHeader },
  { name: 'cart', path: '/cart', ready: pageHeader },
  { name: 'checkout form', path: '/checkout', ready: pageHeader },
  { name: 'profile forms', path: '/profile', ready: pageHeader },
  {
    name: 'not found',
    path: '/route-that-does-not-exist',
    ready: (page) => page.getByRole('heading', { name: 'Page not found', level: 1 }),
  },
]

const adminRoutes: RouteCase[] = [
  { name: 'store operations', path: '/admin', ready: pageHeader },
  { name: 'order operations', path: '/admin/orders', ready: pageHeader },
  { name: 'return operations', path: '/admin/returns', ready: pageHeader },
  { name: 'payment operations', path: '/admin/payments', ready: pageHeader },
  { name: 'logistics operations', path: '/admin/logistics', ready: pageHeader },
  { name: 'member operations', path: '/admin/members?userId=7', ready: pageHeader },
  { name: 'inventory', path: '/inventory?skuId=7', ready: pageHeader },
  { name: 'marketing', path: '/marketing', ready: pageHeader },
  { name: 'risk review', path: '/risk', ready: pageHeader },
  { name: 'dashboard', path: '/dashboard', ready: pageHeader },
  { name: 'tenant administration', path: '/tenants?tenant=1', ready: pageHeader },
]

test('login route and every progressive authentication form pass Axe', async ({ page }) => {
  test.setTimeout(60_000)
  const unhandled = await installApiMocks(page, 'anonymous')
  await page.goto('/login')
  await expect(page.getByRole('tabpanel', { name: 'Sign in' })).toBeVisible({ timeout: 30_000 })
  await page.waitForLoadState('networkidle')
  await expectNoAxeViolations(page)

  await page.getByRole('tab', { name: 'Register' }).click()
  await expect(page.getByRole('tabpanel', { name: 'Register' })).toBeVisible()
  await expectNoAxeViolations(page)

  await page.getByRole('tab', { name: 'Reset password' }).click()
  await expect(page.getByRole('tabpanel', { name: 'Reset password' })).toBeVisible()
  await expectNoAxeViolations(page)
  expect(unhandled).toEqual([])
})

for (const routeCase of consumerRoutes) {
  test(`consumer route: ${routeCase.name} passes Axe in a real rendered state`, async ({
    page,
  }) => {
    const unhandled = await installApiMocks(page, 'user')
    await settleRoute(page, routeCase)
    await expectNoAxeViolations(page)
    expect(unhandled).toEqual([])
  })
}

for (const routeCase of adminRoutes) {
  test(`admin route: ${routeCase.name} passes Axe in a real rendered state`, async ({ page }) => {
    const unhandled = await installApiMocks(page, 'admin')
    await settleRoute(page, routeCase)
    await expectNoAxeViolations(page)
    expect(unhandled).toEqual([])
  })
}

test('tenant export state table passes Axe with all five provider states visible', async ({
  page,
}) => {
  const unhandled = await installApiMocks(page, 'admin')
  await settleRoute(
    page,
    adminRoutes.find((item) => item.name === 'tenant administration')!,
  )
  await page.getByRole('tab', { name: 'Export' }).click()
  const exportPanel = page.getByRole('tabpanel', { name: 'Export' })
  await expect(exportPanel.getByText('Queued', { exact: true })).toBeVisible()
  await expect(exportPanel.getByText('Running', { exact: true })).toBeVisible()
  await expect(exportPanel.getByText('Succeeded', { exact: true })).toBeVisible()
  await expect(exportPanel.getByText('Failed', { exact: true })).toBeVisible()
  await expect(exportPanel.getByText('Unavailable', { exact: true })).toBeVisible()
  await expectNoAxeViolations(page)
  expect(unhandled).toEqual([])
})

test('consumer confirmation dialog passes Axe', async ({ page }) => {
  const unhandled = await installApiMocks(page, 'user')
  await settleRoute(
    page,
    consumerRoutes.find((item) => item.name === 'orders')!,
  )
  await page.getByRole('button', { name: 'Return', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expectNoAxeViolations(page)
  expect(unhandled).toEqual([])
})

test('admin product dialog and tenant dialog pass Axe', async ({ page }) => {
  const unhandled = await installApiMocks(page, 'admin')
  await settleRoute(
    page,
    adminRoutes.find((item) => item.name === 'store operations')!,
  )
  await page.getByRole('button', { name: 'Create product', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Create product' })).toBeVisible()
  await expectNoAxeViolations(page)
  await page.getByRole('button', { name: 'Cancel', exact: true }).click()

  await settleRoute(
    page,
    adminRoutes.find((item) => item.name === 'tenant administration')!,
  )
  await page.getByRole('button', { name: 'Create tenant', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Create a tenant' })).toBeVisible()
  await expectNoAxeViolations(page)
  expect(unhandled).toEqual([])
})

test('risk decision drawer passes Axe', async ({ page }) => {
  const unhandled = await installApiMocks(page, 'admin')
  await settleRoute(
    page,
    adminRoutes.find((item) => item.name === 'risk review')!,
  )
  await page.getByRole('button', { name: 'Block case 101' }).click()
  await expect(page.locator('.el-drawer')).toBeVisible()
  await expectNoAxeViolations(page)
  expect(unhandled).toEqual([])
})

async function expectVisualBaseline(page: Page, name: string) {
  await page.waitForFunction(
    () =>
      Array.from(document.images)
        .filter((image) => {
          const rect = image.getBoundingClientRect()
          return (
            rect.width > 0 &&
            rect.height > 0 &&
            rect.bottom > 0 &&
            rect.right > 0 &&
            rect.top < window.innerHeight &&
            rect.left < window.innerWidth
          )
        })
        .every((image) => image.complete && image.naturalWidth > 0),
    undefined,
    { timeout: 10_000 },
  )
  await expectViewportFit(page, name)
  await expect(page).toHaveScreenshot(name, {
    animations: 'disabled',
    caret: 'hide',
    scale: 'css',
  })
}

async function expectViewportFit(page: Page, name: string) {
  const viewport = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(
    viewport.scrollWidth,
    `${name} must not overflow the viewport horizontally`,
  ).toBeLessThanOrEqual(viewport.clientWidth + 1)
}

function consumerRoute(name: string) {
  return consumerRoutes.find((item) => item.name === name)!
}

function adminRoute(name: string) {
  return adminRoutes.find((item) => item.name === name)!
}

test.describe('visual route baselines', () => {
  test.skip(process.env.RUN_VISUAL_BASELINES !== '1', 'Run through npm run test:visual')

  test('visual baseline: authentication shell on desktop and mobile', async ({ page }) => {
    test.setTimeout(60_000)
    const unhandled = await installApiMocks(page, 'anonymous')

    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/login')
    await expect(page.getByRole('tabpanel', { name: 'Sign in' })).toBeVisible({ timeout: 30_000 })
    await expectVisualBaseline(page, 'auth-login-desktop-light.png')

    await page.setViewportSize({ width: 390, height: 844 })
    await page.reload()
    await expect(page.getByRole('tabpanel', { name: 'Sign in' })).toBeVisible({ timeout: 30_000 })
    await expectVisualBaseline(page, 'auth-login-mobile-light.png')
    expect(unhandled).toEqual([])
  })

  test('visual baseline: every consumer route on desktop plus core mobile flows', async ({
    page,
  }) => {
    test.setTimeout(120_000)
    const unhandled = await installApiMocks(page, 'user')

    await page.setViewportSize({ width: 1440, height: 900 })
    for (const routeCase of consumerRoutes.filter((item) => item.name !== 'root redirect')) {
      await settleRoute(page, routeCase)
      await expectVisualBaseline(
        page,
        `consumer-${routeCase.name.replaceAll(' ', '-')}-desktop-light.png`,
      )
    }

    await page.setViewportSize({ width: 390, height: 844 })
    for (const routeCase of [
      consumerRoute('shop'),
      consumerRoute('cart'),
      consumerRoute('checkout form'),
    ]) {
      await settleRoute(page, routeCase)
      await expectVisualBaseline(
        page,
        `consumer-${routeCase.name.replaceAll(' ', '-')}-mobile-light.png`,
      )
    }
    expect(unhandled).toEqual([])
  })

  test('visual baseline: consumer dark theme', async ({ page }) => {
    const unhandled = await installApiMocks(page, 'user', 'dark')
    await page.setViewportSize({ width: 1440, height: 900 })
    await settleRoute(page, consumerRoute('shop'))
    await expectVisualBaseline(page, 'consumer-shop-desktop-dark.png')
    expect(unhandled).toEqual([])
  })

  test('visual baseline: every admin workspace', async ({ page }) => {
    test.setTimeout(120_000)
    const unhandled = await installApiMocks(page, 'admin')
    await page.setViewportSize({ width: 1440, height: 900 })
    for (const routeCase of adminRoutes) {
      await settleRoute(page, routeCase)
      await expectVisualBaseline(
        page,
        `admin-${routeCase.name.replaceAll(' ', '-')}-desktop-light.png`,
      )
    }
    expect(unhandled).toEqual([])
  })

  test('visual baseline: every consumer route fits the mobile viewport', async ({ page }) => {
    test.setTimeout(120_000)
    const unhandled = await installApiMocks(page, 'user')
    await page.setViewportSize({ width: 390, height: 844 })

    for (const routeCase of consumerRoutes) {
      await settleRoute(page, routeCase)
      await expectViewportFit(page, `consumer ${routeCase.name} mobile`)
    }
    expect(unhandled).toEqual([])
  })

  test('visual baseline: every admin workspace fits the mobile viewport', async ({ page }) => {
    test.setTimeout(120_000)
    const unhandled = await installApiMocks(page, 'admin')
    await page.setViewportSize({ width: 390, height: 844 })

    for (const routeCase of adminRoutes) {
      await settleRoute(page, routeCase)
      await expectViewportFit(page, `admin ${routeCase.name} mobile`)
    }
    expect(unhandled).toEqual([])
  })

  test('visual baseline: admin dark theme', async ({ page }) => {
    const unhandled = await installApiMocks(page, 'admin', 'dark')
    await page.setViewportSize({ width: 1440, height: 900 })
    await settleRoute(page, adminRoute('store operations'))
    await expectVisualBaseline(page, 'admin-store-operations-desktop-dark.png')
    expect(unhandled).toEqual([])
  })
})
