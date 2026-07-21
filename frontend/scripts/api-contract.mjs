import fs from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const apiSourceFiles = (await fs.readdir(path.join(root, 'src', 'api'), { withFileTypes: true }))
  .filter(
    (entry) => entry.isFile() && entry.name.endsWith('.ts') && !entry.name.endsWith('.test.ts'),
  )
  .map((entry) => `src/api/${entry.name}`)
  .sort()
const sourceFiles = [
  ...apiSourceFiles,
  'src/composables/useCheckout.ts',
  'src/router/index.ts',
  'src/stores/auth.ts',
  'src/types.ts',
  'src/utils/csrf.ts',
  'src/utils/idempotencyIntent.ts',
]
const sources = Object.fromEntries(
  await Promise.all(
    sourceFiles.map(async (file) => [file, await fs.readFile(path.join(root, file), 'utf8')]),
  ),
)
const apiSource = apiSourceFiles.map((file) => sources[file]).join('\n')
const cookieAuthSource = [
  sources['src/api/http.ts'],
  sources['src/api/auth.ts'],
  sources['src/stores/auth.ts'],
  sources['src/types.ts'],
].join('\n')
const failures = []

function requireIncludes(file, snippet, label = snippet) {
  if (!sources[file]?.includes(snippet)) {
    failures.push(`${file}: missing ${label}`)
  }
}

function forbidIn(label, source, pattern, description) {
  if (pattern.test(source)) {
    failures.push(`${label}: contains forbidden ${description}`)
  }
}

async function filesUnder(directory) {
  const entries = await fs.readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(
    entries.map((entry) => {
      const file = path.join(directory, entry.name)
      return entry.isDirectory() ? filesUnder(file) : [file]
    }),
  )
  return nested.flat()
}

requireIncludes('src/api/http.ts', "baseURL: '/api/v1'", 'canonical v1 base URL')
requireIncludes('src/api/http.ts', 'withCredentials: true', 'cookie credential mode')
requireIncludes('src/api/http.ts', "'X-Trace-Id'", 'trace request header')
requireIncludes('src/api/http.ts', "'Idempotency-Key'", 'idempotency request header')
requireIncludes('src/api/http.ts', "'X-Device-Fingerprint'", 'device fingerprint header')
requireIncludes('src/api/http.ts', 'crypto.randomUUID', 'crypto trace generation')
requireIncludes('src/api/http.ts', 'crypto.getRandomValues', 'crypto trace fallback')
requireIncludes('src/api/http.ts', 'unsafeMethods.has(method)', 'unsafe request detection')
requireIncludes('src/api/http.ts', 'config.headers.set(csrfHeader())', 'unsafe request CSRF')
requireIncludes(
  'src/api/http.ts',
  'config.headers.set(idempotencyKeyHeader, createTraceId())',
  'unsafe request idempotency fallback',
)
requireIncludes('src/api/http.ts', "http.post('/auth/refresh')", 'cookie session refresh')
requireIncludes('src/api/http.ts', 'sessionRefreshPromise', 'single-page refresh coordination')
requireIncludes('src/api/http.ts', 'friendlyMessage', 'localized backend error normalization')
requireIncludes('src/api/http.ts', 'retryAfterSeconds', 'rate-limit retry metadata')

requireIncludes('src/api/auth.ts', "'/api/v1/auth/captcha'", 'auth captcha endpoint')
requireIncludes('src/api/auth.ts', "'/api/v1/users/captcha'", 'user captcha endpoint')
requireIncludes('src/api/auth.ts', "url: '/auth/login'", 'login endpoint')
requireIncludes('src/api/auth.ts', "url: '/users/logout'", 'filter-owned logout endpoint')
requireIncludes('src/api/auth.ts', "url: '/auth/register'", 'registration endpoint')
requireIncludes(
  'src/api/auth.ts',
  "url: '/auth/reset-password/request'",
  'password reset challenge endpoint',
)
requireIncludes('src/api/auth.ts', "url: '/auth/reset-password'", 'password reset endpoint')
requireIncludes('src/stores/auth.ts', 'await loadCurrentUser()', 'session hydration after login')
requireIncludes('src/stores/auth.ts', 'clearLocalSession()', 'local session invalidation')
requireIncludes('src/stores/auth.ts', 'isSafeLocalPath', 'safe post-login redirect')
requireIncludes('src/utils/csrf.ts', "'X-XSRF-TOKEN'", 'Spring CSRF header')
requireIncludes('src/api/orders.ts', 'url: `/orders/admin/${id}/shipments`', 'admin shipment read')
requireIncludes(
  'src/api/membership.ts',
  'url: `/membership/admin/${userId}/dashboard`',
  'target member dashboard',
)
requireIncludes(
  'src/api/membership.ts',
  'url: `/membership/admin/${userId}/points/earn`',
  'target member points adjustment',
)
requireIncludes(
  'src/api/membership.ts',
  'url: `/membership/admin/${userId}/level`',
  'target member level change',
)
requireIncludes('src/api/payments.ts', "url: '/payments/admin/refund'", 'admin refund endpoint')
requireIncludes(
  'src/api/payments.ts',
  "url: '/payments/reconciliation'",
  'structured reconciliation endpoint',
)
requireIncludes('src/api/risk.ts', "url: '/risk/assess'", 'risk assessment endpoint')
requireIncludes('src/composables/useCheckout.ts', 'await assessRisk({', 'checkout risk gate')
requireIncludes(
  'src/composables/useCheckout.ts',
  "assessment.decision !== 'ALLOW'",
  'risk decision enforcement',
)
requireIncludes('src/router/index.ts', "path: '/admin/orders'", 'admin order workspace')
requireIncludes('src/router/index.ts', "path: '/admin/payments'", 'admin payment workspace')
requireIncludes('src/router/index.ts', "path: '/admin/logistics'", 'admin logistics workspace')
requireIncludes('src/router/index.ts', "path: '/admin/members'", 'admin member workspace')

