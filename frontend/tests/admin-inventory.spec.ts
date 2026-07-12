import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'inventory-test' }
}

function stock(availableQuantity: number) {
  return {
    skuId: 7,
    warehouseId: 1,
    warehouseCode: 'EAST-1',
    province: 'East',
    availableQuantity,
    lockedQuantity: 20 - availableQuantity,
    deductedQuantity: 0,
    inTransitQuantity: 0,
    safetyStock: 3,
    totalQuantity: 20,
    belowSafetyStock: availableQuantity < 3,
  }
}

async function installInventoryMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    if (pathname === '/users/me') {
      data = { isLogin: true, identity: 'ADMIN', username: 'admin' }
    } else if (pathname === '/inventory/skus/7/stocks') {
      data = [
        stock(12),
        { ...stock(6), warehouseId: 2, warehouseCode: 'WEST-1', province: 'West' },
      ]
    } else if (pathname === '/inventory/reservations' && request.method() === 'POST') {
      const body = request.postDataJSON() as { reservationKey: string }
      data = {
        reservationKey: body.reservationKey,
        skuId: 7,
        warehouseId: 1,
        quantity: 1,
        status: 'RESERVED',
        expiresAt: '2026-07-12T09:00:00',
        stock: stock(body.reservationKey === 'reserve-one' ? 11 : 10),
      }
    } else if (/\/inventory\/reservations\/.+\/release$/.test(pathname)) {
      await new Promise((resolve) => setTimeout(resolve, 300))
      const key = decodeURIComponent(pathname.split('/')[3] ?? '')
      data = {
        reservationKey: key,
        skuId: 7,
        warehouseId: 1,
        quantity: 1,
        status: 'RELEASED',
        expiresAt: '2026-07-12T09:00:00',
        stock: stock(11),
      }
    } else if (pathname === '/inventory/reconciliation') {
      data = { balanced: true, discrepancies: [] }
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

test('inventory keeps URL query and updates only the active reservation row', async ({ page }) => {
  await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7&region=East')
  await expect(page.getByRole('spinbutton', { name: 'SKU id' })).toHaveValue('7')
  await expect(page.getByRole('textbox', { name: 'Region' })).toHaveValue('East')
  await expect(page.getByText('EAST-1')).toBeVisible()
  await expect(page.getByText('WEST-1')).toHaveCount(0)

  await page.reload()
  await expect(page.getByRole('spinbutton', { name: 'SKU id' })).toHaveValue('7')

  const keyInput = page.getByRole('textbox', { name: 'Reservation key' })
  await keyInput.fill('reserve-one')
  await page.getByRole('button', { name: 'Reserve', exact: true }).click()
  await expect(page.getByText('reserve-one', { exact: true })).toBeVisible()
  await keyInput.fill('reserve-two')
  await page.getByRole('button', { name: 'Reserve', exact: true }).click()
  await expect(page.getByText('reserve-two', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Release reserve-one' }).click()
  await expect(page.getByRole('button', { name: 'Release reserve-one' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Release reserve-two' })).toBeEnabled()
  await expect(page.getByText('Released', { exact: true })).toBeVisible()
  await expect(page.locator('body')).not.toContainText('鈥')
})
