import { existsSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import { chromium } from '@playwright/test'
import { createServer } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const now = '2026-07-05T12:00:00+08:00'
const maxH1Px = 36
const maxApiRequestsPerPage = 24
const bannedCopy = ['Too many requests', 'Operation is not permitted']

const viewports = [
  {
    name: 'desktop',
    options: {
      viewport: { width: 1440, height: 900 },
      deviceScaleFactor: 1,
      isMobile: false,
      hasTouch: false,
    },
  },
  {
    name: 'tablet',
    options: {
      viewport: { width: 768, height: 1024 },
      deviceScaleFactor: 1,
      isMobile: false,
      hasTouch: true,
    },
  },
  {
    name: 'mobile',
    options: {
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2,
      isMobile: true,
      hasTouch: true,
    },
  },
]

const routes = [
  { path: '/login', auth: false },
  { path: '/shop', auth: true },
  { path: '/shop/1', auth: true },
  { path: '/search', auth: true },
  { path: '/recommendations', auth: true },
  { path: '/orders', auth: true },
  { path: '/orders/1/review', auth: true },
  { path: '/payment/1', auth: true },
  { path: '/logistics/1', auth: true },
  { path: '/membership', auth: true },
  { path: '/cart', auth: true },
  { path: '/checkout', auth: true },
  { path: '/profile', auth: true },
  { path: '/admin', auth: true },
  { path: '/inventory', auth: true },
  { path: '/marketing', auth: true },
  { path: '/risk', auth: true },
  { path: '/dashboard', auth: true },
  { path: '/tenants', auth: true },
]

const product = {
  id: 1,
  name: 'Golden Snub-nosed',
  breed: 'Rhinopithecus roxellana',
  price: '128.00',
  description: 'Healthy catalog item used for UI smoke checks.',
  imageUrl: '/images/default_product.jpg',
  stock: 8,
  categoryId: 10,
  categoryName: 'Featured',
  status: 'LISTED',
  memberPrice: '118.00',
  strikePrice: '168.00',
  regionPrices: { 'CN-ZJ': '118.00' },
  attributes: { color: 'gold', description: 'Featured product profile.' },
  skus: [
    {
      id: 101,
      spuId: 1,
      skuCode: 'SKU-GOLD-1',
      specification: { Size: 'Standard' },
      originalPrice: '128.00',
      memberPrice: '118.00',
      strikePrice: '168.00',
      regionPrices: { 'CN-ZJ': '118.00' },
      active: true,
    },
  ],
}

const address = {
  id: 1,
  receiverName: 'Codex User',
  phone: '138****8000',
  detailAddress: 'Hangzhou Xihu District No. 100',
  isDefault: 1,
}

const order = {
  id: 1,
  orderNo: 'MS202607050001',
  userId: 1,
  buyerName: 'Codex User',
  buyerAvatar: '/images/default_avatar.jpg',
  productId: product.id,
  productName: product.name,
  productImage: product.imageUrl,
  price: product.price,
  description: product.description,
  receiverName: address.receiverName,
  receiverPhone: address.phone,
  addressSnapshot: address.detailAddress,
  shippingTime: now,
  status: 'COMPLETED',
  createTime: now,
}

const stock = {
  skuId: 101,
  warehouseId: 1,
  warehouseCode: 'HZ-01',
  province: 'CN-ZJ',
  availableQuantity: 88,
  lockedQuantity: 3,
  deductedQuantity: 12,
  inTransitQuantity: 6,
  safetyStock: 10,
  totalQuantity: 109,
  belowSafetyStock: false,
}

const cart = {
  userId: 1,
  items: [
    {
      skuId: 101,
      shopId: 1,
      productName: product.name,
      productImage: product.imageUrl,
      unitPrice: product.price,
      quantity: 2,
      selected: true,
      lineAmount: '256.00',
      updatedAt: now,
    },
  ],
  selectedQuantity: 2,
  selectedAmount: '256.00',
}

const checkout = {
  id: 1,
  checkoutNo: 'CO202607050001',
  userId: 1,
  addressId: 1,
  originalAmount: '256.00',
  discountAmount: '20.00',
  payableAmount: '236.00',
  status: 'RESERVED',
  province: 'CN-ZJ',
  createdAt: now,
  subOrders: [
    {
      id: 11,
      shopId: 1,
      orderNo: 'SUB202607050001',
      originalAmount: '256.00',
      discountAmount: '20.00',
      payableAmount: '236.00',
      status: 'RESERVED',
      lines: [
        {
          id: 111,
          skuId: 101,
          shopId: 1,
          categoryId: 10,
          productName: product.name,
          productImage: product.imageUrl,
          quantity: 2,
          unitPrice: product.price,
          originalAmount: '256.00',
          discountAmount: '20.00',
          payableAmount: '236.00',
          couponCodes: ['PLATFORM-20'],
          reservationKey: 'RSV-UI-001',
          warehouseId: 1,
        },
      ],
    },
  ],
}

const tenant = {
  id: 1,
  code: 'platform',
  name: 'MonkeyShop Platform Tenant',
  status: 'ACTIVE',
  plan: 'ENTERPRISE',
  contactName: 'Ops',
  maskedContactPhone: '138****8000',
  createdAt: now,
  expiresAt: '2027-07-05T12:00:00+08:00',
  version: 1,
}

function ok(data) {
  return {
    code: 'OK',
    message: 'ok',
    data,
    traceId: 'ui-smoke-trace',
  }
}

function browserExecutablePath() {
  const candidates = [
    process.env.CHROME_PATH,
    process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
    process.env.ProgramFiles
      ? path.join(process.env.ProgramFiles, 'Google', 'Chrome', 'Application', 'chrome.exe')
      : undefined,
    process.env['ProgramFiles(x86)']
      ? path.join(process.env['ProgramFiles(x86)'], 'Google', 'Chrome', 'Application', 'chrome.exe')
      : undefined,
    process.env.LOCALAPPDATA
      ? path.join(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'Application', 'chrome.exe')
      : undefined,
    process.env['ProgramFiles(x86)']
      ? path.join(
          process.env['ProgramFiles(x86)'],
          'Microsoft',
          'Edge',
          'Application',
          'msedge.exe',
        )
      : undefined,
    process.env.ProgramFiles
      ? path.join(process.env.ProgramFiles, 'Microsoft', 'Edge', 'Application', 'msedge.exe')
      : undefined,
  ].filter(Boolean)
  return candidates.find((candidate) => existsSync(candidate))
}

function svgBody(label, fill = '#d9e2ec') {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 520" role="img" aria-label="${label}"><rect width="800" height="520" fill="${fill}"/><circle cx="400" cy="260" r="118" fill="#7fb069"/><text x="400" y="284" text-anchor="middle" font-family="Arial" font-size="48" fill="#1f2937">${label}</text></svg>`
}

function catalogSpu() {
  return {
    id: product.id,
    categoryId: product.categoryId,
    name: product.name,
    title: product.breed,
    status: 'LISTED',
    originalPrice: product.price,
    memberPrice: product.memberPrice,
    strikePrice: product.strikePrice,
    regionPrices: product.regionPrices,
    attributes: product.attributes,
    imageUrl: product.imageUrl,
    skus: product.skus,
  }
}

function stats() {
  return {
    totalGmv: '23888.00',
    totalOrders: 128,
    totalVisits: 9832,
    returnRate: '1.8%',
    xAxis: ['Mon', 'Tue', 'Wed'],
    seriesOrder: [30, 42, 56],
    seriesGmv: ['5600.00', '7800.00', '10488.00'],
    seriesVisit: [2000, 3180, 4652],
  }
}

function payment() {
  return {
    id: 1,
    paymentNo: 'PAY202607050001',
    orderId: 1,
    userId: 1,
    method: 'WECHAT',
    amount: '128.00',
    paidAmount: '128.00',
    refundedAmount: '0.00',
    status: 'PAID',
    providerTradeNo: 'WX-UI-001',
    createTime: now,
    paidAt: now,
  }
}

function logistics() {
  return {
    id: 1,
    trackingNo: 'SFUIFLOW789CN',
    orderId: 1,
    userId: 1,
    carrier: 'SF',
    status: 'IN_TRANSIT',
    province: 'Zhejiang',
    city: 'Hangzhou',
    district: 'Xihu',
    detailSummary: 'Hangzhou Xihu District',
    freightAmount: '12.00',
    etaHours: 24,
    pickedUpAt: now,
    inTransitAt: now,
    createTime: now,
    updateTime: now,
    events: [
      {
        id: 1,
        eventType: 'PICKUP',
        fromStatus: 'ORDERED',
        toStatus: 'PICKED_UP',
        eventId: 'EVT-1',
        eventTime: now,
        location: 'Hangzhou',
        remark: 'Package picked up',
      },
    ],
  }
}

function membershipDashboard() {
  return {
    profile: {
      userId: 1,
      level: 'GOLD',
      growthValue: 6800,
      verified: true,
      maskedRealName: 'C***x',
      maskedIdCardNo: '3301********1234',
      version: 1,
      benefits: ['priority-service'],
    },
    wallet: {
      userId: 1,
      balance: 2880,
      totalEarned: 5200,
      totalSpent: 2320,
      moneyEquivalent: '28.80',
      version: 1,
    },
    coupons: [
      {
        id: 1,
        couponId: 101,
        couponCode: 'PLATFORM-20',
        status: 'CLAIMED',
        claimedAt: now,
      },
    ],
    collections: [
      {
        id: 1,
        productId: product.id,
        productName: product.name,
        productImage: product.imageUrl,
        lastPrice: product.price,
        targetPrice: '99.00',
        priceDropNotified: false,
        createTime: now,
        updateTime: now,
      },
    ],
    browseHistory: [
      {
        productId: product.id,
        productName: product.name,
        productImage: product.imageUrl,
        viewedAt: now,
        expiresAt: '2026-08-05T12:00:00+08:00',
      },
    ],
  }
}

function realtimeDashboard() {
  return {
    pageViews: 4200,
    uniqueVisitors: 1280,
    orderCount: 92,
    paymentAmount: '18888.00',
    funnel: [
      { eventType: 'PAGE_VIEW', count: 4200, conversionRate: 1 },
      { eventType: 'PRODUCT_VIEW', count: 2600, conversionRate: 0.62 },
      { eventType: 'PAYMENT_SUCCESS', count: 92, conversionRate: 0.022 },
    ],
    generatedAt: now,
    refreshIntervalSeconds: 5,
  }
}

function tenantDashboard() {
  return {
    activeTenants: 12,
    expiredTenants: 1,
    currentMonthOrders: 2400,
    currentMonthRevenue: '98600.00',
    tenants: [tenant],
  }
}

function riskReview() {
  return {
    id: 1,
    userId: 1,
    orderId: 1,
    productId: product.id,
    type: 'PRICE_ANOMALY',
    score: 62,
    status: 'PENDING',
    detail: 'Price movement requires review.',
    createdAt: now,
  }
}

function searchPage() {
  return {
    content: [
      {
        productId: product.id,
        categoryId: product.categoryId,
        name: product.name,
        title: product.breed,
        imageUrl: product.imageUrl,
        originalPrice: product.price,
        memberPrice: product.memberPrice,
        attributes: { color: 'gold' },
        score: 98,
      },
    ],
    page: 0,
    size: 12,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  }
}

function apiFixture(pathname, method, authenticated) {
  if (pathname === '/users/me') {
    return authenticated
      ? {
          isLogin: true,
          identity: 'ADMIN',
          username: 'codex_ui_admin',
          avatar: '/images/default_avatar.jpg',
          maskedPhone: '138****8000',
          passwordChangeRequired: false,
        }
      : { isLogin: false }
  }
  if (pathname === '/users/profile') return apiFixture('/users/me', method, true)
  if (pathname === '/auth/captcha/config') return { provider: 'local', siteKey: '' }
  if (pathname === '/auth/login') return { role: 'ADMIN', passwordChangeRequired: false }
  if (pathname.startsWith('/auth/') || pathname.startsWith('/users/update-password')) return {}
  if (pathname === '/users/logout') return {}
  if (pathname === '/addresses') return method === 'GET' ? [address] : address
  if (pathname.startsWith('/addresses/')) return method === 'DELETE' ? null : address
  if (pathname === '/monkeys') return [product]
  if (pathname.startsWith('/monkeys/')) return null
  if (pathname === '/monkeys/add' || pathname === '/monkeys/update') return product
  if (pathname === '/uploads') {
    return { path: '/images/default_product.jpg', cropped: true, variants: {} }
  }
  if (pathname === '/catalog/categories/tree') {
    return [{ id: 10, parentId: null, level: 1, code: 'featured', name: 'Featured', children: [] }]
  }
  if (pathname.startsWith('/catalog/spus/') && pathname.endsWith('/price')) {
    return {
      spuId: product.id,
      salePrice: product.memberPrice,
      strikePrice: product.strikePrice,
      strategy: 'MEMBER',
    }
  }
  if (pathname.startsWith('/catalog/spus/')) return catalogSpu()
  if (pathname === '/stats/data') return stats()
  if (pathname === '/orders/my' || pathname === '/orders/all') return [order]
  if (pathname.startsWith('/orders/review/')) {
    return method === 'GET'
      ? [
          {
            id: 1,
            orderId: 1,
            userId: 1,
            skuId: 101,
            rating: 5,
            content: 'Stable UI smoke review.',
            imageUrls: ['/images/default_product.jpg'],
            anonymous: false,
            createTime: now,
          },
        ]
      : {
          id: 2,
          orderId: 1,
          userId: 1,
          rating: 5,
          content: 'Submitted',
          imageUrls: [],
          anonymous: false,
          createTime: now,
        }
  }
  if (pathname.includes('/shipments')) return []
  if (pathname.startsWith('/orders/')) return order
  if (pathname === '/payments/pay' || pathname.startsWith('/payments/orders/')) return payment()
  if (pathname === '/payments/refund') {
    return {
      ledgerId: 1,
      paymentNo: 'PAY202607050001',
      amount: '10.00',
      refundedAmount: '10.00',
      paymentStatus: 'PARTIALLY_REFUNDED',
      ledgerStatus: 'SUCCESS',
      createTime: now,
    }
  }
  if (pathname === '/payments/reconciliation') {
    return {
      id: 1,
      provider: 'WECHAT',
      reportDate: '2026-07-04',
      platformAmount: '128.00',
      providerAmount: '128.00',
      diffAmount: '0.00',
      issueCount: 0,
      status: 'BALANCED',
      createTime: now,
    }
  }
  if (pathname === '/logistics/shipments' || pathname.startsWith('/logistics/orders/'))
    return logistics()
  if (pathname.startsWith('/logistics/tracking/') || pathname === '/logistics/webhook')
    return logistics()
  if (pathname === '/logistics/freight/quote') {
    return {
      carrier: 'SF',
      province: 'Zhejiang',
      weightKg: 1.2,
      itemCount: 1,
      amount: '12.00',
      etaHours: 24,
      appliedModes: ['WEIGHT'],
    }
  }
  if (pathname === '/logistics/address/parse') {
    return {
      province: 'Zhejiang',
      city: 'Hangzhou',
      district: 'Xihu',
      detail: 'No. 100 Wenyi Road',
    }
  }
  if (pathname === '/membership/dashboard') return membershipDashboard()
  if (pathname === '/membership/check-in') {
    return {
      checkInDate: '2026-07-05',
      streakDays: 3,
      rewardPoints: 10,
      wallet: membershipDashboard().wallet,
    }
  }
  if (pathname.startsWith('/membership/')) return membershipDashboard()
  if (pathname === '/cart') return cart
  if (pathname.startsWith('/cart/items')) return cart
  if (pathname === '/cart/checkout/preview' || pathname === '/cart/checkout') return checkout
  if (pathname.startsWith('/inventory/skus/')) return [stock]
  if (pathname === '/inventory/reconciliation') return { balanced: true, discrepancies: [] }
  if (pathname.startsWith('/inventory/reservations')) {
    return {
      reservationKey: 'RSV-UI-001',
      skuId: 101,
      warehouseId: 1,
      quantity: 1,
      status: 'RESERVED',
      expiresAt: now,
      stock,
    }
  }
  if (pathname === '/inventory/compensations') return { balanced: true, discrepancies: [] }
  if (
    pathname === '/marketing/coupons/claim' ||
    pathname === '/marketing/coupons/redeem' ||
    pathname === '/marketing/coupons/return'
  ) {
    return {
      id: 1,
      couponId: 101,
      couponCode: 'PLATFORM-20',
      userId: 1,
      status: 'CLAIMED',
      claimedAt: now,
    }
  }
  if (pathname === '/marketing/price/quote') {
    return {
      originalAmount: '128.00',
      discountAmount: '20.00',
      payableAmount: '108.00',
      appliedCoupons: ['PLATFORM-20'],
    }
  }
  if (pathname === '/marketing/seckill-orders') {
    return {
      id: 1,
      activityId: 2500000000001,
      skuId: 101,
      userId: 1,
      quantity: 1,
      idempotencyKey: 'flash-ui',
      createdAt: now,
    }
  }
  if (pathname === '/marketing/group-buy/join') {
    return {
      id: 1,
      activityId: 2600000000001,
      skuId: 101,
      leaderUserId: 1,
      targetSize: 3,
      joinedCount: 2,
      status: 'OPEN',
      expiresAt: now,
    }
  }
  if (pathname === '/risk/assess') {
    return {
      userId: 1,
      score: 18,
      decision: 'ALLOW',
      signals: [{ type: 'PRICE_ANOMALY', weight: 8, detail: 'Within smoke threshold.' }],
      productAutoUnlisted: false,
      userTokensRevoked: false,
      assessedAt: now,
    }
  }
  if (pathname === '/risk/reviews') return [riskReview()]
  if (pathname.startsWith('/risk/reviews/'))
    return { ...riskReview(), status: 'APPROVED', handledAt: now, resolution: 'Approved by smoke.' }
  if (pathname === '/tracking/dashboard') return realtimeDashboard()
  if (pathname === '/tracking/profile/me' || pathname.startsWith('/tracking/profile/')) {
    return {
      userId: 1,
      profileSummary: 'PAGE_VIEW, PRODUCT_VIEW, ADD_TO_CART',
      behaviorTags: ['event:page_view', 'source:web'],
      interestTags: ['product:featured'],
      lastEventAt: now,
      version: 1,
    }
  }
  if (pathname.startsWith('/tracking/products/')) {
    return {
      productId: 1,
      categoryId: 10,
      tagVector: ['featured', 'stable'],
      salesCount: 42,
      reviewScore: '4.9',
      lastEventAt: now,
      version: 1,
    }
  }
  if (pathname === '/tracking/events') {
    return {
      id: 1,
      userId: 1,
      sessionId: 'smoke',
      traceId: 'ui-smoke-trace',
      eventType: 'PAGE_VIEW',
      page: '/shop',
      occurredAt: now,
    }
  }
  if (pathname === '/search/products') return searchPage()
  if (pathname === '/search/suggestions')
    return [{ keyword: 'golden', source: 'catalog', score: 90 }]
  if (pathname === '/search/hot') return [{ keyword: 'golden monkey', score: 100 }]
  if (pathname === '/search/recommendations') {
    return [
      {
        productId: 1,
        name: product.name,
        title: product.breed,
        imageUrl: product.imageUrl,
        reason: 'premium match',
        score: 96,
      },
    ]
  }
  if (pathname === '/search/profile') {
    return {
      userId: 1,
      maskedInterestProfile: 'premium***',
      tags: ['premium', 'fast'],
      updatedAt: now,
      version: 1,
    }
  }
  if (pathname === '/search/conversions') return null
  if (pathname === '/tenants/dashboard') return tenantDashboard()
  if (pathname === '/tenants') return method === 'GET' ? [tenant] : tenant
  if (pathname.match(/^\/tenants\/\d+\/configs$/)) {
    return method === 'GET'
      ? [
          {
            id: 1,
            tenantId: 1,
            configType: 'PAYMENT',
            provider: 'wechat',
            settings: { merchantId: 'demo' },
            enabled: true,
            updatedAt: now,
            version: 1,
          },
        ]
      : {
          id: 1,
          tenantId: 1,
          configType: 'PAYMENT',
          provider: 'wechat',
          settings: { merchantId: 'demo' },
          enabled: true,
          updatedAt: now,
          version: 1,
        }
  }
  if (pathname.match(/^\/tenants\/\d+\/bills$/)) {
    return method === 'GET'
      ? [
          {
            id: 1,
            tenantId: 1,
            billingMonth: '2026-07',
            plan: 'ENTERPRISE',
            orderCount: 120,
            monthlyFee: '2999.00',
            usageFee: '360.00',
            totalAmount: '3359.00',
            paymentAmount: '3359.00',
            status: 'GENERATED',
            generatedAt: now,
            version: 1,
          },
        ]
      : {
          id: 1,
          tenantId: 1,
          billingMonth: '2026-07',
          plan: 'ENTERPRISE',
          orderCount: 120,
          monthlyFee: '2999.00',
          usageFee: '360.00',
          totalAmount: '3359.00',
          paymentAmount: '3359.00',
          status: 'GENERATED',
          generatedAt: now,
          version: 1,
        }
  }
  if (pathname.match(/^\/tenants\/\d+\/exports$/)) {
    return method === 'GET'
      ? [
          {
            id: 1,
            tenantId: 1,
            exportType: 'FULL',
            status: 'COMPLETED',
            encryptedArchivePath: '/exports/full.enc',
            requestedBy: 1,
            requestedAt: now,
            completedAt: now,
            auditTraceId: 'ui-smoke',
            version: 1,
          },
        ]
      : {
          id: 1,
          tenantId: 1,
          exportType: 'FULL',
          status: 'REQUESTED',
          requestedBy: 1,
          requestedAt: now,
          auditTraceId: 'ui-smoke',
          version: 1,
        }
  }
  if (pathname.match(/^\/tenants\/\d+\/(renew|downgrade)$/)) return tenant
  return method === 'GET' ? [] : {}
}

async function installMocks(context, authenticated, counters) {
  await context.route('**/api/v1/auth/captcha**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/svg+xml',
      body: svgBody('CAPTCHA', '#eef6ff'),
    })
  })
  await context.route('**/api/v1/users/captcha**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/svg+xml',
      body: svgBody('CAPTCHA', '#eef6ff'),
    })
  })
  await context.route('**/api/v1/**', async (route) => {
    counters.apiRequests += 1
    const requestUrl = new URL(route.request().url())
    const pathname = requestUrl.pathname.replace('/api/v1', '') || '/'
    const method = route.request().method().toUpperCase()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(apiFixture(pathname, method, authenticated))),
    })
  })
  await context.route('**/images/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/svg+xml',
      body: svgBody('MonkeyShop', '#dbeafe'),
    })
  })
}

