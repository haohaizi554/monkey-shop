import { expect, test, type Locator, type Page } from '@playwright/test'

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

interface InventoryMocks {
  stockRequests: (skuId: number) => number
  stockRequestTotal: () => number
}

async function expectTableRow(row: Locator, values: string[]) {
  const cellTexts = await row.locator('td .cell').allTextContents()
  expect(cellTexts.map((value) => value.replace(/\s+/g, ''))).toEqual(
    values.map((value) => value.replace(/\s+/g, '')),
  )
}

async function installInventoryMocks(page: Page): Promise<InventoryMocks> {
  const stockRequestCounts = new Map<number, number>()
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
    } else if (/^\/inventory\/skus\/\d+\/stocks$/.test(pathname)) {
      const skuId = Number(pathname.split('/')[3])
      const count = (stockRequestCounts.get(skuId) ?? 0) + 1
      stockRequestCounts.set(skuId, count)
      if (skuId === 7 && count === 1) {
        data = [stock(12), stock(6, 2, 'WEST-1', 'West')]
      } else if (skuId === 7) {
        await new Promise((resolve) => setTimeout(resolve, 650))
        data = [stock(4, 1, 'EAST-STALE', 'East'), stock(5, 2, 'WEST-STALE', 'West')]
      } else if (skuId === 8) {
        await new Promise((resolve) => setTimeout(resolve, 700))
        data = [stock(18, 3, 'NORTH-STALE', 'North', 8)]
      } else if (skuId === 9) {
        data = []
      } else if (skuId === 10) {
        status = 500
        data = { code: 'INVENTORY_DOWN', message: 'Inventory service unavailable' }
      }
    } else if (pathname === '/inventory/reservations' && request.method() === 'POST') {
      const body = request.postDataJSON() as { reservationKey: string }
      if (body.reservationKey === 'interleave') {
        await new Promise((resolve) => setTimeout(resolve, 250))
      }
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
          : reconciliationRuns === 2
            ? {
                balanced: false,
                discrepancies: [discrepancy(7, 1, 4)],
              }
            : reconciliationRuns === 3
              ? { balanced: true, discrepancies: [] }
              : { balanced: false, discrepancies: [] }
    } else if (pathname === '/tracking/events') {
      data = { id: 1, eventType: 'PAGE_VIEW' }
    }
    await route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(status === 200 ? ok(data) : data),
    })
  })

  return {
    stockRequests: (skuId) => stockRequestCounts.get(skuId) ?? 0,
    stockRequestTotal: () =>
      [...stockRequestCounts.values()].reduce((total, count) => total + count, 0),
  }
}

test('inventory persists and canonicalizes its URL-backed query without loading an invalid SKU', async ({
  page,
}) => {
  const mocks = await installInventoryMocks(page)
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

  await expect(page.getByText('NORTH-STALE')).toBeVisible()
  const requestsBeforeInvalidSku = mocks.stockRequestTotal()
  await page.goto('/inventory?skuId=not-a-number&region=%20East%20')
  await expect(page.getByRole('spinbutton', { name: 'SKU id' })).toHaveValue('')
  await expect.poll(() => new URL(page.url()).searchParams.get('skuId')).toBeNull()
  await expect.poll(() => new URL(page.url()).searchParams.get('region')).toBe('East')
  await page.waitForTimeout(350)
  expect(mocks.stockRequestTotal()).toBe(requestsBeforeInvalidSku)
})

test('inventory preserves the current table while a query updates', async ({ page }) => {
  await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7&region=East')
  await expect(page.getByText('EAST-1')).toBeVisible()

  await page.getByRole('spinbutton', { name: 'SKU id' }).fill('8')
  await page.getByRole('textbox', { name: 'Region' }).fill('North')
  await expect(page.locator('.async-state-view[data-status="updating"]')).toBeVisible()
  await expect(page.getByText('EAST-1')).toBeVisible()
  await expect(page.getByText('NORTH-STALE')).toBeVisible()
})

test('an older SKU response cannot replace the later query or its applied region', async ({
  page,
}) => {
  await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7&region=East')
  await expect(page.getByText('EAST-1')).toBeVisible()

  await page.getByRole('spinbutton', { name: 'SKU id' }).fill('8')
  await page.getByRole('textbox', { name: 'Region' }).fill('North')
  await expect(page.locator('.async-state-view[data-status="updating"]')).toBeVisible()
  await page.getByRole('spinbutton', { name: 'SKU id' }).fill('7')
  await page.getByRole('textbox', { name: 'Region' }).fill('East')

  await expect(page.getByText('EAST-STALE')).toBeVisible()
  await page.waitForTimeout(800)
  await expect(page.getByText('NORTH-STALE')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: 'Region' })).toHaveValue('East')
})

