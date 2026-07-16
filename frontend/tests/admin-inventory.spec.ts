import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'inventory-test' }
}

function stock(
  availableQuantity: number,
  warehouseId = 1,
  warehouseCode = 'EAST-1',
  province = 'East',
  skuId = 7,
) {
  return {
    skuId,
    warehouseId,
    warehouseCode,
    province,
    availableQuantity,
    lockedQuantity: 20 - availableQuantity,
    deductedQuantity: 0,
    inTransitQuantity: 0,
    safetyStock: 3,
    totalQuantity: 20,
    belowSafetyStock: availableQuantity < 3,
  }
}

function discrepancy(skuId: number, warehouseId: number, actualLocked: number) {
  return {
    skuId,
    warehouseId,
    actualLocked,
    expectedLocked: 3,
    actualDeducted: 0,
    expectedDeducted: 0,
  }
}

async function installInventoryMocks(page: Page) {
  let reconciliationRuns = 0

  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    let status = 200
    if (pathname === '/users/me') {
      data = { isLogin: true, identity: 'ADMIN', username: 'admin' }
    } else if (pathname === '/inventory/skus/7/stocks') {
      data = [stock(12), stock(6, 2, 'WEST-1', 'West')]
    } else if (pathname === '/inventory/skus/8/stocks') {
      await new Promise((resolve) => setTimeout(resolve, 500))
      data = [stock(18, 3, 'NORTH-1', 'North', 8)]
    } else if (pathname === '/inventory/skus/9/stocks') {
      data = []
    } else if (pathname === '/inventory/skus/10/stocks') {
      status = 500
      data = { code: 'INVENTORY_DOWN', message: 'Inventory service unavailable' }
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
      await new Promise((resolve) => setTimeout(resolve, 500))
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
      reconciliationRuns += 1
      data =
        reconciliationRuns === 1
          ? {
              balanced: false,
              discrepancies: [discrepancy(7, 1, 1), discrepancy(8, 1, 2)],
            }
          : {
              balanced: false,
              discrepancies: [discrepancy(7, 1, 4)],
            }
    } else if (pathname === '/tracking/events') {
      data = { id: 1, eventType: 'PAGE_VIEW' }
    }
    await route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(status === 200 ? ok(data) : data),
    })
  })
}

test('inventory persists and canonicalizes its URL-backed query', async ({ page }) => {
  await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7&region=East')
  await expect(page.getByRole('spinbutton', { name: 'SKU id' })).toHaveValue('7')
  await expect(page.getByRole('textbox', { name: 'Region' })).toHaveValue('East')
  await expect(page.getByText('EAST-1')).toBeVisible()
  await expect(page.getByText('WEST-1')).toHaveCount(0)

  await page.reload()
  await expect(page.getByRole('spinbutton', { name: 'SKU id' })).toHaveValue('7')
  await expect(page.getByRole('textbox', { name: 'Region' })).toHaveValue('East')

  await page.getByRole('spinbutton', { name: 'SKU id' }).fill('8')
  await page.getByRole('textbox', { name: 'Region' }).fill('North')
  await expect.poll(() => new URL(page.url()).searchParams.get('skuId')).toBe('8')
  await expect.poll(() => new URL(page.url()).searchParams.get('region')).toBe('North')

  await page.goto('/inventory?skuId=not-a-number&region=%20East%20')
  await expect(page.getByRole('spinbutton', { name: 'SKU id' })).toHaveValue('')
  await expect.poll(() => new URL(page.url()).searchParams.get('skuId')).toBeNull()
  await expect.poll(() => new URL(page.url()).searchParams.get('region')).toBe('East')
})

test('inventory preserves the current table while a query updates', async ({ page }) => {
  await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7&region=East')
  await expect(page.getByText('EAST-1')).toBeVisible()

  await page.getByRole('spinbutton', { name: 'SKU id' }).fill('8')
  await page.getByRole('textbox', { name: 'Region' }).fill('North')
  await expect(page.locator('.async-state-view[data-status="updating"]')).toBeVisible()
  await expect(page.getByText('EAST-1')).toBeVisible()
  await expect(page.getByText('NORTH-1')).toBeVisible()
})

test('inventory scopes reservation locking to the active row without locking queries or reconciliation', async ({
  page,
}) => {
  await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7&region=East')
  await expect(page.getByText('EAST-1')).toBeVisible()

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
  await expect(page.getByRole('spinbutton', { name: 'SKU id' })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Reconcile', exact: true })).toBeEnabled()
  await expect(page.getByText('Released', { exact: true })).toBeVisible()
})

test('inventory patches reconciliation rows by sku and warehouse without dropping unrelated rows', async ({
  page,
}) => {
  await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7')

  await page.getByRole('button', { name: 'Reconcile', exact: true }).click()
  const discrepancies = page.locator('.data-table-shell__scroller[aria-label="Discrepancies"]')
  await expect(discrepancies).toContainText('1')
  await expect(discrepancies).toContainText('2')

  await page.getByRole('button', { name: 'Reconcile', exact: true }).click()
  await expect(discrepancies).toContainText('4')
  await expect(discrepancies).toContainText('2')
})

test('inventory makes state branches exclusive and safety stock accessible without color alone', async ({
  page,
}) => {
  await installInventoryMocks(page)
  await page.goto('/inventory')
  await expect(page.getByText('Enter a SKU id to load warehouse stock')).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)

  await page.goto('/inventory?skuId=9')
  await expect(page.locator('.async-state-view__empty')).toContainText(
    'Enter a SKU id to load warehouse stock',
  )
  await expect(page.getByRole('alert')).toHaveCount(0)

  await page.goto('/inventory?skuId=10')
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible()

  await page.goto('/inventory?skuId=7')
  const safetyStock = page.getByText('Above safety stock', { exact: false }).first()
  await expect(safetyStock).toContainText('Safety threshold: 3')
  await expect(safetyStock.locator('svg')).toHaveCount(1)
  await expect(page.locator('body')).not.toContainText(String.fromCharCode(0x8def))
  await expect(page.locator('body')).not.toContainText(String.fromCharCode(0x95b3))
})
test('inventory keeps the page contained while tables scroll on mobile', async ({ page }) => {
  await installInventoryMocks(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/inventory?skuId=7&region=East')
  await expect(page.getByText('EAST-1')).toBeVisible()
  await page.screenshot({ path: 'output/task-3-inventory-desktop.png', fullPage: true })

  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload()
  await expect(page.getByText('EAST-1')).toBeVisible()
  const layout = await page.evaluate(() => {
    const tables = Array.from(document.querySelectorAll<HTMLElement>('.data-table-shell__scroller'))
    return {
      pageOverflows: document.documentElement.scrollWidth > window.innerWidth,
      tableScrolls: tables.some((table) => table.scrollWidth > table.clientWidth),
    }
  })
  expect(layout.pageOverflows).toBe(false)
  expect(layout.tableScrolls).toBe(true)
  await page.screenshot({ path: 'output/task-3-inventory-mobile.png', fullPage: true })
})
