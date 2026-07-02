import fs from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sourceFiles = [
  'src/api/admin.ts',
  'src/api/auth.ts',
  'src/api/catalog.ts',
  'src/api/http.ts',
  'src/api/orders.ts',
  'src/api/user.ts',
  'src/composables/useCheckout.ts',
  'src/components/AppShell.vue',
  'src/components/ProductImage.vue',
  'src/locales/index.ts',
  'src/stores/auth.ts',
  'src/stores/theme.ts',
  'src/seo/nuxt-reservation.ts',
  'src/seo/product-json-ld.ts',
  'src/seo/useJsonLd.ts',
  'src/router/index.ts',
  'src/types.ts',
  'src/utils/csrf.ts',
  'src/views/AdminView.vue',
  'src/views/OrdersView.vue',
  'src/views/ProductDetailView.vue',
  'src/views/ShopView.vue',
  'public/robots.txt',
  'public/sitemap.xml',
  'tests/a11y.spec.ts',
  'scripts/lighthouse.mjs',
  'vite.config.ts',
]

const sources = Object.fromEntries(
  await Promise.all(
    sourceFiles.map(async (file) => [file, await fs.readFile(path.join(root, file), 'utf8')]),
  ),
)
const apiSource = Object.entries(sources)
  .filter(([file]) => file.startsWith('src/api/'))
  .map(([, source]) => source)
  .join('\n')
const cookieAuthSource = [
  sources['src/api/http.ts'],
  sources['src/api/auth.ts'],
  sources['src/stores/auth.ts'],
  sources['src/types.ts'],
].join('\n')

const failures = []

function requireIncludes(file, snippet, label = snippet) {
  if (!sources[file].includes(snippet)) {
    failures.push(`${file}: missing ${label}`)
  }
}

function forbidIn(label, source, pattern, description) {
  if (pattern.test(source)) {
    failures.push(`${label}: contains legacy ${description}`)
  }
}