test('inventory allows a search during reservation and cancels the stale stock response before its row patch', async ({
  page,
}) => {
  const mocks = await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7&region=East')
  await expect(page.getByText('EAST-1')).toBeVisible()

  const keyInput = page.getByRole('textbox', { name: 'Reservation key' })
  await keyInput.fill('interleave')
  await page.getByRole('button', { name: 'Reserve', exact: true }).click()
  const search = page.getByRole('button', { name: 'Search', exact: true })
  await expect(search).toBeEnabled()
  const stockRequestsBeforeSearch = mocks.stockRequests(7)
  await search.click()
  await expect.poll(() => mocks.stockRequests(7)).toBeGreaterThan(stockRequestsBeforeSearch)
  await expect(page.getByText('interleave', { exact: true })).toBeVisible()
  await page.waitForTimeout(800)

  const stockRows = page.locator(
    '.data-table-shell__scroller[aria-label="Warehouse stock"] tbody tr',
  )
  await expectTableRow(stockRows.nth(0), [
    'EAST-1',
    'East',
    '10',
    '10',
    '0',
    '20',
    'Above safety stockSafety threshold: 3',
  ])
  await expect(page.getByText('EAST-STALE')).toHaveCount(0)
})

test('inventory scopes reservation locking and patches each reservation row independently', async ({
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
  const reservationRows = page.locator(
    '.data-table-shell__scroller[aria-label="Reservations"] tbody tr',
  )
  await expectTableRow(reservationRows.nth(0), [
    'reserve-two',
    '1',
    'Reserved',
    '2026-07-12T09:00:00',
    'Release',
  ])
  await expectTableRow(reservationRows.nth(1), [
    'reserve-one',
    '1',
    'Released',
    '2026-07-12T09:00:00',
    'Release',
  ])
})

test('inventory applies reconciliation as a full snapshot with stable composite row keys', async ({
  page,
}) => {
  await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7')

  await page.getByRole('button', { name: 'Reconcile', exact: true }).click()
  const discrepancyRows = page.locator(
    '.data-table-shell__scroller[aria-label="Discrepancies"] tbody tr',
  )
  await expect(discrepancyRows).toHaveCount(2)
  await expectTableRow(discrepancyRows.nth(0), ['7', '1', '1', '3', '0', '0'])
  await expectTableRow(discrepancyRows.nth(1), ['8', '1', '2', '3', '0', '0'])

  await page.getByRole('button', { name: 'Reconcile', exact: true }).click()
  await expect(discrepancyRows).toHaveCount(1)
  await expectTableRow(discrepancyRows.nth(0), ['7', '1', '4', '3', '0', '0'])
})

test('inventory distinguishes a balanced empty reconciliation from an unbalanced empty reconciliation', async ({
  page,
}) => {
  await installInventoryMocks(page)
  await page.goto('/inventory?skuId=7')

  const reconcile = page.getByRole('button', { name: 'Reconcile', exact: true })
  await reconcile.click()
  await reconcile.click()
  await reconcile.click()
  await expect(page.getByText('No inventory discrepancies')).toBeVisible()
  await expect(page.getByText('Balanced', { exact: true })).toBeVisible()

  await reconcile.click()
  await expect(page.getByText('No inventory discrepancies')).toBeVisible()
  await expect(page.getByText('Discrepancy', { exact: true })).toBeVisible()
  await expect(page.getByText('Balanced', { exact: true })).toHaveCount(0)
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

test('inventory keeps the page contained while all rendered tables scroll on mobile', async ({
  page,
}) => {
  await installInventoryMocks(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/inventory?skuId=7&region=East')
  await expect(page.getByText('EAST-1')).toBeVisible()
  await page.getByRole('textbox', { name: 'Reservation key' }).fill('mobile-row')
  await page.getByRole('button', { name: 'Reserve', exact: true }).click()
  await expect(page.getByText('mobile-row', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Reconcile', exact: true }).click()
  await expect(
    page.locator('.data-table-shell__scroller[aria-label="Discrepancies"]'),
  ).toBeVisible()
  await page.screenshot({ path: 'output/task-3-inventory-desktop.png', fullPage: true })

  await page.setViewportSize({ width: 390, height: 844 })
  const layout = await page.evaluate(() => {
    const labels = ['Warehouse stock', 'Reservations', 'Discrepancies']
    const tables = labels.map((label) => {
      const table = document.querySelector<HTMLElement>(
        `.data-table-shell__scroller[aria-label="${label}"]`,
      )
      return {
        label,
        exists: table !== null,
        scrolls: table ? table.scrollWidth > table.clientWidth : false,
      }
    })
    return { pageOverflows: document.documentElement.scrollWidth > window.innerWidth, tables }
  })
  expect(layout.pageOverflows).toBe(false)
  expect(layout.tables).toEqual([
    { label: 'Warehouse stock', exists: true, scrolls: true },
    { label: 'Reservations', exists: true, scrolls: true },
    { label: 'Discrepancies', exists: true, scrolls: true },
  ])
  await page.screenshot({ path: 'output/task-3-inventory-mobile.png', fullPage: true })
})
