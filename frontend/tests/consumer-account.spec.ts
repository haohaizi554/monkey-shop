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
  passwordExpired?: boolean
  checkInGate?: Promise<void>
  failRedeem?: boolean
  emptyCollections?: boolean
  onAddressUpdate?: () => void
  onForgetMe?: () => void
  redeemKeys?: string[]
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
    passwordExpired: options.passwordExpired ?? false,
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
      await fulfillJson(
        route,
        ok({
          content: addresses,
          page: 0,
          size: 100,
          totalElements: addresses.length,
          totalPages: 1,
          first: true,
          last: true,
        }),
      )
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
      const dashboard = membershipDashboard()
      await fulfillJson(
        route,
        ok(options.emptyCollections ? { ...dashboard, collections: [] } : dashboard),
      )
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
      options.redeemKeys?.push((await request.allHeaders())['idempotency-key'] ?? '')
      await fulfillJson(
        route,
        { title: rawBackendError, detail: rawBackendError, status: 500, traceId: 'raw-secret' },
        500,
      )
      return
    }
    if (pathname === '/membership/points/redeem') {
      options.redeemKeys?.push((await request.allHeaders())['idempotency-key'] ?? '')
      await fulfillJson(
        route,
        ok({
          id: 11,
          type: 'REDEEM',
          points: -100,
          moneyEquivalent: '1.00',
          referenceKey: 'wallet-redemption',
          createdAt: '2026-07-12T09:00:00Z',
        }),
      )
      return
    }
    if (pathname === '/users/forget-me') {
      options.onForgetMe?.()
      await fulfillJson(route, ok(null))
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
  await expect(page.locator('img.mascot-state[data-pose="celebrate"]')).toBeVisible()
  const sectionOrder = await page
    .locator('[data-membership-section]')
    .evaluateAll((sections) =>
      sections.map((section) => section.getAttribute('data-membership-section')),
    )
  expect(sectionOrder).toEqual(['identity', 'points', 'price-watch', 'coupons', 'history'])
  await expect(page.locator('.data-table-shell')).toHaveCount(3)
  await expect(page.getByRole('button', { name: 'Earn', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Adjust', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Scan price drops', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Record', exact: true })).toHaveCount(0)

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
  const redeemKeys: string[] = []
  await installAccountMocks(page, { failRedeem: true, redeemKeys })
  await page.goto('/membership')

  await page.getByRole('button', { name: 'Redeem' }).click()

  await expect.poll(() => redeemKeys.length).toBe(1)
  await expect(page.locator('.app-feedback-item')).toContainText(
    'Operation failed, please try again',
  )
  await expect(page.locator('body')).not.toContainText(rawBackendError)

  await page.getByRole('button', { name: 'Redeem' }).click()
  await expect.poll(() => redeemKeys.length).toBe(2)
  expect(redeemKeys[0]).not.toBe('')
  expect(redeemKeys[1]).toBe(redeemKeys[0])
})

test('empty price watch uses the shopping bag mascot without exposing admin controls', async ({
  page,
}) => {
  await installAccountMocks(page, { emptyCollections: true })
  await page.goto('/membership')

  await expect(page.locator('img.mascot-state[data-pose="shoppingBag"]')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Scan price drops', exact: true })).toHaveCount(0)
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
  await expect(page.getByRole('tab', { name: 'Security' })).toHaveAttribute('aria-selected', 'true')
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
  expect(sectionOrder).toEqual([
    'overview',
    'required-password',
    'identity',
    'addresses',
    'security',
    'privacy',
  ])
})

test('expired passwords use the same blocking security corridor', async ({ page }) => {
  await installAccountMocks(page, { passwordExpired: true })
  await page.goto('/profile')

  await expect(page.getByRole('tab', { name: 'Security' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('.el-alert[role="alert"]')).toContainText(
    'Update your password before continuing',
  )
  await expect(page.getByRole('tab', { name: 'Identity' })).toHaveClass(/is-disabled/)
  await expect(page.getByRole('tab', { name: 'Addresses' })).toHaveClass(/is-disabled/)
})

test('profile identity summarizes account readiness without exposing raw pii', async ({ page }) => {
  await installAccountMocks(page)
  await page.goto('/profile')

  const identity = page.locator('[data-account-section="identity"]')
  await expect(identity.locator('img.mascot-state[data-pose="support"]')).toBeVisible()
  await expect(identity.locator('.identity-fact')).toHaveCount(4)
  await expect(identity).toContainText('Member account')
  await expect(identity).toContainText('138****8000')
  await expect(identity).toContainText('1 saved')
  await expect(identity).toContainText('Protected')
  await expect(identity.getByRole('link', { name: 'Open membership' })).toBeVisible()
  await expect(page.locator('body')).not.toContainText(rawPhone)
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

  await expect(page.locator('[data-account-section="identity"]')).toBeVisible()
  await expect(page.locator('body')).not.toContainText(rawRealName)
  await expect(page.locator('body')).not.toContainText(rawPhone)

  await page.getByRole('tab', { name: 'Addresses' }).click()
  await expect(
    page.locator('[data-account-section="addresses"] .async-state-view[data-status="success"]'),
  ).toBeVisible()

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

test('profile requests one bounded address page at a time', async ({ page }) => {
  const queries: Array<{ page: string | null; size: string | null; sort: string | null }> = []
  await installAccountMocks(page)
  await page.route('**/api/v1/addresses**', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }
    const url = new URL(route.request().url())
    const pageNumber = Number(url.searchParams.get('page') ?? 0)
    queries.push({
      page: url.searchParams.get('page'),
      size: url.searchParams.get('size'),
      sort: url.searchParams.get('sort'),
    })
    await fulfillJson(
      route,
      ok({
        content: [
          {
            id: 100 + pageNumber,
            receiverName: `Receiver ${pageNumber + 1}`,
            phone: '13800138000',
            detailAddress: `Address page ${pageNumber + 1}`,
            isDefault: pageNumber === 0 ? 1 : 0,
          },
        ],
        page: pageNumber,
        size: 8,
        totalElements: 17,
        totalPages: 3,
        first: pageNumber === 0,
        last: pageNumber === 2,
      }),
    )
  })

  await page.goto('/profile')
  await page.getByRole('tab', { name: 'Addresses' }).click()
  await expect(page.getByText('Address page 1', { exact: true })).toBeVisible()
  expect(queries).toEqual([{ page: '0', size: '8', sort: 'isDefault,desc' }])

  await page.locator('.profile-address-pagination .btn-next').click()
  await expect(page.getByText('Address page 2', { exact: true })).toBeVisible()
  expect(queries.at(-1)).toEqual({ page: '1', size: '8', sort: 'isDefault,desc' })
})

test('profile confirms route changes when an address edit has unsaved changes', async ({
  page,
}) => {
  await installAccountMocks(page)
  await page.goto('/profile')
  await page.getByRole('tab', { name: 'Addresses' }).click()

  const receiver = page.getByLabel('Receiver').first()
  await receiver.fill('Unsaved receiver')

  await page.getByRole('link', { name: 'Membership', exact: true }).click()
  const discardDialog = page.getByRole('dialog', { name: 'Discard unsaved changes?' })
  await expect(discardDialog).toBeVisible()
  await discardDialog.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(page).toHaveURL(/\/profile$/)
  await expect(receiver).toHaveValue('Unsaved receiver')

  await page.getByRole('link', { name: 'Membership', exact: true }).click()
  await page
    .getByRole('dialog', { name: 'Discard unsaved changes?' })
    .getByRole('button', { name: 'OK', exact: true })
    .click()
  await expect(page).toHaveURL(/\/membership$/)
})

test('privacy erasure requires typed confirmation before calling forget me', async ({ page }) => {
  let forgetCalls = 0
  await installAccountMocks(page, {
    onForgetMe: () => {
      forgetCalls += 1
    },
  })
  await page.goto('/profile')
  await page.getByRole('tab', { name: 'Privacy' }).click()

  await expect(page.locator('img.mascot-state[data-pose="shield"]')).toBeVisible()
  await expect(page.getByText('This action cannot be undone.', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Forget me', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Forget this account?' })
  const confirm = dialog.getByRole('button', { name: 'Forget my data', exact: true })
  await expect(confirm).toBeDisabled()
  expect(forgetCalls).toBe(0)

  await dialog.getByLabel('Type FORGET to confirm').fill('FORGET')
  await expect(confirm).toBeEnabled()
  await confirm.click()
  await expect.poll(() => forgetCalls).toBe(1)
  await expect(page).toHaveURL(/\/login$/)
})