forbidIn('src/api', apiSource, /url:\s*['"`]\/api(?:\/|['"`])/, 'raw API-prefixed request URL')
forbidIn('src/api', apiSource, /url:\s*['"`]\/user(?:\/|['"`])/, 'singular user URL')
forbidIn('src/api', apiSource, /url:\s*['"`]\/address(?:\/|['"`])/, 'singular address URL')
forbidIn('src/api', apiSource, /url:\s*['"`]\/upload(?:\/|['"`])/, 'singular upload URL')
forbidIn('src/api', apiSource, /Math\.random/, 'Math.random request identifier')
forbidIn('browser API', apiSource, /\/logistics\/webhook/, 'machine logistics webhook client')
forbidIn(
  'browser API',
  apiSource,
  /url:\s*['"`]\/logistics\/shipments['"`]/,
  'legacy owner shipment client',
)
forbidIn(
  'browser API',
  apiSource,
  /\/inventory\/reservations\/\$\{[^}]+\}\/deduct/,
  'inventory deduction client',
)
forbidIn(
  'browser API',
  apiSource,
  /url:\s*['"`]\/inventory\/compensations['"`]/,
  'inventory compensation client',
)
forbidIn(
  'browser API',
  apiSource,
  /url:\s*['"`]\/membership\/points\/earn['"`]/,
  'legacy self-targeting points client',
)
forbidIn(
  'browser API',
  apiSource,
  /url:\s*['"`]\/membership\/level['"`]/,
  'legacy self-targeting level client',
)
forbidIn('browser API', apiSource, /\/tracking\/profile\/\$\{userId\}/, 'arbitrary profile client')
forbidIn('browser API', apiSource, /\/payments\/callback/, 'payment callback client')
forbidIn('cookie auth frontend', cookieAuthSource, /Authorization|Bearer/, 'token authorization')
forbidIn('cookie auth frontend', cookieAuthSource, /\baccessToken\b/, 'access token handling')
forbidIn('cookie auth frontend', cookieAuthSource, /\brefreshToken\b/, 'refresh token handling')
forbidIn(
  'cookie auth frontend',
  cookieAuthSource,
  /(?:localStorage|sessionStorage)\.(?:getItem|setItem|removeItem)\(['"`][^'"`]*(?:access|refresh|token)[^'"`]*['"`]/,
  'browser token storage',
)

const uiFiles = (await filesUnder(path.join(root, 'src'))).filter(
  (file) =>
    /\.(?:ts|vue)$/.test(file) &&
    !file.includes(`${path.sep}api${path.sep}`) &&
    !file.endsWith('.test.ts'),
)
const uiSource = (await Promise.all(uiFiles.map((file) => fs.readFile(file, 'utf8')))).join('\n')
const clientFunctions = []
const internalApiModules = new Set(['src/api/http.ts', 'src/api/page.ts', 'src/api/safeJson.ts'])
for (const file of apiSourceFiles) {
  if (internalApiModules.has(file)) {
    continue
  }
  for (const match of sources[file].matchAll(/export\s+(?:async\s+)?function\s+(\w+)/g)) {
    const name = match[1]
    clientFunctions.push(`${file}#${name}`)
    if (!new RegExp(`\\b${name}\\b`).test(uiSource)) {
      failures.push(`${file}: exported client ${name} has no UI or workflow consumer`)
    }
  }
}

if (apiSourceFiles.length < 15) {
  failures.push(
    `src/api: expected a complete module scan, found only ${apiSourceFiles.length} files`,
  )
}

if (failures.length > 0) {
  console.error(
    `API contract check failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`,
  )
  process.exit(1)
}

console.log(
  `API contract check passed (${apiSourceFiles.length} modules, ${clientFunctions.length} UI-consumed clients)`,
)
