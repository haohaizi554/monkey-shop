import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown) {
  return { code: 'OK', message: 'ok', data, traceId: 'risk-test' }
}

async function installRiskMocks(page: Page) {
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
    } else if (pathname === '/risk/reviews' && request.method() === 'GET') {
      data = [
        {
          id: 101,
          userId: 7,
          productId: 9,
          type: 'PRICE_ANOMALY',
          score: 88,
          status: 'PENDING',
          detail: 'price changed quickly',
          createdAt: '2026-07-12T08:00:00',
        },
        {
          id: 102,
          userId: 8,
          type: 'SELF_BUY',
          score: 30,
          status: 'APPROVED',
          detail: 'resolved',
          resolution: 'verified',
          createdAt: '2026-07-12T07:00:00',
        },
      ]
    } else if (pathname === '/risk/reviews/101/resolve') {
      await new Promise((resolve) => setTimeout(resolve, 250))
      const body = request.postDataJSON() as {
        status: string
        resolution: string
        totpCode: string
      }
      data = {
        id: 101,
        userId: 7,
        productId: 9,
        type: 'PRICE_ANOMALY',
        score: 88,
        status: body.status,
        detail: 'price changed quickly',
        resolution: body.resolution,
        createdAt: '2026-07-12T08:00:00',
        handledAt: '2026-07-12T08:05:00',
      }
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

test('risk block decision enforces note, TOTP, confirmation, and row pending', async ({ page }) => {
  await installRiskMocks(page)
  await page.goto('/risk?status=PENDING&minScore=70')
  await expect(page.getByText('Price anomaly')).toBeVisible()
  await expect(page.getByText('Self buy')).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText('PRICE_ANOMALY')

  await page.getByRole('button', { name: 'Block case 101' }).click()
  await page.getByRole('button', { name: 'Save decision' }).click()
  await expect(page.getByText('Resolution note is required')).toBeVisible()
  await page.getByRole('textbox', { name: 'Resolution note' }).fill('confirmed abuse')
  await page.getByRole('button', { name: 'Save decision' }).click()
  await expect(page.getByText('Admin TOTP is required for blocking')).toBeVisible()
  await page.getByRole('textbox', { name: 'TOTP', exact: true }).fill('654321')
  await page.getByRole('button', { name: 'Save decision' }).click()
  await expect(page.getByRole('dialog', { name: 'Block risk case' })).toBeVisible()
  await page.getByRole('button', { name: 'Block', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save decision' })).toBeDisabled()
  await expect(page.locator('.el-drawer').getByText('Blocked', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(/status=PENDING/)
})
test('risk assessment is independent from queue refresh and retains its localized result after a failed retry', async ({
  page,
}) => {
  let calls = 0
  let releaseAssessment!: () => void
  const assessmentGate = new Promise<void>((resolve) => {
    releaseAssessment = resolve
  })
  let reviewLoads = 0

  await installRiskMocks(page)
  await page.route('**/api/v1/risk/assess', async (route) => {
    calls += 1
    if (calls === 2) {
      await assessmentGate
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'FAILED', message: 'backend failure' }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          userId: 7,
          score: 91,
          decision: 'BLOCK',
          signals: [{ type: 'PRICE_ANOMALY', weight: 30, detail: 'price changed quickly' }],
          productAutoUnlisted: true,
          userTokensRevoked: true,
          assessedAt: '2026-07-12T08:00:00',
        }),
      ),
    })
  })
  await page.route('**/api/v1/risk/reviews', async (route) => {
    reviewLoads += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok([])),
    })
  })

  await page.goto('/risk')
  await page.getByRole('button', { name: 'Assess', exact: true }).click()
  await expect(page.getByText('91', { exact: true })).toBeVisible()
  await expect(page.getByText('Product auto-delisted', { exact: true })).toBeVisible()
  await expect(page.getByText('User tokens revoked', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Assess', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => reviewLoads).toBeGreaterThan(1)
  releaseAssessment()
  await expect(page.getByText('91', { exact: true })).toBeVisible()
  await expect(page.locator('body')).not.toContainText('backend failure')
})