requireIncludes('src/api/http.ts', "baseURL: '/api/v1'", 'versioned API base URL')
requireIncludes('src/api/http.ts', "'X-Trace-Id'", 'trace id request header')
requireIncludes('src/api/http.ts', "'Idempotency-Key'", 'idempotency key request header')
requireIncludes('src/api/http.ts', 'crypto.randomUUID', 'browser trace id generation')
requireIncludes('src/api/http.ts', 'crypto.getRandomValues', 'crypto-backed trace id fallback')
requireIncludes('src/api/http.ts', 'withCredentials: true', 'cookie credential mode')
requireIncludes('src/api/http.ts', 'unsafeMethods.has(method)', 'unsafe request detection')
requireIncludes(
  'src/api/http.ts',
  'config.headers.set(idempotencyKeyHeader, createTraceId())',
  'global unsafe request idempotency key',
)
requireIncludes('src/api/http.ts', 'csrfHeader()', 'unsafe request CSRF header')
requireIncludes('src/utils/csrf.ts', "'X-XSRF-TOKEN'", 'Spring CSRF header')
requireIncludes('src/api/auth.ts', "'/api/v1/auth/captcha'", 'auth captcha endpoint')
requireIncludes('src/api/auth.ts', "'/api/v1/users/captcha'", 'user captcha endpoint')
requireIncludes('src/api/auth.ts', "url: '/auth/login'", 'cookie-backed login endpoint')
requireIncludes('src/stores/auth.ts', 'await loadCurrentUser()', 'cookie session hydration')
requireIncludes(
  'src/components/AppShell.vue',
  'aria-label="Switch language"',
  'language toggle label',
)
requireIncludes(
  'src/components/AppShell.vue',
  "localStorage.setItem('monkeyshop-locale'",
  'locale persistence',
)
requireIncludes('src/locales/index.ts', 'initialLocale()', 'SSR-safe locale initialization')
requireIncludes('src/locales/index.ts', 'zh: {', 'Chinese locale bundle')
requireIncludes('src/locales/index.ts', "shop: '\\u5546\\u57ce'", 'Chinese shop locale')
requireIncludes('src/stores/theme.ts', "storageKey: 'monkeyshop-theme'", 'theme persistence')
requireIncludes('src/stores/theme.ts', "valueDark: 'dark'", 'Element Plus dark class')
requireIncludes('src/api/orders.ts', "'Idempotency-Key'", 'order idempotency header')
requireIncludes('src/router/index.ts', "path: '/shop/:productId'", 'product detail route')
requireIncludes(
  'src/router/index.ts',
  "import('@/views/ProductDetailView.vue')",
  'product detail route component',
)
requireIncludes('src/composables/useCheckout.ts', 'submitTimer', 'shared checkout debounce')
requireIncludes(
  'src/composables/useCheckout.ts',
  'afterOrderCreated',
  'checkout post-create refresh hook',
)
requireIncludes(
  'src/composables/useCheckout.ts',
  'await createOrder(selectedMonkey.value.id, selectedAddressId.value)',
  'shared checkout order creation',
)
requireIncludes(
  'src/components/ProductImage.vue',
  'v-fallback-img',
  'CSP-compatible fallback image directive',
)
requireIncludes(
  'src/components/ProductImage.vue',
  "addEventListener('error'",
  'directive-managed image error listener',
)
requireIncludes(
  'src/views/ShopView.vue',
  'useCheckout({ afterOrderCreated: loadMonkeys, notify: showNotice })',
  'shared checkout flow',
)
requireIncludes('src/views/ShopView.vue', 'submittingOrder', 'checkout submit loading guard')
requireIncludes(
  'src/views/ShopView.vue',
  ':disabled="submittingOrder"',
  'checkout submit disabled state',
)
requireIncludes('src/views/ShopView.vue', 'openingCheckoutId', 'checkout dialog open loading guard')
requireIncludes('src/views/ShopView.vue', '`/shop/${monkey.id}`', 'catalog product detail links')
requireIncludes(
  'src/views/ShopView.vue',
  'productListStructuredData',
  'shop product structured data',
)
requireIncludes(
  'src/views/ShopView.vue',
  "useJsonLd('monkeyshop-product-list-jsonld'",
  'shop JSON-LD injection',
)
forbidIn(
  'src/views/ShopView.vue',
  sources['src/views/ShopView.vue'],
  /element-plus/,
  'shop Element Plus import',
)
forbidIn(
  'src/components/AppShell.vue',
  sources['src/components/AppShell.vue'],
  /element-plus/,
  'shell Element Plus import',
)
requireIncludes(
  'vite.config.ts',
  'resolveDependencies(_filename, deps)',
  'modulepreload dependency filter',
)
requireIncludes(
  'src/views/ProductDetailView.vue',
  "useJsonLd('monkeyshop-product-jsonld'",
  'product detail structured data',
)
requireIncludes(
  'src/views/ProductDetailView.vue',
  'productJsonLd(product.value)',
  'Product JSON-LD source',
)
requireIncludes('src/views/ProductDetailView.vue', 'useCheckout()', 'detail checkout flow')
requireIncludes('src/views/ProductDetailView.vue', 'listMonkeys()', 'detail catalog resolution')
requireIncludes('src/views/OrdersView.vue', 'useDebounceFn', 'debounced order actions')
requireIncludes('src/views/OrdersView.vue', 'actionInProgress', 'order action loading guard')
requireIncludes(
  'src/views/OrdersView.vue',
  ':loading="actionInProgress ===',
  'order action loading state',
)
requireIncludes('src/views/AdminView.vue', 'useDebounceFn', 'debounced admin actions')
requireIncludes('src/views/AdminView.vue', 'savingProduct', 'product submit loading guard')
requireIncludes(
  'src/views/AdminView.vue',
  'orderActionInProgress',
  'admin order action loading guard',
)
requireIncludes(
  'src/seo/product-json-ld.ts',
  "'@context': 'https://schema.org'",
  'schema.org context',
)
requireIncludes('src/seo/product-json-ld.ts', "'@type': 'Product'", 'Product JSON-LD')
requireIncludes('src/seo/product-json-ld.ts', "'@type': 'ItemList'", 'ItemList JSON-LD')
requireIncludes('src/seo/product-json-ld.ts', 'priceCurrency', 'offer price currency')
requireIncludes(
  'src/seo/product-json-ld.ts',
  'url: `${siteOrigin}/shop/${monkey.id}`',
  'canonical detail offer URL',
)
requireIncludes('src/seo/useJsonLd.ts', 'application/ld+json', 'JSON-LD script type')
requireIncludes('src/seo/useJsonLd.ts', 'csp-nonce', 'CSP nonce propagation')
requireIncludes('src/seo/useJsonLd.ts', 'data.value == null', 'JSON-LD null cleanup')
requireIncludes('src/seo/nuxt-reservation.ts', 'nuxtSsrRouteRules', 'Nuxt SSR reservation rules')
requireIncludes('src/seo/nuxt-reservation.ts', "rendering: 'ssr'", 'public SSR route reservation')
requireIncludes('src/seo/nuxt-reservation.ts', "rendering: 'csr'", 'private CSR route reservation')
requireIncludes('src/seo/nuxt-reservation.ts', 'nuxtPrerenderRoutes', 'Nuxt prerender route list')
requireIncludes(
  'src/seo/nuxt-reservation.ts',
  'nuxtSitemapDynamicSources',
  'dynamic product sitemap source',
)
requireIncludes(
  'src/seo/nuxt-reservation.ts',
  "source: '/api/v1/monkeys'",
  'versioned sitemap source API',
)
requireIncludes('public/robots.txt', 'User-agent: *', 'robots user-agent policy')
requireIncludes('public/robots.txt', 'Allow: /', 'robots public catalog access')
requireIncludes(
  'public/robots.txt',
  'Sitemap: https://monkeyshop.example.com/sitemap.xml',
  'robots sitemap pointer',
)
requireIncludes(
  'public/sitemap.xml',
  '<loc>https://monkeyshop.example.com/shop</loc>',
  'public shop sitemap URL',
)
requireIncludes('public/sitemap.xml', '<priority>1.0</priority>', 'shop sitemap priority')
requireIncludes('tests/a11y.spec.ts', '**/api/v1/users/me', 'versioned user mock')
requireIncludes('tests/a11y.spec.ts', '**/api/v1/monkeys', 'versioned product mock')
requireIncludes(
  'tests/a11y.spec.ts',
  'product detail route renders product JSON-LD',
  'product detail a11y route',
)
requireIncludes(
  'tests/a11y.spec.ts',
  'app shell toggles language and dark theme',
  'language and theme a11y route',
)
requireIncludes('scripts/lighthouse.mjs', "'/api/v1/users/me'", 'versioned Lighthouse user mock')
requireIncludes('scripts/lighthouse.mjs', "'/api/v1/monkeys'", 'versioned Lighthouse product mock')

