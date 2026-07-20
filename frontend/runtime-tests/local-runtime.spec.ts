import { createHmac } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page, type Request, type TestInfo } from '@playwright/test'

const ADMIN_ROUTES = [
  '/admin',
  '/admin/orders',
  '/admin/payments',
  '/admin/logistics',
  '/admin/members',
] as const

function loadLocalEnvironment(): void {
  const envPath = fileURLToPath(new URL('../../.env', import.meta.url))
  let content: string
  try {
    content = readFileSync(envPath, 'utf8')
  } catch {
    return
  }
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const separator = line.indexOf('=')
    if (separator < 1) continue
    const key = line.slice(0, separator).trim()
    if (process.env[key] !== undefined) continue
    let value = line.slice(separator + 1).trim()
    if (
      value.length >= 2 &&
      ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1)
    }
    process.env[key] = value
  }
}

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) {
    throw new Error(`${name} must be configured for the local runtime acceptance test`)
  }
  return value
}

function decodeBase32(value: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  const normalized = value.toUpperCase().replace(/[\s=]/g, '')
  let bits = ''
  for (const character of normalized) {
    const index = alphabet.indexOf(character)
    if (index < 0) throw new Error('ADMIN_TOTP_SECRET is not valid Base32')
    bits += index.toString(2).padStart(5, '0')
  }
  const bytes: number[] = []
  for (let offset = 0; offset + 8 <= bits.length; offset += 8) {
    bytes.push(Number.parseInt(bits.slice(offset, offset + 8), 2))
  }
  return Buffer.from(bytes)
}

function currentTotp(secret: string): string {
  const counter = BigInt(Math.floor(Date.now() / 30000))
  const message = Buffer.alloc(8)
  message.writeBigUInt64BE(counter)
  const digest = createHmac('sha1', decodeBase32(secret)).update(message).digest()
  const offset = digest[digest.length - 1] & 0x0f
  const binary =
    ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff)
  return String(binary % 1_000_000).padStart(6, '0')
}

interface RuntimeMonitor {
  failures: string[]
  pendingApiRequests: Set<Request>
}

function createRuntimeMonitor(page: Page): RuntimeMonitor {
  const failures: string[] = []
  const pendingApiRequests = new Set<Request>()
  page.on('pageerror', (error) => failures.push(`page error: ${error.message}`))
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/')) pendingApiRequests.add(request)
  })
  page.on('requestfinished', (request) => pendingApiRequests.delete(request))
  page.on('requestfailed', (request) => {
    pendingApiRequests.delete(request)
    const url = new URL(request.url())
    const error = request.failure()?.errorText ?? 'unknown error'
    if (url.pathname.startsWith('/api/') && error !== 'net::ERR_ABORTED') {
      failures.push(`request failed: ${request.method()} ${url.pathname} (${error})`)
    }
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/') && response.status() >= 500) {
      failures.push(`server error: ${response.status()} ${url.pathname}`)
    }
  })
  return { failures, pendingApiRequests }
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      ),
    )
    .toBeLessThanOrEqual(1)
}

async function expectRuntimeSettled(page: Page, monitor: RuntimeMonitor): Promise<void> {
  await expect(
    page.locator(
      '.async-state-view[data-status="loading"], .async-state-view[data-status="updating"]',
    ),
  ).toHaveCount(0)
  await expect
    .poll(() => monitor.pendingApiRequests.size, {
      message: 'all runtime API requests should settle before navigating',
    })
    .toBe(0)
}

async function saveEvidence(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await page.screenshot({
    path: testInfo.outputPath(`${name}.png`),
    fullPage: true,
    animations: 'disabled',
  })
}

loadLocalEnvironment()

test('local stack serves the storefront and authenticates the bootstrap admin', async ({
  page,
}, testInfo) => {
  const adminUsername = requiredEnvironment('ADMIN_INIT_USERNAME')
  const adminPassword = requiredEnvironment('ADMIN_INIT_PASSWORD')
  const adminTotpSecret = requiredEnvironment('ADMIN_TOTP_SECRET')
  const runtimeMonitor = createRuntimeMonitor(page)

  await page.addInitScript(() => {
    localStorage.setItem('monkeyshop-locale', 'en')
    localStorage.setItem('monkeyshop-theme', 'light')
  })

  const storefrontResponse = await page.goto('/shop', { waitUntil: 'domcontentloaded' })
  expect(storefrontResponse?.ok()).toBe(true)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.locator('#main-content')).toBeVisible()
  await expectRuntimeSettled(page, runtimeMonitor)
  await expectNoHorizontalOverflow(page)
  await saveEvidence(page, testInfo, 'storefront-desktop')

  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload({ waitUntil: 'domcontentloaded' })
  await expect(page.locator('#main-content')).toBeVisible()
  await expectRuntimeSettled(page, runtimeMonitor)
  await expectNoHorizontalOverflow(page)
  await saveEvidence(page, testInfo, 'storefront-mobile')

  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.getByTestId('login-username').fill(adminUsername)
  await page.getByTestId('login-password').fill(adminPassword)
  await page.getByTestId('login-submit').click()

  const totpInput = page.locator('input[autocomplete="one-time-code"]')
  await expect(totpInput).toBeVisible()
  await totpInput.fill(currentTotp(adminTotpSecret))
  await page.getByTestId('login-submit').click()
  await expect(page).toHaveURL(/\/admin(?:[/?#]|$)/)

  for (const route of ADMIN_ROUTES) {
    if (new URL(page.url()).pathname !== route) {
      await page.goto(route, { waitUntil: 'domcontentloaded' })
    }
    await expect(page).toHaveURL(new RegExp(`${route.replaceAll('/', '\\/')}(?:[/?#]|$)`))
    await expect(page.locator('#main-content')).toBeVisible()
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expectRuntimeSettled(page, runtimeMonitor)
    await expectNoHorizontalOverflow(page)
  }
  await saveEvidence(page, testInfo, 'admin-members-desktop')

  expect(runtimeMonitor.failures, runtimeMonitor.failures.join('\n')).toEqual([])
})
