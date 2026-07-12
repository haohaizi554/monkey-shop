import { expect, test, type Page, type Route } from '@playwright/test'

const rawRealName = 'Alice Sensitive'
const rawPhone = '13800138000'
const rawIdCard = '310101199001011234'
const rawBackendError = 'DO NOT RENDER MEMBERSHIP SECRET'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'account-test' }
}

function membershipDashboard() {
  return {
    profile: {
      userId: 7,
      level: 'SILVER',
      growthValue: 1250,
      verified: true,
      maskedRealName: 'A***',
      maskedIdCardNo: '310***********1234',
      realName: rawRealName,
      idCardNo: rawIdCard,
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
    collections: [
      {
        id: 3,
        productId: 11,
        productName: 'Golden Snub-nosed',
        lastPrice: '128.00',
        targetPrice: '118.00',
        priceDropNotified: false,
        createTime: '2026-07-10T08:00:00Z',
        updateTime: '2026-07-10T08:00:00Z',
      },
    ],
    browseHistory: [
      {
        productId: 11,
        productName: 'Golden Snub-nosed',
        viewedAt: '2026-07-11T08:00:00Z',
        expiresAt: '2026-08-11T08:00:00Z',
      },
    ],
  }
}

interface AccountMockOptions {
  passwordChangeRequired?: boolean
  checkInGate?: Promise<void>
  failRedeem?: boolean
  onAddressUpdate?: () => void
}

async function fulfillJson(route: Route, data: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(data),
  })
}

async function installAccountMocks(page: Page, options: AccountMockOptions = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })

  const accountProfile = {
    isLogin: true,
    identity: 'USER',
    username: 'account-user',
    maskedPhone: '138****8000',
    passwordChangeRequired: options.passwordChangeRequired ?? false,
    phone: rawPhone,
    realName: rawRealName,
    idCardNo: rawIdCard,
  }
  const addresses = [
    {
      id: 1,
      receiverName: rawRealName,
      phone: rawPhone,
      detailAddress: 'Shanghai Road 1',
      isDefault: 1,
    },
  ]

  await page.route('**/images/default_avatar.jpg', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="88" height="88" />',
    })
  })

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')
    const method = request.method()

    if (pathname === '/users/captcha') {
      await route.fulfill({
        status: 200,
        contentType: 'image/svg+xml',
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40" />',
      })
      return
    }
    if (pathname === '/users/me' || pathname === '/users/profile') {
      await fulfillJson(route, ok(accountProfile))
      return
    }
    if (pathname === '/auth/captcha/config') {
      await fulfillJson(route, ok({ provider: 'local', siteKey: '' }))
      return
    }
    if (pathname === '/addresses' && method === 'GET') {
      await fulfillJson(route, ok(addresses))
      return
    }
    if (pathname === '/addresses' && method === 'POST') {
      await fulfillJson(route, ok({ ...addresses[0], id: 2 }))
      return
    }
    if (pathname === '/addresses/1' && method === 'PUT') {
      options.onAddressUpdate?.()
      await fulfillJson(route, ok({ ...addresses[0], ...request.postDataJSON() }))
      return
    }
    if (pathname === '/addresses/1' && method === 'DELETE') {
      await fulfillJson(route, ok(null))
      return
    }
    if (pathname === '/addresses/set-default/1') {
      await fulfillJson(route, ok(addresses[0]))
      return
    }
    if (pathname === '/membership/dashboard') {
      await fulfillJson(route, ok(membershipDashboard()))
      return
    }
    if (pathname === '/membership/check-in') {
      await options.checkInGate
      await fulfillJson(
        route,
        ok({
          checkInDate: '2026-07-12',
          streakDays: 2,
          rewardPoints: 10,
          wallet: membershipDashboard().wallet,
        }),
      )
      return
    }
    if (pathname === '/membership/points/redeem' && options.failRedeem) {
      await fulfillJson(
        route,
        { title: rawBackendError, detail: rawBackendError, status: 500, traceId: 'raw-secret' },
        500,
      )
      return
    }
    if (pathname === '/membership/identity' || pathname === '/membership/level') {
      await fulfillJson(route, ok(membershipDashboard()))
      return
    }
    if (pathname === '/membership/price-drops/scan') {
      await fulfillJson(route, ok({ scanned: 1, reminders: 0 }))
      return
    }
    if (pathname.startsWith('/membership/')) {
      await fulfillJson(route, ok(null))
      return
    }
    if (pathname === '/tracking/events') {
      await fulfillJson(route, ok({ id: 1, eventType: 'PAGE_VIEW' }))
      return
    }

    await fulfillJson(route, ok(null))
  })
}

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