forbidIn(
  'src/components/ProductImage.vue',
  sources['src/components/ProductImage.vue'],
  /@error\s*=/,
  'template image error handler',
)
forbidIn('src/api', apiSource, /baseURL:\s*['"`]\/api['"`]/, 'unversioned API base URL')
forbidIn('src/api', apiSource, /\/api\/(?!v1(?:\/|['"`]|$))/, 'raw /api URL outside /api/v1')
forbidIn('src/api', apiSource, /url:\s*['"`]\/user(?:\/|['"`])/, 'singular user URL')
forbidIn('src/api', apiSource, /url:\s*['"`]\/address(?:\/|['"`])/, 'singular address URL')
forbidIn('src/api', apiSource, /url:\s*['"`]\/upload(?:\/|['"`])/, 'singular upload URL')
forbidIn('src/api', apiSource, /Math\.random/, 'Math.random trace/id generation')
forbidIn(
  'cookie auth frontend',
  cookieAuthSource,
  /Authorization|Bearer/,
  'token authorization header',
)
forbidIn('cookie auth frontend', cookieAuthSource, /\baccessToken\b/, 'access token handling')
forbidIn('cookie auth frontend', cookieAuthSource, /\brefreshToken\b/, 'refresh token handling')
forbidIn(
  'cookie auth frontend',
  cookieAuthSource,
  /localStorage|sessionStorage/,
  'browser token storage',
)

if (failures.length > 0) {
  console.error(
    `API contract check failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`,
  )
  process.exit(1)
}

console.log('API contract check passed')