test('risk queue normalizes URL filters, survives reload, and preserves table data while refreshing', async ({
  page,
}) => {
  let loads = 0
  let releaseRefresh!: () => void
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve
  })
  await installRiskMocks(page)
  await page.route('**/api/v1/risk/reviews', async (route) => {
    loads += 1
    if (loads > 2) await refreshGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok([
          {
            id: 101,
            userId: 7,
            type: 'PRICE_ANOMALY',
            score: 88,
            status: 'PENDING',
            detail: 'price changed quickly',
            createdAt: '2026-07-12T08:00:00',
          },
          {
            id: 102,
            userId: 8,
            type: 'SELF_BUY',
            score: 30,
            status: 'APPROVED',
            detail: 'resolved',
            createdAt: '2026-07-12T07:00:00',
          },
        ]),
      ),
    })
  })

  await page.goto('/risk?status=not-a-status&minScore=-50&maxScore=150')
  await expect(page).toHaveURL(/minScore=0/)
  await expect(page).toHaveURL(/maxScore=100/)
  await expect(page).not.toHaveURL(/status=not-a-status/)
  await expect(page.getByText('Price anomaly', { exact: true })).toBeVisible()
  await expect(page.locator('body')).not.toContainText('PRICE_ANOMALY')
  await page.reload()
  await expect(page.getByText('Price anomaly', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => loads).toBeGreaterThan(1)
  await expect(page.getByText('Price anomaly', { exact: true })).toBeVisible()
  releaseRefresh()
})

test('risk review locks only its exact action and keeps local retry context after a failed decision', async ({
  page,
}) => {
  let resolveCalls = 0
  let releaseFirst!: () => void
  const firstGate = new Promise<void>((resolve) => {
    releaseFirst = resolve
  })
  await installRiskMocks(page)
  await page.route('**/api/v1/risk/reviews', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok([
          {
            id: 101,
            userId: 7,
            type: 'PRICE_ANOMALY',
            score: 88,
            status: 'PENDING',
            detail: 'price changed quickly',
            createdAt: '2026-07-12T08:00:00',
          },
          {
            id: 103,
            userId: 9,
            type: 'SELF_BUY',
            score: 66,
            status: 'PENDING',
            detail: 'manual review needed',
            createdAt: '2026-07-12T08:00:00',
          },
        ]),
      ),
    })
  })
  await page.route('**/api/v1/risk/reviews/101/resolve', async (route) => {
    resolveCalls += 1
    if (resolveCalls === 1) {
      await firstGate
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          ok({
            id: 101,
            userId: 7,
            type: 'PRICE_ANOMALY',
            score: 88,
            status: 'APPROVED',
            detail: 'price changed quickly',
            resolution: 'verified',
            createdAt: '2026-07-12T08:00:00',
          }),
        ),
      })
      return
    }
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'FAILED', message: 'backend failure' }),
    })
  })

  await page.route('**/api/v1/risk/reviews/103/resolve', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'FAILED', message: 'backend failure' }),
    })
  })
  await page.goto('/risk')
  await page.getByRole('button', { name: 'Approve case 101' }).click()
  const drawer = page.locator('.el-drawer')
  await drawer.getByRole('textbox', { name: 'Resolution note' }).fill('verified')
  await drawer.getByRole('button', { name: 'Save decision' }).click()
  await expect(page.getByRole('button', { name: 'Approve case 103' })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
  releaseFirst()
  await expect(page.locator('tbody').getByText('Approved', { exact: true })).toBeVisible()
  await page.keyboard.press('Escape')

  await page.getByRole('button', { name: 'Approve case 103' }).click()
  await drawer.getByRole('textbox', { name: 'Resolution note' }).fill('retry note')
  await drawer.getByRole('button', { name: 'Save decision' }).click()
  await expect(drawer.getByRole('alert')).toBeVisible()
  await expect(drawer.getByRole('textbox', { name: 'Resolution note' })).toHaveValue('retry note')
})