test('membership groups account tasks, masks identity data, and isolates pending actions', async ({
  page,
}) => {
  const checkIn = deferred()
  await installAccountMocks(page, { checkInGate: checkIn.promise })
  await page.goto('/membership')

  await expect(page.getByRole('heading', { name: 'Membership center', level: 1 })).toBeVisible()
  await expect(page.locator('.async-state-view[data-status="success"]')).toBeVisible()
  const sectionOrder = await page
    .locator('[data-membership-section]')
    .evaluateAll((sections) =>
      sections.map((section) => section.getAttribute('data-membership-section')),
    )
  expect(sectionOrder).toEqual(['identity', 'points', 'price-watch', 'coupons', 'history'])
  await expect(page.locator('.data-table-shell')).toHaveCount(3)

  const body = page.locator('body')
  await expect(body).toContainText('A***')
  await expect(body).toContainText('310***********1234')
  await expect(body).not.toContainText(rawRealName)
  await expect(body).not.toContainText(rawIdCard)

  const checkInButton = page.getByRole('button', { name: 'Daily check-in' })
  const redeemButton = page.getByRole('button', { name: 'Redeem' })
  await checkInButton.click()
  await expect(checkInButton).toBeDisabled()
  await expect(redeemButton).toBeEnabled()

  checkIn.resolve()
  await expect(checkInButton).toBeEnabled()
})

test('membership mutations use safe app feedback instead of raw backend errors', async ({
  page,
}) => {
  await installAccountMocks(page, { failRedeem: true })
  await page.goto('/membership')

  await page.getByRole('button', { name: 'Redeem' }).click()

  await expect(page.locator('.app-feedback-item')).toContainText(
    'Operation failed, please try again',
  )
  await expect(page.locator('body')).not.toContainText(rawBackendError)
})

test('required password updates use a blocking alert with a direct focus action', async ({
  page,
}) => {
  await installAccountMocks(page, { passwordChangeRequired: true })
  const profileRequested = page.waitForRequest((request) =>
    new URL(request.url()).pathname.endsWith('/api/v1/users/profile'),
  )
  await page.goto('/profile')
  await profileRequested

  await expect(page.getByRole('heading', { name: 'Profile', level: 1 })).toBeVisible()
  const alert = page.locator('.el-alert[role="alert"]')
  await expect(alert).toContainText('Update your password before continuing')
  await alert.getByRole('button', { name: 'Complete password update' }).click()
  await expect(page.getByLabel('Current password')).toBeFocused()
  await expect(page.locator('.app-feedback-host')).toHaveCount(0)

  const sectionOrder = await page
    .locator('[data-account-section]')
    .evaluateAll((sections) =>
      sections.map((section) => section.getAttribute('data-account-section')),
    )
  expect(sectionOrder).toEqual(['overview', 'required-password', 'avatar', 'password'])
})

test('profile masks address data and validates the edit dialog before restoring focus', async ({
  page,
}) => {
  let addressUpdates = 0
  await installAccountMocks(page, {
    onAddressUpdate: () => {
      addressUpdates += 1
    },
  })
  await page.goto('/profile')

  await expect(page.locator('.async-state-view[data-status="success"]')).toBeVisible()
  await expect(page.locator('.data-table-shell')).toHaveCount(1)
  await expect(page.locator('body')).not.toContainText(rawRealName)
  await expect(page.locator('body')).not.toContainText(rawPhone)

  const editButton = page.getByRole('button', { name: 'Edit' }).first()
  await editButton.click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()

  await dialog.getByLabel('Receiver').clear()
  await dialog.getByLabel('Phone').clear()
  await dialog.getByLabel('Address').clear()
  await dialog.getByRole('button', { name: 'Save' }).click()
  await expect(dialog.locator('.el-form-item__error')).toHaveCount(3)
  expect(addressUpdates).toBe(0)

  await dialog.getByLabel('Receiver').fill('Bob Builder')
  await dialog.getByLabel('Phone').fill('13900139000')
  await dialog.getByLabel('Address').fill('Beijing Road 2')
  await dialog.getByRole('button', { name: 'Save' }).click()

  await expect(dialog).toBeHidden()
  expect(addressUpdates).toBe(1)
  await expect(editButton).toBeFocused()
})