async function startServer() {
  const server = await createServer({
    root,
    server: {
      host: '127.0.0.1',
      port: 5174,
      strictPort: false,
    },
  })
  await server.listen()
  const url = server.resolvedUrls?.local.find((item) => item.startsWith('http://127.0.0.1'))
  if (!url) {
    throw new Error('Vite did not expose a local 127.0.0.1 URL')
  }
  return { server, baseURL: url.replace(/\/$/, '') }
}

async function runDomChecks(page, route, viewportName) {
  const result = await page.evaluate(
    ({ bannedCopy, maxH1Px }) => {
      const html = document.documentElement
      const body = document.body
      const documentOverflow = Math.max(html.scrollWidth, body.scrollWidth) - window.innerWidth
      const visible = (element) => {
        const style = getComputedStyle(element)
        const rect = element.getBoundingClientRect()
        return (
          style.display !== 'none' &&
          style.visibility !== 'hidden' &&
          Number(style.opacity) !== 0 &&
          rect.width > 0 &&
          rect.height > 0
        )
      }
      const h1TooLarge = Array.from(document.querySelectorAll('h1'))
        .map((element) => ({
          text: element.textContent?.trim() || '<empty>',
          fontSize: Number.parseFloat(getComputedStyle(element).fontSize),
        }))
        .filter((item) => item.fontSize > maxH1Px)
      const loadingMasks = Array.from(document.querySelectorAll('.el-loading-mask')).filter(visible)
      const tableHeader = document.querySelector('.el-table th.el-table__cell')
      const tableHeaderStyle = tableHeader ? getComputedStyle(tableHeader) : undefined
      const text = body.innerText
      const exposedCopy = bannedCopy.filter((phrase) => text.includes(phrase))
      return {
        documentOverflow,
        focusRing: getComputedStyle(html).getPropertyValue('--focus-ring').trim(),
        headerCount: document.querySelectorAll('.app-header').length,
        mainCount: document.querySelectorAll('.app-main').length,
        nestedShellCount: document.querySelectorAll('.app-shell .app-shell').length,
        h1TooLarge,
        exposedCopy,
        visibleLoadingCount: loadingMasks.length,
        tableHeaderBackground: tableHeaderStyle?.backgroundColor || '',
      }
    },
    { bannedCopy, maxH1Px },
  )

  const errors = []
  if (result.documentOverflow > 2) {
    errors.push(`page-level horizontal overflow ${result.documentOverflow}px`)
  }
  if (!result.focusRing) {
    errors.push('missing --focus-ring token')
  }
  if (result.headerCount !== 1 || result.mainCount !== 1 || result.nestedShellCount > 0) {
    errors.push(
      `shell ownership drift header=${result.headerCount} main=${result.mainCount} nested=${result.nestedShellCount}`,
    )
  }
  if (result.h1TooLarge.length > 0) {
    errors.push(
      `oversized h1 on ${viewportName}: ${result.h1TooLarge
        .map((item) => `${item.text}=${Math.round(item.fontSize)}px`)
        .join(', ')}`,
    )
  }
  if (result.exposedCopy.length > 0) {
    errors.push(`raw backend copy exposed: ${result.exposedCopy.join(', ')}`)
  }
  if (result.visibleLoadingCount > 0) {
    errors.push(`stuck loading masks: ${result.visibleLoadingCount}`)
  }
  if (result.tableHeaderBackground === 'rgba(0, 0, 0, 0)') {
    errors.push('transparent table header surface')
  }

  return errors.map((error) => `${route.path} [${viewportName}]: ${error}`)
}