test('risk review renders without page overflow at desktop and mobile workbench sizes', async ({
  page,
}) => {
  await installRiskMocks(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/risk')
  await expect(page.getByText('Manual review', { exact: true })).toBeVisible()
  expect(
    await page.locator('html').evaluate((element) => element.scrollWidth <= element.clientWidth),
  ).toBeTruthy()
  await page.screenshot({ path: 'output/risk-desktop.png', fullPage: true })

  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload()
  const manualReview = page.getByRole('heading', { name: 'Manual review', exact: true })
  await expect(manualReview).toBeVisible()
  expect((await manualReview.boundingBox())?.y).toBeLessThan(844)
  await page.getByRole('button', { name: 'Review case 101' }).click()
  const drawer = page.locator('.el-drawer')
  await drawer.getByText('Reject', { exact: true }).click()
  const resolution = drawer.getByRole('textbox', { name: 'Resolution note' })
  const save = drawer.getByRole('button', { name: 'Save decision' })
  await expect(resolution).toBeVisible()
  await expect(save).toBeVisible()
  expect((await resolution.boundingBox())?.y).toBeLessThan(844)
  expect((await save.boundingBox())?.y).toBeLessThan(844)
  await resolution.fill('mobile review')
  await save.click()
  await expect(page.locator('tbody').getByText('Rejected', { exact: true }).first()).toBeVisible()
  expect(
    await page.locator('html').evaluate((element) => element.scrollWidth <= element.clientWidth),
  ).toBeTruthy()
  await page.screenshot({ path: 'output/risk-mobile.png', fullPage: true })
})

test('risk ignores a stale refresh after a decision, then accepts a later authority refresh', async ({
  page,
}) => {
  let reviewLoads = 0
  let releaseRefresh!: () => void
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve
  })
  let decisionCalls = 0
  let releaseDecision!: () => void
  const decisionGate = new Promise<void>((resolve) => {
    releaseDecision = resolve
  })

  await installRiskMocks(page)
  await page.route('**/api/v1/risk/reviews', async (route) => {
    reviewLoads += 1
    if (reviewLoads === 2) await refreshGate
    const status = reviewLoads >= 3 ? 'REJECTED' : 'PENDING'
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok([
          {
            id: 101,
            userId: 7,
            type: 'PRICE_ANOMALY',
            score: 88,
            status,
            detail: 'price changed quickly',
            createdAt: '2026-07-12T08:00:00',
          },
        ]),
      ),
    })
  })
  await page.route('**/api/v1/risk/reviews/101/resolve', async (route) => {
    decisionCalls += 1
    await decisionGate
    const body = route.request().postDataJSON() as { status: string; resolution: string }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 101,
          userId: 7,
          type: 'PRICE_ANOMALY',
          score: 88,
          status: body.status,
          detail: 'price changed quickly',
          resolution: body.resolution,
          createdAt: '2026-07-12T08:00:00',
        }),
      ),
    })
  })

  await page.goto('/risk')
  await page.getByRole('button', { name: 'Approve case 101' }).click()
  const drawer = page.locator('.el-drawer')
  await drawer.getByRole('textbox', { name: 'Resolution note' }).fill('verified')
  await drawer.getByRole('button', { name: 'Save decision' }).click()
  await expect.poll(() => decisionCalls).toBe(1)
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => reviewLoads).toBe(2)
  releaseDecision()
  await expect(page.locator('tbody').getByText('Approved', { exact: true })).toBeVisible()
  releaseRefresh()
  await page.waitForTimeout(100)
  await expect(page.locator('tbody').getByText('Approved', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => reviewLoads).toBe(3)
  await expect(page.locator('tbody').getByText('Rejected', { exact: true }).first()).toBeVisible()
})

