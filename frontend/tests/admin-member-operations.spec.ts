import { expect, test, type Page, type Route } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'admin-member-test' }
}

async function fulfillOk(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(ok(data)),
  })
}

function dashboard(level = 'BASIC', balance = 100, userId = 7) {
  return {
    profile: {
      userId,
      level,
      growthValue: 360,
      verified: true,
      maskedRealName: 'A***',
      maskedIdCardNo: '310***********1234',
      version: 1,
      benefits: [],
    },
    wallet: {
      userId,
      balance,
      totalEarned: 180,
      totalSpent: 80,
      moneyEquivalent: (balance / 100).toFixed(2),
      version: 1,
    },
    coupons: [],
    collections: [],
    browseHistory: [],
  }
}

async function installAdminMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (pathname === '/users/me') {
      await fulfillOk(route, { isLogin: true, identity: 'ADMIN', username: 'admin' })
      return
    }
    if (pathname === '/tracking/events') {
      await fulfillOk(route, { id: 1, eventType: 'PAGE_VIEW' })
      return
    }
    if (pathname === '/membership/admin/7/dashboard') {
      await fulfillOk(route, dashboard())
      return
    }
    if (pathname === '/membership/price-drops/scan') {
      await fulfillOk(route, { scanned: 20, reminders: 3 })
      return
    }
    await fulfillOk(route, null)
  })
}

test('member operations loads a target member and exposes guarded admin actions', async ({
  page,
}) => {
  await installAdminMocks(page)

  await page.goto('/admin/members?userId=7')

  await expect(page.getByRole('heading', { name: 'Member operations', exact: true })).toBeVisible()
  await expect(
    page.getByRole('region', { name: 'Find a member' }).getByText('Basic', { exact: true }),
  ).toBeVisible()
  await expect(page.getByText('100', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Apply points', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Change level', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Run price-drop scan', exact: true })).toBeEnabled()
})

test('member mutations stay disabled while a different target is loading', async ({ page }) => {
  let lookupCalls = 0
  let releaseLookup!: () => void
  const lookupGate = new Promise<void>((resolve) => {
    releaseLookup = resolve
  })

  await installAdminMocks(page)
  await page.route('**/api/v1/membership/admin/8/dashboard', async (route) => {
    lookupCalls += 1
    await lookupGate
    await fulfillOk(route, dashboard('SILVER', 200, 8))
  })

  await page.goto('/admin/members?userId=7')
  const applyButton = page
    .getByRole('region', { name: 'Manual points adjustment' })
    .getByRole('button', { name: 'Apply points', exact: true })
  await page.getByRole('spinbutton', { name: 'Points' }).fill('25')
  await page.getByRole('textbox', { name: 'Adjustment reason' }).fill('Service recovery')
  await expect(applyButton).toBeEnabled()

  await page.getByRole('spinbutton', { name: 'Member ID' }).fill('8')
  await page.getByRole('button', { name: 'Load member', exact: true }).click()
  await expect.poll(() => lookupCalls).toBe(1)
  await expect(applyButton).toBeDisabled()

  releaseLookup()
  await expect(
    page.getByRole('region', { name: 'Find a member' }).getByText('Silver', { exact: true }),
  ).toBeVisible()
})

test('manual points requires a reason, confirms intent, and keeps scan independently available', async ({
  page,
}) => {
  let adjustCalls = 0
  let releaseAdjustment!: () => void
  const adjustmentGate = new Promise<void>((resolve) => {
    releaseAdjustment = resolve
  })

  await installAdminMocks(page)
  await page.route('**/api/v1/membership/admin/7/points/earn', async (route) => {
    adjustCalls += 1
    expect(route.request().postDataJSON()).toEqual({
      amount: '25',
      referenceKey: 'Service recovery',
    })
    await adjustmentGate
    await fulfillOk(route, {
      id: 1001,
      type: 'ADJUST',
      points: 25,
      moneyEquivalent: '0.25',
      referenceKey: 'Service recovery',
      createdAt: '2026-07-13T08:00:00Z',
    })
  })

  await page.goto('/admin/members?userId=7')
  const applyButton = page
    .getByRole('region', { name: 'Manual points adjustment' })
    .getByRole('button', { name: 'Apply points', exact: true })
  await page.getByRole('spinbutton', { name: 'Points' }).fill('25')
  await expect(applyButton).toBeDisabled()
  await page.getByRole('textbox', { name: 'Adjustment reason' }).fill('Service recovery')
  await expect(applyButton).toBeEnabled()

  await applyButton.click()
  await page.getByRole('dialog').getByRole('button', { name: 'Apply points', exact: true }).click()
  await expect.poll(() => adjustCalls).toBe(1)
  await expect(applyButton).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Run price-drop scan', exact: true })).toBeEnabled()
  await applyButton.evaluate((button) => (button as HTMLButtonElement).click())
  expect(adjustCalls).toBe(1)

  releaseAdjustment()
  await expect(page.getByRole('textbox', { name: 'Adjustment reason' })).toHaveValue('')
})

test('level change sends the selected member, reason, and operator TOTP', async ({ page }) => {
  let levelCalls = 0
  await installAdminMocks(page)
  await page.route('**/api/v1/membership/admin/7/level', async (route) => {
    levelCalls += 1
    expect(route.request().postDataJSON()).toEqual({
      level: 'SILVER',
      reason: 'Retention review',
      totpCode: '123456',
    })
    await fulfillOk(route, dashboard('SILVER'))
  })

  await page.goto('/admin/members?userId=7')
  await page.getByRole('region', { name: 'Level management' }).locator('.el-select').click()
  await page.getByRole('option', { name: 'Silver', exact: true }).click()
  await page.getByRole('textbox', { name: 'Level change reason' }).fill('Retention review')
  await page.getByRole('textbox', { name: 'TOTP code' }).fill('123456')
  await page.getByRole('button', { name: 'Change level', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Change level', exact: true }).click()

  await expect.poll(() => levelCalls).toBe(1)
  await expect(
    page.getByRole('region', { name: 'Find a member' }).getByText('Silver', { exact: true }),
  ).toBeVisible()
})