async function runPopperCheck(page, route, viewportName) {
  const select = page.locator('.el-select').first()
  if ((await select.count()) === 0 || !(await select.isVisible().catch(() => false))) {
    return []
  }
  await select.click({ timeout: 1500 }).catch(() => undefined)
  await page.waitForTimeout(100)
  const popper = page.locator('.el-popper:visible').first()
  if ((await popper.count()) === 0) {
    return []
  }
  const box = await popper.boundingBox()
  await page.keyboard.press('Escape').catch(() => undefined)
  if (!box) {
    return [`${route.path} [${viewportName}]: visible popper has no geometry`]
  }
  const viewport = page.viewportSize()
  if (!viewport) {
    return []
  }
  const outOfViewport =
    box.x < -2 ||
    box.y < -2 ||
    box.x + box.width > viewport.width + 2 ||
    box.y > viewport.height + 2
  return outOfViewport
    ? [`${route.path} [${viewportName}]: popper escapes viewport ${JSON.stringify(box)}`]
    : []
}

async function checkRoute(browser, baseURL, route, viewport) {
  const counters = { apiRequests: 0 }
  const context = await browser.newContext(viewport.options)
  await context.addInitScript(() => {
    window.localStorage.setItem('monkeyshop-locale', 'zh')
  })
  await installMocks(context, route.auth, counters)
  const page = await context.newPage()
  const consoleErrors = []
  const requestFailures = []
  page.on('console', (message) => {
    if (message.type() === 'error') {
      consoleErrors.push(message.text())
    }
  })
  page.on('pageerror', (error) => consoleErrors.push(error.message))
  page.on('requestfailed', (request) => {
    const errorText = request.failure()?.errorText ?? ''
    if (errorText.includes('net::ERR_ABORTED')) {
      return
    }
    requestFailures.push(`${request.method()} ${request.url()} ${errorText}`)
  })

  const failures = []
  try {
    await page.goto(`${baseURL}${route.path}`, {
      waitUntil: 'domcontentloaded',
      timeout: 30000,
    })
    await page.waitForSelector('.app-shell', { state: 'visible', timeout: 10000 })
    await page
      .waitForFunction(
        () => !document.querySelector('.el-loading-mask:not([style*="display: none"])'),
        null,
        { timeout: 5000 },
      )
      .catch(() => undefined)
    await page.waitForTimeout(100)
    const currentPath = new URL(page.url()).pathname
    if (currentPath !== route.path) {
      failures.push(`${route.path} [${viewport.name}]: routed to ${currentPath}`)
    }
    failures.push(...(await runDomChecks(page, route, viewport.name)))
    failures.push(...(await runPopperCheck(page, route, viewport.name)))
    if (counters.apiRequests > maxApiRequestsPerPage) {
      failures.push(
        `${route.path} [${viewport.name}]: too many API requests ${counters.apiRequests}`,
      )
    }
    if (consoleErrors.length > 0) {
      failures.push(`${route.path} [${viewport.name}]: console errors ${consoleErrors.join(' | ')}`)
    }
    if (requestFailures.length > 0) {
      failures.push(
        `${route.path} [${viewport.name}]: request failures ${requestFailures.join(' | ')}`,
      )
    }
  } finally {
    await context.close()
  }

  return {
    route: route.path,
    viewport: viewport.name,
    apiRequests: counters.apiRequests,
    status: failures.length === 0 ? 'pass' : 'fail',
    failures,
  }
}

const { server, baseURL } = await startServer()
const executablePath = browserExecutablePath()
const browser = await chromium.launch(executablePath ? { executablePath } : undefined)
const results = []
try {
  for (const viewport of viewports) {
    for (const route of routes) {
      console.log(`[ui-smoke] start ${route.path} [${viewport.name}]`)
      try {
        results.push(await checkRoute(browser, baseURL, route, viewport))
      } catch (error) {
        results.push({
          route: route.path,
          viewport: viewport.name,
          apiRequests: 0,
          status: 'fail',
          failures: [
            `${route.path} [${viewport.name}]: ${error instanceof Error ? error.message : String(error)}`,
          ],
        })
      }
    }
  }
} finally {
  await browser.close()
  await server.close()
}

console.table(
  results.map((result) => ({
    route: result.route,
    viewport: result.viewport,
    api: result.apiRequests,
    status: result.status,
  })),
)

const failures = results.flatMap((result) => result.failures)
if (failures.length > 0) {
  for (const failure of failures) {
    console.error(failure)
  }
  process.exit(1)
}

console.log(`UI smoke passed ${results.length} route checks.`)