test('risk BLOCK confirmation locks duplicate saves, cancels cleanly, and can be retried', async ({
  page,
}) => {
  let resolveCalls = 0
  await installRiskMocks(page)
  await page.route('**/api/v1/risk/reviews/101/resolve', async (route) => {
    resolveCalls += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 101,
          userId: 7,
          type: 'PRICE_ANOMALY',
          score: 88,
          status: 'BLOCKED',
          detail: 'price changed quickly',
          resolution: 'blocked',
          createdAt: '2026-07-12T08:00:00',
        }),
      ),
    })
  })

  await page.goto('/risk')
  await page.getByRole('button', { name: 'Block case 101' }).click()
  const drawer = page.locator('.el-drawer')
  await drawer.getByRole('textbox', { name: 'Resolution note' }).fill('confirmed abuse')
  await drawer.getByRole('textbox', { name: 'TOTP', exact: true }).fill('654321')
  const save = drawer.getByRole('button', { name: 'Save decision' })
  await save.click()
  await expect(save).toBeDisabled()
  await expect(page.getByRole('dialog', { name: 'Block risk case' })).toHaveCount(1)
  await page.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect.poll(() => resolveCalls).toBe(0)
  await expect(save).toBeEnabled()

  await save.click()
  await page.getByRole('button', { name: 'Block', exact: true }).click()
  await expect.poll(() => resolveCalls).toBe(1)
  await expect(page.locator('tbody').getByText('Blocked', { exact: true })).toBeVisible()
})

test('risk rejects with a verified payload and retries another case after a local failure', async ({
  page,
}) => {
  let retryCalls = 0
  const bodies: Array<{ status: string; resolution: string }> = []
  await installRiskMocks(page)
  await page.route('**/api/v1/risk/reviews', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok([
          {
            id: 101,
            userId: 7,
            type: 'PRICE_ANOMALY',
            score: 88,
            status: 'PENDING',
            detail: 'price changed quickly',
            createdAt: '2026-07-12T08:00:00',
          },
          {
            id: 103,
            userId: 9,
            type: 'SELF_BUY',
            score: 66,
            status: 'PENDING',
            detail: 'manual review needed',
            createdAt: '2026-07-12T08:00:00',
          },
        ]),
      ),
    })
  })
  await page.route('**/api/v1/risk/reviews/101/resolve', async (route) => {
    const body = route.request().postDataJSON() as { status: string; resolution: string }
    bodies.push(body)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 101,
          userId: 7,
          type: 'PRICE_ANOMALY',
          score: 88,
          status: body.status,
          detail: 'price changed quickly',
          resolution: body.resolution,
          createdAt: '2026-07-12T08:00:00',
        }),
      ),
    })
  })
  await page.route('**/api/v1/risk/reviews/103/resolve', async (route) => {
    retryCalls += 1
    if (retryCalls === 1) {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'FAILED', message: 'backend failure' }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        ok({
          id: 103,
          userId: 9,
          type: 'SELF_BUY',
          score: 66,
          status: 'APPROVED',
          detail: 'manual review needed',
          resolution: 'verified',
          createdAt: '2026-07-12T08:00:00',
        }),
      ),
    })
  })

  await page.goto('/risk')
  await page.getByRole('button', { name: 'Reject case 101' }).click()
  const drawer = page.locator('.el-drawer')
  await drawer.getByRole('textbox', { name: 'Resolution note' }).fill('rejected manually')
  await drawer.getByRole('button', { name: 'Save decision' }).click()
  await expect(page.locator('tbody').getByText('Rejected', { exact: true }).first()).toBeVisible()
  expect(bodies).toEqual([{ status: 'REJECTED', resolution: 'rejected manually' }])
  await page.keyboard.press('Escape')

  await page.getByRole('button', { name: 'Approve case 103' }).click()
  await drawer.getByRole('textbox', { name: 'Resolution note' }).fill('retry note')
  await drawer.getByRole('button', { name: 'Save decision' }).click()
  await expect(drawer.getByRole('alert')).toBeVisible()
  await expect(drawer).not.toContainText('backend failure')
  await expect(drawer.getByRole('textbox', { name: 'Resolution note' })).toHaveValue('retry note')
  await drawer.getByRole('button', { name: 'Save decision' }).click()
  await expect(page.locator('tbody').getByText('Approved', { exact: true })).toBeVisible()
  await expect(drawer.getByRole('alert')).toHaveCount(0)
})
